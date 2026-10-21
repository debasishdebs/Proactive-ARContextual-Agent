package com.rokid.context.glasses.capture.session

import com.rokid.context.core.model.CameraWake
import com.rokid.context.core.model.CaptureMode
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.MotionState
import com.rokid.context.glasses.capture.keyframe.ImageHashes
import com.rokid.context.glasses.capture.motion.GlanceParams
import com.rokid.context.glasses.capture.keyframe.Candidate
import com.rokid.context.glasses.capture.keyframe.Nv21Frame
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SamplingSessionTest {
    private fun rig(t: TestScope, settings: HudSettings = HudSettings(captureMode = CaptureMode.SMART), workRest: WorkRest? = null, configure: Rig.() -> Unit = {}) =
        Rig(t, settings, workRest = workRest).apply(configure)

    private fun gaps(r: Rig) = r.camera.openTimes.zipWithNext { a, b -> b - a }
    private fun assertGaps(expected: List<Long>, actual: List<Long>) {
        assertTrue(actual.size >= expected.size, "gaps=$actual")
        expected.forEachIndexed { i, e -> assertTrue(kotlin.math.abs(actual[i] - e) <= 1_000, "gap $i expected ~$e: $actual") }
    }

    @Test fun stillSceneBacksOffToSixtySeconds() = runTest {
        val r = rig(this)
        r.session.start()
        delay(200_000)
        assertGaps(listOf(5_000, 10_000, 20_000, 40_000, 60_000, 60_000), gaps(r))
        assertEquals(60_000L, r.session.stillGapMs())
        r.session.stop("test")
    }

    @Test fun settleStillSceneBacksOff() = runTest {
        val r = rig(this) { profile = CaptureProfile.of(CameraWake.SETTLE, BudgetTier.T0) }
        r.session.start()
        delay(200_000)
        assertGaps(listOf(10_000, 20_000, 40_000, 60_000), gaps(r))
        r.session.stop("test")
    }

    @Test fun changedSceneResetsTheBackoff() = runTest {
        val r = rig(this)
        r.session.start()
        while (r.session.stillStep < 2) delay(250)
        r.scene = 2
        while (r.session.stillStep >= 2) delay(250)
        assertEquals(0, r.session.stillStep)
        val opens = r.camera.openTimes.size
        delay(8_000)
        val g = r.camera.openTimes.drop(opens - 1).zipWithNext { a, b -> b - a }
        assertTrue(g.isNotEmpty() && kotlin.math.abs(g[0] - 5_000) <= 1_000, "gap after the reset: $g")
        r.session.stop("test")
    }

    // Wave A3 final review I2: in SETTLE mode a head-motion onset (micro-motion) no longer resets the backoff; MOTION keeps the A2 reset.
    @Test fun onsetResetsTheBackoffInMotionMode() = runTest {
        val r = rig(this)
        r.session.start()
        while (r.session.stillStep < 2) delay(250)
        r.motion.onGyro(2f, 0f, 0f, r.clock())
        runCurrent()
        assertEquals(0, r.session.stillStep)
        r.session.stop("test")
    }

    @Test fun onsetLeavesTheBackoffInSettleMode() = runTest {
        val r = rig(this) { profile = CaptureProfile.of(CameraWake.SETTLE, BudgetTier.T0) }
        r.session.start()
        while (r.session.stillStep < 2) delay(250)
        val before = r.session.stillStep
        r.motion.onGyro(2f, 0f, 0f, r.clock())
        runCurrent()
        assertEquals(before, r.session.stillStep)
        r.session.stop("test")
    }

    @Test fun aCappedChangedFrameDoesNotResetTheBackoff() = runTest {
        val r = glanceRig(this)
        r.session.start()
        while (r.session.stillStep < 2) r.gyro(250, 0f)
        val step = r.session.stillStep
        r.session.diagSnapshot()
        r.scene = 77
        val opens = r.camera.opens
        while (r.camera.opens == opens) r.gyro(20, 0f)
        // the camera is open (its first frame is ~0.5 s away): the minute's cap is used up now, as if keeps landed since the timer decided (task 9 no longer opens into a used cap)
        val now = r.clock()
        repeat(4) { r.gate.recordKeep(Candidate(0, Scenes.thumb(99), Nv21Frame(640, 480, 0, ByteArray(4))), 0L, 0L, now) }
        r.gyro(2_000, 0f)
        val d = r.session.diagSnapshot()
        assertTrue(d.capped >= 1, "capped=${d.capped}")
        assertTrue(r.session.stillStep >= step, "step ${r.session.stillStep} < $step")
        r.session.stop("test")
    }

    @Test fun dwellResetsTheBackoff() = runTest {
        val r = rig(this) { profile = CaptureProfile.of(CameraWake.SETTLE, BudgetTier.T0) }
        r.startSensors()
        r.session.start()
        while (r.session.stillStep < 2) delay(250)
        r.gyroMag = 3f
        delay(300)
        val moving = r.camera.opens
        r.gyroMag = 0f
        delay(2_500) // the dwell forces a brief check: the glance promise is unchanged
        assertTrue(r.camera.opens > moving, "dwell opened a brief check")
        assertTrue(r.session.stillStep <= 1, "step=${r.session.stillStep}")
        val last = r.camera.openTimes.last()
        delay(15_000)
        val next = r.camera.openTimes.first { it > last }
        assertTrue(kotlin.math.abs(next - last - 10_000) <= 1_500, "next gap ${next - last}")
        r.session.stop("test")
    }

    @Test fun unblockResetsTheBackoff() = runTest {
        val r = rig(this)
        r.session.start()
        while (r.session.stillStep < 2) delay(250)
        r.paused = true
        delay(2_000)
        r.paused = false
        delay(300)
        assertEquals(0, r.session.stillStep)
        r.session.stop("test")
    }

    @Test fun startsOnlyWhenAllConditionsHold() = runTest {
        val blockers: List<Pair<String, Rig.() -> Unit>> = listOf(
            "paused" to { paused = true },
            "mode" to { settings = settings.copy(captureMode = CaptureMode.FIXED) },
            "heat" to { heatReading = HeatReading(4, Float.NaN, 30f); heat.poll() },
            "not_worn" to { wear.onRemote(false) },
        )
        for ((why, block) in blockers) {
            val r = rig(this) { block() }
                        r.session.start()
            delay(1_000)
            assertEquals(0, r.camera.opens, "$why: the camera must stay closed")
            assertFalse(r.session.status.value.running, why)
            assertEquals(why, r.session.status.value.reason)
            r.session.stop("test")
        }
        val ok = rig(this)
        ok.session.start()
        delay(2_000)
        assertTrue(ok.camera.opens >= 1)
        assertEquals(1, ok.keyframes.size) // the start-up brief check saved the first scene
        assertNull(ok.session.status.value.reason)
        ok.session.stop("test")
    }

    @Test fun onsetTriggersImmediateCheck() = runTest {
        val r = rig(this)
        r.session.start()
        delay(3_000) // start-up check done, camera closed, next check not due until about 5 s
        assertEquals(1, r.camera.opens); assertFalse(r.camera.isOpen)
        r.motion.onGyro(2f, 0f, 0f, r.clock()) // head starts to move
        runCurrent()
        assertEquals(2, r.camera.opens, "opened at the onset, not at the next 250 ms tick or the 5 s check")
        val analysed = r.session.analysedFrames
        delay(700)
        assertTrue(r.session.analysedFrames > analysed, "the first frame after open is analysed at once")
        r.session.stop("test")
    }

    @Test fun settleOnsetOpensNoCamera() = runTest {
        val r = rig(this) { profile = CaptureProfile.of(CameraWake.SETTLE, BudgetTier.T0) }
        r.session.start()
        delay(3_000) // start-up check done
        val before = r.camera.opens
        r.motion.onGyro(2f, 0f, 0f, r.clock())
        runCurrent()
        assertEquals(before, r.camera.opens, "no check at the first sign of movement in SETTLE")
        assertFalse(r.camera.isOpen)
        r.session.stop("test")
    }

    @Test fun settleDwellOpensABriefCheck() = runTest {
        val r = rig(this) { profile = CaptureProfile.of(CameraWake.SETTLE, BudgetTier.T0) }
        r.startSensors()
        r.session.start()
        delay(3_000)
        r.gyroMag = 3f
        delay(300)
        val moving = r.camera.opens
        r.gyroMag = 0f
        delay(2_500) // the dwell (1 s still) forces a brief check
        assertTrue(r.camera.opens > moving, "dwell opened a brief check")
        assertFalse(r.camera.isOpen || r.session.status.value.reason != null)
        r.session.stop("test")
    }

    @Test fun pausedProfileBlocksWithBatteryBudget() = runTest {
        val r = rig(this)
        r.startSensors()
        r.gyroMag = 2f
        r.session.start()
        delay(2_000)
        assertTrue(r.camera.isOpen)
        r.profile = CaptureProfile.of(CameraWake.SETTLE, BudgetTier.T3)
        delay(1_000)
        assertFalse(r.camera.isOpen)
        assertEquals("battery_budget", r.session.status.value.reason)
        r.profile = CaptureProfile.DEFAULT
        delay(1_000)
        assertTrue(r.camera.isOpen)
        r.session.stop("test")
    }

    @Test fun parkedBlocksOnlyWithPauseWhenNotWorn() = runTest {
        val r = rig(this, HudSettings(captureMode = CaptureMode.SMART, pauseWhenNotWorn = true))
        r.startSensors()
        r.gyroMag = 2f
        r.session.start()
        delay(2_000)
        assertTrue(r.camera.isOpen)
        for (i in 0 until 130) r.wear.onSecond(0f, 0f, i * 1000L) // 130 still seconds: parked (unknown wear: 120 s)
        assertTrue(r.wear.parked.value)
        delay(1_000)
        assertFalse(r.camera.isOpen)
        assertEquals("parked", r.session.status.value.reason)
        r.settings = r.settings.copy(pauseWhenNotWorn = false)
        delay(1_000)
        assertTrue(r.camera.isOpen)
        assertNull(r.session.status.value.reason)
        r.session.stop("test")
    }

    @Test fun phoneInUseBlocksOnlyWithThePauseSetting() = runTest {
        val r = rig(this, HudSettings(captureMode = CaptureMode.SMART, pauseWhenPhoneInUse = true))
        r.startSensors()
        r.gyroMag = 2f
        r.session.start()
        delay(2_000)
        assertTrue(r.camera.isOpen)
        r.phoneInUse = true
        delay(1_000)
        assertFalse(r.camera.isOpen)
        assertEquals("phone_in_use", r.session.status.value.reason)
        r.settings = r.settings.copy(pauseWhenPhoneInUse = false)
        delay(1_000)
        assertTrue(r.camera.isOpen)
        assertNull(r.session.status.value.reason)
        r.session.stop("test")
    }

    @Test fun onsetInBurstModeForcesOneBriefCheck() = runTest {
        val r = rig(this, HudSettings(captureMode = CaptureMode.SMART, quickLookMs = 3000))
        r.session.start()
        delay(3_000)
        val before = r.camera.opens
        r.motion.onGyro(2f, 0f, 0f, r.clock())
        runCurrent()
        assertEquals(before + 1, r.camera.opens)
        r.session.stop("test")
    }

    @Test fun dwellForcesKeyframe() = runTest {
        val r = rig(this)
        r.startSensors()
        r.session.start()
        for (s in 1..3) { r.scene = s; while (r.scenesKept.size < s) delay(250) } // three settled scenes, one brief check each (a still scene backs the checks off)
        assertEquals(listOf(1, 2, 3), r.scenesKept)
        // turn to a new scene 4: the dwell right after the head settles keeps it (a look back at a ring scene is a duplicate since A3)
        val turnAt = r.clock()
        r.gyroMag = 3f; r.scene = 4
        delay(100); r.gyroMag = 0f
        delay(2_000)
        val (at, last) = r.keyframes.last()
        assertEquals(4, Scenes.sceneOf(last.jpeg)); assertTrue(last.dwell)
        assertTrue(at - turnAt in 1_000..1_400, "dwell keyframe right after 1.0 s still, was ${at - turnAt}")
        assertEquals(4, r.keyframes.size)
        r.session.stop("test")
    }

    /** Fix wave H: the Android proximity sensor is diagnostic only (it read a constant "far" while worn); it never stops a session. */
    @Test fun proximityNoiseNeverStopsASession() = runTest {
        val r = rig(this, HudSettings(captureMode = CaptureMode.SMART, pauseWhenNotWorn = true)) { wear.onProximity(false, 0) }
        r.startSensors()
        r.gyroMag = 2f // walking about: the camera is open
        r.session.start()
        delay(12_000)
        assertTrue(r.wear.worn.value, "proximity never changes the wear state")
        assertTrue(r.camera.isOpen)
        assertTrue(r.session.status.value.running)
        assertNull(r.session.status.value.reason)
        r.session.stop("test")
    }

    /** A real pc_wear=false stops the camera; true (or a session reset to unknown) lets it run again; the setting off ignores it. */
    @Test fun realNotWornStopsCameraAndUnknownNeverGates() = runTest {
        val r = rig(this)
        r.startSensors()
        r.gyroMag = 2f
        r.session.start()
        delay(2_000)
        assertTrue(r.camera.isOpen)
        r.wear.onRemote(false)
        delay(1_500)
        assertFalse(r.camera.isOpen)
        assertEquals("not_worn", r.session.status.value.reason)
        r.wear.reset() // the session closed: unknown counts as worn
        delay(1_500)
        assertTrue(r.camera.isOpen)
        r.wear.onRemote(false); delay(1_500); assertFalse(r.camera.isOpen)
        r.settings = r.settings.copy(pauseWhenNotWorn = false)
        delay(1_500)
        assertTrue(r.camera.isOpen, "the setting off ignores a real not-worn")
        r.session.stop("test")
    }

    @Test fun modeSwitchReleasesSessionBeforeFixedCapture() = runTest {
        val r = rig(this)
        r.startSensors()
        r.gyroMag = 2f
        r.session.start()
        delay(2_000)
        assertTrue(r.camera.isOpen); assertEquals("session", r.owner.current)
        val seen = ArrayList<String>()
        r.settings = r.settings.copy(captureMode = CaptureMode.FIXED)
        backgroundScope.launch { r.owner.acquire("fixed") { seen += "fixed: cameraOpen=${r.camera.isOpen} owner=${r.owner.current}" } }
        delay(1_000)
        assertEquals(listOf("fixed: cameraOpen=false owner=fixed"), seen)
        r.session.stop("test")
    }

    @Test fun stopReleasesTheCameraBeforeAFixedCaptureRuns() = runTest {
        val r = rig(this)
        r.startSensors()
        r.gyroMag = 2f
        r.session.start()
        delay(2_000)
        assertTrue(r.camera.isOpen)
        val seen = ArrayList<String>()
        r.session.stop("mode")
        backgroundScope.launch { r.owner.acquire("fixed") { seen += "cameraOpen=${r.camera.isOpen}" } }
        delay(100)
        assertEquals(listOf("cameraOpen=false"), seen)
        val opens = r.camera.opens
        delay(5_000)
        assertEquals(opens, r.camera.opens, "a stopped session never reopens")
    }

    @Test fun bindFailsThriceFallsBackToFixed() = runTest {
        val r = rig(this)
        r.camera.failOpens = 99
        r.session.start()
        delay(10_000)
        assertEquals(4, r.camera.opens, "the first open is the free cold-start retry")
        assertEquals(1, r.fallbacks)
        assertTrue(r.session.status.value.degraded)
        assertFalse(r.session.status.value.running)
        assertEquals("bind", r.session.status.value.reason)
        delay(20_000)
        assertEquals(4, r.camera.opens)
    }

    @Test fun firstBindFailureRetriesAfter500msAndIsNotCounted() = runTest {
        val r = rig(this)
        r.camera.failOpens = 1
        r.session.start()
        delay(2_000)
        assertTrue(r.logs.any { it.contains("not counted") })
        assertFalse(r.logs.any { it.contains("camera bind failed") }, "the cold-start retry must not count")
        assertEquals(2, r.camera.opens)
        r.session.stop("test")
    }

    @Test fun aBindThatWorksAgainResetsTheFailureCount() = runTest {
        val r = rig(this)
        r.camera.failOpens = 2
        r.session.start()
        delay(8_000)
        assertEquals(0, r.fallbacks)
        assertEquals(1, r.keyframes.size)
        r.camera.failOpens = 2
        r.scene = 2
        delay(20_000)
        assertEquals(0, r.fallbacks)
        r.session.stop("test")
    }

    @Test fun keyframeLongSideAtMost640NoUpscale() = runTest {
        // portrait 480x640 and landscape 640x480 keep their size, a 320x240 is NOT upscaled, a 1280x960 comes down to 640x480
        assertEquals(480 to 640, YuvCopy.outputSize(480, 640, 0)); assertEquals(640 to 480, YuvCopy.outputSize(640, 480, 0))
        assertEquals(480 to 640, YuvCopy.outputSize(640, 480, 90)); assertEquals(320 to 240, YuvCopy.outputSize(320, 240, 0))
        assertEquals(640 to 480, YuvCopy.outputSize(1280, 960, 0)); assertEquals(480 to 640, YuvCopy.outputSize(1280, 960, 90))
        val enc = KeyframeEncoder { f ->
            val (w, h) = YuvCopy.outputSize(f.w, f.h, f.rotation)
            EncodedKeyframe(f.data.copyOf(), w, h)
        }
        val small = Rig(this, encoder = enc)
        small.camera.frames = { t -> Scenes.frame(small.scene, 1, t).let { FrameCopy(it.thumb, Nv21Frame(320, 240, 0, it.pixels.data)) } }
        small.session.start()
        delay(2_000)
        assertEquals(320, small.keyframes.single().second.width)
        small.session.stop("test")
        assertTrue(small.logs.any { Regex("keyframe seq=\\d+ src=320x240 out=320x240").containsMatchIn(it) })
    }

    @Test fun captureNowForcesKeyframe() = runTest {
        val r = rig(this)
        r.session.start()
        delay(3_000)
        assertEquals(1, r.keyframes.size)
        delay(10_000) // same scene: every check is a duplicate
        assertEquals(1, r.keyframes.size)
        assertTrue(r.session.captureNow())
        delay(1_500)
        assertEquals(2, r.keyframes.size, "forced, though the scene is unchanged")
        // the blur floor still applies
        r.soft = 100
        assertTrue(r.session.captureNow())
        delay(4_000)
        assertEquals(2, r.keyframes.size)
        r.session.stop("test")
    }

    @Test fun diagSnapshotCountsKeyframesAndResets() = runTest {
        val r = rig(this)
        r.session.start()
        delay(3_000)
        val d = r.session.diagSnapshot()
        assertEquals(1, d.kf1m)
        assertTrue(d.spanMs <= 3_500, "span bounded by the session lifetime: ${d.spanMs}")
        assertTrue(d.spanMs >= 1 && d.openMs in 0..d.spanMs)
        assertEquals(0, r.session.diagSnapshot().analysed)
        r.session.stop("test")
    }

    @Test fun diagSnapshotCountsOpensAndWakeReasons() = runTest {
        val r = rig(this) { profile = CaptureProfile.of(CameraWake.SETTLE, BudgetTier.T0) }
        r.startSensors()
        r.session.start()
        delay(1_000)
        r.gyroMag = 3f
        delay(300)
        r.gyroMag = 0f
        delay(2_500)
        val d = r.session.diagSnapshot()
        assertTrue(d.opens >= 2, "opens=${d.opens}")
        assertTrue(d.wakes["timer"]!! >= 1, "wakes=${d.wakes}")
        assertTrue(d.wakes["dwell"]!! >= 1, "wakes=${d.wakes}")
        assertEquals(0, r.session.diagSnapshot().opens)
        r.session.stop("test")
    }

    @Test fun captureNowIsRefusedWhileNotSampling() = runTest {
        val r = rig(this)
        r.paused = true
        r.session.start()
        delay(1_000)
        assertFalse(r.session.captureNow())
        r.session.stop("test")
    }

    @Test fun restKeepsSamplingAtTwoPerMinute() = runTest {
        val r = glanceRig(this) { work = 1; rest = 5; settings = HudSettings(captureMode = CaptureMode.SMART, workRestEnabled = true) }
        r.session.start()
        r.gyro(61_000, 0f) // one minute of work, then rest
        assertNotNull(r.session.status.value.restingUntilMs, "resting")
        assertTrue(r.session.status.value.running, "rest keeps the session running")
        val restStart = r.clock()
        for (i in 1..6) { r.scene = 10 + i; r.gyro(400, 1f); r.gyro(9_600, 0f) } // six real glances, 10 s apart, each on a new scene
        assertTrue(r.session.status.value.running)
        assertNotNull(r.session.status.value.restingUntilMs)
        val inRest = r.keyframes.map { it.first }.filter { it >= restStart }
        for (t in inRest) assertTrue(inRest.count { it in t until t + 60_000 } <= 2, "rolling minute from $t: $inRest")
        assertTrue(inRest.isNotEmpty(), "rest still captures glances")
        assertEquals(0, r.fixedCaptures.size)
        r.session.stop("test")
    }

    private fun motionRestRig(t: TestScope) = glanceRig(t) {
        work = 1; rest = 5; profile = CaptureProfile.DEFAULT // MOTION wake, T0
        settings = HudSettings(captureMode = CaptureMode.SMART, workRestEnabled = true)
    }

    @Test fun restWithMotionProfileOnsetOpensNothingAndTimerChecksAreSpaced() = runTest {
        val r = motionRestRig(this)
        r.session.start()
        r.gyro(61_000, 0f)
        assertNotNull(r.session.status.value.restingUntilMs)
        r.gyro(5_000, 0f)
        val before = r.camera.opens
        r.gyro(200, 1f) // an onset (no completed glance yet)
        assertEquals(before, r.camera.opens, "an onset opens nothing in rest")
        r.gyro(120_000, 0f)
        val opens = r.camera.openTimes
        val inRest = opens.filter { it > 66_000 }
        inRest.zipWithNext { a, b -> assertTrue(b - a >= 29_000, "timer checks at least 30 s apart: $inRest") }
        r.session.stop("test")
    }

    @Test fun restWithMotionProfileStillOpensForAGlanceAtTwoPerMin() = runTest {
        val r = motionRestRig(this)
        r.session.start()
        r.gyro(61_000, 0f)
        val restStart = r.clock()
        for (i in 1..6) { r.scene = 20 + i; r.gyro(400, 1f); r.gyro(9_600, 0f) }
        val inRest = r.keyframes.map { it.first }.filter { it >= restStart }
        assertTrue(inRest.isNotEmpty())
        for (t in inRest) assertTrue(inRest.count { it in t until t + 60_000 } <= 2, "$inRest")
        r.session.stop("test")
    }

    @Test fun motionOnsetOutsideRestStillOpensImmediately() = runTest {
        val r = glanceRig(this) { profile = CaptureProfile.DEFAULT }
        r.session.start()
        r.gyro(8_000, 0f)
        val before = r.camera.opens
        r.gyro(300, 1f)
        assertTrue(r.camera.opens > before, "onset opens in work")
        r.session.stop("test")
    }

    @Test fun workRestDisabledByDefaultNeverRests() = runTest {
        val r = glanceRig(this) { work = 1; rest = 5 }
        r.session.start()
        var t = 0
        while (t < 120) { r.gyro(60_000, 0f); t += 60; assertNull(r.session.status.value.restingUntilMs); assertTrue(r.session.status.value.running) }
        assertNull(r.sessionWorkRest.resting.value)
        r.session.stop("test")
    }

    @Test fun restingFlagIsClearedWhenTheSwitchTurnsOffMidRest() = runTest {
        val r = glanceRig(this) { work = 1; rest = 5; settings = HudSettings(captureMode = CaptureMode.SMART, workRestEnabled = true) }
        r.session.start()
        r.gyro(61_000, 0f)
        assertNotNull(r.session.status.value.restingUntilMs)
        r.settings = r.settings.copy(workRestEnabled = false)
        r.gyro(2_000, 0f)
        assertNull(r.session.status.value.restingUntilMs)
        assertTrue(r.session.status.value.running)
        r.session.stop("test")
    }

    @Test fun briefChecksFollowThe5sBeatAndEndAfterTheSettle() = runTest {
        val r = rig(this)
        r.session.start()
        // a scene that changes every 5 s keeps the beat: first check at start, then one about every 5 s (each opens 535 ms early, closing on its first frame)
        for (s in 1..6) { r.scene = s; delay(5_000) }
        assertTrue(r.camera.opens in 6..7, "opens=${r.camera.opens}")
        // a scene that never gets sharp: the check ends once the exposure has settled (about 0.2 s after the first frame)
        val b = rig(this) { soft = 100 }
        b.session.start()
        delay(1_200)
        assertEquals(1, b.camera.opens); assertFalse(b.camera.isOpen)
        assertEquals(0, b.keyframes.size)
        r.session.stop("test"); b.session.stop("test")
    }

    /** Total ms the fake camera was open, from its open/close events. */
    private fun openMs(r: Rig, until: Long): Long {
        var total = 0L; var since: Long? = null
        for (e in r.camera.events) {
            val t = e.substringAfter('@').toLong()
            if (e.startsWith("open")) since = t else since?.let { total += t - it; since = null }
        }
        since?.let { total += until - it }
        return total
    }

    @Test fun aDarkSceneDoesNotHoldTheCameraOpen() = runTest {
        // face-down or in a dark room with the wear check off: a brief check ends after the settle, then the cadence backs off
        val r = rig(this, HudSettings(captureMode = CaptureMode.SMART, pauseWhenNotWorn = false)) { camera.frames = { t -> Scenes.dark(t) } }
        r.session.start()
        delay(60_000)
        val open = openMs(r, 60_000)
        assertTrue(open < 60_000 * 0.12, "camera open $open ms of 60 s (was about 40 % with the 1.5 s give-up)")
        assertTrue(r.camera.opens <= 8, "opens=${r.camera.opens}: 3 checks on the 5 s beat, then 15 s")
        assertEquals(0, r.keyframes.size)
        r.session.stop("test")
    }

    @Test fun aBriefCheckEndsOnAnUnusableFrameAfterTheSettle() = runTest {
        val r = rig(this) { soft = 100 }
        r.session.start()
        delay(1_200)
        assertFalse(r.camera.isOpen)
        val closedAt = r.camera.events.last { it.startsWith("close") }.substringAfter('@').toLong()
        assertTrue(closedAt - 535 <= 400, "closed at $closedAt, first frame about 535")
        r.session.stop("test")
    }

    @Test fun aBusyCameraIsNotABindFailure() = runTest {
        val r = rig(this)
        r.camera.busyOpens = true
        r.session.start()
        delay(95_000)
        assertEquals(0, r.fallbacks, "another app holding the camera must not latch the Fixed fallback")
        assertFalse(r.session.status.value.degraded)
        assertTrue(r.camera.opens in 3..5, "retries about every 30 s, opens=${r.camera.opens}")
        r.camera.busyOpens = false
        delay(35_000)
        assertTrue(r.keyframes.isNotEmpty(), "Smart works again once the camera is free")
        r.session.stop("test")
    }

    @Test fun aHungOpenTimesOutAndCountsAsAFailedBind() = runTest {
        val r = rig(this)
        r.camera.hangOpens = true
        r.session.start()
        delay(30_000)
        assertEquals(1, r.fallbacks)
        assertTrue(r.logs.any { it.contains("timed out") })
        assertFalse(r.camera.isOpen)
    }

    @Test fun aFrameStallClosesTheEpisode() = runTest {
        val r = rig(this)
        r.startSensors()
        r.gyroMag = 2f // the camera stays open while walking
        r.camera.stallAfterFrames = 5
        r.session.start()
        delay(6_000)
        assertTrue(r.logs.any { it.contains("stalled") })
        assertTrue(r.camera.closes >= 1)
        r.session.stop("test")
    }

    @Test fun theLeadFollowsTheMeasuredStartup() = runTest {
        val r = rig(this) { camera.startupMs = { 900L } }
        r.session.start()
        delay(60_000) // the checks back off on a still scene: 0, 5, 15, 35 s
        val stats = r.session.startupStats()
        assertTrue(stats.contains("p95=900") && stats.contains("lead=900"), stats)
        r.session.stop("test")
    }

    @Test fun oneWorkRestSurvivesASessionRestart() = runTest {
        val shared = WorkRest({ testScheduler.currentTime }, { 2 }, { 5 })
        val a = rig(this, workRest = shared)
        a.session.start()
        delay(90_000)
        a.session.stop("exit")
        shared.onPaused() // what the service does when a session ends
        val b = rig(this, workRest = shared)
        b.session.start()
        delay(10_000)
        assertNull(shared.resting.value, "work 100 s of 120 s: no rest yet")
        delay(30_000)
        assertNotNull(shared.resting.value, "the work clock kept running across the restart: rest after 2 min in total")
        assertNotNull(b.session.status.value.restingUntilMs)
        assertTrue(b.session.status.value.running, "rest no longer blocks")
        b.session.stop("test")
    }

    @Test fun lowBatteryOrHeatSlowStillSamples() = runTest {
        val r = rig(this) { heatReading = HeatReading(3, Float.NaN, 30f); heat.poll() }
        r.session.start()
        delay(2_000)
        assertEquals(HeatLevel.SLOW, r.heat.level.value)
        assertEquals(1, r.keyframes.size)
        r.session.stop("test")
    }

    @Test fun keyframesLast10MinCountsKeeps() = runTest {
        val r = rig(this)
        r.session.start()
        for (s in 1..3) { r.scene = s; while (r.scenesKept.size < s) delay(250) }
        assertEquals(3, r.session.status.value.keyframesLast10Min)
        delay(11 * 60_000L)
        assertEquals(0, r.session.status.value.keyframesLast10Min)
        r.session.stop("test")
    }

    /**
     * JVM stand-in for the Android encoder (android.graphics is not on this classpath): the Y plane of the winning
     * candidate's NV21 copy, lossless, with its size in front. The Android encoder's JPEG step is covered on device.
     */
    private fun encodeY(f: Nv21Frame): ByteArray = ByteBuffer.allocate(8 + f.w * f.h).putInt(f.w).putInt(f.h).put(f.data, 0, f.w * f.h).array()

    /** 640x480 grey frame whose 160x120 thumbnail (4x4 blocks) is the scene's thumb. */
    private fun nv21Of(scene: Int, soft: Int): Nv21Frame {
        val t = Scenes.thumb(scene, soft)
        val data = ByteArray(640 * 480 * 3 / 2) { 128.toByte() }
        for (y in 0 until 480) for (x in 0 until 640) data[y * 640 + x] = t.luma[(y / 4) * 160 + x / 4]
        return Nv21Frame(640, 480, 0, data)
    }

    @Test fun emittedJpegMatchesWinningThumb() = runTest {
        val r = Rig(this, encoder = KeyframeEncoder { f -> EncodedKeyframe(encodeY(f), 640, 480) })
        val frames = HashMap<String, Nv21Frame>()
        var scene = 1; var soft = 1
        r.camera.frames = { _ -> FrameCopy(Scenes.thumb(scene, soft), frames.getOrPut("$scene/$soft") { nv21Of(scene, soft) }) }
        r.startSensors()
        r.session.start()
        delay(7_000) // settled scene 1 kept by the start-up check; the 6 s moving cap (K1) must have passed
        // move: the first samples are scene 2 and sharp, later ones show scene 3 and are softer; the window's winner is the first
        r.gyroMag = 3f
        scene = 2; soft = 1
        delay(1_100)
        scene = 3; soft = 4
        delay(1_400)
        r.gyroMag = 0f
        delay(3_000)
        assertTrue(r.keyframes.size >= 2)
        val (_, k) = r.keyframes[1]
        val buf = ByteBuffer.wrap(k.jpeg)
        assertEquals(640, buf.int); assertEquals(480, buf.int)
        val grey = ByteArray(640 * 480).also { buf.get(it) }
        val rebuilt = YuvCopy.thumb(ByteBuffer.wrap(grey), 640, 1, 640, 480)
        val winner = Scenes.thumb(2, 1)
        val d = java.lang.Long.bitCount(ImageHashes.dHash(rebuilt) xor ImageHashes.dHash(winner))
        assertTrue(d <= 2, "dHash distance to the winning thumb was $d")
        val later = Scenes.thumb(3, 4)
        assertTrue(java.lang.Long.bitCount(ImageHashes.dHash(rebuilt) xor ImageHashes.dHash(later)) > 10, "not the later frame")
        assertEquals(k.dhash, ImageHashes.dHash(winner).toULong().toString(16).padStart(16, '0'), "metadata describes the winner")
        r.session.stop("test")
    }

    /** CameraOwner is not reentrant: the session starts Fixed work only after its own acquire block has ended. */
    @Test fun fallbackFixedCapturesNeverRunInsideTheSessionBlock() = runTest {
        val r = rig(this)
        val ran = ArrayList<String>()
        r.camera.failOpens = 99
        r.onFallbackAction = { r.owner.acquire("fixed") { ran += "fallback:${r.owner.current}:camera=${r.camera.isOpen}" } }
        r.session.start()
        delay(10_000)
        assertEquals(listOf("fallback:fixed:camera=false"), ran, "the fallback capture got the owner (no deadlock)")
    }

    // ---- battery wave A3 task 3: novelty gate and keep cap with the DEFAULT glance parameters ----

    private fun glanceRig(t: TestScope, configure: Rig.() -> Unit = {}) =
        Rig(t, HudSettings(captureMode = CaptureMode.SMART), glance = GlanceParams())
            .apply { profile = CaptureProfile.of(CameraWake.SETTLE, BudgetTier.T0) }.apply(configure)

    /** Gyro at 50 Hz for [ms]: rotation [z] rad/s about the vertical axis (0 = still). Feeds the classifier directly. */
    private suspend fun Rig.gyro(ms: Long, z: Float) {
        val end = clock() + ms
        while (clock() < end) { motion.onGyro(0f, 0f, z, clock()); delay(20) }
    }

    /** Dwell-triggered opens since the last snapshot. */
    private fun dwellOpens(d: DiagStats) = d.wakes["dwell"] ?: 0

    @Test fun glanceWithoutHeadTurnSoonAfterAKeepDoesNotOpen() = runTest {
        val r = glanceRig(this)
        r.session.start()
        r.gyro(5_000, 0f)               // the start-up keep, then 5 s of stillness
        r.session.diagSnapshot()
        r.gyro(300, 1f); r.gyro(300, -1f) // a real look away and straight back: net rotation about 0
        r.gyro(3_000, 0f)
        val d = r.session.diagSnapshot()
        assertEquals(1, d.glances); assertEquals(1, d.novelSkips); assertEquals(0, dwellOpens(d), "no open for the glance, wakes=${d.wakes}")
        r.session.stop("test")
    }

    @Test fun glanceAfterTwentyOneSecondsOpens() = runTest {
        val r = glanceRig(this)
        r.session.start()
        r.gyro(21_500, 0f)              // the keep was at about 0.6 s: 21 s ago when the look starts
        r.session.diagSnapshot()
        r.gyro(300, 1f); r.gyro(300, -1f)
        r.gyro(3_000, 0f)
        val d = r.session.diagSnapshot()
        assertEquals(1, d.glances); assertEquals(0, d.novelSkips); assertEquals(1, dwellOpens(d), "wakes=${d.wakes}")
        r.session.stop("test")
    }

    @Test fun glanceWithSeventeenDegreeTurnOpens() = runTest {
        val r = glanceRig(this)
        r.session.start()
        r.gyro(5_000, 0f)
        r.session.diagSnapshot()
        r.gyro(400, 1f)                 // 0.4 rad net (23 deg)
        r.gyro(3_000, 0f)
        val d = r.session.diagSnapshot()
        assertEquals(1, d.glances); assertEquals(0, d.novelSkips); assertEquals(1, dwellOpens(d), "wakes=${d.wakes}")
        r.session.stop("test")
    }

    @Test fun microMotionNeverOpensAndIsCounted() = runTest {
        val r = glanceRig(this)
        r.session.start()
        r.gyro(25_000, 0f)
        r.session.diagSnapshot()
        r.gyro(60, 1.5f)                // a jerk: 60 ms
        r.gyro(4_000, 0f)
        val d = r.session.diagSnapshot()
        assertEquals(0, d.glances); assertEquals(1, d.micro); assertEquals(0, d.novelSkips); assertEquals(0, dwellOpens(d))
        r.session.stop("test")
    }

    @Test fun overCapGlanceIsDroppedBeforeOpening() = runTest {
        val r = glanceRig(this)
        r.session.start()
        for (s in 1..3) { r.scene = s; while (r.scenesKept.size < s) r.gyro(250, 0f) } // three timer keeps, one scene change each (timer keeps stop at cap - 1)
        r.scene = 4; r.gyro(400, 1f); r.gyro(3_000, 0f)  // the fourth keep is a glance (0.4 rad, novel)
        assertEquals(4, r.scenesKept.size)
        assertTrue(r.clock() < 55_000, "four keeps inside a minute, at ${r.clock()}")
        r.gyro(2_000, 0f)
        r.session.diagSnapshot()
        r.gyro(500, 1f)                 // 0.5 rad: novel enough (>= 0.30), under the 0.8 big turn
        r.gyro(3_000, 0f)
        val d = r.session.diagSnapshot()
        assertEquals(1, d.glances); assertTrue(d.capped >= 1, "capped=${d.capped}"); assertEquals(0, dwellOpens(d), "wakes=${d.wakes}")
        r.gyro(1_000, 1f)               // 1.0 rad more: the net turn since the last keep is now past 0.8
        r.gyro(3_000, 0f)
        val e = r.session.diagSnapshot()
        assertEquals(1, dwellOpens(e), "a big turn opens, wakes=${e.wakes}")
        r.session.stop("test")
    }

    @Test fun walkedGlanceOpensEvenInsideTheNoveltyWindow() = runTest {
        val r = glanceRig(this)
        r.session.start()
        r.gyro(3_000, 0f)
        r.session.diagSnapshot()
        // a 3 s walk (2 Hz stride on the accelerometer), then 6 s of stillness on both sensors: the arrival glance is novel by the walk
        val start = r.clock()
        while (r.clock() < start + 9_000) {
            val t = r.clock()
            r.motion.onGyro(0f, 0f, 0f, t)
            val stride = if (t < start + 3_000) 2.0f * kotlin.math.sin(2 * Math.PI * 2.0 * t / 1000.0).toFloat() else 0f
            r.motion.onLinearAccel(0f, stride, 0f, t)
            delay(20)
        }
        val d = r.session.diagSnapshot()
        assertEquals(0, d.novelSkips, "walked: not a novelty skip"); assertTrue(dwellOpens(d) >= 1, "wakes=${d.wakes} glances=${d.glances} micro=${d.micro} capped=${d.capped}")
        r.session.stop("test")
    }

    // Wave A3 final review I3: timer keeps in front of changing content leave a slot for a real look.
    @Test fun aLookAtAPersonStillKeptAfterTimerKeepsOfAChangingScreen() = runTest {
        val r = glanceRig(this)
        r.session.start()
        while (r.clock() < 50_000) { r.gyro(5_000, 0f); r.scene++ } // timer checks all minute see new TV content
        r.gyro(2_000, 0f)
        r.session.diagSnapshot()
        val kept = r.scenesKept.size
        r.scene = 500
        r.gyro(500, 1f)                 // 0.5 rad (about 29 deg): novel by turn, under the big turn
        r.gyro(3_000, 0f)
        val d = r.session.diagSnapshot()
        assertEquals(1, dwellOpens(d), "the look opens, wakes=${d.wakes} capped=${d.capped}")
        assertEquals(kept + 1, r.scenesKept.size, "the look is kept")
        r.session.stop("test")
    }

    // Wave A3 task 9: a timer check does not power up the camera when its frame would only be capped.
    private suspend fun Rig.tvFor(untilMs: Long) { while (clock() < untilMs) { gyro(1_000, 0f); scene++ } }

    @Test fun timerChecksStopOpeningOnceTheKeepBudgetIsUsedAndResumeWhenItAgesOut() = runTest {
        val r = glanceRig(this)
        r.session.start()
        while (r.keyframes.size < 3 && r.clock() < 59_000) { r.gyro(1_000, 0f); r.scene++ } // timer keeps of a changing screen: the budget is cap - 1 = 3
        assertEquals(3, r.keyframes.size, "keeps=${r.keyframes.map { it.first }}")
        val first = r.keyframes[0].first
        val third = r.keyframes[2].first
        r.tvFor(first + 57_000)
        assertTrue(third + 60_000 > first + 57_000 && r.camera.openTimes.none { it > third + 2_000 }, "no opens while the budget is used: ${r.camera.openTimes} third=$third")
        r.tvFor(first + 70_000)
        assertTrue(r.camera.openTimes.any { it >= first + 59_000 }, "timer checks resume once the oldest keep ages out: ${r.camera.openTimes}")
        r.session.stop("test")
    }

    @Test fun aGlanceInTheDeferredWindowStillOpensAndKeeps() = runTest {
        val r = glanceRig(this)
        r.session.start()
        while (r.keyframes.size < 3 && r.clock() < 59_000) { r.gyro(1_000, 0f); r.scene++ }
        assertEquals(3, r.keyframes.size)
        val first = r.keyframes[0].first
        r.tvFor(r.clock() + 12_000)
        r.session.diagSnapshot()
        val kept = r.keyframes.size
        val opens = r.camera.opens
        r.scene = 900
        r.gyro(500, 1f); r.gyro(3_000, 0f)
        assertTrue(r.clock() < first + 59_000, "still inside the deferred window")
        val d = r.session.diagSnapshot()
        assertEquals(1, dwellOpens(d), "wakes=${d.wakes}")
        assertTrue(r.camera.opens > opens)
        assertEquals(kept + 1, r.keyframes.size, "the glance is kept")
        r.session.stop("test")
    }

    @Test fun restAtCapTwoHoldsTimerChecksAfterOneKeep() = runTest {
        val r = glanceRig(this) { work = 1; rest = 5; settings = HudSettings(captureMode = CaptureMode.SMART, workRestEnabled = true) }
        r.session.start()
        r.gyro(61_000, 0f)
        assertNotNull(r.session.status.value.restingUntilMs, "resting")
        val restStart = r.clock()
        r.tvFor(restStart + 150_000)
        val keeps = r.keyframes.map { it.first }.filter { it >= restStart }
        assertTrue(keeps.size >= 2, "rest still keeps: $keeps")
        val opens = r.camera.openTimes.filter { it >= restStart }
        for (k in keeps) assertTrue(opens.none { it > k + 2_000 && it < k + 58_000 }, "no timer open within the minute after a rest keep at $k: $opens")
        r.session.stop("test")
    }

    @Test fun theHoldDoesNotSpinTheTickLoopAndTheCheckOpensAtHoldEnd() = runTest {
        val r = glanceRig(this)
        r.session.start()
        while (r.keyframes.size < 3 && r.clock() < 59_000) { r.gyro(1_000, 0f); r.scene++ }
        assertEquals(3, r.keyframes.size)
        val holdEnd = r.keyframes[0].first + 60_000
        r.tvFor(holdEnd - 3_000)
        val reads = r.profileReads
        val opens = r.camera.opens
        while (r.clock() < holdEnd - 20) { r.gyro(20, 0f); r.scene++ }
        assertEquals(opens, r.camera.opens, "no open before the hold end")
        while (r.clock() < holdEnd + 300) { r.gyro(20, 0f); r.scene++ }
        assertTrue(r.camera.opens > opens, "the deferred check opened at the hold end: ${r.camera.openTimes}")
        assertTrue(r.profileReads - reads < 80, "tick wake-ups over ~3.3 s: ${r.profileReads - reads} (a spin is ~1 per ms)")
        r.session.stop("test")
    }

    /** Task 9 re-review: the deferred open lands its frame after the oldest keep has aged out, whatever the camera's start-up time. */
    private suspend fun TestScope.deferredCheckKeeps(startupMs: Long) {
        val r = Rig(this, HudSettings(captureMode = CaptureMode.SMART), startupMs = { startupMs }, glance = GlanceParams())
            .apply { profile = CaptureProfile.of(CameraWake.SETTLE, BudgetTier.T0) }
        r.session.start()
        while (r.keyframes.size < 3 && r.clock() < 59_000) { r.gyro(1_000, 0f); r.scene++ }
        assertEquals(3, r.keyframes.size, "keeps=${r.keyframes.map { it.first }}")
        val holdEnd = r.keyframes[0].first + 60_000
        r.tvFor(holdEnd + 4_000)
        val after = r.keyframes.filter { it.first >= holdEnd }
        assertTrue(after.isNotEmpty(), "the deferred check ended in a keep (not a capped frame): opens=${r.camera.openTimes} keeps=${r.keyframes.map { it.first }} holdEnd=$holdEnd")
        r.session.stop("test")
    }

    @Test fun deferredCheckKeepsWhenStartupIsShorterThanTheLead() = runTest { deferredCheckKeeps(300) }

    @Test fun deferredCheckKeepsWhenStartupIsLongerThanTheLead() = runTest { deferredCheckKeeps(900) }
}
