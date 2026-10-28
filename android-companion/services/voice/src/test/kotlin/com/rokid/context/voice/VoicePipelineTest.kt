package com.rokid.context.voice

import com.rokid.context.core.model.Overlay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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

@OptIn(ExperimentalCoroutinesApi::class)
class VoicePipelineTest {
    private val engine = FakeSpeechEngine()
    private val speaker = FakeSpeaker()
    private val caps = MutableStateFlow(VoiceCapabilities(VoiceStatus.SUPPORTED, VoiceStatus.SUPPORTED, canDownloadPack = true))
    private var bluetooth = true
    private var fallbacks = 0
    private val asked = mutableListOf<Pair<String, VoiceSource>>()
    private var answer: suspend (String) -> AnswerResult = { AnswerResult.Answered("ask_1", "Milk and eggs.") }
    private val pcm = ByteArray(3_200) { 9 }
    private var language: () -> SpeechLanguage = { SpeechLanguage.ENGLISH }

    private var interceptor: UtteranceInterceptor = UtteranceInterceptor { _, _, _ -> InterceptResult.PassThrough }
    private val logs = mutableListOf<String>()

    private fun TestScope.pipeline() = VoicePipeline(
        backgroundScope, engine, caps, { language() },
        Answerer { q, source -> asked += q to source; answer(q) },
        speaker, bluetoothMedia = { bluetooth }, onFallbackSuspected = { fallbacks++ },
        interceptor = { t, s, g -> interceptor.intercept(t, s, g) }, log = { logs += it },
    ).also { runCurrent() }

    @Test fun interceptorRunsBeforeThinking() = runTest {
        val seen = mutableListOf<Triple<String, VoiceSource, Boolean>>()
        lateinit var p: VoicePipeline
        var stateDuring: VoiceState? = null
        interceptor = UtteranceInterceptor { t, s, g -> seen += Triple(t, s, g); stateDuring = p.state.value; InterceptResult.PassThrough }
        p = pipeline()
        p.glassesListenStart(); engine.last.listener.onFinal(SttResult.Text("milk?")); runCurrent()
        assertEquals(listOf(Triple("milk?", VoiceSource.GLASSES, true)), seen)
        assertTrue(stateDuring is VoiceState.Listening, "Thinking is not yet published when the interceptor runs")
        assertEquals(listOf("milk?" to VoiceSource.GLASSES), asked)
    }

    @Test fun interceptorSeesTheUnclippedTranscript() = runTest {
        var len = 0
        interceptor = UtteranceInterceptor { t, _, _ -> len = t.length; InterceptResult.PassThrough }
        val p = pipeline()
        p.glassesListenStart(); engine.last.listener.onFinal(SttResult.Text("a".repeat(900))); runCurrent()
        assertEquals(900, len)
        assertEquals(500, asked.single().first.length, "Ask still gets the clipped text")
    }

    @Test fun interceptorHandledMakesNoModelCall() = runTest {
        interceptor = UtteranceInterceptor { _, _, _ -> InterceptResult.Handled("marked \u00b7 driving") }
        val p = pipeline()
        p.glassesListenStart(); engine.last.listener.onFinal(SttResult.Text("mark that")); runCurrent()
        assertTrue(asked.isEmpty())
        assertEquals(Overlay.Toast("marked \u00b7 driving"), p.overlay.value)
        advanceTimeBy(4_001)
        assertEquals(VoiceState.Idle, p.state.value)
        assertTrue(logs.any { it == "outcome=MARKED source=GLASSES" })
    }

