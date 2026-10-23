package com.rokid.context.capture.replay

import com.rokid.context.capture.plan.CapturePlan
import com.rokid.context.capture.plan.PlanMeta
import com.rokid.context.capture.plan.PlanResolver
import com.rokid.context.capture.replay.ReplayFixtures.PRE_ROLL_END
import com.rokid.context.capture.replay.ReplayFixtures.START
import com.rokid.context.capture.trace.EpisodeCause
import com.rokid.context.capture.trace.EpisodeOutcome
import com.rokid.context.capture.trace.Exactness
import com.rokid.context.capture.trace.Inexact
import com.rokid.context.capture.trace.ReplayRule
import com.rokid.context.capture.trace.TraceEvent
import com.rokid.context.core.model.CameraWake
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task 23c: the glasses deliver sensor data late, in bursts (a "stall": the delivery time `tMs` jumps while the sensor time `sMs` stays continuous, see `TraceEvent.Gyro`),
 * and the live loop decides nothing inside one. The traces here are generated: still sensors at 50 Hz, a stall whose events are all delivered at its end, and the timer
 * episodes of the fixture oracle moved so that the recorded open follows the stall end by [resumeDelay] ms (real traces: 266-1254 ms).
 */
class StallReplayTest {
    private class Scenario(val events: List<TraceEvent>, val recordedOpens: List<Long>, val stallStart: Long, val stallEnd: Long, val endMs: Long)

    private fun sensors(fromMs: Long, toMs: Long, stall: LongRange): List<TraceEvent> = (fromMs..toMs step 20).flatMap { s ->
        val t = if (s in stall) stall.last else s
        listOf(TraceEvent.Gyro(t, s, 0f, 0f, 0.02f), TraceEvent.Accel(t, s, 0f, 0f, 0f))
    }

    /** The oracle's third open after the pre-roll falls inside a [stallLen] ms stall that ends [stallLen] - 1500 ms after it; the recorded open (and everything after it) follows the stall end by [resumeDelay]. */
    private fun scenario(resumeDelay: Long, stallLen: Long = 6_000L, initial: List<TraceEvent> = ReplayFixtures.initial()): Scenario {
        val o = ReplayFixtures.timerOracle(untilMs = START + 60_000L)
        val post = o.opens.filter { it >= PRE_ROLL_END }
        val due = post[2]
        val stall = (due - 1_500)..(due - 1_500 + stallLen)
        val delta = stall.last + resumeDelay - due
        val moved = o.events.map { e ->
            if (e.tMs < due) e else when (e) { is TraceEvent.Episode -> e.copy(tMs = e.tMs + delta); is TraceEvent.Backoff -> e.copy(tMs = e.tMs + delta); else -> e }
        }
        val recorded = post.map { if (it < due) it else it + delta }
        val end = recorded.last() + 800 + 1_000
        return Scenario(ReplayFixtures.trace(moved + sensors(START, end, stall), endMs = end, initial = initial), recorded, stall.first, stall.last, end)
    }

    private fun run(s: Scenario, cand: ReplayCandidate? = null) = CaptureSimulator.replay(s.events.asSequence(), ReplayFixtures.manifest(endMs = s.endMs), cand)

    // ---- (1) the simulator does not tick through a delivery stall ----

    /** The BASE replay decides ONCE for the due timer, at the resume instant (the first tick after the burst), and its twin is the recorded open 300 ms later. */
    @Test fun baselineDecidesOnceAtTheResumeInstantOfAStall() {
        val s = scenario(resumeDelay = 300)
        val r = run(s)
        assertEquals(0, r.failed, r.mismatches.toString())
        assertEquals(0, r.inexact, r.inexactReasons.toString())
        assertTrue(r.mismatches.none { it.kind == "open_extra" }, r.mismatches.toString())
        assertEquals(s.recordedOpens.size, r.opens.size, r.opens.toString())
        assertTrue(r.opens.none { it.atMs > s.stallStart && it.atMs < s.stallEnd }, "no decision inside the stall: ${r.opens}")
        assertTrue(r.opens.count { it.atMs in s.stallEnd..s.stallEnd + 50 } == 1, r.opens.toString())
    }

