package com.rokid.context.capture.replay

import com.rokid.context.capture.plan.PlanResolver
import com.rokid.context.capture.plan.PlanState
import com.rokid.context.capture.plan.TimerPhotoGaps
import com.rokid.context.capture.replay.ReplayFixtures.START
import com.rokid.context.capture.trace.TraceEvent
import com.rokid.context.core.model.context.ContextState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A candidate with its own per-state timer photo gaps (Task 24): the candidate's cadence is its OWN (a decided open sets its next check), not the recorded one.
 * Expected opens are plain arithmetic: a plan runs SETTLE wake on the T0 tier (settled 10 s, quiet 30 s, still cap 60 s), the row's gap is a minimum on top,
 * the check is due `gap` after the last one and opens `LEAD` (535 ms) earlier, and a decided open ends 700 ms later.
 */
class CandidatePolicySimulatorTest {
    private val rest = listOf(TraceEvent.Ctx(START, ContextState.REST))
    private val initial = ReplayFixtures.initial().filter { it !is TraceEvent.Ctx } + rest

    private fun table(settledMs: Long, quietMs: Long = 0, walkMs: Long = 0, stillCapMs: Long = 0, cap: Int? = null) =
        ReplayCandidate.policyTable(mapOf(PlanState.REST to PlanResolver.defaults().policy.getValue(PlanState.REST).copy(
            capPerMin = cap, timerPhotoGapsMs = TimerPhotoGaps(settledMs, quietMs, walkMs, stillCapMs),
        )))

    private fun run(body: List<TraceEvent>, cand: ReplayCandidate, endMs: Long) =
        CaptureSimulator.replay(ReplayFixtures.trace(body, endMs = endMs, initial = initial).asSequence(), ReplayFixtures.manifest(endMs = endMs), cand)

    @Test fun candidateTimerGapSetsTheCadence() {
        val r = run(emptyList(), ReplayCandidate("c20", table = table(20_000)), START + 120_000)
        val step = 20_000 + 700 - ReplayFixtures.LEAD                // a check opens 535 ms early and ends 700 ms later
        val expected = (0 until 5).map { START + 20_000 - ReplayFixtures.LEAD + it * step }
        assertEquals(expected, r.opens.map { it.atMs })
    }

    @Test fun candidateGapUpTo300sIsReplayed() {
        val r = run(emptyList(), ReplayCandidate("c300", table = table(300_000)), START + 700_000)
        val first = START + 300_000 - ReplayFixtures.LEAD
        // The second open is the reserved timer photo attempt (S83): 300 s in REST after the last attempt, 165 ms before the 300 s timer check would be.
        assertEquals(listOf(first to com.rokid.context.capture.trace.EpisodeCause.TIMER, first + 300_000 to com.rokid.context.capture.trace.EpisodeCause.RESV), r.opens.map { it.atMs to it.cause })
    }

    @Test fun aGapOfZeroInheritsTheTierBase() {
        val r = run(emptyList(), ReplayCandidate("c0", table = table(0)), START + 60_000)
        val step = 10_000 + 700 - ReplayFixtures.LEAD                // SETTLE wake on T0: 10 s
        assertEquals((0 until 5).map { START + 10_000 - ReplayFixtures.LEAD + it * step }.filter { it >= ReplayFixtures.PRE_ROLL_END }, r.opens.map { it.atMs })
    }

    /** The candidate does not inherit the recording's own cadence: a recorded close between two candidate checks does not move the next one. */
    @Test fun recordedClosesDoNotDriveACandidateWithItsOwnGaps() {
        val recorded = ReplayFixtures.timerEpisode(START + 25_000)
        val r = run(recorded, ReplayCandidate("c20", table = table(20_000)), START + 120_000)
        val step = 20_000 + 700 - ReplayFixtures.LEAD
        assertEquals((0 until 5).map { START + 20_000 - ReplayFixtures.LEAD + it * step }, r.opens.map { it.atMs })
    }

    @Test fun theRowOfTheRecordedContextApplies() {
        // The trace is in REST; a table whose REST row is the default (30 s settled) opens at 30 s, a UNKNOWN-only change would not.
        val r = run(emptyList(), ReplayCandidate("def", table = ReplayCandidate.policyTable(mapOf(PlanState.REST to PlanResolver.defaults().policy.getValue(PlanState.REST)))), START + 40_000)
        assertEquals(listOf(START + 30_000 - ReplayFixtures.LEAD), r.opens.map { it.atMs })
        assertTrue(r.compared.not())
    }

    @Test fun theRowCapReplacesTheRecordedContextCap() {
        // capPerMin 1 on the row: the candidate's gate cap is 1, so a second keep inside a minute is refused (hard).
        val a = ReplayFixtures.score(START + 30_000, 1, scene = 1, force = false)
        val b = ReplayFixtures.score(START + 40_000, 2, scene = 2)
        val r = run(listOf(a, b), ReplayCandidate("cap1", table = table(0, cap = 1)), START + 60_000)
        assertEquals(1, r.keeps)
    }

    /** WorkRest on: `forRest()` lifts the settled gap to at least 30 s whatever the row says. */
    @Test fun workRestLiftsTheCandidateGap() {
        val r = run(listOf(TraceEvent.Resting(START, true)), ReplayCandidate("rest", table = table(0)), START + 40_000)
        assertEquals(listOf(START + 30_000 - ReplayFixtures.LEAD), r.opens.map { it.atMs })
    }
}
