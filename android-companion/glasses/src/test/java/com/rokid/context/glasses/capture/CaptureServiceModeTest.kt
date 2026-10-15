package com.rokid.context.glasses.capture

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.CaptureMode
import com.rokid.context.core.model.HudSettings
import com.rokid.context.glasses.GlassesRuntime
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ServiceController
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Smart vs Fixed in the keep-alive service: the session follows the synced mode, the alarm chain is only a watchdog in Smart. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CaptureServiceModeTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val runtime get() = GlassesRuntime.get(app)
    private var controller: ServiceController<CaptureService>? = null

    @Before fun setUp() { shadowOf(app).grantPermissions(Manifest.permission.CAMERA) }

    @After fun tearDown() {
        controller?.destroy()
        GlassesRuntime.resetForTests()
    }

    private fun startKeepAlive(): CaptureService {
        val c = Robolectric.buildService(CaptureService::class.java, Intent(app, CaptureService::class.java).setAction(CaptureService.ACTION_KEEP_ALIVE))
        controller = c
        return c.create().startCommand(0, 1).get()
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** Main-looper time moves 100 ms a round; the pause store loads on a real thread, hence the sleeps. */
    private fun waitUntil(what: String, cond: () -> Boolean) {
        repeat(400) {
            idle(100)
            if (cond()) return
            Thread.sleep(10)
        }
        kotlin.test.fail("never: $what")
    }

    private fun alarmIn(): Long? = shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.singleOrNull()?.triggerAtTime?.let { it - System.currentTimeMillis() }

    @Test fun fixedModeRunsNoSession() {
        startKeepAlive()
        idle(2_500)
        assertFalse(runtime.smartCapture.value)
        assertFalse(runtime.captureStatus.value.degraded)
        assertEquals("fixed", runtime.captureStatus.value.reason)
    }

    @Test fun smartModeStartsASessionAndArmsOnlyTheWatchdog() {
        runtime.settingsCache.write(HudSettings(captureMode = CaptureMode.SMART, captureIntervalSec = 60))
        startKeepAlive()
        waitUntil("session") { runtime.smartCapture.value }
        val in5min = assertNotNull(alarmIn())
        assertTrue(in5min in 290_000..300_000, "the alarm is a 5 min watchdog, not the 60 s interval: $in5min")
    }

    @Test fun switchingToFixedStopsTheSessionAndRearmsTheInterval() {
        runtime.settingsCache.write(HudSettings(captureMode = CaptureMode.SMART, captureIntervalSec = 60))
        startKeepAlive()
        waitUntil("session") { runtime.smartCapture.value }
        runtime.settingsCache.write(HudSettings(captureMode = CaptureMode.FIXED, captureIntervalSec = 60))
        waitUntil("release") { !runtime.smartCapture.value }
        assertEquals(null, runtime.cameraOwner.current, "nobody holds the camera")
        val next = assertNotNull(alarmIn())
        assertTrue(next in 50_000..60_000, "Fixed interval armed again: $next")
        // and back
        runtime.settingsCache.write(HudSettings(captureMode = CaptureMode.SMART, captureIntervalSec = 60))
        waitUntil("session again") { runtime.smartCapture.value }
    }

    @Test fun exitingTheHudEndsTheSession() {
        runtime.settingsCache.write(HudSettings(captureMode = CaptureMode.SMART))
        val svc = startKeepAlive()
        waitUntil("session") { runtime.smartCapture.value }
        svc.endKeepAlive()
        assertFalse(runtime.smartCapture.value)
        assertEquals("exit", runtime.captureStatus.value.reason)
    }
}

/** The Fixed capture forcing rule and the Smart fallback latch (review of G3b, important 2 and 5). */
class CaptureForceAndLatchTest {
    @Test fun aUserCaptureNowIsForcedButTheAlarmAndRestAreNot() {
        assertTrue(gateForce(skipDedupe = false, userRequest = true))
        assertTrue(gateForce(skipDedupe = true, userRequest = false))
        assertFalse(gateForce(skipDedupe = false, userRequest = false))
    }

    @Test fun theFallbackIsClearedByTheWatchdogAfterFiveMinutes() {
        var now = 0L
        val latch = DegradedLatch({ now }, 5 * 60_000L)
        assertFalse(latch.tripped)
        latch.trip()
        now = 4 * 60_000L
        assertFalse(latch.retryDue()); assertTrue(latch.tripped)
        now = 5 * 60_000L
        assertTrue(latch.retryDue()); assertFalse(latch.tripped)
        assertFalse(latch.retryDue())
    }
}