    /** A recorded event that is not a sensor sample can fall inside a stall (a context change, a back-off): it must not make the replay tick through the stall either. */
    @Test fun aRecordedEventInsideTheStallDoesNotTickThroughIt() {
        val s = scenario(resumeDelay = 300)
        val inside = listOf(TraceEvent.Ctx(s.stallStart + 3_000, com.rokid.context.core.model.context.ContextState.UNKNOWN), TraceEvent.Backoff(s.stallStart + 4_000, 0))
        val ev = (s.events + inside).sortedBy { it.tMs }
        val r = CaptureSimulator.replay(ev.asSequence(), ReplayFixtures.manifest(endMs = s.endMs))
        assertEquals(0, r.failed, r.mismatches.toString())
        assertEquals(s.recordedOpens.size, r.opens.size, r.opens.toString())
        assertTrue(r.opens.none { it.atMs > s.stallStart + 250 && it.atMs < s.stallEnd }, r.opens.toString())
    }

    /** Real traces: the first recorded open after a stall follows its end by up to 1254 ms (beyond the 500 ms LATE slack). The first decision after a stall matches up to 1300 ms later. */
    @Test fun theFirstDecisionAfterAStallMatchesARecordedOpenUpTo1300MsLater() {
        val s = scenario(resumeDelay = 1_200)
        val r = run(s)
        assertEquals(0, r.failed, r.mismatches.toString())
        assertEquals(0, r.inexact, r.inexactReasons.toString())
        assertEquals(s.recordedOpens.size, r.opens.size, "a post-stall decision waits for its twin instead of deciding again every second: ${r.opens}")
    }

    /** The slack is for the first decision after a stall only: a late recorded open elsewhere still fails (500 ms), see CaptureSimulatorTest.lateRecordedOpenBeyondLateSlackFails. */
    @Test fun stallSlackIsNotGrantedToADecisionAwayFromAStall() {
        assertEquals(1_300L, ReplayRule.STALL_LATE_SLACK_MS)
        assertTrue(ReplayRule.opensMatch(2_000, EpisodeCause.TIMER, 700, EpisodeCause.TIMER, ReplayRule.STALL_LATE_SLACK_MS))
        assertFalse(ReplayRule.opensMatch(2_001, EpisodeCause.TIMER, 700, EpisodeCause.TIMER, ReplayRule.STALL_LATE_SLACK_MS))
        assertFalse(ReplayRule.opensMatch(2_000, EpisodeCause.TIMER, 1_499, EpisodeCause.TIMER))
    }

    // ---- the stall uncertainty belongs to the resume decision only (Codex review of 7be5f473) ----

    private fun withoutEpisodeAt(s: Scenario, at: Long) = s.events.filterNot { it is TraceEvent.Episode && (it.tMs == at || it.tMs == at + 800) }

    private fun runEvents(s: Scenario, ev: List<TraceEvent>) = CaptureSimulator.replay(ev.asSequence(), ReplayFixtures.manifest(endMs = s.endMs))

    /** A wrong decision in the window of a stall (here an extra TIMER decision before it: its recorded open is missing) is NOT hidden by the stall: the window fails. */
    @Test fun anUnrelatedWrongDecisionInAStallWindowStillFails() {
        val s = scenario(resumeDelay = 1_700)
        val before = s.recordedOpens.last { it < s.stallStart }
        val r = runEvents(s, withoutEpisodeAt(s, before))
        assertTrue(r.failed >= 1, "failed=${r.failed} inexact=${r.inexact} ${r.mismatches}")
    }

