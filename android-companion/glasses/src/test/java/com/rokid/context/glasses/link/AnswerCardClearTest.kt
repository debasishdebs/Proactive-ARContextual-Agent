package com.rokid.context.glasses.link

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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

    @Before fun clearLogs() = ShadowLog.clear()

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
