package com.rokid.context.capture.replay

import com.rokid.context.capture.plan.PlanBounds
import com.rokid.context.capture.replay.ReplayFixtures.PRE_ROLL_END
import com.rokid.context.capture.replay.ReplayFixtures.START
import com.rokid.context.capture.trace.TraceEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CounterfactualReplayTest {
    private fun est(opens: List<Long>, gates: List<Pair<Long, Boolean>>) = CounterfactualReplay.estimateKeeps(opens, gates.map { it.first }, gates.map { it.second })

    @Test fun nearestObservedFrameWithinTwoSecondsDecides() {
        val e = est(listOf(10_000L, 20_000L), listOf(9_500L to false, 20_900L to true))
        assertEquals(1, e.keeps); assertEquals(0, e.unobserved)             // the first open inherits a SKIP, the second a KEEP
    }
    @Test fun noFrameWithinTwoSecondsCountsAsAKeepAndUnobserved() {
        val e = est(listOf(10_000L), listOf(12_001L to false))
        assertEquals(1, e.keeps); assertEquals(1, e.unobserved)
        assertEquals(0, est(listOf(10_000L), listOf(12_000L to false)).keeps)   // exactly 2 s is still within
    }
    @Test fun anEarlierFrameWinsATie() { assertEquals(0, est(listOf(10_000L), listOf(9_000L to false, 11_000L to true)).keeps) }

    private fun frames(opens: List<Long>, which: List<Int>): List<TraceEvent> =
        which.flatMap { i -> listOf(ReplayFixtures.score(opens[i] + 520, i + 1L, scene = i), ReplayFixtures.keep(opens[i] + 520, i + 1L, opens[i] + 500)) }

    private fun trace(): Pair<List<TraceEvent>, List<Long>> {
        val o = ReplayFixtures.timerOracle(untilMs = START + 90_000L)
        val ops = o.opens.filter { it >= PRE_ROLL_END }
        return ReplayFixtures.trace(o.events + frames(ops, ops.indices.filter { it % 2 == 0 }), endMs = START + 90_000L) to ops
    }

    @Test fun baselineOnlyIsExactAndHasNoUnobservedShare() {
        val (ev, ops) = trace()
        val r = CounterfactualReplay.run({ ev.asSequence() }, ReplayFixtures.manifest())
        assertEquals("EXACT", r.replayClass)
        assertEquals(ops.size + 1, r.baseline.opens)                       // the replay also decides the check after the last recorded one (inside the trace)
        assertEquals(0.0, r.unobservedOpensPct)
        assertEquals(null, r.candidate)
        assertEquals(0, r.baseFailed)
        assertTrue(r.warnings.any { it.startsWith("replay is approximate") })
    }

    @Test fun counterfactualReportsUnobservedShareAndPointEstimate() {
        val (ev, ops) = trace()                                              // every second open produced a frame; the others never did
        val r = CounterfactualReplay.run({ ev.asSequence() }, ReplayFixtures.manifest(), ReplayCandidate("ffffffffffff"))
        assertEquals("COUNTERFACTUAL", r.replayClass)
        val c = r.candidate!!
        assertEquals(ops.size + 1, c.opens)
        val observed = (ops.indices).count { it % 2 == 0 }
        assertEquals(c.opens - observed, c.unobservedOpens)
        assertEquals(100.0 * c.unobservedOpens / c.opens, r.unobservedOpensPct, 1e-9)
        assertEquals(c.opens.toDouble(), c.keeps)                         // observed ones kept, unobserved counted as keeps
        assertTrue(r.warnings.any { it.startsWith("unobserved opens: ") && it.endsWith(" %") })
        assertEquals("ffffffffffff", c.configHash)
    }

    @Test fun batteryFiguresFollowTheLine() {
        val (ev, _) = trace()
        val r = CounterfactualReplay.run({ ev.asSequence() }, ReplayFixtures.manifest(), ReplayCandidate("ffffffffffff"))
        val c = r.candidate!!
        assertEquals(BatteryLine.pctPerMin(c.camOpenSPerMin), c.estimatedPctPerMin, 1e-12)
        assertEquals(100.0 / c.estimatedPctPerMin / 60.0, c.estimatedHoursFromFull, 1e-12)
        assertEquals(0.8 * c.opens / r.minutes, c.camOpenSPerMin, 1e-9)    // each fixture episode is open for 800 ms, so the mean open is 0.8 s
    }

    @Test fun fusionChangeWarnsAndRestrictsToShadowOrTrial() {
        val (ev, _) = trace()
        val changed = PlanBounds.FUSION_DEFAULTS.toMutableMap().also { k -> k[k.keys.first()] = k.values.first() * 1.1 }
        val r = CounterfactualReplay.run({ ev.asSequence() }, ReplayFixtures.manifest(), ReplayCandidate("ffffffffffff"), candidateFusion = changed)
        assertTrue("fusion changes are not replayed" in r.warnings); assertTrue(r.shadowOrTrialOnly)
        val same = CounterfactualReplay.run({ ev.asSequence() }, ReplayFixtures.manifest(), ReplayCandidate("ffffffffffff"))
        assertFalse("fusion changes are not replayed" in same.warnings); assertFalse(same.shadowOrTrialOnly)
    }

    @Test fun inexactWindowsAreCarriedIntoTheReportAndWarnings() {
        val o = ReplayFixtures.timerOracle(untilMs = START + 90_000L)
        val now = TraceEvent.Episode(PRE_ROLL_END + 5_000, true, com.rokid.context.capture.trace.EpisodeCause.NOW)
        val ev = ReplayFixtures.trace(o.events + now + TraceEvent.Episode(PRE_ROLL_END + 5_800, false, com.rokid.context.capture.trace.EpisodeCause.NOW, firstFrameAtMs = PRE_ROLL_END + 5_500, openedAtMs = PRE_ROLL_END + 5_050, outcome = com.rokid.context.capture.trace.EpisodeOutcome.OK), endMs = START + 90_000L)
        val r = CounterfactualReplay.run({ ev.asSequence() }, ReplayFixtures.manifest())
        assertTrue(r.inexactWindows > 0)
        assertTrue(r.warnings.any { it.contains("excluded") && it.contains("capture_now") })
    }

    @Test fun reportJsonCarriesEstimateFlagAndWarnings() {
        val (ev, _) = trace()
        val j = CounterfactualReplay.run({ ev.asSequence() }, ReplayFixtures.manifest(), ReplayCandidate("ffffffffffff")).toJson().toString()
        assertTrue("\"estimate\":true" in j); assertTrue("\"warnings\":[" in j); assertTrue("\"unobservedOpensPct\"" in j)
    }
}
