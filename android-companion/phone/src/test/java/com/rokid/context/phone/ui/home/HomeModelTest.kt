package com.rokid.context.phone.ui.home

import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.Fixtures.TODAY
import com.rokid.context.phone.ui.Fixtures.ZONE
import org.junit.Assert.assertEquals
import com.rokid.context.voice.PhoneVoiceState
import com.rokid.context.voice.VoiceStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeModelTest {
    @Test fun feedMergesAsksAndNudgesNewestFirstWithStableTies() {
        val feed = Feed.build(
            asks = listOf(Fixtures.ask("a1", Fixtures.at(TODAY, 8, 15)), Fixtures.ask("a2", Fixtures.at(TODAY, 9, 41))),
            nudges = listOf(Fixtures.nudge("n1", Fixtures.at(TODAY, 9, 41)), Fixtures.nudge("n0", Fixtures.at(TODAY.minusDays(2), 7))),
            today = TODAY, zone = ZONE,
        )
        assertEquals(listOf("a2", "n1", "a1", "n0"), feed.map { it.id })
        assertEquals("09:41", feed[0].timeLabel)
        assertEquals("10 Oct", feed[3].timeLabel)
        assertEquals(2, Feed.build(feed.indices.map { Fixtures.ask("x$it", it.toLong()) }, emptyList(), TODAY, ZONE, limit = 2).size)
    }

    @Test fun suggestionsUseRecentTagsThenFallbacks() {
        val recent = listOf(
            Fixtures.memory("m1", "Milk", 3, tags = "milk, fridge"),
            Fixtures.memory("m2", "Keys", 2, tags = "Milk,x,😀emoji, a-b"),
        )
        assertEquals(listOf("What about milk?", "What about fridge?", SuggestedQuestions.FALLBACKS[0]), SuggestedQuestions.from(recent))
        assertEquals(SuggestedQuestions.FALLBACKS, SuggestedQuestions.from(emptyList()))
    }

    @Test fun hindiTagsBecomeSuggestions() {
        assertEquals("What about दूध?", SuggestedQuestions.from(listOf(Fixtures.memory("m1", "दूध खत्म", 1, tags = "दूध")))[0])
    }

    @Test fun theMicFollowsThePhoneVoiceGate() {
        val ready = HomeVoiceUi.from(PhoneVoiceState(status = VoiceStatus.SUPPORTED))
        assertTrue(ready.enabled); assertEquals("or tap your glasses", ready.hint)
        val listening = HomeVoiceUi.from(PhoneVoiceState(status = VoiceStatus.SUPPORTED, listening = true, partial = "where are"))
        assertTrue(listening.listening); assertEquals("where are", listening.partial); assertEquals("Listening\u2026 tap the mic to send", listening.hint)
        assertFalse("still speaking is never thinking", listening.thinking)
        val permission = HomeVoiceUi.from(PhoneVoiceState(status = VoiceStatus.NEEDS_MIC_PERMISSION))
        assertTrue(permission.enabled && permission.needsPermission)
        val pack = HomeVoiceUi.from(PhoneVoiceState(status = VoiceStatus.NEEDS_SPEECH_PACK))
        assertFalse(pack.enabled); assertEquals("Download the speech pack in Settings to ask by voice", pack.hint)
        val off = HomeVoiceUi.from(PhoneVoiceState(status = VoiceStatus.SUPPORTED, micOff = true))
        assertFalse(off.enabled); assertEquals("The phone mic is off in Settings \u2014 type a question below", off.hint)
        assertFalse(HomeVoiceUi.from(PhoneVoiceState(status = VoiceStatus.NEEDS_NEWER_ANDROID)).enabled)
        assertEquals("Type a question below, or tap your glasses", HomeVoiceUi().hint)
    }

    /** Fix 1 #2: before the switch loads the mic is disabled and says so, never "off in Settings". */
    @Test fun whileSettingsLoadTheMicSaysLoadingNotOff() {
        val loading = HomeVoiceUi.from(PhoneVoiceState(status = VoiceStatus.SUPPORTED, micOff = true, settingsLoading = true))
        assertFalse(loading.enabled); assertFalse(loading.micOff)
        assertEquals("Voice is still loading\u2026", loading.hint)
    }

    /** Fix 1 #3: thinking is cancellable and keeps the error and the mic switch. */
    @Test fun thinkingIsCancellableAndKeepsErrorAndMicOff() {
        val t = HomeVoiceUi.from(PhoneVoiceState(status = VoiceStatus.SUPPORTED, thinking = true, error = "e", micOff = true))
        assertTrue(t.enabled && t.thinking); assertFalse(t.listening)
        assertEquals("e", t.error); assertTrue(t.micOff)
    }

    /** Fix 1 #4: a permanently denied mic points at the app's settings. */
    @Test fun aBlockedMicPointsAtAppSettings() {
        val b = HomeVoiceUi.from(PhoneVoiceState(status = VoiceStatus.NEEDS_MIC_PERMISSION), micBlocked = true)
        assertTrue(b.enabled && b.needsPermission && b.blocked)
        assertEquals(HomeVoiceUi.HINT_BLOCKED, b.hint)
        assertFalse(HomeVoiceUi.from(PhoneVoiceState(status = VoiceStatus.SUPPORTED), micBlocked = true).blocked)
    }
}
