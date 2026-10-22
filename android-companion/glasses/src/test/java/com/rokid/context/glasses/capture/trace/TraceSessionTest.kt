package com.rokid.context.glasses.capture.trace

import com.rokid.context.capture.trace.CloseReason
import com.rokid.context.capture.trace.EpisodeCause
import com.rokid.context.capture.trace.EpisodeOutcome
import com.rokid.context.capture.trace.GateOutcome
import com.rokid.context.capture.trace.TraceEvent
import com.rokid.context.capture.trace.TraceStatus
import com.rokid.context.core.model.CaptureMode
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.PlanMode
import com.rokid.context.glasses.capture.session.HeatReading
import com.rokid.context.glasses.capture.session.Rig
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the session offered at each `start` call (called under the session lock, so the reads below are the real state at the boundary). */
private class Spy(private val inner: TraceRecorder, private val rigOf: () -> Rig) : SessionTrace by inner {
    class At(val episodeOpen: Boolean, val quiet: Boolean, val windowEmpty: Boolean, val ok: Boolean)
    val starts = ArrayList<At>()
    override fun start(s: TraceStart): Boolean {
        val r = rigOf()
        val ok = inner.start(s)
        starts += At(r.session.episodeOpen(), r.motion.quiet(), r.gate.windowEmpty(), ok)
        return ok
    }
}

/** The recorder behind the real [com.rokid.context.glasses.capture.session.SamplingSession], the real gate and the fake camera, in virtual time. */
class TraceSessionTest {
    private val root = TraceTestKit.tempDir()
    @AfterTest fun cleanup() { root.deleteRecursively() }

    private class Fx(val t: TestScope, val root: File, val sampleEvery: Int = 1, settings: HudSettings = HudSettings(captureMode = CaptureMode.SMART), val collect: Boolean = true, encoder: com.rokid.context.glasses.capture.session.KeyframeEncoder? = null) {
        var ids = 0
        val rec = TraceRecorder(
            root, { t.testScheduler.currentTime }, { 1_700_000_000_000 + t.testScheduler.currentTime }, { "7" }, sampleEvery, io = { it.run() },
            newTraceId = { "trace-%04d-sess".format(++ids) },
        )
        lateinit var rig: Rig
        val spy = Spy(rec) { rig }
        init {
            rig = if (encoder != null) Rig(t, settings, encoder = encoder, trace = spy) else Rig(t, settings, trace = spy)
            rig.motion.addTap(rec)
            if (collect) rec.onControl(TraceTestKit.collect())
        }
        fun finish(): List<Recorded> { rig.session.stop("test"); return TraceTestKit.readAll(root) }
    }

    @Test fun startsOnlyAtCleanBoundary() = runTest {
        val fx = Fx(this, root); val r = fx.rig
        r.startSensors(); r.gyroMag = 3f // the head is turning: never quiet
        r.session.start()
        delay(30_000)
        assertTrue(fx.spy.starts.isEmpty(), "no boundary offered while the detector is not quiet")
        assertFalse(fx.rec.recording)
        r.gyroMag = 0.01f
        delay(120_000)
        assertTrue(fx.spy.starts.isNotEmpty(), "a boundary came once the head was still")
        assertTrue(fx.spy.starts.all { !it.episodeOpen && it.quiet && it.windowEmpty && it.ok }, "every start: no episode, QUIET, empty gate window")
        val traces = fx.finish()
        assertTrue(traces.isNotEmpty())
        for (t in traces) {
            val afterInitial = t.events.drop(5)
            val firstEp = afterInitial.indexOfFirst { it is TraceEvent.Episode }
            val head = if (firstEp < 0) afterInitial else afterInitial.take(firstEp)
            assertTrue(head.none { it is TraceEvent.Score || it is TraceEvent.Gate || it is TraceEvent.Flush || (it is TraceEvent.Episode) }, "nothing of an earlier episode leaks in: ${head.map { it::class.simpleName }}")
            assertTrue(t.all<TraceEvent.Episode>().let { eps -> eps.runningFold(0) { n, e -> n + if (e.open) 1 else -1 }.all { it >= 0 } }, "every ep close has its open")
        }
    }

