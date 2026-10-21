package com.rokid.context.glasses

import android.Manifest
import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.KeyEvent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.hud.DoubleTapDetector
import com.rokid.context.core.hud.DoubleTapExit
import com.rokid.context.core.model.CaptureState
import com.rokid.context.core.model.Channels
import com.rokid.context.core.model.Command
import com.rokid.context.core.model.CommandEnvelope
import com.rokid.context.core.model.Decoded
import com.rokid.context.core.model.Health
import com.rokid.context.core.model.Hello
import com.rokid.context.core.model.MotionState
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.PauseOrigin
import com.rokid.context.core.model.PauseRecord
import com.rokid.context.core.model.StateFrame
import com.rokid.context.core.model.Wire
import com.rokid.context.glasses.capture.CaptureService
import com.rokid.context.glasses.capture.SessionStatus
import com.rokid.context.glasses.link.CxrBridge
import com.rokid.context.glasses.link.FakeBridge
import com.rokid.context.glasses.link.HudController
import com.rokid.context.glasses.voice.PcmSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.util.concurrent.Semaphore
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/** MainActivity wiring (spec §1.7 send gate, §5.5 pause round trip, R-34) against the fake CXR bridge. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MainActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val bridge = FakeBridge()
    private val runtime get() = GlassesRuntime.get(app)

    /** The glasses mic in tests: blocks in read() until a permit (each permit = one loud 100 ms chunk). */
    private class FakeMic : PcmSource {
        val permits = Semaphore(0)
        @Volatile var closed = false
        override fun start() = Unit
        override fun read(buf: ByteArray): Int { permits.acquire(); if (closed) return -1; buf.fill(40.toByte()); return buf.size }
        override fun close() { closed = true; permits.release(100) }
    }
    private val mic = FakeMic()

    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        GlassesRuntime.bridgeFactory = { bridge }
        GlassesRuntime.pcmSourceFactory = { mic }
    }

    @After fun tearDown() {
        mic.permits.release(100)
        GlassesRuntime.resetForTests()
        GlassesRuntime.bridgeFactory = { CxrBridge(it) }
        GlassesRuntime.pcmSourceFactory = { com.rokid.context.glasses.voice.AudioRecordSource() }
    }

    private fun tap(a: MainActivity) {
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
    }
    private fun sentOn(channel: String) = bridge.sent.count { it.first == channel }

    private fun phoneState() = bridge.phone(
        Channels.STATE,
        Wire.encode(StateFrame(seq = 1, capture = CaptureState.Running, intervalSec = 300, phoneLinked = true, aiHealth = Health.Ok)),
    )

    private fun commands(): List<Command> = bridge.sent.filter { it.first == Channels.CMD }
        .map { (Wire.decode<CommandEnvelope>(it.second[0]) as Decoded.Ok).value.command }

    /** The pause store writes on real IO threads; pump the main looper until [cond] holds. */
    private fun waitUntil(cond: () -> Boolean) {
        repeat(500) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return
            Thread.sleep(10)
        }
        fail("condition not reached")
    }

    /** Spec 2026-10-14 §2: a page double-tap pauses only when the 3 s exit gate ends (uptime clock). */
    private fun gateExpires() = shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(DoubleTapExit.WINDOW_MS + 300))

    /** Capture requests sent to [CaptureService], the only capture path (Task 11). */
    private fun captureStarts(): Int {
        var n = 0
        while (true) {
            val i = shadowOf(app).nextStartedService ?: return n
            if (i.component?.className == CaptureService::class.java.name && i.action == CaptureService.ACTION_NOW) n++
        }
    }

    @Suppress("DEPRECATION")
    @Test fun backPausesThenResumesAndTellsThePhone() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            assertTrue(runtime.buffer.add("f1", byteArrayOf(1), System.currentTimeMillis(), null, null, null))
            s.onActivity { it.onBackPressed() }
            gateExpires()
            waitUntil { commands().any { it is Command.Pause } }
            val pause = commands().filterIsInstance<Command.Pause>().single()
            assertEquals(0, runtime.buffer.size)

            Thread.sleep(DoubleTapDetector.SETTLE_MS + 100) // the detector runs on the wall clock, which Robolectric leaves real
            s.onActivity { it.onBackPressed() }
            gateExpires()
            waitUntil { commands().any { it is Command.Resume } }
            assertEquals(setOf(pause.pause.pauseId), commands().filterIsInstance<Command.Resume>().single().clears)
        }
    }

    /** A Resume made while BT is down (its one-shot command lost) is re-sent in the `pc_hello` of the next session. */
    @Suppress("DEPRECATION")
    @Test fun aResumeMadeOfflineIsResentInTheNextSessionsHello() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            val p = PauseRecord("phone-p", PauseOrigin.PHONE, System.currentTimeMillis() - 1_000, null)
            val settings = Wire.encode(HudSettings(pauses = listOf(p)))
            bridge.phone(Channels.SETTINGS, settings)
            waitUntil { runtime.pauses.records.value.any { it.pauseId == p.pauseId } }
            bridge.disconnected()
            s.onActivity { it.onBackPressed() } // the wearer resumes offline
            gateExpires()
            waitUntil { commands().any { it is Command.Resume } }
            assertTrue(runtime.pauses.captureAllowed())

            bridge.sent.clear()
            bridge.phone(Channels.SETTINGS, settings) // reconnect: the phone still lists P
            val hello = bridge.sent.first { it.first == Channels.HELLO }.second.single()
            assertEquals(setOf(p.pauseId), (Wire.decode<Hello>(hello) as Decoded.Ok).value.resumedAtMs.keys)
            Thread.sleep(200); shadowOf(Looper.getMainLooper()).idle()
            assertTrue(runtime.pauses.captureAllowed(), "the stale push does not re-pause")
        }
    }

    /** G4 carry-over: the capture status reaches the HUD ("Cooling" on a heat stop, "capture degraded" after three failed binds). */
    @Test fun theHudShowsTheCoolingAndDegradedMarks() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("Cooling", substring = true).assertDoesNotExist()
            compose.onNodeWithText("capture degraded", substring = true).assertDoesNotExist()
            runtime.captureStatus.value = SessionStatus(false, MotionState.STILL, 0, null, "heat", cooling = true)
            shadowOf(Looper.getMainLooper()).idle()
            compose.onNodeWithText("Cooling", substring = true).assertExists()
            runtime.captureStatus.value = runtime.captureStatus.value.copy(cooling = false, degraded = true)
            shadowOf(Looper.getMainLooper()).idle()
            compose.onNodeWithText("Cooling", substring = true).assertDoesNotExist()
            compose.onNodeWithText("capture degraded", substring = true).assertExists()
        }
    }

    @Test fun aTapStartsPushToTalkAndASecondTapSendsIt() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            phoneState()
            s.onActivity { tap(it) }
            waitUntil { Command.ListenStart in commands() }
            mic.permits.release(2)
            waitUntil { sentOn(Channels.AUDIO) == 2 }
            assertTrue(runtime.listening.value, "frames don't flush while recording")
            compose.onNodeWithText("tap to send", substring = true).assertExists()
            s.onActivity { tap(it) }
            mic.permits.release(1)
            waitUntil { sentOn(Channels.LISTEN_END) == 1 && !runtime.listening.value }
        }
    }

    @Test fun aDoubleTapWhileRecordingCancelsWithoutAListenEnd() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            phoneState()
            s.onActivity { tap(it) }
            waitUntil { Command.ListenStart in commands() }
            @Suppress("DEPRECATION") s.onActivity { it.onBackPressed() } // BACK = double-tap on this firmware
            mic.permits.release(1)
            waitUntil { Command.Cancel in commands() && !runtime.listening.value }
            assertEquals(0, sentOn(Channels.LISTEN_END))
        }
    }

    @Test fun thePhonesReasonBlocksAskWithAToast() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            bridge.phone(Channels.SETTINGS, Wire.encode(HudSettings(askBlockedReason = "Voice not supported on this phone")))
            phoneState()
            s.onActivity { tap(it) }
            compose.onNodeWithText("Voice not supported on this phone", substring = true).assertExists() // HudToast prefixes a space
            assertFalse(Command.ListenStart in commands())
        }
    }

    @Suppress("DEPRECATION")
    @Test fun askWorksWhileCaptureIsPaused() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            phoneState()
            s.onActivity { it.onBackPressed() }
            gateExpires()
            waitUntil { commands().any { it is Command.Pause } }
            assertFalse(runtime.pauses.captureAllowed())
            Thread.sleep(DoubleTapDetector.SETTLE_MS + 100)
            s.onActivity { tap(it) }
            waitUntil { Command.ListenStart in commands() }
        }
    }

    /**
     * The session closes synchronously on the disconnect; pc_listen_end is gated on the open session, so whichever of
     * the ptt thread (the permit) and the runtime's cancel runs first, no pc_listen_end goes out (fix round 1).
     */
    @Test fun closingTheSessionStopsRecordingWithoutAListenEnd() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            phoneState()
            s.onActivity { tap(it) }
            waitUntil { runtime.listening.value && Command.ListenStart in commands() }
            bridge.disconnected()
            mic.permits.release(1)
            waitUntil { !runtime.listening.value }
            assertEquals(0, sentOn(Channels.LISTEN_END))
        }
    }

    @Test fun withoutTheMicPermissionATapSaysSo() {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            phoneState()
            s.onActivity { tap(it) }
            compose.onNodeWithText("Allow the microphone on the glasses", substring = true).assertExists() // HudToast prefixes a space
            assertFalse(Command.ListenStart in commands())
        }
    }

    @Test fun closingTheActivityClosesTheSessionForGood() {
        val s = ActivityScenario.launch(MainActivity::class.java)
        phoneState()
        lateinit var c: HudController
        s.onActivity { c = it.controller }
        assertTrue(c.sessionOpen.value)
        s.close()
        assertFalse(c.sessionOpen.value)
        assertNull(runtime.controller)
        phoneState() // the phone's heartbeat must not reopen it
        assertFalse(c.sessionOpen.value)
    }

    /** Spec §1.7: the capture fallback may bring the HUD back while it is in front... */
    @Test fun theCaptureFallbackWorksWhileTheActivityIsInFront() {
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue(runtime.fallback.maybeLaunch(app))
            assertEquals(MainActivity::class.java.name, shadowOf(app).nextStartedActivity.component?.className)
        }
    }

    /** ...and never after the wearer exited it: a failed capture window must not pull the HUD over another app. */
    @Test fun theCaptureFallbackDoesNotReopenAnExitedActivity() {
        ActivityScenario.launch(MainActivity::class.java).close()
        while (shadowOf(app).nextStartedActivity != null) Unit // drain the scenario's own launch
        assertFalse(runtime.hudPresence.fallbackAllowed())
        assertFalse(runtime.fallback.maybeLaunch(app))
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun recreationKeepsOneControllerAndDoesNotRerunTheCaptureIntent() {
        val intent = Intent(app, MainActivity::class.java).setAction(MainActivity.ACTION_CAPTURE_NOW)
        ActivityScenario.launch<MainActivity>(intent).use { s ->
            assertEquals(1, captureStarts())
            lateinit var old: HudController
            s.onActivity { old = it.controller }

            s.recreate()
            assertEquals(0, captureStarts())
            s.onActivity {
                assertNotSame(old, it.controller)
                assertSame(it.controller, runtime.controller)
            }
            assertEquals(1, bridge.subscriptions.count { it == Channels.STATE }) // CXR-S has no unsubscribe

            phoneState()
            s.onActivity { assertTrue(it.controller.sessionOpen.value) }
            assertFalse(old.sessionOpen.value)
        }
    }

    /** Spec §5.5: a pause during the capture window (after CaptureService's check before binding) keeps the frame out. */
    @Test fun aPauseDuringTheCaptureWindowKeepsTheFrameOutAndSendsNothing() {
        ActivityScenario.launch(MainActivity::class.java).use {
            phoneState()
            runBlocking { runtime.pauses.ensureLoaded() }
            assertTrue(runtime.pauses.captureAllowed()) // CaptureService's check before the camera binds
            runBlocking { runtime.togglePause() } // the wearer pauses while the analyzer waits for a frame
            assertEquals(BufferResult.PAUSED, runtime.bufferCaptured("g1", byteArrayOf(1, 2, 3), System.currentTimeMillis(), 90, "0", 80))
            assertEquals(0, runtime.buffer.size)
            runtime.onFrameBuffered()
            Thread.sleep(GlassesRuntime.FLUSH_AFTER_HELLO_MS)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(bridge.sent.none { it.first == Channels.FRAME })
        }
    }

    /** Control for the test above: the same path sends a frame when capture is allowed. */
    @Test fun aFrameCapturedWhileAllowedIsSent() {
        ActivityScenario.launch(MainActivity::class.java).use {
            phoneState()
            runBlocking { runtime.pauses.ensureLoaded() }
            assertEquals(BufferResult.STORED, runtime.bufferCaptured("g2", byteArrayOf(1, 2, 3), System.currentTimeMillis(), 90, "0", 80))
            runtime.onFrameBuffered()
            waitUntil { bridge.sent.any { it.first == Channels.FRAME } }
        }
    }

    /** Phase 3 carry: before the phone's first message the link is CONNECTING; a tap says so instead of "not connected". */
    @Test fun tapWhileConnectingSaysConnecting() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            s.onActivity {
                it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
            compose.onNodeWithText("Connecting to phone\u2026", substring = true).assertExists()
            compose.onNodeWithText("Phone not connected", substring = true).assertDoesNotExist()
        }
    }

    /** Fix round 1 ruling: leaving the activity mid-recording tells the phone to drop the ask (it would answer it otherwise). */
    @Test fun leavingTheActivityWhileRecordingCancelsTheAsk() {
        val s = ActivityScenario.launch(MainActivity::class.java)
        phoneState()
        s.onActivity { tap(it) }
        waitUntil { Command.ListenStart in commands() }
        s.close()
        assertTrue(Command.Cancel in commands())
        mic.permits.release(1)
        waitUntil { !runtime.listening.value }
        assertEquals(0, sentOn(Channels.LISTEN_END))
    }

    /** Spec 1.8 / R-08: no frame chunk goes out while recording; the flush resumes after it. */
    @Test fun noFrameGoesOutWhileRecordingAndTheFlushResumesAfter() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            phoneState()
            s.onActivity { tap(it) }
            waitUntil { Command.ListenStart in commands() }
            runBlocking { runtime.pauses.ensureLoaded() }
            assertEquals(BufferResult.STORED, runtime.bufferCaptured("g9", byteArrayOf(1, 2, 3), System.currentTimeMillis(), 90, "0", 80))
            runtime.onFrameBuffered()
            Thread.sleep(GlassesRuntime.FLUSH_AFTER_HELLO_MS + 300); shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, sentOn(Channels.FRAME), "no frame while recording")
            s.onActivity { tap(it) }
            mic.permits.release(1)
            waitUntil { sentOn(Channels.LISTEN_END) == 1 && !runtime.listening.value }
            waitUntil { sentOn(Channels.FRAME) > 0 }
        }
    }

    /** Minor 11: a tap while the mic request from onCreate is still pending does not launch a second one. */
    @Test fun aTapDoesNotRequestTheMicTwice() {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            phoneState()
            s.onActivity { tap(it) }
            compose.onNodeWithText("Allow the microphone on the glasses", substring = true).assertExists()
            // Activity drops a second request with this warning (and answers it empty) instead of asking again.
            assertFalse(ShadowLog.getLogs().any { it.msg?.contains("only one set of permissions") == true }, "a second request was launched")
        }
    }

    /** Phase 8 C3: a force-stop lost the capture alarm; reopening re-arms it and says so, once. */
    @Test fun aMissedCaptureShowsTheResumeToastOnce() {
        app.getSharedPreferences("capture_scheduler", 0).edit().putLong("next_trigger_at_ms", System.currentTimeMillis() - 10 * 60_000).commit()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText(MainActivity.CAPTURE_RESUMED_AFTER_STOP, substring = true).assertExists()
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText(MainActivity.CAPTURE_RESUMED_AFTER_STOP, substring = true).assertDoesNotExist()
        }
        assertEquals("Capture resumed \u2014 it stops while the app is closed", MainActivity.CAPTURE_RESUMED_AFTER_STOP)
    }

    /** A process restart after a kill while the app was in front: the next start is "pending". Seeded before the runtime exists. */
    private fun seedKilledWhileRunning() {
        app.getSharedPreferences("restart_diag", android.content.Context.MODE_PRIVATE).edit()
            .putLong("last_alive_ms", System.currentTimeMillis() - 5_000).putString("ui_mode", "full").commit()
    }

    private fun launcherIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        .setClassName(app, MainActivity::class.java.name)
        .putExtra(Intent.EXTRA_REFERRER_NAME, "android-app://com.rokid.os.launcher")

    @Test fun killRestartStartDrawsHiddenHudBeforeMinimise() {
        seedKilledWhileRunning()
        val intent = Intent(app, MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(intent).use { s ->
            compose.onNodeWithTag("hud_hidden").assertExists()
            compose.onNodeWithTag("hud_root").assertDoesNotExist()
            s.onActivity { assertTrue(it.minimised) }
        }
    }

    @Test fun launcherOpenShowsHud() {
        seedKilledWhileRunning()
        ActivityScenario.launch<MainActivity>(launcherIntent()).use { s ->
            compose.onNodeWithTag("hud_root").assertExists()
            compose.onNodeWithTag("hud_hidden").assertDoesNotExist()
            s.onActivity { assertFalse(it.minimised) }
        }
    }

    @Test fun startLineWrittenToDiagLog() {
        ShadowLog.clear()
        ActivityScenario.launch<MainActivity>(launcherIntent()).use {
            val line = ShadowLog.getLogs().filter { it.tag == "PcCapture" && it.msg.startsWith("start src=") }.map { it.msg }.single()
            assertEquals("start src=LAUNCHER ref=LAUNCHER pending=0 marker=0 fromMin=0 extra=0 minimise=0", line)
            assertFalse(line.contains("rokid"))
        }
    }
}
