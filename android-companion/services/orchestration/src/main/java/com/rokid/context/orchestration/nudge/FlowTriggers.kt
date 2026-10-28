package com.rokid.context.orchestration.nudge

import com.rokid.context.ai.canvas.CronSpec
import com.rokid.context.ai.canvas.FlowPlan
import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.canvas.NumberCompare
import com.rokid.context.ai.canvas.WindowAggregate
import com.rokid.context.core.log.PcLog
import com.rokid.context.memory.db.CanvasSlice3Dao
import com.rokid.context.orchestration.FlowPlans
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException

/**
 * v0.9.8 slice C: the state the Nudge triggers keep between runs (rate-limit stamps, the signal's last state, the schedule's last check). Production is the existing
 * `canvas_meta` key/value table (no schema change, survives a restart); the default here is in memory (tests, and a process that has no database yet).
 */
interface TriggerMeta {
    suspend fun get(key: String): String?
    suspend fun put(key: String, value: String)
}

class MemoryTriggerMeta : TriggerMeta {
    private val map = java.util.concurrent.ConcurrentHashMap<String, String>()
    override suspend fun get(key: String) = map[key]
    override suspend fun put(key: String, value: String) { map[key] = value }
}

class DaoTriggerMeta(private val dao: CanvasSlice3Dao) : TriggerMeta {
    override suspend fun get(key: String) = dao.meta(key)
    override suspend fun put(key: String, value: String) = dao.putMeta(key, value)
}

/**
 * The per-pipeline trigger rate limit: at most [admit]'s `perHour` runs may START in any rolling hour for one trigger type, so a bursty trigger (a Moment per second of frames)
 * cannot spam model calls. A refused call is not counted. Stamps persist in [meta], so a restart does not reset the budget. `perHour <= 0` = unlimited.
 */
class TriggerRateLimiter(private val meta: TriggerMeta, private val clock: () -> Long = System::currentTimeMillis) {
    /** [scope] = the publication (a new publish starts with a clean budget). A stamp in the future (the clock went back) still counts: a rollback never reopens the budget. */
    suspend fun admit(trigger: String, perHour: Int, scope: String = ""): Boolean {
        if (perHour <= 0) return true
        val now = clock()
        val key = "$KEY$scope|$trigger"
        val live = meta.get(key)?.split(',')?.mapNotNull { it.toLongOrNull() }.orEmpty().filter { it > now - HOUR_MS }
        if (live.size >= perHour) return false
        meta.put(key, (live + now).joinToString(","))
        return true
    }

    companion object { const val KEY = "nudge_trigger_runs_"; const val HOUR_MS = 3_600_000L }
}

/** The numbers `trigger.signal` measures: the sensor [feature] of the newest [count] Scenes, oldest first, null where a Scene has none (`WindowAggregate.featureOf`). */
fun interface SignalValues { suspend fun values(feature: String, count: Int, nowMs: Long): List<Double?> }

/** The photo behind an IMAGE query: its frame id (fenced and stored as a dependency of the nudge) and its embedding, already checked to be in the ACTIVE image space. */
class TriggerPhoto(val frameId: String, val vector: FloatArray)

/** The newest photo of the last few minutes for an IMAGE query, or null (none, no embedding, or an embedding of another space). Never throws for a missing photo. */
fun interface TriggerImages { suspend fun photo(nowMs: Long): TriggerPhoto? }

/**
 * `trigger.signal`: [WindowAggregate] over the newest Scenes, then [NumberCompare]. It fires on the EDGE (the condition holds now and did not at the last check), so a signal that stays
 * high for an hour is one nudge decision, not one per Scene. The held state persists in [meta].
 */
class SignalGate(private val meta: TriggerMeta, private val scope: String = "", private val values: SignalValues) {
    suspend fun fires(signal: FlowPlan.Signal, nowMs: Long): Boolean {
        val a = signal.aggregate
        val v = values.values(a.feature, a.window, nowMs)
        val number = WindowAggregate.over(v, a.op, a.window).lastOrNull()
        val holds = number != null && NumberCompare.holds(number, signal.compare)
        val key = KEY + scope
        val was = meta.get(key) == "1"
        if (holds != was) meta.put(key, if (holds) "1" else "0")
        return holds && !was
    }