    /** A gate disagreement in the same window as an excused resume latency still fails the window. */
    @Test fun aGateRegressionInAStallWindowStillFails() {
        val s = scenario(resumeDelay = 1_700)
        val before = s.recordedOpens.last { it < s.stallStart }
        val r = runEvents(s, (s.events + listOf(ReplayFixtures.score(before + 520, 1, scene = 1), ReplayFixtures.skip(before + 520, 1, "DUPLICATE"))).sortedBy { it.tMs })   // the gate would KEEP
        assertTrue(r.failed >= 1 && r.mismatches.any { it.kind == "gate" }, "failed=${r.failed} inexact=${r.inexact} ${r.mismatches}")
    }

    /** The 1300 ms slack is for the FIRST decision after the stall: the second one (held for 1301 ms) has the normal 500 ms, so a recorded open 2200 ms after the resume is not its twin (it decides within 1301..1551 ms). */
    @Test fun theSecondDecisionAfterAStallDoesNotReuseTheStallSlack() {
        val s = scenario(resumeDelay = 2_200)
        val r = run(s)
        assertTrue(r.failed >= 1, "failed=${r.failed} inexact=${r.inexact} ${r.mismatches}")
        assertTrue(r.mismatches.any { it.kind == "open_extra" && it.atMs > s.stallEnd + 1_300 }, r.mismatches.toString())
    }

    /** The resume latency is excused only for a recorded open with the cause of the resume decision: a different cause (here RESV where the replay decides TIMER) fails. */
    @Test fun aWrongCauseAfterTheStallStillFails() {
        val s = scenario(resumeDelay = 300)
        val at = s.recordedOpens.first { it > s.stallEnd }
        val ev = s.events.filterNot { it is TraceEvent.Episode && (it.tMs == at || it.tMs == at + 800) } + ReplayFixtures.timerEpisode(at, EpisodeCause.RESV)
        val r = runEvents(s, ev.sortedBy { it.tMs })
        assertTrue(r.failed >= 1, "failed=${r.failed} inexact=${r.inexact} ${r.mismatches}")
    }

    /** The same with the recorded open arriving AFTER the resume decision's slack ran out: still only a recorded open of the decision's cause excuses it. */
    @Test fun aLateRecordedOpenOfAnotherCauseDoesNotExcuseTheResumeDecision() {
        val s = scenario(resumeDelay = 1_700)
        val at = s.recordedOpens.first { it > s.stallEnd }
        val ev = s.events.filterNot { it is TraceEvent.Episode && (it.tMs == at || it.tMs == at + 800) } + ReplayFixtures.timerEpisode(at, EpisodeCause.RESV)
        val r = runEvents(s, ev.sortedBy { it.tMs })
        assertTrue(r.failed >= 1 && r.inexactReasons[Inexact.STALL] == null && r.mismatches.none { it.kind == "stall_resume" }, "failed=${r.failed} inexact=${r.inexact} ${r.mismatches}")
    }

    /** The excuse is bounded (twice the slack): a recorded open 3 s after the resume is not explained by the stall. */
    @Test fun aRecordedOpenBeyondTheExcuseHorizonFails() {
        val r = run(scenario(resumeDelay = 3_000))
        assertTrue(r.failed >= 1 && r.inexactReasons[Inexact.STALL] == null, "failed=${r.failed} inexact=${r.inexact} ${r.mismatches}")
    }

    /** Without any recorded open after the stall the resume decision has no twin to be late for: it is an extra and fails. */
    @Test fun aResumeDecisionWithNoRecordedTwinFails() {
        val s = scenario(resumeDelay = 300)
        val at = s.recordedOpens.first { it > s.stallEnd }
        val r = runEvents(s, withoutEpisodeAt(s, at).filter { it.tMs < at || it is TraceEvent.Close || it is TraceEvent.Gyro || it is TraceEvent.Accel })
        assertTrue(r.failed >= 1 && r.mismatches.any { it.kind == "open_extra" }, "failed=${r.failed} inexact=${r.inexact} ${r.mismatches}")
    }

