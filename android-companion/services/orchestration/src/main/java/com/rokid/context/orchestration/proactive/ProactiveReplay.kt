package com.rokid.context.orchestration.proactive

import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.forget.lifecycle.Leases
import com.rokid.context.memory.place.UserPinStore
import com.rokid.context.orchestration.DataOpGate
import com.rokid.context.orchestration.nudge.NudgeHud
import com.rokid.context.orchestration.proactive.generators.CandidateGenerator
import com.rokid.context.orchestration.proactive.generators.PlaceRecallGenerator
import com.rokid.context.provenance.ProvenanceGate
import java.time.LocalDate
import java.time.ZoneId

/** One step of a replay request. */
data class ReplayStep(val atMs: Long, val trigger: TriggerKind)

data class ReplayCandidate(val key: String, val family: String, val score: Double, val verdict: Gate, val sourceIds: List<String>, val text: String?)
data class ReplayStepResult(val atMs: Long, val trigger: TriggerKind, val gate: Gate, val candidates: List<ReplayCandidate>)

/**
 * The dry-run replay of the loop over STORED data (design 4): triggers -> generators -> ranker -> gates 4-7 as of each step. Read-only: the engine runs with `dry = true` (the lease is verified, never committed),
 * into an EPHEMERAL store that only simulates the budget across the steps; the real store, the nudge table, notifications and the HUD are not touched. The live-state gates (switch, pause, zone fence, worn, driving)
 * describe now, not a past day, and are NOT replayed ([NOT_REPLAYED]).
 */
class ProactiveReplayer(
    private val db: ContextDatabase,
    private val provenance: ProvenanceGate,
    private val generator: PlaceRecallGenerator,
    private val seams: () -> ProactiveSeams,
    private val never: () -> Set<String> = { emptySet() },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val gate: DataOpGate = DataOpGate.shared,
) {
    /** Test seam: runs after step [Int] (0-based) of the replay, still inside the replay-wide lease (a Forget "during later steps"). */
    @Volatile internal var testAfterStep: (suspend (Int) -> Unit)? = null

    /** The steps of [day]: every [stepMin] minutes of that local day, plus [extra] (an arrival or Moment end at a given time). */
    fun steps(day: LocalDate, stepMin: Int, extra: List<ReplayStep>): List<ReplayStep> {
        val z = zone()
        val start = day.atStartOfDay(z).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli()
        val ticks = generateSequence(start) { it + stepMin * 60_000L }.takeWhile { it < end }.map { ReplayStep(it, TriggerKind.TICK) }.toList()
        return (ticks + extra.filter { it.atMs in start until end }).sortedBy { it.atMs }
    }

    /**
     * ONE read lease is held for the whole replay and every id any step touched (selected or not) joins it. Before the answer is built, every returned id and text is checked for liveness AGAIN: a Forget that
     * revoked the lease (or a Delete all) empties the answer; otherwise the candidates with a gone memory, photo, mark or pin, or with a pin that was edited during the replay, are dropped. Nothing forgotten is returned.
     * [LeaseBlocked][DataOpGate.LeaseBlocked] (a delete is running) propagates: the caller answers "busy".
     */
    suspend fun replay(day: LocalDate, stepMin: Int, extra: List<ReplayStep>, hud: NudgeHud? = null): List<ReplayStepResult> {
        val base = seams()
        // the replay sees the quiet hours of NOW and the day's own clock; the live-state gates are replaced by "pass" seams
        val replaySeams = ProactiveSeams(enabled = { true }, paused = { false }, zoneGuard = com.rokid.context.orchestration.AskZoneGuard.None, worn = { true }, driving = { false },
            hud = { hud ?: base.hud() }, globalCap = base.globalCap, globalToday = base.globalToday, anchorBlocked = base.anchorBlocked, pinsLive = base.pinsLive)
        val sim = InMemorySuggestionStore()
        never().forEach { sim.addNever(it) } // the real never list is read, never written
        val engine = ProactiveEngine(db, provenance, sim, listOf<CandidateGenerator>(generator), replaySeams, gate)
        val editedPins = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val pinHook: (Set<String>) -> Unit = { editedPins += it }
        UserPinStore.addChangeListener(pinHook)
        val lease = gate.lease(Leases.Kind.PROACTIVE)
        try {
            val out = ArrayList<ReplayStepResult>()
            for ((i, s) in steps(day, stepMin, extra).withIndex()) {
                val run = engine.run(s.trigger, s.atMs, dry = true)
                lease.extend(SourceIds.refs(run.reports.flatMap { it.candidate.sourceIds }))
                val cands = run.reports.map { r -> ReplayCandidate(r.candidate.key, r.candidate.family.token, r.score, r.verdict, r.candidate.sourceIds, if (r.verdict == Gate.WOULD_SHOW) com.rokid.context.orchestration.proactive.Templates.placeRecall(r.candidate.facts, zone()) else null) }
                if (cands.isNotEmpty() || run.gate == Gate.ZONE_ANCHOR || run.gate == Gate.ERROR) out += ReplayStepResult(s.atMs, s.trigger, run.gate, cands)
                testAfterStep?.invoke(i)
            }
            return finalCheck(out, lease, base, editedPins)
        } finally {
            UserPinStore.removeChangeListener(pinHook)
            lease.close()
        }
    }

    private fun revoked(l: Leases.Lease) = l.isRevoked || l.generation != com.rokid.context.orchestration.MemoryWipe.generation.get()

    private suspend fun finalCheck(out: List<ReplayStepResult>, lease: Leases.Lease, base: ProactiveSeams, editedPins: Set<String>): List<ReplayStepResult> {
        val ids = out.flatMap { s -> s.candidates.flatMap { it.sourceIds } }.toSet()
        if (revoked(lease)) return emptyList() // a Forget reached an input (or a Delete all ran): nothing from this replay is returned
        val dead = HashSet<String>()
        provenance.goneMemories(SourceIds.memories(ids).toSet()).forEach { dead += SourceIds.mem(it) }
        provenance.goneFrames(SourceIds.frames(ids).toSet(), requirePresent = false).forEach { dead += SourceIds.frame(it) }
        provenance.goneMarks(SourceIds.marks(ids).toSet()).forEach { dead += SourceIds.mark(it) }
        for (p in SourceIds.pins(ids)) if (p in editedPins || !(try { base.pinsLive(listOf(p)) } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (e: Exception) { false })) dead += SourceIds.pin(p)
        if (revoked(lease)) return emptyList() // and once more after the reads
        return out.mapNotNull { s ->
            val keep = s.candidates.filter { c -> c.sourceIds.none { it in dead } }
            if (keep.size == s.candidates.size) s else if (keep.isEmpty() && s.candidates.isNotEmpty()) null else s.copy(candidates = keep)
        }
    }

    companion object {
        val NOT_REPLAYED = listOf(Gate.SWITCH, Gate.PAUSE, Gate.ZONE, Gate.WEAR, Gate.DRIVING)
        const val MAX_STEPS = 300
    }
}