    @Test fun aCleanTraceValidatesAndCarriesTheSchedulerEvents() = runTest {
        val fx = Fx(this, root); val r = fx.rig
        delay(1_000) // the session treats an open at elapsed 0 as "never opened"; a device is never at 0
        r.startSensors(); r.gyroMag = 0.01f
        r.session.start()
        delay(180_000)
        val t = fx.finish().first()
        assertEquals("ok", t.verification.reason, "the frozen validator accepts a live trace")
        assertEquals(TraceStatus.COMPLETE, t.manifest.recorded)
        assertTrue(t.manifest.exact)
        val tags = t.tags.toSet()
        for (needed in listOf("cx", "rs", "pf", "gp", "pl", "g", "ep", "sc", "gt", "pr", "cl")) assertTrue(needed in tags, "$needed in $tags")
        val eps = t.all<TraceEvent.Episode>()
        assertTrue(eps.filter { it.open }.all { it.cause == EpisodeCause.TIMER || it.cause == EpisodeCause.RESV })
        assertTrue(eps.filter { !it.open }.all { it.outcome == EpisodeOutcome.OK && it.firstFrameAtMs != null && it.openedAtMs != null })
        val firstScore = t.all<TraceEvent.Score>().first()
        val kept = r.keyframes.first().second
        assertEquals(kept.dhash, java.lang.Long.toHexString(firstScore.d).padStart(16, '0'), "the score carries the hashes the gate computed")
        assertTrue(firstScore.tMs >= firstScore.nowMs, "sc.nowMs is the gate's own clock reading, never after the append")
    }

    @Test fun scoreEmittedForBlurFailure() = runTest {
        val fx = Fx(this, root); val r = fx.rig
        r.soft = 100 // every frame is below the blur floor
        r.startSensors(); r.gyroMag = 0.01f
        r.session.start()
        delay(120_000)
        val t = fx.finish().first()
        val scores = t.all<TraceEvent.Score>()
        assertTrue(scores.isNotEmpty(), "blur failures are scored too")
        assertTrue(scores.all { !it.passedBlur && it.d == 0L && it.p == 0L && it.blur < 60f })
        val gates = t.all<TraceEvent.Gate>().associateBy { it.seq }
        assertTrue(scores.all { gates[it.seq]?.let { g -> g.outcome == GateOutcome.SKIP && g.reason == "BLUR" } == true }, "and each has its gt skip BLUR")
        assertEquals("ok", t.verification.reason)
    }

    @Test fun closeAndPlanEventsEmitted() = runTest {
        val fx = Fx(this, root); val r = fx.rig
        r.startSensors(); r.gyroMag = 0.01f
        r.session.start()
        delay(60_000)
        r.traceInfo = TraceTestKit.info.copy(planGen = 3, planHash = "abc123def456", planMode = PlanMode.TRIAL) // a plan activates
        delay(60_000)
        r.traceInfo = TraceTestKit.info.copy(planGen = 3, planHash = "abc123def456", planMode = PlanMode.TRIAL, settingsHash = "0f0f0f0f0f0f") // a settings edit
        delay(60_000)
        r.traceInfo = TraceTestKit.info.copy(settingsHash = "0f0f0f0f0f0f") // the plan is reverted
        delay(60_000)
        val ts = fx.finish()
        assertEquals(listOf("plan", "settings", "plan_revert", "stop"), ts.map { it.manifest.closeReason }, "one segment per configuration")
        assertTrue(ts.all { it.verification.reason == "ok" }, ts.map { it.verification.reason }.toString())
        assertEquals(listOf(0L, 3L, 3L, 0L), ts.map { it.all<TraceEvent.Plan>().single().gen }, "each segment starts with its own pl; none changes plan inside")
        assertEquals(PlanMode.TRIAL, ts[1].all<TraceEvent.Plan>().single().mode)
        assertEquals("abc123def456", ts[1].manifest.snapshot.planHash)
        assertTrue(ts.zipWithNext().all { (a, b) -> b.manifest.startElapsedMs >= a.manifest.endElapsedMs }, "segments never overlap")
        assertTrue(ts.all { it.tags.last() == "cl" })
    }

    @Test fun aPrivacyPauseClosesTheTraceAndNothingIsRecordedWhilePaused() = runTest {
        val fx = Fx(this, root); val r = fx.rig
        r.startSensors(); r.gyroMag = 0.01f
        r.session.start()
        delay(60_000)
        assertTrue(fx.rec.recording)
        r.paused = true
        delay(2_000)
        assertFalse(fx.rec.recording)
        val startsWhenPaused = fx.spy.starts.size
        delay(60_000)
        assertEquals(startsWhenPaused, fx.spy.starts.size, "no boundary is offered while paused")
        r.paused = false
        delay(60_000)
        assertTrue(fx.rec.recording, "a new segment after the pause")
        val ts = fx.finish()
        assertEquals(listOf("pause", "stop"), ts.map { it.manifest.closeReason })
        assertTrue(ts.all { it.verification.reason == "ok" })
    }

