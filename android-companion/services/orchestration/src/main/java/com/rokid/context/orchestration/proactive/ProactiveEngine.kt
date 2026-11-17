package com.rokid.context.orchestration.proactive

import com.rokid.context.core.log.PcLog
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.forget.lifecycle.Leases
import com.rokid.context.orchestration.AskZoneGuard
import com.rokid.context.orchestration.DataOpGate
import com.rokid.context.orchestration.MemoryWipe
import com.rokid.context.orchestration.nudge.NudgeHud
import com.rokid.context.orchestration.nudge.NudgeRunLock
import com.rokid.context.orchestration.proactive.generators.CandidateGenerator
import com.rokid.context.provenance.ProvenanceGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.UUID

/**
 * What the gates ask the app. Every default FAILS CLOSED (switch off, paused, zone blocked, not worn, driving, no HUD settings, no budget, pin not live): a missing wire never lets a suggestion through.
 */
class ProactiveSeams(
    val enabled: () -> Boolean = { false },
    /** Any HubPauses record (user, quiet hours, auto-stop, `zone:`) or Battery Saver. A throw counts as paused. */
    val paused: suspend () -> Boolean = { true },
    /** `AskZoneFence` in production (fails closed itself). */
    val zoneGuard: AskZoneGuard = AskZoneGuard { true },
    val worn: () -> Boolean = { false },
    /** A vehicle episode (ruling 10: drop everything while driving, no backlog). */
    val driving: () -> Boolean = { true },
    val hud: () -> NudgeHud? = { null },
    /** The user's "Nudges per day" (0 = off) and what the nudge table already holds since [Long]. */
    val globalCap: () -> Int = { 0 },
    val globalToday: suspend (sinceMs: Long) -> Int = { Int.MAX_VALUE },
    /** True = this place is inside an enabled pause zone, or it cannot be told. */
    val anchorBlocked: suspend (anchorKey: String) -> Boolean = { true },
    /** Every pin id is still in the user's pin list. */
    val pinsLive: suspend (pinIds: Collection<String>) -> Boolean = { false },
)

data class CandidateReport(val candidate: Candidate, val score: Double, val verdict: Gate)

/** [gate] is [Gate.WOULD_SHOW] (with [record]) or the gate that stopped the run. [reports] lists every candidate with its verdict (the replay's answer). */
data class ProactiveRun(val gate: Gate, val record: SuggestionRecord? = null, val reports: List<CandidateReport> = emptyList())

/**
 * The 9-step gate pipeline of design 2.4, SHADOW MODE: step 9 only RECORDS a would-show through [SuggestionStore]; nothing is delivered to the HUD, a notification or Nexus. Every gate fails closed and any throw is
 * a drop. Order: 1 switch, 2 pause (and Battery Saver), 3 zones (fence ticket taken before the first suspension; places inside a zone are dropped at generation), 4 budget (quiet hours, cap inside the global cap, gap,
 * family max, cooldown), 5 feedback (never list), 6 read lease `PROACTIVE`, 7 provenance (inside the lease commit), 8 worn / driving, 9 commit. A run holds the nudge engine's lock, so the shared budgets are never raced.
 *
 * [dry] (the replay): steps 1, 2, 3 (the live fence), 8 and the commit are not applied: the lease is VERIFIED (not committed), the records go to the caller's own ephemeral store, no counter is bumped.
 */
