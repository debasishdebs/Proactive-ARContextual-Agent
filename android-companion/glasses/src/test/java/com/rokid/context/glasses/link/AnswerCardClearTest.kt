package com.rokid.context.glasses.link

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.log.Level
import com.rokid.context.core.log.Line
import com.rokid.context.core.log.LogSink
import com.rokid.context.core.log.PcLog
import com.rokid.context.core.log.Route
import com.rokid.context.core.model.CaptureState
import com.rokid.context.core.model.Channels
import com.rokid.context.core.model.Health
import com.rokid.context.core.model.Hello
import com.rokid.context.core.model.Overlay
import com.rokid.context.core.model.StateFrame
import com.rokid.context.core.model.Wire
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * rc1 device bug (2026-11-13 01:06): a glasses voice answer stayed on the HUD after its photo was forgotten. The answer card is phone state: the glasses clear it
 * explicitly when the phone's overlay ends, when a Forget withdraws its ask (also after the phone's 30 s hold), when the session closes, and after their own bounded hold.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AnswerCardClearTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val bridge = FakeBridge()
    private var now = 1_000L
    private var seq = 1L
    private val answer = Overlay.Answer("where did I keep my shoes", "Shoes are on the floor.", speaking = true, askId = "ask_1")

    /** PcLog is process-wide: with no sink installed the milestone lines wait in its ring and ShadowLog stays empty, so these tests depended on which test ran before (found when Gradle ran this class first). Same bridge as HudControllerTest. */
    @Before fun installLogBridge() {
        PcLog.testOnlyResetNeverCallFromProduction()
        PcLog.setDiagnostic(true)
        check(PcLog.install(object : LogSink {
            override fun emit(line: Line, route: Route) { if (route.logcat) android.util.Log.println(when (line.level) { Level.D -> android.util.Log.DEBUG; Level.I -> android.util.Log.INFO; Level.W -> android.util.Log.WARN; Level.E -> android.util.Log.ERROR }, line.tag, line.msg) }
        }))
        ShadowLog.clear()
    }
    @org.junit.After fun removeLogBridge() { PcLog.testOnlyResetNeverCallFromProduction() }

    /** The answer-card lines (milestones: an I line, so logcat carries them as well as the file). */
    private fun milestones() = ShadowLog.getLogs().filter { it.tag == HudController.TAG && it.msg.startsWith("answer card cleared") }.map { it.msg }
    private fun hello() = Hello(glassesBatteryPct = 80, bufferSize = 2, glassesNowMs = now, pauses = emptyList())
    private fun TestScope.controller() =
        HudController(backgroundScope, bridge, HudSettingsCache(ctx.getSharedPreferences("answer_clear", 0)), ::hello) { now }.also { it.start() }
    private fun frame(o: Overlay?, withdrawn: List<String> = emptyList()) = bridge.phone(
        Channels.STATE, Wire.encode(StateFrame(seq = seq++, capture = CaptureState.Running, intervalSec = 300, phoneLinked = true, aiHealth = Health.Ok, overlay = o, withdrawnAskIds = withdrawn)),
    )

    @Test fun anIdleFrameClearsTheAnswerCard() = runTest {
        val c = controller()
        frame(answer)
        assertEquals(answer, c.frame.value?.overlay)
        frame(answer.copy(speaking = false))
        assertEquals("speech ended: still the same card", answer.copy(speaking = false), c.frame.value?.overlay)
        frame(null) // the phone's 30 s hold ended (VoiceState.Idle)
        assertNull(c.frame.value?.overlay)
        frame(null)
        assertNull("idempotent", c.frame.value?.overlay)
    }

    @Test fun aRedactionAfterTheHoldClearsAnAnswerStillOnTheGlasses() = runTest {
        val c = controller()
        frame(answer)
        // the phone's Idle frame was missed (a link gap) and the Forget came after the hold: its frame names the ask
        frame(answer.copy(speaking = false), withdrawn = listOf("ask_1"))
        assertNull("dropped even though the frame still carries it", c.frame.value?.overlay)
        frame(answer.copy(speaking = false), withdrawn = listOf("ask_1"))
        assertNull("a repeat never brings it back", c.frame.value?.overlay)
        assertEquals(listOf("answer card cleared: redaction"), milestones())
        // another ask's answer is untouched by the hint
        val other = Overlay.Answer("milk?", "Milk and eggs.", speaking = false, askId = "ask_2")
        frame(other, withdrawn = listOf("ask_1"))
        assertEquals(other, c.frame.value?.overlay)
        frame(null, withdrawn = listOf("ask_1"))
        assertNull(c.frame.value?.overlay)
    }

    @Test fun aSessionCloseDropsTheAnswerAndAReconnectNeverRedisplaysIt() = runTest {
        val c = controller()
        frame(answer)
        bridge.disconnected()
        assertNull("a closed session holds no answer", c.frame.value?.overlay)
        assertTrue(milestones().contains("answer card cleared: session closed"))
        frame(null) // the new session's first frame: the phone holds nothing
        assertNull(c.frame.value?.overlay)
    }

    /**
     * Codex r1 P1 repro: a repeat of answer A is received, then paused between receipt and publication; the session closes (and the Forget of A's photo happens while
     * disconnected); the paused publication resumes. A must not come back: a frame of a closed session never lands after the close, and a newer session's frame wins.
     */
    @Test fun aPublicationPausedAcrossASessionCloseNeverRestoresTheAnswer() = runTest {
        val c = controller()
        frame(answer)
        assertEquals(answer, c.frame.value?.overlay)
        for (reopen in listOf(false, true)) {
            if (reopen) { frame(answer); assertEquals(answer, c.frame.value?.overlay) }
            val reached = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
            c.afterReceipt = { c.afterReceipt = {}; reached.countDown(); release.await() }
            val t = Thread { frame(answer.copy(speaking = false)) }.also { it.start() }
            assertTrue(reached.await(5, java.util.concurrent.TimeUnit.SECONDS))
            bridge.disconnected()
            assertNull(c.frame.value?.overlay)
            if (reopen) frame(null) // the next session's first frame arrives before the paused one resumes
            release.countDown(); t.join(5_000)
            assertNull("reopen=$reopen: the paused frame of the closed session is dropped", c.frame.value?.overlay)
        }
    }

    /**
     * Codex r2 P1, the exact repro: a repeat of A is held right after RECEIPT (before phoneMessage() and the generation read of r1); BT drops; the Forget of A's photo
     * happens; the session reopens with an idle frame. A resumes inside the new session and must not adopt its generation: it is dropped, A stays off the card.
     */
    @Test fun anAnswerHeldBeforeTheSessionOpenNeverAdoptsTheReopenedSession() = runTest {
        val c = controller()
        frame(answer)
        val reached = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        c.afterReceipt = { c.afterReceipt = {}; reached.countDown(); release.await() }
        val t = Thread { frame(answer.copy(speaking = false)) }.also { it.start() }
        assertTrue(reached.await(5, java.util.concurrent.TimeUnit.SECONDS))
        bridge.disconnected() // the link drops; the Forget runs on the phone meanwhile
        assertNull(c.frame.value?.overlay)
        frame(null) // the reopened session's first frame: idle
        assertTrue(c.sessionOpen.value)
        release.countDown(); t.join(5_000)
        assertNull("the held frame of the old session is dropped, not republished in the new one", c.frame.value?.overlay)
        assertTrue(c.sessionOpen.value)
    }

    /** Codex r2: an Answer whose ask a frame ever named as withdrawn is never published again, even by a later frame that no longer names it, and after a reconnect. */
    @Test fun aWithdrawnAskIsNeverRepublished() = runTest {
        val c = controller()
        frame(null, withdrawn = listOf("ask_1"))
        frame(null)
        frame(answer)
        assertNull("a late frame that still carries it", c.frame.value?.overlay)
        bridge.disconnected(); frame(answer)
        assertNull("nor after a reconnect", c.frame.value?.overlay)
        val other = answer.copy(askId = "ask_2", text = "Milk and eggs.")
        frame(other)
        assertEquals("another ask still shows", other, c.frame.value?.overlay)
    }

    /** Codex r1: the glasses hold is key-independent: whatever answer is on the card goes at the bound, even after a close reset its key. */
    @Test fun theBoundExpiresWhateverAnswerIsShown() = runTest {
        val c = controller()
        frame(answer)
        bridge.disconnected(); frame(answer) // a new session shows it again (the phone still holds it): its time restarts there
        assertEquals(answer, c.frame.value?.overlay)
        var waited = 0L
        while (waited < HudController.ANSWER_MAX_MS) { now += 10_000; waited += 10_000; frame(answer); advanceTimeBy(1_000) }
        assertNull(c.frame.value?.overlay)
    }

    @Test fun aSilentPhoneEndsTheAnswerWithTheSession() = runTest {
        val c = controller()
        frame(answer)
        now += 26_000; advanceTimeBy(1_100) // no phone message for longer than the 25 s liveness window
        assertNull(c.frame.value?.overlay)
    }

    @Test fun theGlassesOwnBoundedHoldEndsAnAnswerThePhoneKeepsSending() = runTest {
        val c = controller()
        frame(answer)
        var waited = 0L
        while (waited < HudController.ANSWER_MAX_MS - 10_000) { now += 10_000; waited += 10_000; frame(answer.copy(speaking = false)); advanceTimeBy(1_000) }
        assertEquals("inside the bound the card stays (heartbeats keep the session open)", answer.copy(speaking = false), c.frame.value?.overlay)
        now += 10_000; frame(answer.copy(speaking = false)); advanceTimeBy(1_000)
        assertNull("past the bound it goes", c.frame.value?.overlay)
        assertTrue(milestones().contains("answer card cleared: glasses hold ended"))
        frame(answer.copy(speaking = false)); assertNull("the same answer stays dropped", c.frame.value?.overlay)
        frame(null); frame(answer)
        assertEquals("a later frame of it after the phone cleared it is a new card", answer, c.frame.value?.overlay)
    }
}