    companion object { const val KEY = "nudge_signal_held" }
}

/**
 * Event triggers (a Moment landed, a Scene stored): asks [enqueue] to run the Nudge flow only when the PUBLISHED Nudge flow's root is that trigger (one cheap plan lookup, cached per
 * publish id), and at most once per [debounceMs] per trigger. A burst of events is therefore one queued run; the engine's own gates and the rate limit decide what it does.
 */
class FlowTriggers(private val plans: FlowPlans, private val enqueue: suspend (String) -> Boolean, private val clock: () -> Long = System::currentTimeMillis, private val debounceMs: Long = DEBOUNCE_MS) {
    private val last = java.util.concurrent.ConcurrentHashMap<String, Long>()

    suspend fun fire(trigger: String): Boolean {
        val now = clock()
        val before = last[trigger]
        if (before != null && now - before in 0 until debounceMs) return false
        last[trigger] = now
        val plan = try { plans.plan(FlowPlan.Kind.NUDGE) } catch (c: CancellationException) { throw c } catch (e: Exception) {
            // a lookup that fails must not silence the built-in flow: run it as a summarizer pass (that path has its own 30 minute gap)
            PcLog.i(TAG, "trigger lookup failed (${e.javaClass.simpleName}); built-in flow")
            return enqueue(NodeTypes.TRIGGER_SUMMARIZER)
        }
        if (plan?.trigger != trigger) return false
        return enqueue(trigger)
    }

    companion object { const val DEBOUNCE_MS = 60_000L; private const val TAG = "FlowTriggers" }
}

/**
 * `trigger.schedule`: called by the 15 minute WorkManager cadence that already exists (no extra worker, no alarm, no wake lock). When the published Nudge flow is a schedule and its cron
 * has an occurrence between the last check and now, it fires ONCE (missed occurrences while the phone slept are not replayed). The first check only records the time, so a newly published
 * schedule never fires retroactively. The last-check time persists in [meta], so a restart neither loses nor repeats an occurrence.
 */
class ScheduleTick(
    private val plans: FlowPlans, private val meta: TriggerMeta, private val fire: suspend (String) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis, private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    /**
     * State per publication, ONE value `checkpoint;pending` so it changes atomically: the occurrence is written as pending BEFORE the enqueue and cleared (checkpoint moved) only after
     * the unique work was durably enqueued; a death in between retries the same occurrence (unique KEEP work: at most one extra run, which the rate limit and gates still bound).
     * The checkpoint never moves backwards (a clock set back neither replays nor skips), and an occurrence search that finds nothing moves it to now instead of wedging.
     */
    suspend fun tick(): Boolean {
        val plan = plans.plan(FlowPlan.Kind.NUDGE)?.takeIf { it.trigger == NodeTypes.TRIGGER_SCHEDULE } ?: return false
        val spec = plan.cron?.let(CronSpec::parse) ?: return false
        val key = KEY + plan.scope
        val now = clock()
        val state = meta.get(key)?.split(';')
        val last = state?.getOrNull(0)?.toLongOrNull()
        var pending = state?.getOrNull(1)?.toLongOrNull()
        if (last == null) { meta.put(key, "$now;"); return false } // a new publication only records the time: never retroactive
        if (pending == null) {
            val next = spec.nextAfter(last, zone())
            if (next == null) { meta.put(key, "${maxOf(last, now)};"); return false }
            if (next > now) return false
            pending = next
            meta.put(key, "$last;$pending")
        }
        if (!fire(NodeTypes.TRIGGER_SCHEDULE)) return false // not durably enqueued: the pending occurrence stays for the next tick
        meta.put(key, "${maxOf(last, now, pending)};")
        return true
    }

    companion object { const val KEY = "nudge_schedule_state_" }
}
