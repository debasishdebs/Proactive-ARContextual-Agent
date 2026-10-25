package com.rokid.context.glasses

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The 2026-10-23 ANR class: the trace recorder's construction queues `TraceStorage.recover()`. It must be built ONLY on the writer thread, and the main thread must
 * neither build it nor wait for it (the writer is held back here, so a lazy build on the caller would show at once).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class GlassesRuntimeRecorderThreadTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @After fun tearDown() { GlassesRuntime.recorderBuildHook = null; GlassesRuntime.resetForTests() }

    @Test fun theRecorderIsBuiltOnTheWriterThreadAndTheMainThreadNeverBuildsOrWaitsForIt() {
        GlassesRuntime.resetForTests()
        val release = CountDownLatch(1)
        GlassesRuntime.recorderBuildHook = { release.await(30, TimeUnit.SECONDS) }
        val runtime = GlassesRuntime.get(app) // on the main looper in this test
        assertEquals(Looper.getMainLooper(), Looper.myLooper(), "this test runs on the main looper")
        // Writer held back: nothing exists, nothing is built by the caller, a read does not block.
        assertFalse(runtime.traceRecorderReady)
        assertNull(runtime.traceRecorderOrNull)
        assertEquals(null, runtime.recorderBuiltOn, "not built on the main thread")
        val e = runCatching { runtime.traceRecorder }.exceptionOrNull()
        assertTrue(e is IllegalStateException, "the main thread must not wait for or build the recorder: $e")
        runtime.pollTraceState() // a no-op until the recorder exists
        assertNull(runtime.recorderBuiltOn)
        release.countDown()
        repeat(500) { if (runtime.traceRecorderReady) return@repeat; Thread.sleep(10) }
        if (!runtime.traceRecorderReady) fail("the writer never built the recorder")
        val built = runtime.recorderBuiltOn
        assertEquals("imu-trace", built?.name)
        assertNotEquals(Looper.getMainLooper().thread, built)
        assertTrue(runtime.traceRecorderOrNull === runtime.traceRecorder)
    }

    /** A control that waits in the writer queue (here: behind a held-back recorder build) keeps the clocks of its ARRIVAL: its 300 ms collect window is over when the writer finally applies it. */
    @Test fun aControlQueuedBehindTheWriterIsAppliedWithItsArrivalClocks() {
        GlassesRuntime.resetForTests()
        val release = CountDownLatch(1)
        GlassesRuntime.recorderBuildHook = { release.await(30, TimeUnit.SECONDS) }
        val runtime = GlassesRuntime.get(app)
        val bridge = com.rokid.context.glasses.link.FakeBridge()
        val c = com.rokid.context.glasses.link.HudController(runtime.scope, bridge, com.rokid.context.glasses.link.HudSettingsCache(app.getSharedPreferences("rt_ctl", 0)), { com.rokid.context.core.model.Hello(glassesBatteryPct = 80, bufferSize = 0, glassesNowMs = 0, pauses = emptyList()) })
        runtime.attach(c); c.start()
        bridge.phone(com.rokid.context.core.model.Channels.TRACE_CTL, com.rokid.context.core.model.Wire.encode(com.rokid.context.core.model.TraceControl(collect = true, collectUntilMs = System.currentTimeMillis() + 300, installId = "inst-1", deletionCounter = 1, atMs = System.currentTimeMillis())))
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1_500)) // the control waits 1.5 s for the writer (Robolectric's elapsed clock only moves when told)
        release.countDown()
        repeat(1000) { if (runtime.traceRecorderReady && runtime.traceRecorder.state().recovered) return@repeat; Thread.sleep(5) }
        val st = runtime.traceRecorder.state()
        assertTrue(st.recovered, "the writer finished its start-up")
        assertFalse(st.collect, "the window ended 1.2 s ago on the arrival clock; sampled at execution it would still be open")
    }
}
