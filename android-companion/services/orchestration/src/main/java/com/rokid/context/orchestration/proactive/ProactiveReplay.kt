package com.rokid.context.orchestration.proactive

import com.rokid.context.memory.db.ContextDatabase
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
) {
    /** The steps of [day]: every [stepMin] minutes of that local day, plus [extra] (an arrival or Moment end at a given time). */
    fun steps(day: LocalDate, stepMin: Int, extra: List<ReplayStep>): List<ReplayStep> {
        val z = zone()
        val start = day.atStartOfDay(z).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli()
        val ticks = generateSequence(start) { it + stepMin * 60_000L }.takeWhile { it < end }.map { ReplayStep(it, TriggerKind.TICK) }.toList()
        return (ticks + extra.filter { it.atMs in start until end }).sortedBy { it.atMs }
    }

    suspend fun replay(day: LocalDate, stepMin: Int, extra: List<ReplayStep>, hud: NudgeHud? = null): List<ReplayStepResult> {
        val base = seams()
        // the replay sees the quiet hours of NOW and the day's own clock; the live-state gates are replaced by "pass" seams
        val replaySeams = ProactiveSeams(enabled = { true }, paused = { false }, zoneGuard = com.rokid.context.orchestration.AskZoneGuard.None, worn = { true }, driving = { false },
            hud = { hud ?: base.hud() }, globalCap = base.globalCap, globalToday = base.globalToday, anchorBlocked = base.anchorBlocked, pinsLive = base.pinsLive)
        val sim = InMemorySuggestionStore()
        never().forEach { sim.addNever(it) } // the real never list is read, never written
        val engine = ProactiveEngine(db, provenance, sim, listOf<CandidateGenerator>(generator), replaySeams)
        val out = ArrayList<ReplayStepResult>()
        for (s in steps(day, stepMin, extra)) {
            val run = engine.run(s.trigger, s.atMs, dry = true)
            val cands = run.reports.map { r -> ReplayCandidate(r.candidate.key, r.candidate.family.token, r.score, r.verdict, r.candidate.sourceIds, if (r.verdict == Gate.WOULD_SHOW) com.rokid.context.orchestration.proactive.Templates.placeRecall(r.candidate.facts, zone()) else null) }
            if (cands.isNotEmpty() || run.gate == Gate.ZONE_ANCHOR || run.gate == Gate.ERROR) out += ReplayStepResult(s.atMs, s.trigger, run.gate, cands)
        }
        return out
    }

    companion object {
        val NOT_REPLAYED = listOf(Gate.SWITCH, Gate.PAUSE, Gate.ZONE, Gate.WEAR, Gate.DRIVING)
        const val MAX_STEPS = 300
    }
}
