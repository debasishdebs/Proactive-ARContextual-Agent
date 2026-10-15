package com.rokid.context.glasses.capture.session

import com.rokid.context.core.model.CaptureMode
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.MotionState
import com.rokid.context.glasses.capture.keyframe.ImageHashes
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
        for (s in 1..3) { r.scene = s; delay(6_000) } // three settled scenes, one brief check each
        assertEquals(listOf(1, 2, 3), r.scenesKept)
        // look back at scene 1: not new against the last five, so only a dwell can save it
        val turnAt = r.clock()
        r.gyroMag = 3f; r.scene = 1
        delay(100); r.gyroMag = 0f
        delay(2_000)
        val (at, last) = r.keyframes.last()
        assertEquals(1, Scenes.sceneOf(last.jpeg)); assertTrue(last.dwell)
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

    @Test fun captureNowIsRefusedWhileNotSampling() = runTest {
        val r = rig(this)
        r.paused = true
        r.session.start()
        delay(1_000)
        assertFalse(r.session.captureNow())
        r.session.stop("test")
    }

    @Test fun restUsesFixedEvery60s() = runTest {
        val r = rig(this) { work = 1; rest = 5 }
        r.fixedDuring = { assertFalse(r.camera.isOpen); assertEquals("fixed", r.owner.current) }
        r.session.start()
        delay(61_000) // one minute of work, then rest
        assertEquals("rest", r.session.status.value.reason)
        assertNotNull(r.session.status.value.restingUntilMs)
        assertFalse(r.camera.isOpen)
        val restStart = r.clock()
        val opensAtRest = r.camera.opens
        delay(4 * 60_000L + 30_000)
        assertEquals(opensAtRest, r.camera.opens, "no sampling during rest")
        assertEquals(4, r.fixedCaptures.size)
        r.fixedCaptures.zipWithNext { a, b -> assertEquals(60_000L, b - a) }
        assertTrue(r.fixedCaptures.first() - restStart in 0..61_000)
        delay(60_000) // rest over: sampling resumes
        assertEquals(4, r.fixedCaptures.size)
        assertNull(r.session.status.value.restingUntilMs)
        assertTrue(r.camera.opens > opensAtRest)
        r.session.stop("test")
    }

    @Test fun briefChecksFollowThe5sBeatAndEndAfterTheSettle() = runTest {
        val r = rig(this)
        r.session.start()
        delay(30_000)
        // first check at start, then one about every 5 s (each opens 535 ms early, closing on its first frame)
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
        delay(25_000)
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
        assertEquals("rest", b.session.status.value.reason)
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
        for (s in 1..3) { r.scene = s; delay(6_000) }
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
    @Test fun fallbackAndRestFixedCapturesNeverRunInsideTheSessionBlock() = runTest {
        val r = rig(this)
        val ran = ArrayList<String>()
        r.camera.failOpens = 99
        r.onFallbackAction = { r.owner.acquire("fixed") { ran += "fallback:${r.owner.current}:camera=${r.camera.isOpen}" } }
        r.session.start()
        delay(10_000)
        assertEquals(listOf("fallback:fixed:camera=false"), ran, "the fallback capture got the owner (no deadlock)")
        val rest = rig(this) { work = 1; rest = 5 }
        rest.fixedDuring = { ran += "rest:${rest.owner.current}:camera=${rest.camera.isOpen}" }
        rest.session.start()
        delay(61_000 + 61_000)
        assertTrue(ran.contains("rest:fixed:camera=false"), "$ran")
        rest.session.stop("test")
    }
}