    @Test fun heatStopClosesTheTrace() = runTest {
        val fx = Fx(this, root); val r = fx.rig
        r.startSensors(); r.gyroMag = 0.01f
        r.session.start()
        delay(60_000)
        assertTrue(fx.rec.recording)
        r.heatReading = HeatReading(0, Float.NaN, 46f)
        delay(60_000)
        assertFalse(fx.rec.recording, "heat STOP: no recording")
        val ts = fx.finish()
        assertEquals("stop", ts.first().manifest.closeReason)
        assertTrue(ts.first().verification.reason == "ok")
    }

    @Test fun aBlockThatIsNotAPauseIsAnEventNotAClose() = runTest {
        val fx = Fx(this, root); val r = fx.rig
        r.startSensors(); r.gyroMag = 0.01f
        r.session.start()
        delay(60_000)
        r.phoneInUse = true; r.settings = r.settings.copy(pauseWhenPhoneInUse = true)
        delay(10_000)
        assertTrue(fx.rec.recording)
        r.phoneInUse = false
        delay(10_000)
        val t = fx.finish().single()
        val bl = t.all<TraceEvent.Blocked>().map { it.reason }
        assertEquals(listOf("phone_in_use", null), bl)
        assertEquals("ok", t.verification.reason)
    }

    @Test fun theSessionStopClosesTheTrace() = runTest {
        val fx = Fx(this, root); val r = fx.rig
        r.startSensors(); r.gyroMag = 0.01f
        r.session.start()
        delay(40_000)
        assertTrue(fx.rec.recording)
        r.session.stop("mode")
        assertFalse(fx.rec.recording)
        assertEquals("stop", TraceTestKit.readAll(root).single().manifest.closeReason)
    }

    @Test fun aColdFirstBindIsRecordedAsColdAndABusyCameraAsBusy() = runTest {
        val fx = Fx(this, root); val r = fx.rig
        r.camera.failOpens = 1 // the first open after start: not counted as a bind failure (cold start grace)
        r.startSensors(); r.gyroMag = 0.01f
        r.session.start()
        delay(10_000)
        r.camera.busyOpens = true
        delay(100_000)
        r.camera.busyOpens = false
        delay(10_000)
        val ts = fx.finish()
        val outcomes = ts.flatMap { it.all<TraceEvent.Episode>() }.filter { !it.open }.mapNotNull { it.outcome }
        assertTrue(EpisodeOutcome.BUSY in outcomes, "outcomes=$outcomes")
        assertTrue(ts.all { it.verification.reason == "ok" })
    }

    @Test fun darkFramesRecordTheStreakAndNoScore() = runTest {
        val fx = Fx(this, root); val r = fx.rig
        r.camera.darkFrames = 1_000 // every frame of every open is too dark
        r.startSensors(); r.gyroMag = 0.01f
        r.session.start()
        delay(180_000)
        val t = fx.finish().first()
        val dk = t.all<TraceEvent.DarkStreak>().map { it.streak }
        assertTrue(dk.isNotEmpty() && dk.first() == 1 && dk.zipWithNext().all { (a, b) -> b == a + 1 || b == 0 }, "dk=$dk")
        assertEquals(0, t.all<TraceEvent.Score>().size, "dark frames never reach the gate")
    }

    @Test fun recordingDoesNotChangeWhatTheSessionDoes() = runTest {
        // The same scenario with and without a recorder: identical camera opens, keyframes and policy ticks (S84: recording must not slow or change sensing).
        suspend fun run(withTrace: Boolean, root: File): Triple<List<Long>, List<Long>, Int> {
            val fx = if (withTrace) Fx(this, root) else null
            val r = fx?.rig ?: Rig(this)
            r.startSensors(); r.gyroMag = 0.01f
            val start = testScheduler.currentTime
            r.session.start()
            delay(300_000)
            r.session.stop("test")
            return Triple(r.camera.openTimes.map { it - start }, r.keyframes.map { it.first - start }, r.profileReads)
        }
        val without = run(false, root)
        val with = run(true, File(root, "w").also { it.mkdirs() })
        assertEquals(without.first, with.first, "camera opens")
        assertEquals(without.second, with.second, "keyframes")
        assertEquals(without.third, with.third, "policy ticks (no extra wake-up)")
        assertTrue(TraceTestKit.readAll(File(root, "w")).isNotEmpty())
    }

    @Test fun noRecordingWithoutACollectControl() = runTest {
        val fx = Fx(this, root, collect = false); val r = fx.rig
        r.startSensors(); r.gyroMag = 0.01f
        r.session.start()
        delay(120_000)
        assertTrue(fx.spy.starts.isEmpty())
        assertEquals(0, fx.finish().size)
    }

