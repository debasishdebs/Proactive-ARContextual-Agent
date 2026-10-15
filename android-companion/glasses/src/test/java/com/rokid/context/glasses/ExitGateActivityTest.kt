package com.rokid.context.glasses

import android.Manifest
import android.app.Application
import android.os.Looper
import android.view.KeyEvent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.hud.DoubleTapExit
import com.rokid.context.core.model.CaptureState
import com.rokid.context.core.model.Channels
import com.rokid.context.core.model.Command
import com.rokid.context.core.model.CommandEnvelope
import com.rokid.context.core.model.Decoded
import com.rokid.context.core.model.Health
import com.rokid.context.core.model.StateFrame
import com.rokid.context.core.model.Wire
import com.rokid.context.glasses.link.CxrBridge
import com.rokid.context.glasses.link.FakeBridge
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/** Spec 2026-10-14 §2 in the activity: the gate runs on the uptime clock (Robolectric's looper clock). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ExitGateActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val bridge = FakeBridge()
    private val runtime get() = GlassesRuntime.get(app)

    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        GlassesRuntime.bridgeFactory = { bridge }
        GlassesRuntime.pcmSourceFactory = { SilentMic() }
    }

    /** Blocks until closed, then ends: an ask that never sends audio. */
    private class SilentMic : com.rokid.context.glasses.voice.PcmSource {
        private val closed = java.util.concurrent.CountDownLatch(1)
        override fun start() = Unit
        override fun read(buf: ByteArray): Int { closed.await(); return -1 }
        override fun close() { closed.countDown() }
    }

    @After fun tearDown() {
        GlassesRuntime.resetForTests()
        GlassesRuntime.bridgeFactory = { CxrBridge(it) }
        GlassesRuntime.pcmSourceFactory = { com.rokid.context.glasses.voice.AudioRecordSource() }
    }

    private fun phoneState() = bridge.phone(
        Channels.STATE,
        Wire.encode(StateFrame(seq = 1, capture = CaptureState.Running, intervalSec = 300, phoneLinked = true, aiHealth = Health.Ok)),
    )

    private fun commands(): List<Command> = bridge.sent.filter { it.first == Channels.CMD }
        .map { (Wire.decode<CommandEnvelope>(it.second[0]) as Decoded.Ok).value.command }

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun waitUntil(cond: () -> Boolean) {
        repeat(300) { shadowOf(Looper.getMainLooper()).idle(); if (cond()) return; Thread.sleep(10) }
        fail("condition not reached")
    }

    @Suppress("DEPRECATION")
    private fun doubleTap(s: ActivityScenario<MainActivity>) = s.onActivity { it.onBackPressed() }

    @Test fun aPageDoubleTapShowsTheCountdownAndPausesOnceAtExpiry() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            phoneState()
            doubleTap(s)
            compose.onNodeWithText(DoubleTapExit.ARMED_TEXT, substring = true).assertExists()
            advance(DoubleTapExit.WINDOW_MS - 500)
            Thread.sleep(200); shadowOf(Looper.getMainLooper()).idle()
            assertTrue(commands().none { it is Command.Pause }, "no pause inside the window")
            advance(800)
            waitUntil { commands().any { it is Command.Pause } }
            advance(5_000); Thread.sleep(200); shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, commands().count { it is Command.Pause }, "exactly once")
        }
    }

    @Test fun minimiseClearsGateNoLatePause() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            phoneState()
            doubleTap(s)
            doubleTap(s) // no pause between the pairs: the settle guard does not swallow a second double-tap while armed
            compose.onNodeWithText(DoubleTapExit.MINIMISING_TEXT, substring = true).assertExists()
            s.onActivity { assertEquals(0, it.tasksMovedBack, "the second double-tap only opens the exit window") }
            advance(DoubleTapExit.MINIMISE_WINDOW_MS + 400)
            s.onActivity { assertEquals(1, it.tasksMovedBack, "the window ending minimises") }
            advance(DoubleTapExit.WINDOW_MS * 3)
            Thread.sleep(300); shadowOf(Looper.getMainLooper()).idle()
            assertTrue(commands().none { it is Command.Pause || it is Command.Resume }, "no late pause")
            compose.onNodeWithText(DoubleTapExit.ARMED_TEXT, substring = true).assertDoesNotExist(); compose.onNodeWithText(DoubleTapExit.MINIMISING_TEXT, substring = true).assertDoesNotExist()
        }
    }

    /** Fix wave H: a third double-tap inside the window exits for good and tells the phone; no minimise, no pause. */
    @Test fun aThirdDoubleTapExitsAndTellsThePhone() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            phoneState()
            doubleTap(s); doubleTap(s); doubleTap(s)
            s.onActivity { assertTrue(it.exitedByUser); assertTrue(it.isFinishing); assertEquals(0, it.tasksMovedBack) }
            assertTrue(bridge.sent.any { it.first == Channels.USER_EXIT }, "pc_user_exit sent")
            advance(DoubleTapExit.WINDOW_MS * 3)
            assertTrue(commands().none { it is Command.Pause || it is Command.Resume })
        }
    }

    /** Fix wave H (A): started with the minimised extra (what a restart after a force-stop in icon mode does), no HUD stays in front. */
    @Test fun startMinimisedExtraGoesStraightToIconMode() {
        val i = android.content.Intent(ApplicationProvider.getApplicationContext<android.app.Application>(), MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_START_MINIMISED, true)
        ActivityScenario.launch<MainActivity>(i).use { s ->
            advance(500)
            s.onActivity { assertEquals(1, it.tasksMovedBack); assertEquals("icon", GlassesRuntime.get(it).uiMode.get()) }
        }
    }

    private fun press83(a: MainActivity) {
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_NOTIFICATION))
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_NOTIFICATION))
    }

    @Test fun fastFourTapOf83KeysMinimisesWithNoPause() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            phoneState()
            s.onActivity { a -> repeat(4) { press83(a) } }
            advance(DoubleTapExit.MINIMISE_WINDOW_MS + 400)
            s.onActivity { assertEquals(1, it.tasksMovedBack, "a quick 4-tap minimises once the exit window ends") }
            advance(DoubleTapExit.WINDOW_MS * 3)
            Thread.sleep(300); shadowOf(Looper.getMainLooper()).idle()
            assertTrue(commands().none { it is Command.Pause || it is Command.Resume }, "no pause")
        }
    }

    @Test fun aTapWhileArmedCancelsTheGateAndStillActs() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            phoneState()
            doubleTap(s)
            s.onActivity {
                it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
            waitUntil { Command.ListenStart in commands() } // the tap is still the Ask
            advance(DoubleTapExit.WINDOW_MS * 2)
            Thread.sleep(200); shadowOf(Looper.getMainLooper()).idle()
            assertFalse(commands().any { it is Command.Pause })
            s.onActivity { assertEquals(0, it.tasksMovedBack) }
        }
    }
}
