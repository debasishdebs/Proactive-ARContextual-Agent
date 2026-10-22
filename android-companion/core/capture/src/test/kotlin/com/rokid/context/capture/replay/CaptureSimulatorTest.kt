package com.rokid.context.capture.replay

import com.rokid.context.capture.replay.ReplayFixtures.LEAD
import com.rokid.context.capture.replay.ReplayFixtures.PRE_ROLL_END
import com.rokid.context.capture.replay.ReplayFixtures.START
import com.rokid.context.capture.trace.EpisodeCause
import com.rokid.context.capture.trace.EpisodeOutcome
import com.rokid.context.capture.trace.GateOutcome
import com.rokid.context.capture.trace.Inexact
import com.rokid.context.capture.trace.ReplayRule
import com.rokid.context.capture.trace.TraceEvent
import com.rokid.context.core.model.MotionState
import com.rokid.context.core.model.PlanMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptureSimulatorTest {
    private fun run(events: List<TraceEvent>, snap: com.rokid.context.capture.trace.StartSnapshot = ReplayFixtures.snapshot, candidate: ReplayCandidate? = null) =
        CaptureSimulator.replay(events.asSequence(), ReplayFixtures.manifest(snap), candidate)

    /** Two minutes of a still scene: the timer opens recomputed by `CameraPolicy.decide` equal the recorded ones; the arithmetic oracle is in [ReplayFixtures.timerOracle]. */
    @Test fun recomputedOpensEqualRecorded() {
        val o = ReplayFixtures.timerOracle(untilMs = START + 120_000L)
        val r = run(ReplayFixtures.trace(o.events))
        val expected = o.opens.filter { it >= PRE_ROLL_END }
        assertTrue(expected.size >= 15, "the fixture has opens after the pre-roll")
        assertEquals(expected, r.opens.map { it.atMs })
        assertTrue(r.opens.all { it.cause == EpisodeCause.TIMER })
        assertEquals(0, r.failed)
        assertEquals(expected.size + 1, r.passed)            // one window ends at each recorded open, plus the last
        assertEquals(0, r.inexact)
        assertTrue(r.mismatches.isEmpty())
    }

    /** The back-off ladder (5, 10, 20, 40 s; a 60 s gap would meet the reserved photo attempt, S83) comes from the recorded `bo` events: the recomputed opens follow it. */
    @Test fun backoffLadderIsRecomputedFromRecordedStillStep() {
        val o = ReplayFixtures.timerOracle(untilMs = START + 300_000L) { k -> minOf(k, 3) }
        val r = run(ReplayFixtures.trace(o.events, endMs = START + 300_000L))
        assertEquals(o.opens.filter { it >= PRE_ROLL_END }, r.opens.map { it.atMs })
        assertEquals(0, r.failed)
        assertTrue(o.opens.zipWithNext { a, b -> b - a }.max() >= 40_000 - ReplayFixtures.STARTUP - 800, "the ladder reaches 40 s")
    }

    /**
     * Mutation guard (brief): the opens are recomputed, not echoed. A wrong `stillStep` fed to `CameraPolicy.decide` (here the recorded `bo` events are
     * shifted by one step) moves the recomputed opens away from the recorded ones, and the verdict is FAILED. An echoing replay would still pass.
     */
    @Test fun recomputedOpensCatchABrokenPolicyInput() {
        val o = ReplayFixtures.timerOracle(untilMs = START + 300_000L) { k -> minOf(k, 3) }
        val broken = o.events.map { if (it is TraceEvent.Backoff) it.copy(stillStep = it.stillStep + 1) else it }
        val r = run(ReplayFixtures.trace(broken, endMs = START + 300_000L))
        assertTrue(r.failed > 0, "a stillStep mismatch must fail windows")
        assertTrue(r.mismatches.any { it.kind == "open_missed" || it.kind == "open_extra" })
        // the same trace with the true steps is clean
        assertEquals(0, run(ReplayFixtures.trace(o.events, endMs = START + 300_000L)).failed)
    }

    /** A scripted two-minute session: the opens, and every `gt` (keep, duplicate, blur) reproduced exactly. Stands in for the real-session trace of Task 23a. */
    @Test fun exactReplayMatchesGoldenSessionTrace() {
        val o = ReplayFixtures.timerOracle(untilMs = START + 120_000L)
        val extra = ArrayList<TraceEvent>(); val expectedGates = ArrayList<TraceEvent.Gate>()
        var seq = 0L
        o.opens.forEachIndexed { i, open ->
            if (open < PRE_ROLL_END) return@forEachIndexed
            seq++
            val t = open + 520
            when (i % 3) {
                0 -> { extra += ReplayFixtures.score(t, seq, scene = i); extra += ReplayFixtures.keep(t, seq, keptAt = open + 500); expectedGates += ReplayFixtures.keep(t, seq, open + 500) }
                1 -> { extra += ReplayFixtures.score(t, seq, scene = i - 1); extra += ReplayFixtures.skip(t, seq, "DUPLICATE"); expectedGates += ReplayFixtures.skip(t, seq, "DUPLICATE") }
                else -> { extra += ReplayFixtures.score(t, seq, scene = i, blur = 30f); extra += ReplayFixtures.skip(t, seq, "BLUR"); expectedGates += ReplayFixtures.skip(t, seq, "BLUR") }
            }
        }
        val r = run(ReplayFixtures.trace(o.events + extra))
        assertEquals(0, r.failed, r.mismatches.toString())
        assertEquals(0, r.inexact)
        assertTrue(r.keeps >= 5 && r.skips >= 10, "keeps=${r.keeps} skips=${r.skips}")
        assertEquals(expectedGates.size, r.gates.size)
        r.gates.zip(expectedGates).forEach { (a, b) -> assertTrue(ReplayRule.gatesMatch(a, b), "$a vs $b") }
    }

    /** A recorded gate that the gate cannot reproduce fails the window (gates are compared exactly where the inputs are fully recorded). */
    @Test fun aGateMismatchFailsTheWindow() {
        val o = ReplayFixtures.timerOracle(untilMs = START + 60_000L)
        val open = o.opens.first { it >= PRE_ROLL_END }
        val body = o.events + listOf(ReplayFixtures.score(open + 520, 1, scene = 1), ReplayFixtures.skip(open + 520, 1, "DUPLICATE"))   // the gate would KEEP
        val r = run(ReplayFixtures.trace(body, endMs = START + 60_000L))
        assertEquals(1, r.failed)
        assertTrue(r.mismatches.any { it.kind == "gate" })
    }

    /**
     * Window selection (K-wave): three moving samples inside 1.5 s are held (WINDOW), the flush picks the sharpest (seq 2, blur 150) and keeps it; the
     * keep is booked at the flush time. Expected values by hand from KeyframeGate's rules.
     */
    @Test fun windowSelectionReproduced() {
        val o = ReplayFixtures.timerOracle(untilMs = START + 60_000L)
        val open = o.opens.first { it >= PRE_ROLL_END }
        fun moving(dt: Long, seq: Long, scene: Int, blur: Float) = ReplayFixtures.score(open + dt, seq, scene, blur, motion = MotionState.HEAD_MOVING, nowMs = open + dt)
        val inEpisode = listOf(
            moving(100, 1, 1, 80f), ReplayFixtures.skip(open + 100, 1, "WINDOW"),
            moving(300, 2, 2, 150f), ReplayFixtures.skip(open + 300, 2, "WINDOW"),
            moving(500, 3, 3, 100f), ReplayFixtures.skip(open + 500, 3, "WINDOW"),
            TraceEvent.Flush(open + 700, "timer", nowMs = open + 690), ReplayFixtures.keep(open + 700, 2, keptAt = open + 690),
        )
        val r = run(ReplayFixtures.trace(o.events + inEpisode, endMs = START + 60_000L))
        assertEquals(0, r.failed, r.mismatches.toString())
        assertEquals(listOf(1L, 2L, 3L, 2L), r.gates.map { it.seq })
        assertEquals(listOf(GateOutcome.SKIP, GateOutcome.SKIP, GateOutcome.SKIP, GateOutcome.KEEP), r.gates.map { it.outcome })
        // a recording that names a different winner (the blurrier seq 3) does not match
        val wrong = inEpisode.map { if (it is TraceEvent.Gate && it.outcome == GateOutcome.KEEP) ReplayFixtures.keep(it.tMs, 3, it.keptAtMs!!) else it }
        assertTrue(run(ReplayFixtures.trace(o.events + wrong, endMs = START + 60_000L)).failed > 0)
    }

    /** Decisions before `PreRollEnd` are discarded: a wrong open and a wrong gate inside the pre-roll neither fail nor appear. */
    @Test fun preRollDiscarded() {
        val o = ReplayFixtures.timerOracle(untilMs = START + 120_000L)
        val clean = run(ReplayFixtures.trace(o.events))
        val noise = listOf(
            TraceEvent.Episode(START + 2_000, true, EpisodeCause.DWELL), TraceEvent.Episode(START + 2_700, false, EpisodeCause.DWELL, null, null, EpisodeOutcome.OK),
            ReplayFixtures.score(START + 2_600, 99, scene = 7), ReplayFixtures.skip(START + 2_600, 99, "CAPPED"),
        )
        val r = run(ReplayFixtures.trace(o.events + noise))
        assertEquals(0, r.failed)
        assertTrue(r.opens.none { it.atMs < PRE_ROLL_END })
        assertEquals(clean.passed, r.passed)
        assertEquals(clean.opens, r.opens)
    }

    @Test fun approximateTraceRefused() {
        val approx = ReplayFixtures.snapshot.copy(sampleEvery = 2, exact = false)
        val e = assertFailsWith<IllegalArgumentException> { run(ReplayFixtures.trace(emptyList()), snap = approx) }
        assertEquals("approximate trace", e.message)
    }

    @Test fun deterministic() {
        val o = ReplayFixtures.timerOracle(untilMs = START + 120_000L)
        val ev = ReplayFixtures.trace(o.events + ReplayFixtures.score(o.opens.first { it >= PRE_ROLL_END } + 520, 1, 1) + ReplayFixtures.keep(o.opens.first { it >= PRE_ROLL_END } + 520, 1))
        assertEquals(run(ev), run(ev))
    }

    /** The gate sees the recorded decision time (`sc.nowMs`), not the append time: a 3,000 ms non-dwell gap that is exactly met at `nowMs` is a KEEP although `tMs` is 10 ms earlier. */
    @Test fun gateUsesTheRecordedDecisionTimeNotTheAppendTime() {
        val o = ReplayFixtures.timerOracle(untilMs = START + 60_000L)
        val open = o.opens.first { it >= PRE_ROLL_END }
        val body = o.events + listOf(
            ReplayFixtures.score(open + 100, 1, scene = 1), ReplayFixtures.keep(open + 100, 1, keptAt = open + 100),
            ReplayFixtures.score(open + 3_090, 2, scene = 2, nowMs = open + 3_100), ReplayFixtures.keep(open + 3_090, 2, keptAt = open + 3_100),
        )
        val r = run(ReplayFixtures.trace(body, endMs = START + 60_000L))
        assertEquals(0, r.failed, r.mismatches.toString())
        assertEquals(2, r.keeps)
    }

    /** The flush decides at the clock `flushWindow()` read (`fl.nowMs`): 5,990 ms after the last keep is still inside the 6 s moving gap (nothing kept, no `gt`), although the append time is 6,000 ms. */
    @Test fun flushUsesTheRecordedFlushTime() {
        val o = ReplayFixtures.timerOracle(untilMs = START + 60_000L)
        val open = o.opens.first { it >= PRE_ROLL_END }
        val body = o.events + listOf(
            ReplayFixtures.score(open + 50, 1, scene = 1), ReplayFixtures.keep(open + 50, 1, keptAt = open + 50),
            ReplayFixtures.score(open + 60, 2, scene = 2, motion = MotionState.HEAD_MOVING), ReplayFixtures.skip(open + 60, 2, "WINDOW"),
            TraceEvent.Flush(open + 6_050, "timer", nowMs = open + 6_040),
        )
        val r = run(ReplayFixtures.trace(body, endMs = START + 60_000L))
        assertEquals(0, r.failed, r.mismatches.toString())
        assertEquals(1, r.keeps)
    }

    /** Three unusable brief checks in a row (the `dk` event) put the policy on the quiet cadence: the first open is 15 s out, then the 5 s cadence returns with `dk 0`. */
    @Test fun darkStreakDrivesTheQuietCadence() {
        val first = START + 15_000 - LEAD
        val second = first + 800 + 5_000 - LEAD
        val body = listOf(TraceEvent.DarkStreak(START, 3)) + ReplayFixtures.timerEpisode(first) + TraceEvent.DarkStreak(first + 800, 0) + ReplayFixtures.timerEpisode(second)
        val r = run(ReplayFixtures.trace(body, endMs = second + 2_000))
        assertEquals(listOf(first, second), r.opens.map { it.atMs }, r.mismatches.toString())
        assertEquals(0, r.failed, r.mismatches.toString())
    }

    /** S83: with a 120 s timer gap the reserved timer photo attempt still opens 60 s after the last attempt, with its own cause. */
    @Test fun reservedPhotoAttemptIsRecomputed() {
        val resv1 = START + 60_000
        val resv2 = resv1 + 50 + 60_000
        val body = ReplayFixtures.timerEpisode(resv1, EpisodeCause.RESV) + ReplayFixtures.timerEpisode(resv2, EpisodeCause.RESV)
        val r = run(ReplayFixtures.trace(body, endMs = resv2 + 2_000, initial = ReplayFixtures.initial(settledMs = 120_000)))
        assertEquals(listOf(resv1 to EpisodeCause.RESV, resv2 to EpisodeCause.RESV), r.opens.map { it.atMs to it.cause })
        assertEquals(0, r.failed, r.mismatches.toString())
    }

    // ---- the controller ruling: replay is approximate; inexact windows are excluded from pass/fail and counted ----

    @Test fun captureNowWindowIsInexactAndExcluded() {
        val o = ReplayFixtures.timerOracle(untilMs = START + 120_000L)
        val k = o.opens.first { it >= PRE_ROLL_END } + 5_300                     // between two timer opens: a "Capture now" the trace cannot explain
        val now = listOf(
            TraceEvent.Episode(k, true, EpisodeCause.NOW), ReplayFixtures.score(k + 400, 1, 1, force = true), ReplayFixtures.keep(k + 400, 1),
            TraceEvent.Episode(k + 900, false, EpisodeCause.NOW, k + 400, k + 50, EpisodeOutcome.OK),
        )
        val clean = run(ReplayFixtures.trace(o.events))
        val r = run(ReplayFixtures.trace(o.events + now))
        assertTrue(r.inexact >= 1)
        assertTrue((r.inexactReasons[Inexact.CAPTURE_NOW] ?: 0) >= 1)
        assertEquals(0, r.failed, r.mismatches.toString())                       // the unexplained open is not a failure, its window is excluded
        assertTrue(r.passed + r.inexact >= clean.passed)
    }

    @Test fun bindFailureWindowIsInexact() {
        val o = ReplayFixtures.timerOracle(untilMs = START + 120_000L)
        val failing = o.events.map { if (it is TraceEvent.Episode && !it.open && it.tMs > PRE_ROLL_END + 10_000 && it.tMs < PRE_ROLL_END + 20_000) it.copy(outcome = EpisodeOutcome.BUSY) else it }
        val r = run(ReplayFixtures.trace(failing))
        assertTrue((r.inexactReasons[Inexact.BIND_FAILURE] ?: 0) >= 1)
        assertEquals(0, r.failed, r.mismatches.toString())
    }

    /**
     * Found by the real-session parity test (Task 23a): an open that FAILS (busy, bind) is recorded as `ep` open and `ep` close at the SAME instant, and the close
     * starts a back-off. The window that ENDS at that open is exact (the failure is in the next window), so the replay must still make its own decision at that
     * instant. It used to run the tick after both events, saw the back-off and decided nothing: a failed window for an open the session decided correctly.
     */
    @Test fun anOpenThatFailsAtTheSameInstantIsStillRecomputed() {
        val o = ReplayFixtures.timerOracle(untilMs = START + 120_000L)
        val at = o.opens.filter { it >= PRE_ROLL_END }[8]
        val failing = o.events.filter { it.tMs < at } + listOf(
            TraceEvent.Episode(at, true, EpisodeCause.TIMER), TraceEvent.Episode(at, false, EpisodeCause.TIMER, openedAtMs = at, outcome = EpisodeOutcome.BUSY),
        )
        val r = run(ReplayFixtures.trace(failing, endMs = at + 25_000))
        assertEquals(0, r.failed, r.mismatches.toString())
        assertTrue(r.passed >= 8, "the windows before the failed open passed: ${r.passed}")
        assertTrue(r.mismatches.none { it.kind == "open_missed" && it.atMs == at }, r.mismatches.toString())
        assertTrue((r.inexactReasons[Inexact.BIND_FAILURE] ?: 0) >= 1)
    }

    /**
     * A head turn at 20.0-20.7 s over three timer checks. By hand from the glance model: the first over-threshold sample (20,000) is the onset and the loop is
     * kicked, so the ONSET brief check opens at once; the bout ends at 20,700, settles 1.5 s later and the glance fires on the 22,200 sample, which opens a DWELL
     * check on the next tick (admission: 21 s since the last keyframe is past the 20 s novelty window). The ONSET episode runs until 21,000 because the camera
     * stays up while the head decelerates. The replay recomputes both opens from the sensors alone; their windows are inexact (async callbacks), the timer
     * window before them still passes.
     */
    @Test fun asyncDwellWindowIsInexactButStillReplayed() {
        val end = START + 25_000
        val sensors = (START..end step 20).flatMap { t ->
            val turning = t in 20_000..20_700
            listOf(TraceEvent.Gyro(t, t, 0f, 0f, if (turning) 1.5f else 0.02f), TraceEvent.Accel(t, t, 0f, 0f, 0f))
        }
        val timers = ReplayFixtures.timerOracle(untilMs = 19_000).events
        val async = listOf(
            TraceEvent.Episode(20_000, true, EpisodeCause.ONSET), TraceEvent.Episode(21_000, false, EpisodeCause.ONSET, 20_500, 20_050, EpisodeOutcome.OK),
            TraceEvent.Episode(22_200, true, EpisodeCause.DWELL), ReplayFixtures.score(22_720, 1, scene = 1, motion = MotionState.STILL, dwellArmed = true),
            ReplayFixtures.keep(22_720, 1, keptAt = 22_700), TraceEvent.Episode(23_000, false, EpisodeCause.DWELL, 22_700, 22_250, EpisodeOutcome.OK),
        )
        val r = run(ReplayFixtures.trace(sensors + timers + async, endMs = end))
        assertEquals(listOf(EpisodeCause.TIMER, EpisodeCause.ONSET, EpisodeCause.DWELL), r.opens.map { it.cause }, r.opens.toString())
        assertTrue(r.opens[1].atMs in 20_000..20_250, r.opens.toString())
        assertTrue(r.opens[2].atMs in 22_200..22_450, r.opens.toString())
        assertEquals(1, r.passed)
        assertEquals(3, r.inexact)
        assertEquals(3, r.inexactReasons[Inexact.ASYNC_CALLBACK])
        assertEquals(0, r.failed, r.mismatches.toString())
    }

    // ---- base versus candidate ----

    @Test fun configHashOnResults() {
        val o = ReplayFixtures.timerOracle(untilMs = START + 60_000L)
        val ev = ReplayFixtures.trace(o.events, endMs = START + 60_000L, initial = ReplayFixtures.initial(hash = "abc123def456", mode = PlanMode.SHADOW))
        val base = run(ev)
        assertEquals("abc123def456", base.configHash)
        assertTrue(base.compared)
        assertEquals("0", run(ReplayFixtures.trace(o.events, endMs = START + 60_000L)).configHash)       // settings: no plan
        val cand = run(ev, candidate = ReplayCandidate(configHash = "ffffffffffff"))
        assertEquals("ffffffffffff", cand.configHash)
        assertFalse(cand.compared)
        assertEquals(0, cand.passed + cand.failed + cand.inexact)               // a candidate is not checked against the recording
    }

    @Test fun candidateGateParamsChangeTheKeeps() {
        val o = ReplayFixtures.timerOracle(untilMs = START + 90_000L)
        val ops = o.opens.filter { it >= PRE_ROLL_END }.take(4)
        val frames = ops.mapIndexed { i, open -> listOf(ReplayFixtures.score(open + 520, i + 1L, scene = i), ReplayFixtures.keep(open + 520, i + 1L, open + 500)) }.flatten()
        val ev = ReplayFixtures.trace(o.events + frames, endMs = START + 90_000L)
        val base = run(ev)
        assertEquals(4, base.keeps); assertEquals(0, base.failed)
        val strict = run(ev, candidate = ReplayCandidate(configHash = "ffffffffffff", gate = com.rokid.context.glasses.capture.keyframe.GateParams(hamming = 64, bigChange = 64)))
        assertEquals(1, strict.keeps)                                           // the empty ring keeps the first frame; nothing differs by more than 64 bits after it
        assertEquals(3, strict.skips)
    }

    @Test fun aCandidateCountsTheOpensItDecidesWhereARecordedOpenCoincides() {
        val o = ReplayFixtures.timerOracle(untilMs = START + 60_000L)
        val ev = ReplayFixtures.trace(o.events, endMs = START + 60_000L)
        val base = run(ev)
        val cand = run(ev, candidate = ReplayCandidate(configHash = "ffffffffffff"))
        assertTrue(base.opens.isNotEmpty())
        assertEquals(base.opens, cand.opens)                                    // the same config as a candidate decides the same opens: a recorded open is not a pre-emption
    }
}
