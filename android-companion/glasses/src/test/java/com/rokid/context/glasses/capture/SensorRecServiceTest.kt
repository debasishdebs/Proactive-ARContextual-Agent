package com.rokid.context.glasses.capture

import android.Manifest
import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.CaptureMode
import com.rokid.context.core.model.HudSettings
import com.rokid.context.glasses.GlassesRuntime
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.time.Duration

/** v0.9.7 B item 3: the keep-alive service starts the developer sensor recording only with a Smart session, a chosen rate and no pause; it stops with them. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SensorRecServiceTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val runtime get() = GlassesRuntime.get(app)
    private var controller: ServiceController<CaptureService>? = null

    @Before fun setUp() { shadowOf(app).grantPermissions(Manifest.permission.CAMERA); ShadowLog.clear() }
    @After fun tearDown() { controller?.destroy(); GlassesRuntime.resetForTests() }

    private fun startKeepAlive(): CaptureService {
        val c = Robolectric.buildService(CaptureService::class.java, Intent(app, CaptureService::class.java).setAction(CaptureService.ACTION_KEEP_ALIVE))
        controller = c
        return c.create().startCommand(0, 1).get()
    }
    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun logs() = ShadowLog.getLogs().filter { it.tag == "PcCapture" }.map { it.msg }
    private fun waitUntil(what: String, cond: () -> Boolean) {
        repeat(400) { idle(100); if (cond()) return; Thread.sleep(10) }
        org.junit.Assert.fail("never: $what")
    }

    @Test fun aSmartSessionWithARateStartsTheRecording() {
        runtime.settingsCache.write(HudSettings(captureMode = CaptureMode.SMART, captureIntervalSec = 60, sensorRecHz = 10))
        startKeepAlive()
        waitUntil("recording") { "sensor recording started" in logs() }
        assertTrue(runtime.smartCapture.value)
    }

    @Test fun offByDefaultNeverStarts() {
        runtime.settingsCache.write(HudSettings(captureMode = CaptureMode.SMART, captureIntervalSec = 60))
        startKeepAlive()
        waitUntil("session") { runtime.smartCapture.value }
        idle(3_000)
        assertFalse(logs().toString(), logs().any { it.startsWith("sensor recording") })
    }

    @Test fun withoutASmartSessionTheRateDoesNothing() {
        runtime.settingsCache.write(HudSettings(captureMode = CaptureMode.FIXED, captureIntervalSec = 60, sensorRecHz = 25))
        startKeepAlive()
        waitUntil("the trace recorder") { runtime.traceRecorderReady }
        kotlinx.coroutines.runBlocking { runtime.pauses.ensureLoaded() } // loaded, so only the missing Smart session keeps it off
        idle(4_000)
        assertFalse(runtime.smartCapture.value)
        assertFalse(logs().any { it.startsWith("sensor recording") })
    }

    @Test fun aPauseStopsItAndResumingStartsItAgain() {
        runtime.settingsCache.write(HudSettings(captureMode = CaptureMode.SMART, captureIntervalSec = 60, sensorRecHz = 10))
        startKeepAlive()
        waitUntil("recording") { "sensor recording started" in logs() }
        kotlinx.coroutines.runBlocking { runtime.pauses.ensureLoaded(); runtime.pauses.toggle() }
        waitUntil("stopped by the pause") { "sensor recording stopped" in logs() }
        kotlinx.coroutines.runBlocking { runtime.pauses.toggle() }
        waitUntil("started again") { logs().count { it == "sensor recording started" } >= 2 }
    }

    @Test fun endingTheKeepAliveStopsItAtOnce() {
        runtime.settingsCache.write(HudSettings(captureMode = CaptureMode.SMART, captureIntervalSec = 60, sensorRecHz = 10))
        val service = startKeepAlive()
        waitUntil("recording") { "sensor recording started" in logs() }
        service.endKeepAlive()
        assertTrue(logs().toString(), "sensor recording stopped" in logs())
    }

    @Test fun clearingTheRateStopsItAndTheLogsNameEventsOnly() {
        runtime.settingsCache.write(HudSettings(captureMode = CaptureMode.SMART, captureIntervalSec = 60, sensorRecHz = 5))
        startKeepAlive()
        waitUntil("recording") { "sensor recording started" in logs() }
        runtime.settingsCache.write(HudSettings(captureMode = CaptureMode.SMART, captureIntervalSec = 60, sensorRecHz = 0))
        waitUntil("stopped") { "sensor recording stopped" in logs() }
        assertTrue(logs().filter { it.startsWith("sensor ") }.all { it in setOf("sensor recording started", "sensor recording stopped", "sensor batch written", "sensor batch pruned", "sensor recording blocked") })
    }
}
