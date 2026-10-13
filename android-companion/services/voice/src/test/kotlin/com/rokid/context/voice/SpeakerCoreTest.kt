package com.rokid.context.voice

import com.rokid.context.voice.android.AudioRoutes
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SpeakerCoreTest {
    private class FakeEngine(var languages: Set<Locale> = setOf(Locale.US)) : TtsEngine {
        val spoken = mutableListOf<Pair<String, String>>()
        var stops = 0
        var shutdowns = 0
        override fun setLanguage(locale: Locale): Int = if (locale in languages) 0 else -2
        override fun speak(text: String, utteranceId: String): Int { spoken += utteranceId to text; return 0 }
        override fun stop() { stops++ }
        override fun shutdown() { shutdowns++ }

        /** The engine-side id of the latest utterance asked for under [callerId]. */
        fun idOf(callerId: String): String = spoken.last { it.first.substringBefore('#') == callerId }.first
        fun texts() = spoken.map { it.first.substringBefore('#') to it.second }
    }

    private class FakeFocus(var grant: Boolean = true) : AudioFocus {
        var held = false
        var requests = 0
        var abandons = 0
        override fun request(): Boolean { requests++; held = grant; return grant }
        override fun abandon() { abandons++; held = false }
    }

    private class FakeTimer : SpeechTimer {
        var now = 0L
        val pending = mutableListOf<Triple<Long, () -> Unit, BooleanArray>>()
        override fun after(delayMs: Long, block: () -> Unit): () -> Unit {
            val cancelled = BooleanArray(1)
            pending += Triple(now + delayMs, block, cancelled)
            return { cancelled[0] = true }
        }
        fun advance(ms: Long) {
            now += ms
            val due = pending.filter { it.first <= now }
            pending.removeAll(due)
            due.filter { !it.third[0] }.forEach { it.second() }
        }
        fun live() = pending.count { !it.third[0] }
    }

    private val engines = mutableListOf<FakeEngine>()
    private val engine get() = engines.last()
    private val focus = FakeFocus()
    private val timer = FakeTimer()
    private val events = mutableListOf<SpeakerEvent>()
    private var initCallback: ((Boolean) -> Unit)? = null
    private val speaker = SpeakerCore(focus, timer, { events += it }) { cb -> initCallback = cb; FakeEngine().also { engines += it } }
    private val done = mutableListOf<String>()

    private fun warm() { speaker.speak("warm", "ok") {}; initCallback!!(true); speaker.onUtteranceDone(engine.idOf("warm")) }

    @Test fun speechAskedBeforeTheEngineIsReadyStartsOnInit() {
        assertTrue(speaker.speak("a1", "Milk and eggs.") { done += "a1" })
        assertTrue(engines.single().spoken.isEmpty())
        initCallback!!(true)
        assertEquals(listOf("a1" to "Milk and eggs."), engine.texts())
        speaker.onUtteranceDone(engine.idOf("a1"))
        assertEquals(listOf("a1"), done)
        speaker.onUtteranceDone(engine.idOf("a1"))
        assertEquals(listOf("a1"), done, "onDone runs once")
    }

    /** Fix round 1, Important 1: a failed init shuts that engine down and the next answer builds a new one. */
    @Test fun anEngineThatFailsToStartIsShutDownAndTheNextAnswerRetries() {
        speaker.speak("a1", "Milk.") { done += "a1" }
        val first = engine
        initCallback!!(false)
        assertEquals(listOf("a1"), done)
        assertEquals(1, first.shutdowns)
        assertTrue(first.spoken.isEmpty())
        assertEquals(0, focus.requests, "nothing spoken, no focus taken")
        assertEquals(listOf(SpeakerEvent.INIT_FAILED), events)
        assertTrue(speaker.speak("a2", "Eggs.") { done += "a2" })
        assertEquals(2, engines.size, "a fresh engine")
        initCallback!!(true)
        assertEquals(listOf("a2" to "Eggs."), engine.texts())
    }

    @Test fun aLateInitFromAShutDownEngineIsIgnored() {
        speaker.speak("a1", "Milk.") { done += "a1" }
        val staleInit = initCallback!!
        staleInit(false)
        speaker.speak("a2", "Eggs.") { done += "a2" }
        staleInit(true)
        assertTrue(engine.spoken.isEmpty(), "only the new engine's init counts")
        initCallback!!(true)
        assertEquals(listOf("a2" to "Eggs."), engine.texts())
    }

    @Test fun aHindiAnswerWithoutAHindiVoiceIsNotSpoken() {
        warm()
        val requests = focus.requests
        assertFalse(speaker.speak("h1", "\u0926\u0942\u0927 \u092B\u094D\u0930\u093F\u091C \u092E\u0947\u0902 \u0939\u0948") { done += "h1" })
        assertEquals(requests, focus.requests, "no voice: focus is not even asked for")
        assertTrue(done.isEmpty(), "false = onDone is not called (preflight P17)")
        assertEquals(listOf(SpeakerEvent.LANG_MISSING), events)
        engine.languages = setOf(Locale.US, TtsLocale.HINDI)
        assertTrue(speaker.speak("h2", "\u0926\u0942\u0927 \u092B\u094D\u0930\u093F\u091C \u092E\u0947\u0902 \u0939\u0948") { done += "h2" })
        assertEquals("h2", engine.texts().last().first)
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
        assertEquals("hi-IN", TtsLocale.HINDI.toLanguageTag())
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
        speaker.onUtteranceDone(engine.idOf("a1"))
        assertFalse(focus.held)
        assertEquals(1 to 1, focus.requests to focus.abandons)
    }

    @Test fun focusIsKeptAcrossAReplacementAndAbandonedOnStop() {
        speaker.speak("warm", "ok") {}; initCallback!!(true)
        val warmId = engine.idOf("warm")
        speaker.speak("a2", "Eggs.") { done += "a2" } // the engine flushes "warm"; its onStop arrives later
        speaker.onUtteranceDone(warmId)
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
        warm()
        focus.grant = false
        assertFalse(speaker.speak("a1", "Milk.") { done += "a1" })
        assertEquals(listOf("warm"), engine.texts().map { it.first })
        assertTrue(done.isEmpty())
        assertEquals(listOf(SpeakerEvent.FOCUS_DENIED), events)
    }

    @Test fun anUtteranceTheEngineRefusesGivesFocusBack() {
        val refusing = object : TtsEngine {
            override fun setLanguage(locale: Locale) = 0
            override fun speak(text: String, utteranceId: String) = -1
            override fun stop() = Unit
            override fun shutdown() = Unit
        }
        var init: ((Boolean) -> Unit)? = null
        val s = SpeakerCore(focus, timer, {}) { cb -> init = cb; refusing }
        s.speak("a1", "Milk.") { done += "a1" }
        init!!(true)
        assertEquals(listOf("a1"), done, "a waiting utterance that can't be spoken still ends")
        assertFalse(focus.held)
        assertEquals(1, focus.abandons)
        assertEquals(0, timer.live(), "no watchdog left behind")
    }

    @Test fun aCallbackThatSpeaksAgainKeepsItsFocus() {
        warm()
        speaker.speak("a1", "Milk.") { speaker.speak("a2", "Eggs.") { done += "a2" } }
        speaker.stop()
        assertEquals("a2", engine.texts().last().first)
        assertTrue(focus.held, "focus was given back before a1's onDone asked again")
    }

    // Fix round 1, Minor 2: the engine sees its own id per utterance, so an old callback can't end a newer one.

    @Test fun aLateCallbackForTheSameCallerIdNeverEndsTheNewerUtterance() {
        warm()
        speaker.speak("a1", "Milk.") { done += "a1-first" }
        val firstId = engine.idOf("a1")
        speaker.speak("a1", "Eggs.") { done += "a1-second" } // same caller id: the first is replaced
        val secondId = engine.idOf("a1")
        assertNotEquals(firstId, secondId)
        assertEquals(listOf("a1-first"), done)
        speaker.onUtteranceDone(firstId) // the flushed first utterance's late onStop
        assertEquals(listOf("a1-first"), done, "the second is still speaking")
        assertTrue(focus.held)
        speaker.onUtteranceDone(secondId)
        assertEquals(listOf("a1-first", "a1-second"), done)
        assertFalse(focus.held)
    }

    @Test fun stopThenTheSameIdAgainIsNotEndedByTheOldOnStop() {
        warm()
        speaker.speak("a1", "Milk.") { done += "old" }
        val oldId = engine.idOf("a1")
        speaker.stop()
        speaker.speak("a1", "Milk.") { done += "new" }
        speaker.onUtteranceDone(oldId)
        assertEquals(listOf("old"), done)
        assertTrue(focus.held)
    }

    // Fix round 1, Minor 3: an utterance the engine never reports on is stopped by a watchdog.

    @Test fun anUtteranceThatNeverEndsIsStoppedByTheWatchdog() {
        warm()
        speaker.speak("a1", "Milk.") { done += "a1" }
        timer.advance(29_999)
        assertTrue(done.isEmpty())
        timer.advance(1)
        assertEquals(listOf("a1"), done, "onDone runs once, from the watchdog")
        assertFalse(focus.held)
        assertEquals(listOf(SpeakerEvent.WATCHDOG_STOP), events)
        assertEquals(1, engine.stops)
        speaker.onUtteranceDone(engine.idOf("a1"))
        assertEquals(listOf("a1"), done)
    }

    @Test fun theWatchdogGrowsWithTheTextAndIsCancelledWhenSpeechEnds() {
        assertEquals(30_000L, SpeakerCore.watchdogMs("Milk."))
        val long = "word ".repeat(200) // 1000 characters
        assertTrue(SpeakerCore.watchdogMs(long) > 60_000L)
        warm()
        assertEquals(0, timer.live())
        speaker.speak("a1", long) { done += "a1" }
        timer.advance(30_000)
        assertTrue(done.isEmpty(), "a long answer gets longer than 30 s")
        speaker.onUtteranceDone(engine.idOf("a1"))
        assertEquals(0, timer.live())
        timer.advance(SpeakerCore.watchdogMs(long))
        assertEquals(listOf("a1"), done)
        assertTrue(events.isEmpty())
    }
}
