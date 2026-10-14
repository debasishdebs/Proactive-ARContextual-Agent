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
import com.rokid.context.core.hud.DoubleTapDetector
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
            compose.onNodeWithText("Double-tap again to exit \u00b7 3", substring = true).assertExists()
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
            Thread.sleep(DoubleTapDetector.SETTLE_MS + 100) // the detector's tail guard runs on the wall clock
            doubleTap(s)
            s.onActivity { assertEquals(1, it.tasksMovedBack, "the second double-tap minimises") }
            advance(DoubleTapExit.WINDOW_MS * 3)
            Thread.sleep(300); shadowOf(Looper.getMainLooper()).idle()
            assertTrue(commands().none { it is Command.Pause || it is Command.Resume }, "no late pause")
            compose.onNodeWithText(DoubleTapExit.COUNTDOWN_PREFIX, substring = true).assertDoesNotExist()
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
