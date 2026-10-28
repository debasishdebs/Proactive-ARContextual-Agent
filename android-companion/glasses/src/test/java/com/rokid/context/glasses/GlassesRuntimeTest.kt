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

    /**
     * v0.9.6 slice C, the bounded exception on the glasses: a mark's photo is buffered while capture is PAUSED (an ordinary frame is refused), names its mark, and is the only thing that does:
     * the same pause still refuses every ordinary frame.
     */
    @Test fun aMarkPhotoIsBufferedWhileCapturePausedButAnOrdinaryFrameIsNot() = runBlocking {
        val runtime = GlassesRuntime.get(app)
        runtime.pauses.ensureLoaded()
        check(runtime.togglePause() is PauseToggle.Paused)
        val kf = com.rokid.context.glasses.capture.KeyframeOut(ByteArray(1_024), 640, com.rokid.context.core.model.MotionState.UNKNOWN, false, 90f, "0", "0", 1, "mark")
        assertEquals(BufferResult.PAUSED, runtime.bufferCaptured("g1", ByteArray(1_024), System.currentTimeMillis(), 90, "0", 80))
        assertEquals(true, runtime.bufferMarkPhoto("g2", kf, System.currentTimeMillis(), 90, 80, "ep:1"))
        assertEquals("ep:1", runtime.buffer.oldest()!!.meta.markRef)
        assertEquals(1, runtime.buffer.size)
        assertEquals(true, runtime.markPhoto.let { it.discard("ep:1"); runtime.buffer.size == 0 })
    }

    /** The service-less case: no capture service means a typed failure, never a hang or a crash. */
    @Test fun withNoCaptureServiceAMarkPhotoFailsWithItsReason() = runBlocking {
        val runtime = GlassesRuntime.get(app)
        assertEquals(com.rokid.context.glasses.mark.MarkPhotoOutcome.Failed(com.rokid.context.core.model.MarkPhotoReason.NO_CAPTURE_SERVICE), runtime.markPhoto.take("ep:1"))
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

    /** v0.9.7 slice D: the glasses say they answer a photo request, and a request on its channel reaches the mark photo job (here: no capture service, so the phone hears `no_capture_service`). */
    @Test fun theGlassesAnswerAPhoneMarkPhotoRequest() {
        val runtime = GlassesRuntime.get(app)
        assertEquals(true, runtime.hello(null).markPhotoRequest)
        val bridge = FakeBridge()
        val c = HudController(runtime.scope, bridge, HudSettingsCache(app.getSharedPreferences("rt_test_req", 0)), { Hello(glassesBatteryPct = 80, bufferSize = 0, glassesNowMs = 0, pauses = emptyList()) })
        c.start()
        runtime.attach(c)
        assertEquals(true, bridge.subscriptions.contains(Channels.MARK_PHOTO_REQUEST))
        bridge.phone(Channels.STATE, com.rokid.context.core.model.Wire.encode(com.rokid.context.core.model.StateFrame(seq = 1, capture = com.rokid.context.core.model.CaptureState.Running, intervalSec = 300, phoneLinked = true, aiHealth = com.rokid.context.core.model.Health.Ok))) // opens the session
        bridge.phone(Channels.MARK_PHOTO_REQUEST, com.rokid.context.core.model.Wire.encode(com.rokid.context.core.model.MarkPhotoRequest(ref = "phone:${System.currentTimeMillis()}", atMs = System.currentTimeMillis(), sessionStartMs = 1)))
        val deadline = System.currentTimeMillis() + 5_000
        while (bridge.sent.none { it.first == Channels.MARK_PHOTO } && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(1, bridge.sent.count { it.first == Channels.MARK_PHOTO })
    }

    private fun requestRig(): Pair<HudController, FakeBridge> {
        val runtime = GlassesRuntime.get(app)
        val bridge = FakeBridge()
        val c = HudController(runtime.scope, bridge, HudSettingsCache(app.getSharedPreferences("rt_test_req2", 0)), { Hello(glassesBatteryPct = 80, bufferSize = 0, glassesNowMs = 0, pauses = emptyList()) })
        c.start(); runtime.attach(c)
        return c to bridge
    }
    private fun openSession(b: FakeBridge) = b.phone(Channels.STATE, com.rokid.context.core.model.Wire.encode(com.rokid.context.core.model.StateFrame(seq = 1, capture = com.rokid.context.core.model.CaptureState.Running, intervalSec = 300, phoneLinked = true, aiHealth = com.rokid.context.core.model.Health.Ok)))
    private fun photoRequest(b: FakeBridge, session: Long = 1) { val now = System.currentTimeMillis(); b.phone(Channels.MARK_PHOTO_REQUEST, com.rokid.context.core.model.Wire.encode(com.rokid.context.core.model.MarkPhotoRequest(ref = "phone:$now", atMs = now, sessionStartMs = session))) }
    private fun photoReports(b: FakeBridge) = b.sent.filter { it.first == Channels.MARK_PHOTO }.map { it.second.joinToString() }
    private fun waitFor(b: FakeBridge, n: Int) { val d = System.currentTimeMillis() + 3_000; while (photoReports(b).size < n && System.currentTimeMillis() < d) Thread.sleep(20) }

    /** Review fix: a photo request before the session opened, after it closed or after the wearer exited never reaches the camera (the answer, when one can go, is `session_closed`). */
    @Test fun aPhotoRequestOutsideAnOpenSessionIsRefused() {
        val (c, b) = requestRig()
        photoRequest(b); waitFor(b, 1)
        assertEquals(1, photoReports(b).size); assertEquals(true, photoReports(b).single().contains("session_closed"))
        openSession(b); photoRequest(b); waitFor(b, 2)
        assertEquals(true, photoReports(b).last().contains("no_capture_service"), "an open session runs the job (no capture service here: its own reason)")
        c.onActivityExit(); b.sent.clear(); photoRequest(b); Thread.sleep(300)
        assertEquals(0, photoReports(b).size, "nothing goes out after the wearer exited")
    }

    @Test fun aPhotoRequestOfAnotherPhoneSessionIsRefused() {
        val (_, b) = requestRig()
        openSession(b)
        b.phone(Channels.WEAR, com.rokid.context.core.model.Wire.encode(com.rokid.context.core.model.WearMessage(worn = true, atMs = 5_000, sessionStartMs = 4_000)))
        photoRequest(b, session = 3_000); waitFor(b, 1)
        assertEquals(true, photoReports(b).single().contains("session_closed"))
        photoRequest(b, session = 4_000); waitFor(b, 2)
        assertEquals(true, photoReports(b).last().contains("no_capture_service"))
    }

    /** LGC4: a `pc_settings` push with `diagRecord=true` reaches the persisted request (armed: no epoch yet), under `dataDir/files/diag_state`; an old push (no field) leaves it off. */
    @Test fun aSettingsPushDrivesTheDiagRecordRequestAndPersistsItWithoutAnEpoch() {
        val runtime = GlassesRuntime.get(app)
        val bridge = FakeBridge()
        val c = HudController(runtime.scope, bridge, HudSettingsCache(app.getSharedPreferences("rt_diag", 0)), { Hello(glassesBatteryPct = 80, bufferSize = 0, glassesNowMs = 0, pauses = emptyList()) })
        runtime.attach(c); c.start()
        val seen = java.util.concurrent.CountDownLatch(1)
        val previousHook = runtime.diagRecord.onChanged
        runtime.diagRecord.onChanged = { previousHook(); seen.countDown() }
        try {
        bridge.phone(Channels.SETTINGS, com.rokid.context.core.model.Wire.encode(HudSettings(diagRecord = true)))
        check(seen.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "the writer applied the request" }
        assertEquals(com.rokid.context.glasses.log.DiagFlagState.ARMED_NO_EPOCH, runtime.diagRecord.state())
        assertEquals(false, runtime.diagRecord.diagEnabled())
        assertEquals("1", java.io.File(java.io.File(app.applicationInfo.dataDir, "files"), "diag_state").readText().trim())
        assertEquals("armed_no_epoch", runtime.diagRecord.state().wire)
        } finally { runtime.diagRecord.onChanged = previousHook }
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