    /** One recorded open explains ONE replayed decision: when it excuses the stalled resume decision it is not also the twin of the second decision, which stays an extra. */
    @Test fun aRecordedOpenIsNotConsumedByTheExcuseAndTheTwinAtOnce() {
        val s = scenario(resumeDelay = 1_700)
        val r = run(s)
        assertTrue(r.mismatches.any { it.kind == "stall_resume" }, r.mismatches.toString())
        assertTrue(r.mismatches.any { it.kind == "open_extra" && it.atMs > s.stallEnd + 1_300 }, "the second decision stays an extra: ${r.mismatches}")
        assertEquals(1, r.failed, "failed=${r.failed} inexact=${r.inexact} ${r.mismatches}")
    }

    @Test fun stallsAreCountedWithTheirDeliveryGap() {
        val r = run(scenario(resumeDelay = 300, stallLen = 6_000))
        assertEquals(1, r.stalls.count)
        assertTrue(r.stalls.ms in 6_000L..6_040L, "from the last event delivered before the stall to the burst: ${r.stalls.ms}")   // the 20 ms sensor grid decides the exact ms
        val still = CaptureSimulator.replay(ReplayFixtures.trace(ReplayFixtures.timerOracle(START + 60_000).events + sensors(START, START + 60_000, 0L..-1L), endMs = START + 60_000).asSequence(), ReplayFixtures.manifest())
        assertEquals(0, still.stalls.count)
    }

    // ---- (2) baseline and candidate decide alike (the 154 vs 122 gap of the real trace) ----

    /**
     * The identity plan as a candidate changes nothing the recording was made with (a plan runs the SETTLE wake; the recording here is SETTLE on the T0 gaps). Its opens must
     * equal the recorded config's, stall or not. Before the stall model the BASE re-decided every second inside the stall (lastBriefAt moves only on the recorded close) while
     * the candidate, with its own cadence, decided once.
     */
    @Test fun identityCandidateDecidesAsManyOpensAsTheBaselineAcrossAStall() {
        val settle = ReplayFixtures.initial(wake = CameraWake.SETTLE, settledMs = 10_000L)
        val s = scenarioSettle(resumeDelay = 300, initial = settle)
        val identity = ReplayCandidate.of(CapturePlan(configHash = "f5782e0d8610", config = PlanResolver.identity(), meta = PlanMeta("identity", 0)))
        val base = run(s)
        val cand = run(s, identity)
        assertEquals(base.opens.size, cand.opens.size, "candidate ${cand.opens.size} vs baseline ${base.opens.size}")
        assertEquals(s.recordedOpens.size, base.opens.size, "baseline: one decision per recorded open: ${base.opens}")
        assertEquals(0, base.failed, base.mismatches.toString())
    }

    /** As [scenario] for a recording made under SETTLE wake with a 10 s gap (the T0 SETTLE profile); a local oracle: opens at `lastBrief + 10 s - lead`, closes 800 ms later. */
    private fun scenarioSettle(resumeDelay: Long, initial: List<TraceEvent>): Scenario {
        val stallLen = 6_000L
        val all = ArrayList<TraceEvent>(); val opens = ArrayList<Long>()
        var lastBrief = START; var k = 0; var stall: LongRange? = null
        while (true) {
            val due = lastBrief + 10_000L - ReplayFixtures.leadAfter(k)
            if (due > START + 100_000L) break
            val at = if (stall == null && due >= PRE_ROLL_END + 15_000L) { stall = (due - 1_500)..(due - 1_500 + stallLen); stall.last + resumeDelay } else due
            opens += at; all += ReplayFixtures.timerEpisode(at)
            lastBrief = at + 800
            k++
        }
        val end = opens.last() + 800 + 1_000
        val st = stall!!
        return Scenario(ReplayFixtures.trace(all + sensors(START, end, st), endMs = end, initial = initial), opens.filter { it >= PRE_ROLL_END }, st.first, st.last, end)
    }

    // ---- (3) exactness of dwell/onset ----

