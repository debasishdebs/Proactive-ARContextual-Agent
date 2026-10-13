package com.rokid.context.voice

import com.rokid.context.voice.android.AudioRoutes
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeakerCoreTest {
    private class FakeEngine(var languages: Set<Locale> = setOf(Locale.US)) : TtsEngine {
        val spoken = mutableListOf<Pair<String, String>>()
        var stops = 0
        override fun setLanguage(locale: Locale): Int = if (locale in languages) 0 else -2
        override fun speak(text: String, utteranceId: String): Int { spoken += utteranceId to text; return 0 }
        override fun stop() { stops++ }
    }

    private class FakeFocus(var grant: Boolean = true) : AudioFocus {
        var held = false
        var requests = 0
        var abandons = 0
        override fun request(): Boolean { requests++; held = grant; return grant }
        override fun abandon() { abandons++; held = false }
    }

    private val engine = FakeEngine()
    private val focus = FakeFocus()
    private var initCallback: ((Boolean) -> Unit)? = null
    private val speaker = SpeakerCore(focus) { cb -> initCallback = cb; engine }
    private val done = mutableListOf<String>()

    @Test fun speechAskedBeforeTheEngineIsReadyStartsOnInit() {
        assertTrue(speaker.speak("a1", "Milk and eggs.") { done += "a1" })
        assertTrue(engine.spoken.isEmpty())
        initCallback!!(true)
        assertEquals(listOf("a1" to "Milk and eggs."), engine.spoken)
        speaker.onUtteranceDone("a1")
        assertEquals(listOf("a1"), done)
        speaker.onUtteranceDone("a1")
        assertEquals(listOf("a1"), done, "onDone runs once")
    }

    @Test fun anEngineThatFailsToStartSpeaksNothingAndSaysSo() {
        speaker.speak("a1", "Milk.") { done += "a1" }
        initCallback!!(false)
        assertEquals(listOf("a1"), done)
        assertFalse(speaker.speak("a2", "Eggs.") { done += "a2" })
        assertTrue(engine.spoken.isEmpty())
        assertEquals(0, focus.requests, "nothing spoken, no focus taken")
    }

    @Test fun aHindiAnswerWithoutAHindiVoiceIsNotSpoken() {
        speaker.speak("warm", "ok") {}; initCallback!!(true)
        speaker.onUtteranceDone("warm")
        val requests = focus.requests
        assertFalse(speaker.speak("h1", "\u0926\u0942\u0927 \u092B\u094D\u0930\u093F\u091C \u092E\u0947\u0902 \u0939\u0948") { done += "h1" })
        assertEquals(requests, focus.requests, "no voice: focus is not even asked for")
        assertTrue(done.isEmpty(), "false = onDone is not called (preflight P17)")
        engine.languages = setOf(Locale.US, TtsLocale.HINDI)
        assertTrue(speaker.speak("h2", "\u0926\u0942\u0927 \u092B\u094D\u0930\u093F\u091C \u092E\u0947\u0902 \u0939\u0948") { done += "h2" })
        assertEquals("h2", engine.spoken.last().first)
    }

    @Test fun stopEndsWhatIsSpokenOrWaiting() {
        speaker.speak("a1", "Milk.") { done += "a1" }
        speaker.speak("a2", "Eggs.") { done += "a2" } // replaces the waiting one
        assertEquals(listOf("a1"), done)
        initCallback!!(true)
        speaker.stop()
        assertEquals(listOf("a1", "a2"), done)
        assertEquals(1, engine.stops)
    }

    @Test fun localeFollowsTheScriptAndOnlyA2dpCountsAsGlassesAudio() {
        assertEquals(TtsLocale.HINDI, TtsLocale.pick("\u091A\u093E\u092C\u0940 \u0936\u0947\u0932\u094D\u092B \u092A\u0930 \u0939\u0948"))
        assertEquals(Locale.US, TtsLocale.pick("Keys on the shelf"))
        assertTrue(AudioRoutes.anyBluetoothMedia(listOf(2, 8))) // TYPE_BUILTIN_SPEAKER, TYPE_BLUETOOTH_A2DP
        assertFalse(AudioRoutes.anyBluetoothMedia(listOf(2, 7))) // SCO alone carries calls, not media
    }

    // Task 6 carry: audio focus is requested for speech and abandoned when it ends.

    @Test fun focusIsHeldWhileSpeakingAndAbandonedWhenSpeechEnds() {
        speaker.speak("a1", "Milk.") { done += "a1" }
        assertEquals(0, focus.requests, "waiting for the engine is not speaking")
        initCallback!!(true)
        assertTrue(focus.held)
        speaker.onUtteranceDone("a1")
        assertFalse(focus.held)
        assertEquals(1 to 1, focus.requests to focus.abandons)
    }

    @Test fun focusIsKeptAcrossAReplacementAndAbandonedOnStop() {
        speaker.speak("warm", "ok") {}; initCallback!!(true)
        speaker.speak("a2", "Eggs.") { done += "a2" } // the engine flushes "warm"; its onStop arrives later
        speaker.onUtteranceDone("warm")
        assertTrue(focus.held, "a2 is still speaking")
        assertEquals(1, focus.requests)
        speaker.stop()
        assertEquals(listOf("a2"), done)
        assertFalse(focus.held)
        assertEquals(1, focus.abandons)
        speaker.stop()
        assertEquals(1, focus.abandons, "nothing held, nothing abandoned")
    }

    @Test fun deniedFocusSpeaksNothing() {
        speaker.speak("warm", "ok") {}; initCallback!!(true); speaker.onUtteranceDone("warm")
        focus.grant = false
        assertFalse(speaker.speak("a1", "Milk.") { done += "a1" })
        assertEquals(listOf("warm"), engine.spoken.map { it.first })
        assertTrue(done.isEmpty())
    }

    @Test fun anUtteranceTheEngineRefusesGivesFocusBack() {
        val refusing = object : TtsEngine {
            override fun setLanguage(locale: Locale) = 0
            override fun speak(text: String, utteranceId: String) = -1
            override fun stop() = Unit
        }
        var init: ((Boolean) -> Unit)? = null
        val s = SpeakerCore(focus) { cb -> init = cb; refusing }
        s.speak("a1", "Milk.") { done += "a1" }
        init!!(true)
        assertEquals(listOf("a1"), done, "a waiting utterance that can't be spoken still ends")
        assertFalse(focus.held)
        assertEquals(1, focus.abandons)
    }

    @Test fun aCallbackThatSpeaksAgainKeepsItsFocus() {
        speaker.speak("warm", "ok") {}; initCallback!!(true); speaker.onUtteranceDone("warm")
        speaker.speak("a1", "Milk.") { speaker.speak("a2", "Eggs.") { done += "a2" } }
        speaker.stop()
        assertEquals("a2", engine.spoken.last().first)
        assertTrue(focus.held, "focus was given back before a1's onDone asked again")
    }
}
