package com.rokid.context.glasses.capture.session

import com.rokid.context.core.model.CameraWake
import com.rokid.context.core.model.CaptureMode
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.context.ContextState
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * S23 / S84 (2): "Capture now" and a glance are exempt from novelty, guards, the timer photo gaps, the cap and the hard ceiling; they are
 * NEVER exempt from the user pause, the wear gate, heat STOP, or camera ownership and busy. (Forget/wipe fences live in the phone's
 * deletion epoch and the glasses' buffer, not in the session: there is no wipe seam here to test.)
 */
class CaptureNowPrecedenceTest {
    private fun restrictive(gap: Long = 300_000) = CaptureProfile(CameraWake.SETTLE, gap, gap, gap, 333, false, stillCapMs = gap)
    private fun rig(t: kotlinx.coroutines.test.TestScope, configure: Rig.() -> Unit = {}) =
        Rig(t, HudSettings(captureMode = CaptureMode.SMART, pauseWhenNotWorn = true)).apply { profile = restrictive(); applied = ContextState.REST; configure() }

    private suspend fun settled(r: Rig) { r.startSensors(); r.gyroMag = 0.01f; r.session.start(); delay(100_000) }

    @Test fun captureNowNeverWaitsForTimerGap() = runTest {
        val r = rig(this)
        settled(r)
        val before = r.keyframes.size; val opens = r.camera.opens
        val at = r.clock()
        assertTrue(r.session.captureNow())
        delay(2_000)
        assertEquals(opens + 1, r.camera.opens, "an open at once, not at the next 300 s timer gap")
        assertEquals(before + 1, r.keyframes.size, "the forced keep landed")
        assertTrue(r.keyframes.last().first - at <= 1_500, "latency ${r.keyframes.last().first - at}")
        r.session.stop("test")
    }

    @Test fun glanceNeverWaitsForTimerGap() = runTest {
        val r = rig(this)
        settled(r)
        val opens = r.camera.opens
        r.scene = 99
        r.session.diagSnapshot()
        val at = r.clock()
        r.gyroMag = 1.5f; delay(300); r.gyroMag = 0.01f       // a head turn, then a 1 s dwell
        delay(3_000)
        val d = r.session.diagSnapshot()
        assertEquals(1, d.wakes["dwell"], "the glance opened the camera: ${d.wakes}")
        assertTrue(r.camera.openTimes.last() - at <= 1_500 + 300, "trigger-to-open ${r.camera.openTimes.last() - at}")
        assertTrue(r.camera.opens > opens)
        r.session.stop("test")
    }

    @Test fun captureNowRefusedWhilePaused() = runTest {
        val r = rig(this)
        settled(r)
        r.paused = true; delay(1_000)
        val opens = r.camera.opens
        assertFalse(r.session.captureNow(), "the user pause beats Capture now")
        delay(5_000)
        assertEquals(opens, r.camera.opens)
        r.session.stop("test")
    }

    @Test fun captureNowRefusedWhenNotWorn() = runTest {
        val r = rig(this)
        settled(r)
        r.wear.onRemote(false); delay(1_000)
        val opens = r.camera.opens
        assertFalse(r.session.captureNow(), "the wear gate beats Capture now")
        delay(5_000)
        assertEquals(opens, r.camera.opens)
        r.session.stop("test")
    }

    @Test fun captureNowRefusedDuringHeatStop() = runTest {
        val r = rig(this)
        settled(r)
        r.heatReading = HeatReading(0, Float.NaN, 46f); delay(40_000)
        assertEquals(HeatLevel.STOP, r.heat.level.value)
        val opens = r.camera.opens
        assertFalse(r.session.captureNow(), "heat STOP beats Capture now")
        delay(5_000)
        assertEquals(opens, r.camera.opens)
        r.session.stop("test")
    }

    @Test fun captureNowWhileTheCameraIsBusyBacksOffAndNeverLatchesTheFallback() = runTest {
        val r = rig(this)
        settled(r)
        r.camera.busyOpens = true
        assertTrue(r.session.captureNow())
        delay(25_000)
        val busyOpens = r.camera.openTimes.count { it > 100_000 }
        assertEquals(1, busyOpens, "one attempt, then the busy back-off holds (no retry storm)")
        assertEquals(0, r.fallbacks, "busy is not a bind fault")
        r.session.stop("test")
    }

    @Test fun captureNowStillBypassesTheCapAndNovelty() = runTest {
        val r = rig(this) { contextCap = 1 }
        settled(r)
        val before = r.keyframes.size
        r.session.captureNow(); delay(2_000)
        r.session.captureNow(); delay(2_000)
        assertEquals(before + 2, r.keyframes.size, "forced keeps are exempt from the cap and the hard ceiling (A3 section 5)")
        r.session.stop("test")
    }

    /** Forced keeps are exempt from the ceiling but they count in the minute: the next glance then meets 2 x cap and is a ceiling hit (what the auto-revert counts). */
    @Test fun aGlanceRefusedByTheCeilingIsReportedAndOneBelowItIsNot() = runTest {
        val r = rig(this) { contextCap = 1 }
        settled(r)
        r.session.captureNow(); delay(2_000)
        r.scene = 50; r.gyroMag = 1.5f; delay(300); r.gyroMag = 0.01f; delay(3_000)
        assertTrue(r.ceilingHits.isEmpty(), "one keep in the minute is the cap, not the ceiling: ${r.ceilingHits}")
        r.session.captureNow(); delay(2_000)
        r.scene = 51; r.gyroMag = 1.5f; delay(300); r.gyroMag = 0.01f; delay(3_000)
        assertTrue(r.ceilingHits.isNotEmpty(), "two keeps against cap 1: the ceiling")
        r.session.stop("test")
    }
}