    @Test fun aDwellOrOnsetOpenIsAsyncOnlyWithoutAReplayTwin() {
        val dwell = listOf(TraceEvent.Episode(100, true, EpisodeCause.DWELL), TraceEvent.Episode(900, false, EpisodeCause.DWELL, 600, 150, EpisodeOutcome.OK))
        assertEquals(setOf(Inexact.ASYNC_CALLBACK), Exactness.classify(dwell))                       // twins unknown: conservative
        assertEquals(setOf(Inexact.ASYNC_CALLBACK), Exactness.classify(dwell, twinned = emptySet()))
        assertEquals(emptySet(), Exactness.classify(dwell, twinned = setOf(100L)))
        val onset = listOf(TraceEvent.Episode(100, true, EpisodeCause.ONSET))
        assertEquals(emptySet(), Exactness.classify(onset, twinned = setOf(100L)))
        assertEquals(setOf(Inexact.CAPTURE_NOW), Exactness.classify(listOf(TraceEvent.Episode(100, true, EpisodeCause.NOW)), twinned = setOf(100L)))
    }

    /** A recorded dwell open that the replay cannot reproduce (here: no sensors at all) is the genuinely unmodelled case: the window stays ASYNC_CALLBACK and the open is a mismatch. */
    @Test fun aDwellOpenWithoutATwinStaysAsyncAndVisible() {
        val timers = ReplayFixtures.timerOracle(untilMs = 19_000).events
        val dwell = listOf(TraceEvent.Episode(22_200, true, EpisodeCause.DWELL), TraceEvent.Episode(23_000, false, EpisodeCause.DWELL, 22_700, 22_250, EpisodeOutcome.OK))
        val r = CaptureSimulator.replay(ReplayFixtures.trace(timers + dwell, endMs = START + 25_000).asSequence(), ReplayFixtures.manifest())
        assertTrue((r.inexactReasons[Inexact.ASYNC_CALLBACK] ?: 0) >= 1, r.inexactReasons.toString())
        assertTrue(r.mismatches.any { it.kind == "open_missed" && it.atMs == 22_200L }, r.mismatches.toString())
        assertEquals(0, r.failed)
    }

    // ---- (4) bounds and report fields ----

    /** A trace with a stall (resume matched) and, after its last timer, a recorded dwell open the replay cannot reproduce (no sensors for it): one ASYNC window to bound. */
    @Test fun reportBoundsTheOpensOfTheExcludedWindowsAndReportsTheStalls() {
        val s = scenario(resumeDelay = 300)
        val at = s.recordedOpens.last() + 2_000
        val end = s.endMs + 2_500
        val ev = s.events.filterNot { it is TraceEvent.Close } + listOf(
            TraceEvent.Episode(at, true, EpisodeCause.DWELL), TraceEvent.Episode(at + 800, false, EpisodeCause.DWELL, at + 500, at + 50, EpisodeOutcome.OK), TraceEvent.Close(end, "stop"),
        )
        val rep = CounterfactualReplay.run({ ev.asSequence() }, ReplayFixtures.manifest(endMs = end))
        assertEquals(2, rep.inexactReasons[Inexact.ASYNC_CALLBACK], rep.inexactReasons.toString())   // both windows that contain the dwell open
        assertEquals(0, rep.baseFailed, rep.warnings.toString())
        assertTrue(rep.inexactMinutes > 0.0 && rep.inexactSharePct > 0.0, "minutes=${rep.inexactMinutes} share=${rep.inexactSharePct}")
        assertEquals(1, rep.stallCount)
        assertTrue(rep.stallSeconds in 6.0..6.04, rep.stallSeconds.toString())
        assertTrue(rep.stallSharePct > 0.0)
        val b = rep.baseline
        val recorded = s.recordedOpens.size + 1
        assertTrue(b.opensLow <= recorded && recorded <= b.opensHigh, "recorded $recorded not in [${b.opensLow}, ${b.opensHigh}]")
        assertTrue(rep.warnings.any { it.contains("stall") }, rep.warnings.toString())
        val j = rep.toJson().toString()
        assertTrue("\"opensLow\":${b.opensLow}" in j && "\"opensHigh\":${b.opensHigh}" in j && "\"glanceOpens\"" in j && "\"stalls\":{\"count\":1" in j && "\"inexactMinutes\"" in j && "\"opensRangeBasis\":\"excluded windows only" in j, j)
    }

