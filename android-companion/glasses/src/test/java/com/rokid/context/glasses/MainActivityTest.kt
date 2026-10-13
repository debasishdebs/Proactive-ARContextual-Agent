package com.rokid.context.glasses

import android.Manifest
import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.KeyEvent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.hud.DoubleTapDetector
import com.rokid.context.core.model.CaptureState
import com.rokid.context.core.model.Channels
import com.rokid.context.core.model.Command
import com.rokid.context.core.model.CommandEnvelope
import com.rokid.context.core.model.Decoded
import com.rokid.context.core.model.Health
import com.rokid.context.core.model.Hello
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.PauseOrigin
import com.rokid.context.core.model.PauseRecord
import com.rokid.context.core.model.StateFrame
import com.rokid.context.core.model.Wire
import com.rokid.context.glasses.capture.CaptureService
import com.rokid.context.glasses.link.CxrBridge
import com.rokid.context.glasses.link.FakeBridge
import com.rokid.context.glasses.link.HudController
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
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

    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        GlassesRuntime.bridgeFactory = { bridge }
    }

    @After fun tearDown() {
        GlassesRuntime.resetForTests()
        GlassesRuntime.bridgeFactory = { CxrBridge(it) }
    }

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
            waitUntil { commands().any { it is Command.Pause } }
            val pause = commands().filterIsInstance<Command.Pause>().single()
            assertEquals(0, runtime.buffer.size)

            Thread.sleep(DoubleTapDetector.SETTLE_MS + 100) // the detector runs on the wall clock, which Robolectric leaves real
            s.onActivity { it.onBackPressed() }
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

    @Test fun tapWhileLiveSaysAskIsComingSoon() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            phoneState()
            s.onActivity {
                it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
            compose.onNodeWithText("Ask coming soon", substring = true).assertExists()
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
}
