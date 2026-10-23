package com.rokid.context.glasses

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.Channels
import com.rokid.context.core.model.Hello
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.PauseOrigin
import com.rokid.context.core.model.PauseRecord
import com.rokid.context.glasses.link.HudSettingsCache
import com.rokid.context.glasses.link.FakeBridge
import com.rokid.context.glasses.link.HudController
import com.rokid.context.glasses.pause.PauseToggle
import com.rokid.context.glasses.link.CxrBridge
import com.rokid.context.glasses.voice.PttProblem
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class GlassesRuntimeTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @After fun tearDown() = GlassesRuntime.resetForTests()

    /** Spec §5.5: whichever of the frame write and the pause toggle runs first, a pause leaves no frame behind. */
    @Test fun aFrameRacingAPauseNeverSurvivesIt() = runBlocking {
        val runtime = GlassesRuntime.get(app)
        runtime.pauses.ensureLoaded()
        repeat(40) { i ->
            val write = async(Dispatchers.Default) { runtime.bufferCaptured("g$i", ByteArray(2_048), System.currentTimeMillis(), 90, "0", 80) }
            val toggle = async(Dispatchers.Default) { runtime.togglePause() }
            val t = toggle.await()
            write.await()
            check(t is PauseToggle.Paused) { "round $i started paused" }
            assertEquals(0, runtime.buffer.size, "round $i")
            runtime.togglePause() // resume for the next round
        }
    }

    /** Spec §5.5: a newly merged phone pause active now drops the buffer, like a local pause; an expired one doesn't. */
    @Test fun newActivePhoneRecordClearsBuffer() = runBlocking {
        val runtime = GlassesRuntime.get(app)
        runtime.pauses.ensureLoaded()
        val now = System.currentTimeMillis()
        repeat(3) { runtime.bufferCaptured("f$it", ByteArray(1_024), now - 10_000 + it, 90, "0", 80) }
        assertEquals(3, runtime.buffer.size)
        runtime.applyPhoneSettings(HudSettings(pauses = listOf(PauseRecord("old", PauseOrigin.PHONE, now - 9_000, now - 1_000)), pausesExact = true))
        assertEquals(3, runtime.buffer.size, "an expired record keeps the buffer")
        runtime.applyPhoneSettings(HudSettings(pauses = listOf(PauseRecord("p", PauseOrigin.PHONE, now, null)), pausesExact = true))
        assertEquals(0, runtime.buffer.size)
        runtime.togglePause() // resume, so later tests start clear
        runtime.buffer.clear()
    }

    /** The hello sent when a frame is buffered still carries the trigger that just fired; the phone needs the new one. */
    @Test fun schedulingTheNextCaptureSendsAFreshHello() {
        val runtime = GlassesRuntime.get(app)
        val bridge = FakeBridge()
        val c = HudController(runtime.scope, bridge, HudSettingsCache(app.getSharedPreferences("rt_test", 0)), { Hello(glassesBatteryPct = 80, bufferSize = 0, glassesNowMs = 0, pauses = emptyList()) })
        runtime.attach(c)
        runtime.onCaptureScheduled()
        assertEquals(1, bridge.sent.count { it.first == Channels.HELLO })
    }

    /** LGC4: a `pc_settings` push with `diagRecord=true` reaches the persisted request (armed: no epoch yet), under `dataDir/files/diag_state`; an old push (no field) leaves it off. */
    @Test fun aSettingsPushDrivesTheDiagRecordRequestAndPersistsItWithoutAnEpoch() {
        val runtime = GlassesRuntime.get(app)
        val bridge = FakeBridge()
        val c = HudController(runtime.scope, bridge, HudSettingsCache(app.getSharedPreferences("rt_diag", 0)), { Hello(glassesBatteryPct = 80, bufferSize = 0, glassesNowMs = 0, pauses = emptyList()) })
        runtime.attach(c); c.start()
        val seen = java.util.concurrent.CountDownLatch(1)
        runtime.diagRecord.onChanged = { seen.countDown() }
        bridge.phone(Channels.SETTINGS, com.rokid.context.core.model.Wire.encode(HudSettings(diagRecord = true)))
        check(seen.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "the writer applied the request" }
        assertEquals(com.rokid.context.glasses.log.DiagFlagState.ARMED_NO_EPOCH, runtime.diagRecord.state())
        assertEquals(false, runtime.diagRecord.diagEnabled())
        assertEquals("1", java.io.File(java.io.File(app.applicationInfo.dataDir, "files"), "diag_state").readText().trim())
        assertEquals("armed_no_epoch", runtime.diagRecord.state().wire)
    }

    /** Minor 7: an old activity's late detach must not wipe the new activity's push-to-talk toast handler. */
    @Test fun anOldControllersDetachKeepsTheNewProblemHandler() {
        GlassesRuntime.bridgeFactory = { FakeBridge() }
        try {
            val runtime = GlassesRuntime.get(app)
            fun controller() = HudController(runtime.scope, FakeBridge(), HudSettingsCache(app.getSharedPreferences("rt_test", 0)), { Hello(glassesBatteryPct = 80, bufferSize = 0, glassesNowMs = 0, pauses = emptyList()) })
            val old = controller(); val new = controller()
            runtime.attach(old); runtime.attach(new)
            val handler: (PttProblem) -> Unit = {}
            runtime.ptt.onProblem = handler
            runtime.detach(old)
            assertSame(handler, runtime.ptt.onProblem)
            runtime.detach(new)
            assertNotSame(handler, runtime.ptt.onProblem)
        } finally {
            GlassesRuntime.bridgeFactory = { CxrBridge(it) }
        }
    }

    private fun ctx(state: com.rokid.context.core.model.context.ContextState) =
        com.rokid.context.core.model.context.MotionContextMessage(state = state, atMs = 1)

    /** The nudge policy reads this with no capture service running: the TTL and a session close must still end it. */
    @Test fun contextAppliedExpiresWithTheTtlAndResetsOnCloseWithoutAService() {
        val runtime = GlassesRuntime.get(app)
        val driving = com.rokid.context.core.model.context.ContextState.VEHICLE_DRIVING
        runtime.contextGate.onMessage(ctx(driving), 1_000)
        assertEquals(driving, runtime.contextApplied(2_000))
        assertEquals(true, !com.rokid.context.core.model.context.ContextPolicies.of(runtime.contextApplied(2_000)).nudges)
        assertEquals(com.rokid.context.core.model.context.ContextState.UNKNOWN, runtime.contextApplied(181_001))
        assertEquals(true, com.rokid.context.core.model.context.ContextPolicies.of(runtime.contextApplied(181_001)).nudges)
        runtime.contextGate.onMessage(ctx(driving), 1_000)
        runtime.contextGate.onMessage(null, 2_000) // session close
        assertEquals(com.rokid.context.core.model.context.ContextState.UNKNOWN, runtime.contextApplied(2_500))
    }
}