    @Test fun boundsAreTheRecordedOpensInsideTheExcludedSpansPlusTheReplayedOnesOutside() {
        val spans = listOf(InexactSpan(1_000, 2_000, setOf(Inexact.STALL)))
        fun o(vararg t: Long) = t.map { ReplayOpen(it, EpisodeCause.TIMER) }
        assertEquals(5 to 5, CounterfactualReplay.bounds(o(10, 20, 30, 40, 50), emptyList(), listOf(10L)))
        // replayed opens are placed 500 ms later: 3 outside + 2 recorded inside = low 5; the 2 replayed inside widen it to 7; the span is half-open (the recorded open at 2_000 is outside)
        assertEquals(5 to 7, CounterfactualReplay.bounds(o(10, 20, 2_500, 600, 1_000), spans, listOf(1_000L, 1_900, 2_000)))
        // a decision 300 ms before the recorded open that starts the span is that open's twin: placed in recorded time (+ the 500 ms late slack) it falls inside the span with it
        // (low 1: the recorded open; high 2), instead of outside it where the pair would be counted twice (low 2)
        assertEquals(1 to 2, CounterfactualReplay.bounds(o(700), spans, listOf(1_000L)))
        assertEquals(2 to 2, CounterfactualReplay.bounds(o(400), spans, listOf(1_000L)))      // 400 + 500 is still before the span: not its twin
    }

    @Test fun anExactReplayHasAPointRangeAndNoStallWarning() {
        val s = scenario(resumeDelay = 300)
        val rep = CounterfactualReplay.run({ s.events.asSequence() }, ReplayFixtures.manifest(endMs = s.endMs))
        assertEquals(0, rep.inexactWindows)
        assertEquals(rep.baseline.opens, rep.baseline.opensLow); assertEquals(rep.baseline.opens, rep.baseline.opensHigh)
        assertEquals(0.0, rep.inexactMinutes)
        assertEquals(1, rep.stallCount)
    }

    /** `glanceOpens` counts DWELL and ONSET decisions only: the counterpart of the shadow's `shOpen`. */
    @Test fun glanceOpensCountDwellAndOnsetOnly() {
        val end = START + 25_000
        val sensors = (START..end step 20).flatMap { t ->
            val turning = t in 20_000..20_700
            listOf(TraceEvent.Gyro(t, t, 0f, 0f, if (turning) 1.5f else 0.02f), TraceEvent.Accel(t, t, 0f, 0f, 0f))
        }
        val timers = ReplayFixtures.timerOracle(untilMs = 19_000).events
        val async = listOf(
            TraceEvent.Episode(20_000, true, EpisodeCause.ONSET), TraceEvent.Episode(21_000, false, EpisodeCause.ONSET, 20_500, 20_050, EpisodeOutcome.OK),
            TraceEvent.Episode(22_200, true, EpisodeCause.DWELL), ReplayFixtures.score(22_720, 1, scene = 1, dwellArmed = true),
            ReplayFixtures.keep(22_720, 1, keptAt = 22_700), TraceEvent.Episode(23_000, false, EpisodeCause.DWELL, 22_700, 22_250, EpisodeOutcome.OK),
        )
        val ev = ReplayFixtures.trace(sensors + timers + async, endMs = end)
        val rep = CounterfactualReplay.run({ ev.asSequence() }, ReplayFixtures.manifest())
        assertEquals(2, rep.baseline.glanceOpens)
        assertEquals(1, rep.baseline.glanceAdmissions, "one dwell glance was admitted (onset is not an admission)")
        assertTrue(rep.toJson().toString().contains("\"glanceAdmissions\":1"))
        assertEquals(0, rep.inexactWindows, "dwell and onset opens with a twin are exact windows: ${rep.inexactReasons}")
        assertEquals(0, rep.baseFailed)
    }
}