class ProactiveEngine(
    private val db: ContextDatabase,
    private val provenance: ProvenanceGate,
    private val store: SuggestionStore,
    private val generators: List<CandidateGenerator>,
    private val seams: ProactiveSeams,
    private val gate: DataOpGate = DataOpGate.shared,
    private val budget: ProactiveBudget = ProactiveBudget(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val generation: () -> Long = { MemoryWipe.generation.get() },
) {
    /** Test seam: runs after the lease is open and before the commit (a Forget "during the run"). */
    @Volatile internal var testBeforeCommit: (suspend () -> Unit)? = null

    suspend fun run(trigger: TriggerKind, nowMs: Long = clock(), dry: Boolean = false): ProactiveRun =
        if (dry) guarded(trigger, nowMs, true) else NudgeRunLock.mutex.withLock { guarded(trigger, nowMs, false) }

    private suspend fun guarded(trigger: TriggerKind, nowMs: Long, dry: Boolean): ProactiveRun = try {
        pipeline(trigger, nowMs, dry)
    } catch (c: CancellationException) { throw c } catch (e: Exception) {
        PcLog.i(TAG, "proactive run failed (${e.javaClass.simpleName})")
        stop(Gate.ERROR, dry)
    }

    private fun stop(g: Gate, dry: Boolean, reports: List<CandidateReport> = emptyList()): ProactiveRun {
        if (!dry) store.bump("drop_" + g.name.lowercase())
        return ProactiveRun(g, null, reports)
    }

    private suspend fun pipeline(trigger: TriggerKind, nowMs: Long, dry: Boolean): ProactiveRun {
        val ticket = if (dry) null else seams.zoneGuard.ticket() // before the first suspension, like NexusGates
        if (!dry) {
            store.bump("runs")
            if (!seams.enabled()) return stop(Gate.SWITCH, dry)                                  // 1
            if (try { seams.paused() } catch (c: CancellationException) { throw c } catch (e: Exception) { true }) return stop(Gate.PAUSE, dry) // 2
            seams.zoneGuard.prepare()
            if (seams.zoneGuard.blocked()) return stop(Gate.ZONE, dry)                           // 3
        }
        val hud = seams.hud() ?: return stop(Gate.OFF, dry)
        val gen = generation() // before the reads: a Delete all that lands later fences the commit
        val generated = generators.map { it.generate(nowMs, seams.anchorBlocked) }
        val candidates = generated.flatMap { it.candidates }
        if (candidates.isEmpty()) return stop(if (generated.sumOf { it.zoneDropped } > 0) Gate.ZONE_ANCHOR else Gate.NO_CANDIDATES, dry)
        val midnight = Instant.ofEpochMilli(nowMs).atZone(hud.zone).toLocalDate().atStartOfDay(hud.zone).toInstant().toEpochMilli()
        val state = BudgetState(store.records(), seams.globalToday(midnight), seams.globalCap())      // 4
        val never = store.never()
        val reports = ArrayList<CandidateReport>()
        var chosen: Pair<Candidate, Double>? = null
        for ((c, score) in ProactiveRanker.rank(candidates, nowMs)) {
            if (chosen != null) { reports += CandidateReport(c, score, Gate.NOT_PICKED); continue }
            val verdict = when {
                score < ProactiveRanker.THRESHOLD -> Gate.THRESHOLD
                else -> budget.check(c, state, hud, nowMs).takeIf { it != Gate.WOULD_SHOW } ?: if (c.key in never) Gate.FEEDBACK else Gate.WOULD_SHOW // 4, 5
            }
            if (verdict == Gate.WOULD_SHOW) chosen = c to score
            reports += CandidateReport(c, score, verdict)
        }
        val (c, score) = chosen ?: return stop(reports.first().verdict, dry, reports)
        val lease = try { gate.lease(Leases.Kind.PROACTIVE) } catch (e: DataOpGate.LeaseBlocked) { return stop(Gate.LEASE, dry, reports.withVerdict(c, Gate.LEASE)) } // 6
        try {
            lease.extend(SourceIds.refs(c.sourceIds))
            if (!dry) {
                if (!seams.worn()) return stop(Gate.WEAR, dry, reports.withVerdict(c, Gate.WEAR))                       // 8
                if (seams.driving()) return stop(Gate.DRIVING, dry, reports.withVerdict(c, Gate.DRIVING))
            }
            testBeforeCommit?.invoke()
            val record = SuggestionRecord("sg_" + UUID.randomUUID(), nowMs, c.key, c.family, trigger, score, c.anchorKey, c.sourceIds, com.rokid.context.orchestration.proactive.generators.PlaceRecallGenerator.factors(c))
            val block: suspend () -> Gate = {
                when {
                    provenance.check(SourceIds.provenance(c.sourceIds), gen) != ProvenanceGate.Verdict.Live -> Gate.PROVENANCE      // 7
                    provenance.goneMarks(SourceIds.marks(c.sourceIds).toSet()).isNotEmpty() -> Gate.PROVENANCE
                    !seams.pinsLive(SourceIds.pins(c.sourceIds)) -> Gate.PROVENANCE
                    else -> {
                        val ok = if (ticket != null) ticket.fence.act(ticket) { store.add(record) } else store.add(record)         // 9
                        when (ok) { null -> Gate.ZONE; false -> Gate.COMMIT; else -> Gate.WOULD_SHOW }
                    }
                }
            }
            val verdict = if (dry) { if (lease.verify(db, true)) block() else Gate.LEASE } else when (val r = lease.commit(db, checkLive = true, block = block)) {
                is Leases.Commit.Committed -> r.value
                Leases.Commit.Fenced -> Gate.LEASE
            }
            if (verdict != Gate.WOULD_SHOW) return stop(verdict, dry, reports.withVerdict(c, verdict))
            if (!dry) store.bump("would_show")
            return ProactiveRun(Gate.WOULD_SHOW, record, reports)
        } finally {
            lease.close()
        }
    }

    private fun List<CandidateReport>.withVerdict(c: Candidate, g: Gate) = map { if (it.candidate === c) it.copy(verdict = g) else it }

    companion object { private const val TAG = "PcProactive" }
}
