package com.rokid.context.voice

import com.rokid.context.core.model.Overlay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoicePipelineTest {
    private val engine = FakeSpeechEngine()
    private val speaker = FakeSpeaker()
    private val caps = MutableStateFlow(VoiceCapabilities(VoiceStatus.SUPPORTED, VoiceStatus.SUPPORTED, canDownloadPack = true))
    private var bluetooth = true
    private var fallbacks = 0
    private val asked = mutableListOf<Pair<String, VoiceSource>>()
    private var answer: suspend (String) -> AnswerResult = { AnswerResult.Answered("ask_1", "Milk and eggs.") }
    private val pcm = ByteArray(3_200) { 9 }

    private fun TestScope.pipeline() = VoicePipeline(
        backgroundScope, engine, caps, { SpeechLanguage.ENGLISH },
        Answerer { q, source -> asked += q to source; answer(q) },
        speaker, bluetoothMedia = { bluetooth }, onFallbackSuspected = { fallbacks++ },
    ).also { runCurrent() }

    @Test fun aGlassesAskFlowsToASpokenAnswerThatClearsItself() = runTest {
        val p = pipeline()
        p.glassesListenStart()
        assertEquals(Overlay.Listening(""), p.overlay.value)
        assertTrue(engine.last.external)
        p.glassesAudio(pcm)
        engine.last.listener.onPartial("what do I need")
        assertEquals(Overlay.Listening("what do I need"), p.overlay.value)
        p.glassesListenEnd()
        assertTrue(engine.last.ended)
        engine.last.listener.onFinal(SttResult.Text("What do I need from the store"))
        assertEquals(Overlay.Listening("What do I need from the store"), p.overlay.value, "thinking keeps the flush suspended")
        runCurrent()
        assertEquals(listOf("What do I need from the store" to VoiceSource.GLASSES), asked)
        assertEquals(Overlay.Answer("What do I need from the store", "Milk and eggs.", speaking = true), p.overlay.value)
        assertEquals(listOf("Milk and eggs."), speaker.spoken)
        speaker.finish()
        assertEquals(Overlay.Answer("What do I need from the store", "Milk and eggs.", speaking = false), p.overlay.value)
        advanceTimeBy(30_001)
        assertNull(p.overlay.value)
        assertEquals(VoiceState.Idle, p.state.value)
    }

    @Test fun withoutBluetoothMediaTheGlassesAnswerIsHudOnly() = runTest {
        bluetooth = false
        val p = pipeline()
        p.glassesListenStart(); engine.last.listener.onFinal(SttResult.Text("where are my keys")); runCurrent()
        assertEquals(Overlay.Answer("where are my keys", "Milk and eggs.", speaking = false), p.overlay.value)
        assertTrue(speaker.spoken.isEmpty())
        advanceTimeBy(30_001)
        assertNull(p.overlay.value)
    }

    @Test fun noFinalWithinTheTimeoutUsesTheLastPartial() = runTest {
        val p = pipeline()
        p.glassesListenStart()
        engine.last.listener.onPartial("where did I park")
        p.glassesListenEnd()
        advanceTimeBy(6_001); runCurrent()
        assertEquals(listOf("where did I park" to VoiceSource.GLASSES), asked)
    }

    /** Phase 0 saw a 1.19 s gap before the first chunk after idle: tolerated; 3 s of nothing ends the utterance. */
    @Test fun aOnePointTwoSecondGapKeepsListeningAndThreeSecondsEndIt() = runTest {
        val p = pipeline()
        p.glassesListenStart()
        advanceTimeBy(1_200); p.glassesAudio(pcm)
        advanceTimeBy(1_200); p.glassesAudio(pcm)
        assertFalse(engine.last.ended)
        assertEquals(2, engine.last.fed.size)
        advanceTimeBy(3_001)
        assertTrue(engine.last.ended)
    }

    @Test fun aSuspectedFallbackAbortsAndNeverAnswers() = runTest {
        val p = pipeline()
        p.glassesListenStart(); repeat(30) { p.glassesAudio(pcm) }
        engine.last.listener.onFinal(SttResult.FallbackSuspected); runCurrent()
        assertEquals(Overlay.Toast("Voice not supported on this phone"), p.overlay.value)
        assertTrue(asked.isEmpty())
        assertEquals(1, fallbacks)
        advanceTimeBy(4_001)
        assertNull(p.overlay.value)
    }

    @Test fun aBlockedGlassesAskToastsTheReasonWithoutListening() = runTest {
        caps.value = VoiceCapabilities(VoiceStatus.NOT_VERIFIED, VoiceStatus.SUPPORTED, canDownloadPack = true)
        val p = pipeline()
        p.glassesListenStart()
        assertEquals(Overlay.Toast("Open Proactive Context on phone to set up voice"), p.overlay.value)
        assertTrue(engine.sessions.isEmpty())
        advanceTimeBy(4_001)
        assertNull(p.overlay.value)
    }

    @Test fun cancellingWhileThinkingAnswersNothing() = runTest {
        val gate = CompletableDeferred<AnswerResult>()
        answer = { gate.await() }
        val p = pipeline()
        p.glassesListenStart(); engine.last.listener.onFinal(SttResult.Text("milk?")); runCurrent()
        p.glassesCancel()
        assertNull(p.overlay.value)
        gate.complete(AnswerResult.Answered("ask_1", "Milk and eggs.")); runCurrent()
        assertNull(p.overlay.value)
        assertTrue(speaker.spoken.isEmpty())
    }

    @Test fun dismissingTheAnswerStopsItsSpeech() = runTest {
        val p = pipeline()
        p.glassesListenStart(); engine.last.listener.onFinal(SttResult.Text("milk?")); runCurrent()
        assertTrue((p.overlay.value as Overlay.Answer).speaking)
        p.glassesCancel()
        assertEquals(1, speaker.stops)
        assertNull(p.overlay.value)
    }

    @Test fun oneAskAtATime() = runTest {
        val p = pipeline()
        p.phoneStart()
        p.glassesListenStart()
        assertEquals(Overlay.Toast("Phone is listening — try again"), p.overlay.value)
        assertTrue(p.phone.value.listening, "the phone's ask goes on")
        assertEquals(1, engine.sessions.size)
        p.phoneCancel()
        p.glassesListenStart()
        p.phoneStart()
        assertEquals("Your glasses are listening — try again", p.phone.value.error)
        assertEquals(2, engine.sessions.size)
    }

    @Test fun aNewGlassesAskStopsTheOldAnswersSpeech() = runTest {
        val p = pipeline()
        p.glassesListenStart(); engine.last.listener.onFinal(SttResult.Text("milk?")); runCurrent()
        val first = engine.last
        p.glassesListenStart()
        assertEquals(1, speaker.stops)
        assertEquals(Overlay.Listening(""), p.overlay.value)
        first.listener.onFinal(SttResult.Text("late")); runCurrent()
        assertEquals(listOf("milk?" to VoiceSource.GLASSES), asked, "the old session is ignored")
    }

    @Test fun phoneAsksAreSpokenAndOnlyTheirAnswerReachesTheHud() = runTest {
        bluetooth = false
        val p = pipeline()
        p.phoneStart()
        assertFalse(engine.last.external)
        assertNull(p.overlay.value)
        engine.last.listener.onPartial("where are my keys")
        assertEquals(PhoneVoiceState(VoiceStatus.SUPPORTED, listening = true, partial = "where are my keys"), p.phone.value)
        p.phoneStop()
        assertTrue(engine.last.ended)
        assertTrue(p.phone.value.thinking)
        engine.last.listener.onFinal(SttResult.Text("where are my keys")); runCurrent()
        assertEquals(Overlay.Answer("where are my keys", "Milk and eggs.", speaking = true), p.overlay.value)
        assertEquals(listOf("Milk and eggs."), speaker.spoken, "a phone ask is spoken on the current route")
        assertEquals("ask_1", p.phone.value.lastAskId)
    }

    @Test fun providerTextNeverReachesTheHud() = runTest {
        answer = { throw IllegalStateException("401 Incorrect API key sk-live-999") }
        val p = pipeline()
        p.glassesListenStart(); engine.last.listener.onFinal(SttResult.Text("milk?")); runCurrent()
        assertEquals(Overlay.Toast("Couldn't answer that"), p.overlay.value)
        answer = { AnswerResult.Failed("AI unavailable", "AI unavailable — check Settings › Model endpoints") }
        p.phoneStart(); engine.last.listener.onFinal(SttResult.Text("milk?")); runCurrent()
        assertEquals("AI unavailable — check Settings › Model endpoints", p.phone.value.error)
        assertFalse(p.phone.value.toString().contains("sk-live"))
    }

    @Test fun nothingHeardAndAMissingPackGiveShortToasts() = runTest {
        val p = pipeline()
        p.glassesListenStart(); engine.last.listener.onFinal(SttResult.NoSpeech)
        assertEquals(Overlay.Toast("Couldn't hear that"), p.overlay.value)
        p.glassesListenStart(); engine.last.listener.onFinal(SttResult.PackMissing)
        assertEquals(Overlay.Toast("Download speech pack on phone"), p.overlay.value)
        p.glassesListenStart(); engine.last.listener.onFinal(SttResult.Text("  \u2028 "))
        assertEquals(Overlay.Toast("Couldn't hear that"), p.overlay.value, "a blank transcript is not asked")
        assertTrue(asked.isEmpty())
    }

    @Test fun aDroppedLinkCancelsAnOpenListen() = runTest {
        val p = pipeline()
        p.glassesListenStart(); p.glassesAudio(pcm)
        p.linkLost()
        assertTrue(engine.last.cancelled)
        assertNull(p.overlay.value)
        p.glassesAudio(pcm)
        assertEquals(1, engine.last.fed.size)
    }

    @Test fun aTypedAnswerIsMirroredSilentlyAndNeverOverAVoiceAsk() = runTest {
        val p = pipeline()
        p.showTypedAnswer("ask_t", "where are my keys", "On the hallway shelf.")
        assertEquals(Overlay.Answer("where are my keys", "On the hallway shelf.", speaking = false), p.overlay.value)
        assertTrue(speaker.spoken.isEmpty())
        p.glassesListenStart()
        p.showTypedAnswer("ask_u", "q", "a")
        assertEquals(Overlay.Listening(""), p.overlay.value)
    }

    @Test fun longTextIsClippedForTheHud() = runTest {
        answer = { AnswerResult.Answered("ask_1", "word ".repeat(200)) }
        val p = pipeline()
        p.glassesListenStart(); engine.last.listener.onFinal(SttResult.Text("q ".repeat(300))); runCurrent()
        val a = p.overlay.value as Overlay.Answer
        assertTrue(a.question.length <= 120); assertTrue(a.text.length <= 300)
        assertTrue(speaker.spoken.single().length > 300, "the whole answer is spoken")
    }
}