    @Test fun aDownsampledSessionTraceIsNotExact() = runTest {
        val fx = Fx(this, root, sampleEvery = 2); val r = fx.rig
        r.startSensors(); r.gyroMag = 0.01f
        r.session.start()
        delay(60_000)
        val t = fx.finish().first()
        assertFalse(t.manifest.exact)
        assertEquals(2, t.manifest.snapshot.sampleEvery)
    }

    /**
     * The rules Task 20's simulator relies on: one `gt` follows every `sc`, for the seq the gate judged (a skip: the frame's own seq; a window keep: the WINNER's
     * seq, which can be an earlier frame); a flush that keeps something appends a `gt` for the winner; a flush that keeps nothing appends none.
     */
    @Test fun gateEventsFollowTheSimulatorRules() = runTest {
        val fx = Fx(this, root); val r = fx.rig
        delay(1_000)
        r.startSensors(); r.gyroMag = 0.01f
        r.session.start()
        delay(8_000)
        r.scene = 2; r.gyroMag = 1.0f // a moving window of a NEW scene
        delay(1_300)
        r.settings = r.settings.copy(pauseWhenPhoneInUse = true); r.phoneInUse = true // a block ends the episode with the window still pending: a flush that keeps
        delay(2_000)
        r.phoneInUse = false; r.gyroMag = 0.01f
        delay(10_000)
        r.gyroMag = 1.0f // the same scene again
        delay(1_300)
        r.phoneInUse = true // a flush that keeps nothing (duplicate)
        delay(2_000)
        r.phoneInUse = false; r.gyroMag = 0.01f
        delay(10_000)
        val t = fx.finish().single()
        val ev = t.events.filter { it !is TraceEvent.Gyro }
        val seenScore = HashSet<Long>()
        var flushKeeps = 0; var flushEmpty = 0; var windowKeeps = 0
        ev.forEachIndexed { i, e ->
            when (e) {
                is TraceEvent.Score -> {
                    seenScore += e.seq
                    val g = ev[i + 1] as? TraceEvent.Gate ?: error("sc seq=${e.seq} not followed by its gt")
                    if (g.outcome == GateOutcome.SKIP) assertEquals(e.seq, g.seq, "a skip carries the frame's own seq")
                    else { assertTrue(g.seq in seenScore, "a keep carries a seq already scored"); if (g.seq != e.seq) windowKeeps++ }
                }
                is TraceEvent.Flush -> {
                    val g = ev.getOrNull(i + 1) as? TraceEvent.Gate
                    if (g != null) { flushKeeps++; assertEquals(GateOutcome.KEEP, g.outcome); assertTrue(g.seq in seenScore); assertEquals(e.nowMs, g.keptAtMs, "keptAtMs is the episode-end bookkeeping time"); assertTrue(g.encoded) }
                    else flushEmpty++
                }
                else -> Unit
            }
        }
        // every gt belongs to an sc (its own, or a winner) or to a flush keep
        assertEquals(ev.count { it is TraceEvent.Gate }, ev.count { it is TraceEvent.Score } + flushKeeps)
        assertTrue(flushKeeps >= 1 && flushEmpty >= 1, "both flush shapes occurred: keeps=$flushKeeps empty=$flushEmpty")
        assertEquals("ok", t.verification.reason)
    }

    @Test fun anEncodeFailureIsRecordedAsNotEncodedWithNoKeepTime() = runTest {
        val fx = Fx(this, root, encoder = com.rokid.context.glasses.capture.session.KeyframeEncoder { null }); val r = fx.rig
        delay(1_000)
        r.startSensors(); r.gyroMag = 0.01f
        r.session.start()
        delay(30_000)
        val t = fx.finish().single()
        val keeps = t.all<TraceEvent.Gate>().filter { it.outcome == GateOutcome.KEEP }
        assertTrue(keeps.isNotEmpty() && keeps.all { !it.encoded && it.keptAtMs == null }, keeps.toString())
        assertTrue(r.keyframes.isEmpty(), "nothing was emitted, so the session bookkeeping was not updated either")
    }

    @Test fun anEncodedKeepCarriesTheSessionKeepTime() = runTest {
        val fx = Fx(this, root); val r = fx.rig
        delay(1_000)
        r.startSensors(); r.gyroMag = 0.01f
        r.session.start()
        delay(20_000)
        val t = fx.finish().single()
        val keep = t.all<TraceEvent.Gate>().first { it.outcome == GateOutcome.KEEP }
        assertTrue(keep.encoded)
        assertEquals(r.keyframes.first().first, keep.keptAtMs, "the time the session recorded the keyframe at")
    }
}
