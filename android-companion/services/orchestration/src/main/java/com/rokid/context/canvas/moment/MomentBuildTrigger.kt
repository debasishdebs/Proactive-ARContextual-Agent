package com.rokid.context.canvas.moment

import android.content.Context
import com.rokid.context.memory.MemoryGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * The production caller of [MomentBuilder] (nothing else in the app builds stored Moments). Three triggers feed it: a frame commit ([onFrameCommitted]), a recorded mark
 * ([onMarkRecorded]) and a periodic cadence ([rebuildRecent], the current and the previous hour, run by `MomentCadenceWorker`).
 *
 * - **Off the calling thread, no disk I/O on it:** the two `on...` calls only add a second id to an in-memory set (non-blocking) and make sure ONE drain coroutine is running on [scope]
 *   (production: `Dispatchers.IO`); every build, with all its Room reads and writes, runs there.
 * - **Coalesced:** the pending ids form a SET of second buckets, so a burst of frames in one second is one id; the drain waits [debounceMs] after the first trigger, then builds everything
 *   pending in ONE `build(ids)` call (one transaction, chunks of [MAX_BATCH]). A frame never causes a window rebuild; only the cadence does that.
 * - **Idempotent:** the builder upserts and writes nothing for an unchanged second, so a repeated trigger changes nothing.
 * - **Respects the store switch, tombstones, fences and the unknown-capture-time rule:** [enabled] is checked before scheduling and again at drain (and by the builder); the builder re-reads the
 *   fences and tombstones inside its own transaction, so a Forget that committed first is seen and one that commits afterwards removes what was built. A frame with no capture time
 *   ([onFrameCommitted] with a null `capturedAtMs`) schedules nothing: unknown capture time is never a Moment.
 * - **No ids or content in logs:** only counts are kept ([builds], [secondsBuilt], [failures]).
 */
class MomentBuildTrigger(
    private val build: suspend (Collection<String>) -> MomentBuilder.Report,
    private val rebuild: suspend (Long, Long) -> MomentBuilder.Report,
    private val enabled: () -> Boolean,
    private val scope: CoroutineScope,
    private val debounceMs: Long = DEBOUNCE_MS,
) {
    private val lock = Any()
    private val pending = LinkedHashSet<String>()
    private var job: Job? = null
    private val buildCount = AtomicInteger(); private val secondCount = AtomicInteger(); private val failureCount = AtomicInteger()

    /** Build calls made, seconds (ids) sent to them, and builds that threw (counts only). */
    val builds: Int get() = buildCount.get()
    val secondsBuilt: Int get() = secondCount.get()
    val failures: Int get() = failureCount.get()

    /** A frame was committed ([FrameIntake.Outcome.ADDED]). [tsMs] is the frame row's time (the Moment second), [capturedAtMs] its capture evidence (null = unknown: no Moment). */
    fun onFrameCommitted(tsMs: Long, capturedAtMs: Long?) {
        if (capturedAtMs == null || !enabled()) return
        enqueue(tsMs)
    }

    /** A mark or intent was recorded at [atMs] (phone clock): its second is rebuilt so the MARK part attaches. */
    fun onMarkRecorded(atMs: Long) {
        if (!enabled()) return
        enqueue(atMs)
    }

    private fun enqueue(ms: Long) {
        val id = try { MomentId.ofMs(ms) } catch (_: IllegalArgumentException) { return } // outside 1970..2106: no Moment
        synchronized(lock) {
            pending += id
            if (job?.isActive != true) job = scope.launch { drain() }
        }
    }

    private suspend fun drain() {
        while (true) {
            delay(debounceMs)
            val batch = synchronized(lock) {
                if (pending.isEmpty()) { job = null; return }
                pending.take(MAX_BATCH).also { pending.removeAll(it.toSet()) }
            }
            if (!enabled()) continue
            try {
                build(batch); buildCount.incrementAndGet(); secondCount.addAndGet(batch.size)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { failureCount.incrementAndGet() } // the cadence rebuilds it
        }
    }

    /** Waits until nothing is pending and no build is running (tests, shutdown). */
    suspend fun flush() {
        while (true) {
            val j = synchronized(lock) { job?.takeIf { it.isActive } } ?: return
            j.join()
            if (synchronized(lock) { pending.isEmpty() && job?.isActive != true }) return
        }
    }

    /** The cadence: rebuilds every second of the previous and the current hour (phone clock) and drops stored Moments of it that no longer qualify. */
    suspend fun rebuildRecent(nowMs: Long): MomentBuilder.Report {
        if (!enabled()) return MomentBuilder.Report.NONE
        val hour = Math.floorDiv(nowMs, HOUR_MS) * HOUR_MS
        return rebuild(hour - HOUR_MS, nowMs)
    }

    companion object {
        const val DEBOUNCE_MS = 500L
        const val MAX_BATCH = 200
        const val HOUR_MS = 3_600_000L
    }
}

/** The process-wide trigger (production wiring): the real builder on the app database, its drain on `Dispatchers.IO`, the kill switch [MomentStoreSwitch]. */
object MomentTriggers {
    @Volatile private var instance: MomentBuildTrigger? = null

    fun get(context: Context): MomentBuildTrigger = instance ?: synchronized(this) {
        instance ?: run {
            val builder = MomentBuilder(MemoryGraph.get(context).db)
            MomentBuildTrigger(
                build = builder::build, rebuild = builder::rebuildWindow, enabled = { MomentStoreSwitch.DEFAULT_ENABLED },
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            )
        }.also { instance = it }
    }

    fun resetForTests() { synchronized(this) { instance = null } }
}

/** The process-wide Scene cadence (production wiring): the real builder on the app database, the same kill switch as the Moments. Run by `MomentCadenceWorker` after its Moment rebuild. */
object SceneTriggers {
    @Volatile private var instance: SceneCadence? = null

    fun get(context: Context): SceneCadence = instance ?: synchronized(this) {
        instance ?: run {
            val db = MemoryGraph.get(context).db
            SceneCadence(db, SceneBuilder(db, enabled = { MomentStoreSwitch.DEFAULT_ENABLED }))
        }.also { instance = it }
    }

    fun resetForTests() { synchronized(this) { instance = null } }
}
