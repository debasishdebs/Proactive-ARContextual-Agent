package com.rokid.context.glasses

import android.Manifest
import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.hud.render.HudPresenter
import com.rokid.context.core.model.CaptureState
import com.rokid.context.core.model.Channels
import com.rokid.context.core.model.Command
import com.rokid.context.core.model.CommandEnvelope
import com.rokid.context.core.model.Decoded
import com.rokid.context.core.model.Health
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.Overlay
import com.rokid.context.core.model.StateFrame
import com.rokid.context.core.model.Wire
import com.rokid.context.glasses.capture.CaptureService
import com.rokid.context.glasses.link.CxrBridge
import com.rokid.context.glasses.link.FakeBridge
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowWindowManagerImpl
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spec 2026-10-14 §4: while minimised, Answer/Nudge/Toast go to one pop-up window; a Confirm auto-opens the HUD
 * (RETURN_TO = MINIMISED) and re-minimises after the vote or the timeout. In Robolectric the activity stays resumed
 * after moveTaskToBack, so "back in the background" is simulated with moveToState(CREATED).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MinimisedOverlaysTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val bridge = FakeBridge()
    private val runtime get() = GlassesRuntime.get(app)
    private var keepAlive: ServiceController<CaptureService>? = null
    private var seq = 1L
    private val wm get() = app.getSystemService(WindowManager::class.java)
    private val confirm = Overlay.Confirm("p1", "Add milk?", "Shopping list")

    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        GlassesRuntime.bridgeFactory = { bridge }
        ShadowSettings.setCanDrawOverlays(true)
        val c = Robolectric.buildService(CaptureService::class.java, Intent(app, CaptureService::class.java).setAction(CaptureService.ACTION_KEEP_ALIVE))
        keepAlive = c
        c.create().startCommand(0, 1)
    }

    @After fun tearDown() {
        keepAlive?.destroy()
        ShadowSettings.setCanDrawOverlays(false)
        GlassesRuntime.resetForTests()
        GlassesRuntime.bridgeFactory = { CxrBridge(it) }
    }

    private fun frame(o: Overlay?) {
        bridge.phone(Channels.STATE, Wire.encode(StateFrame(seq = seq++, capture = CaptureState.Running, intervalSec = 300, phoneLinked = true, aiHealth = Health.Ok, overlay = o)))
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun windows(title: String) = Shadow.extract<ShadowWindowManagerImpl>(wm).views
        .mapNotNull { it.layoutParams as? WindowManager.LayoutParams }.filter { it.title == title }

    private fun popups() = windows(com.rokid.context.glasses.overlay.PopupOverlay.WINDOW_TITLE)

    private fun commands(): List<Command> = bridge.sent.filter { it.first == Channels.CMD }
        .map { (Wire.decode<CommandEnvelope>(it.second[0]) as Decoded.Ok).value.command }

    private fun tap(a: MainActivity) {
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
    }

    private fun drainStarts() { while (shadowOf(app).nextStartedActivity != null) Unit }

    /** Minimise and go to the background (onStop), as on the device. */
    private fun minimised(s: ActivityScenario<MainActivity>) {
        frame(null)
        s.onActivity { it.minimise() }
        s.moveToState(Lifecycle.State.CREATED)
        drainStarts()
    }

    /** A Confirm while minimised: returns the launch intent after delivering it like Android does (onStart, then onNewIntent). */
    private fun autoOpen(s: ActivityScenario<MainActivity>): Intent {
        frame(confirm)
        val i = assertNotNull(shadowOf(app).nextStartedActivity, "a Confirm opens the HUD")
        assertEquals(MainActivity::class.java.name, i.component?.className)
        assertEquals(MainActivity.RETURN_TO_MINIMISED, i.getStringExtra(MainActivity.EXTRA_RETURN_TO))
        assertTrue(i.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        s.moveToState(Lifecycle.State.RESUMED)
        s.onActivity { it.onNewIntent(i) }
        idle()
        return i
    }

    @Test fun answerWhileMinimisedShowsPopupThatAutoHides() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            bridge.phone(Channels.SETTINGS, Wire.encode(HudSettings(popupSeconds = 4)))
            minimised(s)
            frame(Overlay.Answer("what's next", "Standup at 10:30", speaking = true))
            assertEquals(1, popups().size)
            assertEquals("Standup at 10:30", runtime.popup.text)
            val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            assertEquals(flags, popups().single().flags and flags, "the pop-up never takes input")
            frame(Overlay.Answer("what's next", "Standup at 10:30", speaking = true)) // a heartbeat repeat does not restart it
            advance(3_800)
            assertEquals(1, popups().size)
            advance(400)
            assertTrue(popups().isEmpty(), "auto-hides after popupSeconds")
            assertNull(shadowOf(app).nextStartedActivity, "an Answer never brings the HUD forward")
        }
    }

    @Test fun newerPopupReplacesOlder() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            minimised(s)
            frame(Overlay.Answer("q", "First answer", speaking = false))
            advance(4_000)
            frame(Overlay.Nudge("Leave now for the 11:00"))
            assertEquals(1, popups().size, "replaced, never stacked")
            assertEquals("Leave now for the 11:00", runtime.popup.text)
            frame(Overlay.Toast("Saved"))
            assertEquals(1, popups().size)
            assertEquals("Saved", runtime.popup.text)
            advance(5_800)
            assertEquals(1, popups().size, "the newest pop-up gets its full 6 s")
            advance(400)
            assertTrue(popups().isEmpty())
        }
    }

    @Test fun confirmWhileMinimisedOpensAndReminimisesAfterVote() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            minimised(s)
            autoOpen(s)
            s.onActivity { assertFalse(it.minimised); assertEquals(1, it.tasksMovedBack) }
            assertTrue(runtime.minimisedIcon.isShown.not(), "the full HUD is in front")
            advance(MainActivity.VOTE_GUARD_MS + 100)
            s.onActivity { tap(it) }
            assertEquals(Command.Confirm("p1", yes = true), commands().filterIsInstance<Command.Confirm>().single())
            s.onActivity { assertEquals(2, it.tasksMovedBack, "re-minimised after the vote"); assertTrue(it.minimised) }
            assertTrue(runtime.minimisedIcon.isShown, "the icon is back")
        }
    }

    @Test fun confirmTimeoutAlsoReminimises() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            minimised(s)
            autoOpen(s)
            advance(HudPresenter.CONFIRM_MS - 1_000)
            s.onActivity { assertEquals(1, it.tasksMovedBack) }
            advance(1_500)
            s.onActivity { assertEquals(2, it.tasksMovedBack, "the 15 s window ended unanswered"); assertTrue(it.minimised) }
            assertTrue(commands().none { it is Command.Confirm })
        }
    }

    @Test fun minimisingSaysHelloWithinASecondSoThePhoneHearsSomething() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            frame(null)
            val before = bridge.sent.count { it.first == Channels.HELLO }
            s.onActivity { it.minimise() }
            advance(MainActivity.MINIMISED_HELLO_MS + 100)
            assertTrue(bridge.sent.count { it.first == Channels.HELLO } > before, "a hello after minimising")
        }
    }

    @Test fun confirmWhileInFrontDoesNotReminimise() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            frame(null)
            drainStarts()
            frame(confirm)
            assertNull(shadowOf(app).nextStartedActivity, "already in front: nothing to open")
            s.onActivity { tap(it) }
            assertTrue(commands().any { it is Command.Confirm })
            advance(HudPresenter.CONFIRM_MS + 1_000)
            s.onActivity { assertEquals(0, it.tasksMovedBack) }
        }
    }

    @Test fun userOpenDuringConfirmKeepsHudInFront() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            minimised(s)
            autoOpen(s)
            // The wearer opens the app from the Rokid launcher while the Confirm is up: they want the HUD.
            s.onActivity { it.onNewIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)) }
            advance(MainActivity.VOTE_GUARD_MS + 100)
            s.onActivity { tap(it) }
            assertTrue(commands().any { it is Command.Confirm })
            advance(HudPresenter.CONFIRM_MS + 1_000)
            s.onActivity { assertEquals(1, it.tasksMovedBack, "no re-minimise"); assertFalse(it.minimised) }
        }
    }

    @Test fun confirmAutoOpenNeedsVisibleConfirmBeforeVote() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            minimised(s)
            autoOpen(s)
            // The tail of a tap meant for the other app lands on the HUD as it opens: it must not vote.
            s.onActivity { tap(it) }
            assertTrue(commands().none { it is Command.Confirm }, "no vote before the Confirm has been visible")
            advance(MainActivity.VOTE_GUARD_MS + 100)
            s.onActivity { tap(it) }
            assertEquals(1, commands().count { it is Command.Confirm })
        }
    }

    @Test fun confirmWithoutTheOverlayPermissionStaysOnThePhone() {
        ShadowSettings.setCanDrawOverlays(false)
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            minimised(s)
            frame(confirm)
            assertNull(shadowOf(app).nextStartedActivity)
            assertTrue(popups().isEmpty())
        }
    }

    @Test fun openOnGlassWhileMinimisedShowsPopupNotHud() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            minimised(s)
            // The phone's "Open on glasses": the session reopens, and Rokid relaunches our app over the front app.
            bridge.disconnected(); idle()
            frame(null)
            s.moveToState(Lifecycle.State.RESUMED)
            s.onActivity { it.onNewIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)) }
            idle()
            s.onActivity { assertEquals(2, it.tasksMovedBack, "sent straight back"); assertTrue(it.minimised) }
            assertEquals(MainActivity.OPENED_FROM_PHONE_POPUP, runtime.popup.text)
            assertEquals(1, popups().size)
            assertTrue(runtime.minimisedIcon.isShown)
        }
    }
}