    @Test fun aSuspendedInterceptionIsConsumedOnceWhateverEndsTheListenAfterIt() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>(); var calls = 0
        interceptor = UtteranceInterceptor { _, _, _ -> calls++; gate.await(); InterceptResult.Handled("mark undone") }
        val p = pipeline()
        p.glassesListenStart(); engine.last.listener.onFinal(SttResult.Text("undo mark")); runCurrent()
        assertEquals(1, calls)
        p.glassesListenEnd(); p.phoneStop(); engine.last.listener.onFinal(SttResult.Text("undo mark")); engine.last.listener.onPartial("again")
        advanceTimeBy(60_000); runCurrent() // every leftover timer of the listen: audio idle, max listen, final timeout
        assertEquals(1, calls, "exactly one interception for the one final")
        assertTrue(logs.none { it.startsWith("outcome=NO_FINAL") || it.startsWith("outcome=NO_SPEECH") }, "no timeout verdict on a consumed final")
        gate.complete(Unit); runCurrent()
        assertEquals(Overlay.Toast("mark undone"), p.overlay.value)
        assertEquals(1, logs.count { it == "outcome=MARKED source=GLASSES" }, "one reply")
    }

    @Test fun aNewListenCancelsAnInterceptionStillWaiting() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>(); var finished = 0
        interceptor = UtteranceInterceptor { _, _, _ -> try { gate.await() } finally { finished++ }; InterceptResult.Handled("mark undone") }
        val p = pipeline()
        p.glassesListenStart(); engine.last.listener.onFinal(SttResult.Text("undo mark")); runCurrent()
        p.glassesCancel(); runCurrent()
        assertEquals(1, finished, "the waiting job was cancelled")
        gate.complete(Unit); runCurrent()
        assertTrue(p.overlay.value !is Overlay.Toast || (p.overlay.value as Overlay.Toast).text != "mark undone", "a cancelled interception never replies")
    }

    @Test fun interceptorCanNarrowTheQuestionToUnmatchedClauses() = runTest {
        interceptor = UtteranceInterceptor { _, _, _ -> InterceptResult.Ask("what's this building") }
        val p = pipeline()
        p.glassesListenStart(); engine.last.listener.onFinal(SttResult.Text("mark that and what's this building")); runCurrent()
        assertEquals(listOf("what's this building" to VoiceSource.GLASSES), asked)
        assertTrue(p.state.value is VoiceState.Answer)
    }

    @Test fun timeoutPartialNotGenuine() = runTest {
        val seen = mutableListOf<Boolean>()
        interceptor = UtteranceInterceptor { _, _, g -> seen += g; InterceptResult.PassThrough }
        val p = pipeline()
        p.glassesListenStart(); engine.last.listener.onPartial("where did I park"); p.glassesListenEnd()
        advanceTimeBy(6_001); runCurrent()
        assertEquals(listOf(false), seen)
    }

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
        assertEquals(1, engine.last.cancels, "a recognizer that never sent a final is released")
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
        assertNull(p.overlay.value, "the HUD never reads thinking while the user is still speaking into the phone")
        p.phoneStop()
        assertTrue(engine.last.ended)
        assertTrue(p.phone.value.thinking)
        assertNull(p.overlay.value)
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
    /** Wave 3 Task 3: the HUD gets "· Photo HH:mm"; the spoken text stays the bare answer. */
    @Test fun photoLabelAppendedToHudNotSpoken() = runTest {
        answer = { AnswerResult.Answered("ask_1", "Keys on the desk.", photoLabel = "Photo 08:53") }
        val p = pipeline()
        p.glassesListenStart(); engine.last.listener.onFinal(SttResult.Text("where are my keys")); runCurrent()
        assertEquals(Overlay.Answer("where are my keys", "Keys on the desk. \u00b7 Photo 08:53", speaking = true), p.overlay.value)
        assertEquals(listOf("Keys on the desk."), speaker.spoken)
        assertEquals("Keys on the desk.", (p.state.value as VoiceState.Answer).text)
    }

    @Test fun longAnswerClippedToFitLabel() = runTest {
        answer = { AnswerResult.Answered("ask_1", "word ".repeat(200), photoLabel = "Photo 08:53") }
        val p = pipeline()
        p.glassesListenStart(); engine.last.listener.onFinal(SttResult.Text("q")); runCurrent()
        val a = p.overlay.value as Overlay.Answer
        assertTrue(a.text.length <= 300, "${a.text.length}")
        assertTrue(a.text.endsWith(" \u00b7 Photo 08:53"), a.text)
        assertTrue(speaker.spoken.single().length > 300)
    }

    @Test fun thinkingIsBoundedAndALateAnswerIsIgnored() = runTest {
        val gate = CompletableDeferred<AnswerResult>()
        answer = { withContext(NonCancellable) { gate.await() } }
        val p = pipeline()
        p.glassesListenStart(); engine.last.listener.onFinal(SttResult.Text("milk?")); runCurrent()
        advanceTimeBy(59_999)
        assertEquals(Overlay.Listening("milk?"), p.overlay.value)
        advanceTimeBy(2); runCurrent()
        assertEquals(Overlay.Toast("That took too long — try again"), p.overlay.value)
        gate.complete(AnswerResult.Answered("ask_1", "Milk and eggs.")); runCurrent()
        assertEquals(Overlay.Toast("That took too long — try again"), p.overlay.value)
        assertTrue(speaker.spoken.isEmpty())
        advanceTimeBy(4_001)
        assertNull(p.overlay.value)
        p.phoneStart()
        assertTrue(p.phone.value.listening, "the phone mic is free again")
    }

    @Test fun aPhoneAskThatTimesOutShowsTheSameCopyOnHome() = runTest {
        answer = { CompletableDeferred<AnswerResult>().await() }
        val p = pipeline()
        p.phoneStart(); engine.last.listener.onFinal(SttResult.Text("milk?")); runCurrent()
        advanceTimeBy(60_001); runCurrent()
        assertEquals("That took too long — try again", p.phone.value.error)
        assertFalse(p.phone.value.thinking)
    }

    /** The 20 s cap ends audio intake; the 6 s final wait sits on top of it (worst case 26 s). */
    @Test fun aPhoneListenIsCappedAtTwentySecondsThenGetsTheFinalWait() = runTest {
        val p = pipeline()
        p.phoneStart()
        advanceTimeBy(19_999)
        assertFalse(engine.last.ended)
        advanceTimeBy(2)
        assertTrue(engine.last.ended)
        assertTrue(p.phone.value.thinking)
        advanceTimeBy(6_001); runCurrent()
        assertEquals(1, engine.last.cancels)
        assertEquals("Couldn't hear that — try again", p.phone.value.error)
        assertTrue(asked.isEmpty())
    }

    @Test fun aGlassesStreamThatNeverGoesQuietIsCappedAtTwentySeconds() = runTest {
        val p = pipeline()
        p.glassesListenStart()
        engine.last.listener.onPartial("tell me everything")
        repeat(19) { advanceTimeBy(1_000); p.glassesAudio(pcm) }
        assertFalse(engine.last.ended)
        advanceTimeBy(1_001)
        assertTrue(engine.last.ended)
        p.glassesAudio(pcm)
        assertEquals(19, engine.last.fed.size, "no audio is taken after the cap")
        advanceTimeBy(6_001); runCurrent()
        assertEquals(listOf("tell me everything" to VoiceSource.GLASSES), asked)
    }

    @Test fun aSpeakerThatCannotSpeakLeavesASilentAnswerThatStillClears() = runTest {
        speaker.available = false
        val p = pipeline()
        p.glassesListenStart(); engine.last.listener.onFinal(SttResult.Text("milk?")); runCurrent()
        assertEquals(Overlay.Answer("milk?", "Milk and eggs.", speaking = false), p.overlay.value)
        advanceTimeBy(30_001)
        assertNull(p.overlay.value)
    }

    @Test fun aRecognizerThatFailsToStartGivesShortCopyAndNeverStaysListening() = runTest {
        engine.failStart = true
        val p = pipeline()
        p.glassesListenStart()
        assertEquals(Overlay.Toast("Couldn't hear that"), p.overlay.value)
        p.phoneStart()
        assertEquals("Couldn't hear that — try again", p.phone.value.error)
        assertFalse(p.phone.value.listening)
        advanceTimeBy(4_001)
        assertEquals(VoiceState.Idle, p.state.value)
    }

    @Test fun aLanguageThatThrowsEndsTheAskInsteadOfListeningForever() = runTest {
        language = { throw IllegalStateException("prefs") }
        val p = pipeline()
        p.glassesListenStart()
        assertEquals(Overlay.Toast("Couldn't hear that"), p.overlay.value)
        assertTrue(engine.sessions.isEmpty())
        advanceTimeBy(4_001)
        assertEquals(VoiceState.Idle, p.state.value)
    }

    @Test fun failedAndNoPermissionResultsGiveTheirCopy() = runTest {
        val p = pipeline()
        p.glassesListenStart(); engine.last.listener.onFinal(SttResult.Failed)
        assertEquals(Overlay.Toast("Couldn't hear that"), p.overlay.value)
        p.glassesListenStart(); engine.last.listener.onFinal(SttResult.NoPermission)
        assertEquals(Overlay.Toast("Allow the microphone on phone"), p.overlay.value)
        p.phoneStart(); engine.last.listener.onFinal(SttResult.NoPermission)
        assertEquals("Allow the microphone to ask by voice", p.phone.value.error)
        assertTrue(asked.isEmpty())
    }

    @Test fun thePhoneErrorClearsAfterTheToastTime() = runTest {
        val p = pipeline()
        p.phoneStart(); engine.last.listener.onFinal(SttResult.NoSpeech)
        assertEquals("Couldn't hear that — try again", p.phone.value.error)
        advanceTimeBy(3_999)
        assertEquals("Couldn't hear that — try again", p.phone.value.error)
        advanceTimeBy(2)
        assertNull(p.phone.value.error)
        p.glassesListenStart(); p.phoneStart()
        assertEquals("Your glasses are listening — try again", p.phone.value.error)
        advanceTimeBy(4_001)
        assertNull(p.phone.value.error, "the busy note does not outlive its toast time")
    }

    @Test fun phoneCancelWhileThinkingAnswersNothing() = runTest {
        val gate = CompletableDeferred<AnswerResult>()
        answer = { gate.await() }
        val p = pipeline()
        p.phoneStart(); engine.last.listener.onFinal(SttResult.Text("milk?")); runCurrent()
        assertTrue(p.phone.value.thinking)
        p.phoneCancel()
        assertFalse(p.phone.value.thinking)
        gate.complete(AnswerResult.Answered("ask_1", "Milk and eggs.")); runCurrent()
        assertNull(p.overlay.value)
        assertTrue(speaker.spoken.isEmpty())
        assertNull(p.phone.value.lastAskId)
    }

    @Test fun aLinkLostWhileThinkingStillAnswers() = runTest {
        val gate = CompletableDeferred<AnswerResult>()
        answer = { gate.await() }
        val p = pipeline()
        p.glassesListenStart(); engine.last.listener.onFinal(SttResult.Text("milk?")); runCurrent()
        p.linkLost()
        gate.complete(AnswerResult.Answered("ask_1", "Milk and eggs.")); runCurrent()
        assertEquals(Overlay.Answer("milk?", "Milk and eggs.", speaking = true), p.overlay.value)
    }

    @Test fun aPhoneAnswerIsHeldThirtySecondsAfterItsSpeech() = runTest {
        val p = pipeline()
        p.phoneStart(); engine.last.listener.onFinal(SttResult.Text("milk?")); runCurrent()
        advanceTimeBy(45_000)
        assertTrue((p.overlay.value as Overlay.Answer).speaking, "held while still speaking")
        speaker.finish()
        advanceTimeBy(29_999)
        assertEquals(Overlay.Answer("milk?", "Milk and eggs.", speaking = false), p.overlay.value)
        advanceTimeBy(2)
        assertNull(p.overlay.value)
        assertEquals(VoiceState.Idle, p.state.value)
    }

    @Test fun aLongFailureToastIsClippedForTheHud() = runTest {
        answer = { AnswerResult.Failed("x".repeat(500), "y") }
        val p = pipeline()
        p.glassesListenStart(); engine.last.listener.onFinal(SttResult.Text("milk?")); runCurrent()
        assertTrue((p.overlay.value as Overlay.Toast).text.length <= 120)
    }
}
