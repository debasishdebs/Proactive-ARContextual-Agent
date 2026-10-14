package com.rokid.context.glasses.capture

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.glasses.GlassesRuntime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * Background capture (Android 11+ while-in-use camera): the keep-alive CaptureService is started while the HUD is in
 * front and stays foreground; alarms reach it in-process so no background service start re-evaluates its camera access.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CaptureKeepAliveTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val runtime get() = GlassesRuntime.get(app)
    private var controller: ServiceController<CaptureService>? = null

    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        clearStartedServices()
    }

    @After fun tearDown() {
        controller?.destroy()
        GlassesRuntime.resetForTests()
    }

    private fun clearStartedServices() { while (shadowOf(app).nextStartedService != null) Unit }

    private fun startKeepAlive(): CaptureService {
        val c = Robolectric.buildService(CaptureService::class.java, Intent(app, CaptureService::class.java).setAction(CaptureService.ACTION_KEEP_ALIVE))
        controller = c
        return c.create().startCommand(0, 1).get()
    }

    private fun waitUntil(cond: () -> Boolean) {
        repeat(300) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return
            Thread.sleep(10)
        }
        fail("condition not reached")
    }

    private fun pause() = runBlocking {
        runtime.pauses.ensureLoaded()
        runtime.togglePause()
        assertFalse(runtime.pauses.captureAllowed())
    }

    private fun alarmAt(): Long? = shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.singleOrNull()?.triggerAtTime

    @Test fun alarmDeliversInProcessWhenServiceAlive() {
        val svc = startKeepAlive()
        pause() // the capture skips before the camera, which Robolectric lacks
        clearStartedServices()
        CaptureAlarmReceiver().onReceive(app, Intent(app, CaptureAlarmReceiver::class.java))
        assertNull("no service start", shadowOf(app).nextStartedService)
        waitUntil { svc.capturesStarted == 1 && alarmAt() != null }
    }

    @Test fun alarmFallsBackToServiceStartWhenNotRunning() {
        assertNull(CaptureService.liveInstance)
        CaptureAlarmReceiver().onReceive(app, Intent(app, CaptureAlarmReceiver::class.java))
        val started = shadowOf(app).nextStartedService
        assertEquals(CaptureService::class.java.name, started.component!!.className)
        assertEquals(CaptureService.ACTION_SCHEDULED, started.action)
    }

    @Test fun keepAliveServiceStaysForegroundAfterCapture() {
        val svc = startKeepAlive()
        assertTrue(CaptureService.liveInstance === svc)
        pause()
        assertTrue(svc.captureNow(skipDedupe = false))
        waitUntil { svc.capturesStarted == 1 && alarmAt() != null && !svc.captureInFlight }
        assertFalse(shadowOf(svc).isStoppedBySelf)
        assertFalse(shadowOf(svc).isForegroundStopped)
        assertTrue(CaptureService.liveInstance === svc)
    }

    @Test fun wearerExitStopsKeepAlive() {
        val svc = startKeepAlive()
        CaptureService.stopKeepAlive(app)
        assertTrue(shadowOf(svc).isStoppedBySelf)
        assertTrue(shadowOf(svc).isForegroundStopped)
        assertNull(CaptureService.liveInstance)
    }

    @Test fun pausedStillSkipsCapture() {
        val svc = startKeepAlive()
        pause()
        val before = runtime.buffer.size
        CaptureAlarmReceiver().onReceive(app, Intent(app, CaptureAlarmReceiver::class.java))
        waitUntil { svc.capturesStarted == 1 && !svc.captureInFlight }
        assertEquals(before, runtime.buffer.size)
        assertEquals(CaptureService.Outcome.SKIPPED, svc.lastOutcome)
    }
}
