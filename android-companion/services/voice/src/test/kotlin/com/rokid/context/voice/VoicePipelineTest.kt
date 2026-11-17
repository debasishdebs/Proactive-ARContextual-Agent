package com.rokid.context.voice

import com.rokid.context.core.model.Overlay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
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
    private var deviceCaps = com.rokid.context.core.model.DeviceCapabilities.ALL
    /** The asks whose stored row was redacted or deleted (Codex r21). */
    private val withdrawn = MutableStateFlow<Set<String>>(emptySet())
    private var withdrawnFor: (String, Boolean) -> kotlinx.coroutines.flow.Flow<Boolean> = { id, _ -> withdrawn.map { id in it } }

    private fun TestScope.pipeline() = VoicePipeline(
        backgroundScope, engine, caps, { language() },
        Answerer { q, source -> asked += q to source; answer(q) },
        speaker, bluetoothMedia = { bluetooth }, onFallbackSuspected = { fallbacks++ },
        interceptor = { t, s, g -> interceptor.intercept(t, s, g) }, log = { logs += it }, deviceCaps = { deviceCaps },
        answerWithdrawn = { id, existed -> withdrawnFor(id, existed) },
    ).also { runCurrent() }

    @Test fun aSwitchedOffGlassesMicStartsNoListenAndSaysSo() = runTest {
        deviceCaps = deviceCaps.with("glasses", "mic", false)
        val p = pipeline()
        p.glassesListenStart(); runCurrent()
        assertEquals(Overlay.Toast(VoiceCopy.GLASSES_MIC_OFF), p.overlay.value)
        assertTrue(engine.sessions.isEmpty(), "no recognizer was started"); assertTrue(asked.isEmpty())
        assertTrue(logs.any { it == "outcome=BLOCKED source=GLASSES" })
    }

    /** v0.9.9: Listening and Thinking are HUD frames; the glasses display switched off sends neither, the ask itself still runs and answers. */
    @Test fun listeningAndThinkingFramesFollowTheGlassesDisplaySwitch() = runTest {
        for (displayOn in listOf(true, false)) {
            deviceCaps = com.rokid.context.core.model.DeviceCapabilities.ALL.with("glasses", "display", displayOn)
            asked.clear()
            val gate = CompletableDeferred<AnswerResult>(); answer = { gate.await() }
            val p = pipeline()
            p.glassesListenStart(); runCurrent()
            assertTrue(p.state.value is VoiceState.Listening)
            assertEquals(displayOn, p.overlay.value is Overlay.Listening, "listening frame, display on=$displayOn")
            engine.last.listener.onFinal(SttResult.Text("milk?")); runCurrent()
            assertTrue(p.state.value is VoiceState.Thinking)
            assertEquals(displayOn, p.overlay.value is Overlay.Listening, "thinking frame, display on=$displayOn")
            if (!displayOn) assertNull(p.overlay.value)
            assertEquals(listOf("milk?" to VoiceSource.GLASSES), asked)
            gate.complete(AnswerResult.Answered("ask_1", "Milk and eggs.")); runCurrent()
            assertTrue(p.state.value is VoiceState.Answer)
        }
    }

    @Test fun aSwitchedOffPhoneMicStartsNoListenAndTheGlassesStillWork() = runTest {
        deviceCaps = deviceCaps.with("phone", "mic", false)
        val p = pipeline()
        p.phoneStart(); runCurrent()
        assertEquals(VoiceCopy.PHONE_MIC_OFF, p.phone.value.error); assertTrue(engine.sessions.isEmpty())
        p.glassesListenStart(); engine.last.listener.onFinal(SttResult.Text("milk?")); runCurrent()
        assertEquals(listOf("milk?" to VoiceSource.GLASSES), asked)
    }

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
        assertEquals(Overlay.Answer("What do I need from the store", "Milk and eggs.", speaking = true, askId = "ask_1"), p.overlay.value)
        assertEquals(listOf("Milk and eggs."), speaker.spoken)
        speaker.finish()
        assertEquals(Overlay.Answer("What do I need from the store", "Milk and eggs.", speaking = false, askId = "ask_1"), p.overlay.value)
        advanceTimeBy(30_001)
        assertNull(p.overlay.value)
        assertEquals(VoiceState.Idle, p.state.value)
    }

    @Test fun withoutBluetoothMediaTheGlassesAnswerIsHudOnly() = runTest {
        bluetooth = false
        val p = pipeline()
        p.glassesListenStart(); engine.last.listener.onFinal(SttResult.Text("where are my keys")); runCurrent()
        assertEquals(Overlay.Answer("where are my keys", "Milk and eggs.", speaking = false, askId = "ask_1"), p.overlay.value)
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
        assertEquals(Overlay.Answer("where are my keys", "Milk and eggs.", speaking = true, askId = "ask_1"), p.overlay.value)
        assertEquals(listOf("Milk and eggs."), speaker.spoken, "a phone ask is spoken on the current route")
        assertEquals("ask_1", p.phone.value.lastAskId)
    }

    // ---- v0.9.8 slice C: a flow's delivery target, one surface each, never twice ----

    private fun routed(phone: Boolean, glasses: Boolean, fallback: String? = null): suspend (String) -> AnswerResult =
        { _ -> AnswerResult.Answered("ask_1", "Milk and eggs.", route = com.rokid.context.core.model.DeliveryRoute(phone, glasses, fallback)) }

    @Test fun aGlassesTargetFromThePhoneMicShowsOnTheHudAndNotOnThePhone() = runTest {
        bluetooth = false
        answer = routed(phone = false, glasses = true)
        val p = pipeline()
        p.phoneStart(); engine.last.listener.onFinal(SttResult.Text("where are my keys")); runCurrent()
        assertEquals(Overlay.Answer("where are my keys", "Milk and eggs.", speaking = false, askId = "ask_1"), p.overlay.value)
        assertTrue(speaker.spoken.isEmpty(), "not spoken on the phone")
        assertNull(p.phone.value.lastAskId, "not offered to Home either")
    }

    @Test fun aPhoneTargetFromTheGlassesLeavesTheHudAndAnswersOnThePhone() = runTest {
        answer = routed(phone = true, glasses = false)
        val p = pipeline()
        p.glassesListenStart(); engine.last.listener.onFinal(SttResult.Text("where are my keys")); runCurrent()
        assertNull(p.overlay.value, "the HUD shows nothing")
        assertEquals("ask_1", p.phone.value.lastAskId)
        assertTrue(speaker.spoken.isEmpty())
    }

    @Test fun bothDeliversToTheHudAndThePhoneOnce() = runTest {
        bluetooth = false
        answer = routed(phone = true, glasses = true)
        val p = pipeline()
        p.phoneStart(); engine.last.listener.onFinal(SttResult.Text("where are my keys")); runCurrent()
        assertEquals(Overlay.Answer("where are my keys", "Milk and eggs.", speaking = true, askId = "ask_1"), p.overlay.value)
        assertEquals(listOf("Milk and eggs."), speaker.spoken)
        assertEquals("ask_1", p.phone.value.lastAskId)
    }

    @Test fun aFallbackReasonIsLoggedAndAnImplicitRouteChangesNothing() = runTest {
        answer = routed(phone = true, glasses = false, fallback = "glasses_not_connected")
        val p = pipeline()
        p.glassesListenStart(); engine.last.listener.onFinal(SttResult.Text("where are my keys")); runCurrent()
        assertTrue(logs.any { it == "deliver fallback=glasses_not_connected" })
        // the caller route (not explicit) is the pipeline's own behaviour: the surface that asked, HUD as before
        answer = { AnswerResult.Answered("ask_2", "Milk and eggs.", route = com.rokid.context.core.model.DeliveryRoute(phone = false, glasses = true, explicit = false)) }
        advanceTimeBy(30_001)
        p.glassesListenStart(); engine.last.listener.onFinal(SttResult.Text("where are my keys")); runCurrent()
        assertEquals(Overlay.Answer("where are my keys", "Milk and eggs.", speaking = true, askId = "ask_2"), p.overlay.value)
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
        assertEquals(Overlay.Answer("where are my keys", "On the hallway shelf.", speaking = false, askId = "ask_t"), p.overlay.value)
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
        assertEquals(Overlay.Answer("where are my keys", "Keys on the desk. \u00b7 Photo 08:53", speaking = true, askId = "ask_1"), p.overlay.value)
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
        assertEquals(Overlay.Answer("milk?", "Milk and eggs.", speaking = false, askId = "ask_1"), p.overlay.value)
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
        assertEquals(Overlay.Answer("milk?", "Milk and eggs.", speaking = true, askId = "ask_1"), p.overlay.value)
    }

    @Test fun aPhoneAnswerIsHeldThirtySecondsAfterItsSpeech() = runTest {
        val p = pipeline()
        p.phoneStart(); engine.last.listener.onFinal(SttResult.Text("milk?")); runCurrent()
        advanceTimeBy(45_000)
        assertTrue((p.overlay.value as Overlay.Answer).speaking, "held while still speaking")
        speaker.finish()
        advanceTimeBy(29_999)
        assertEquals(Overlay.Answer("milk?", "Milk and eggs.", speaking = false, askId = "ask_1"), p.overlay.value)
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

    /** Codex r21 P1-4: an answer on the HUD whose stored ask is redacted (a source forgotten) or deleted is cleared at once, not after its hold. */
    @Test fun aWithdrawnAnswerLeavesTheHudAtOnce() = runTest {
        val p = pipeline()
        p.showTypedAnswer("ask_t", "where are my keys", "On the hallway shelf."); runCurrent()
        assertTrue(p.overlay.value != null, "the typed answer is mirrored")
        withdrawn.value = setOf("ask_other"); runCurrent()
        assertTrue(p.state.value is VoiceState.Answer, "another ask's redaction changes nothing")
        withdrawn.value = setOf("ask_t"); runCurrent()
        assertEquals(VoiceState.Idle, p.state.value)
        assertNull(p.overlay.value, "the hub sends no frame of it any more")
        // a spoken voice answer: its speech stops too
        p.glassesListenStart(); runCurrent(); engine.last.listener.onFinal(SttResult.Text("milk?")); runCurrent()
        assertTrue(p.state.value is VoiceState.Answer)
        withdrawn.value = setOf("ask_t", "ask_1"); runCurrent()
        assertEquals(VoiceState.Idle, p.state.value); assertNull(p.overlay.value)
    }

    /**
     * rc1 (2026-11-13): a committed Forget withdraws the asks it redacted directly, not only through the row watcher. A glasses voice answer still on the HUD
     * is cleared at once, its speech stops and one milestone line (no text, no id) says so; after the hold (Idle) the same call changes nothing.
     */
    @Test fun aForgetWithdrawsTheShownGlassesVoiceAnswerAndLogsAMilestone() = runTest {
        val milestones = mutableListOf<String>()
        withdrawnFor = { _, _ -> kotlinx.coroutines.flow.emptyFlow() } // the row watcher never fires: only the explicit path can clear it
        val p = VoicePipeline(
            backgroundScope, engine, caps, { language() }, Answerer { q, source -> asked += q to source; answer(q) },
            speaker, bluetoothMedia = { bluetooth }, log = { logs += it }, answerWithdrawn = { id, existed -> withdrawnFor(id, existed) }, milestone = { milestones += it },
        ).also { runCurrent() }
        p.glassesListenStart(); runCurrent(); engine.last.listener.onFinal(SttResult.Text("where did I keep my shoes")); runCurrent()
        assertEquals("ask_1", (p.overlay.value as Overlay.Answer).askId, "the HUD card names its ask")
        val stopsBefore = speaker.stops
        assertFalse(p.withdraw(listOf("ask_other")), "another ask's redaction changes nothing")
        assertTrue(p.state.value is VoiceState.Answer)
        assertTrue(p.withdraw(listOf("ask_other", "ask_1")))
        assertEquals(VoiceState.Idle, p.state.value); assertNull(p.overlay.value)
        assertTrue(speaker.stops > stopsBefore, "its speech stops")
        assertEquals(listOf("answer withdrawn by=forget"), milestones)
        // after the hold the phone side is already Idle: a later Forget is a no-op here (the hub tells the glasses)
        p.glassesListenStart(); runCurrent(); engine.last.listener.onFinal(SttResult.Text("milk?")); runCurrent()
        speaker.finish(); advanceTimeBy(30_001); runCurrent()
        assertEquals(VoiceState.Idle, p.state.value, "the 30 s hold ended")
        assertFalse(p.withdraw(listOf("ask_1")))
        assertEquals(1, milestones.size)
    }

    /** Codex z1 r1 P1-3: the zone is asked again as the last step before an answer is shown or spoken; a zone that starts later withdraws the answer on show and stops its speech. */
    @Test fun aZoneRefusesTheShownAndSpokenAnswerAndWithdrawsOneAlreadyOnShow() = runTest {
        var zone = false
        val p = VoicePipeline(
            backgroundScope, engine, caps, { language() }, Answerer { q, source -> asked += q to source; answer(q) },
            speaker, bluetoothMedia = { bluetooth }, log = { logs += it }, deliveryBlocked = { zone },
        ).also { runCurrent() }
        zone = true
        p.glassesListenStart(); runCurrent(); engine.last.listener.onFinal(SttResult.Text("milk?")); runCurrent()
        assertTrue(speaker.spoken.isEmpty(), "nothing is spoken in a zone")
        assertEquals(Overlay.Toast(VoiceCopy.ZONE_PAUSED), p.overlay.value)
        p.showTypedAnswer("ask_t", "q", "text"); assertFalse(p.state.value is VoiceState.Answer, "no typed mirror in a zone")
        advanceTimeBy(30_001); runCurrent()
        zone = false
        p.glassesListenStart(); runCurrent(); engine.last.listener.onFinal(SttResult.Text("milk?")); runCurrent()
        assertEquals("ask_1", (p.overlay.value as Overlay.Answer).askId)
        val stops = speaker.stops
        p.withdrawForZone()
        assertEquals(VoiceState.Idle, p.state.value); assertNull(p.overlay.value); assertTrue(speaker.stops > stops, "its speech stops")
        p.withdrawForZone() // nothing on show: a no-op
    }

    /**
     * Codex r23: the "its row existed" proof travels with each answer. Voice ask A takes its snapshot; typed ask B arrives while A is Thinking (refused on the
     * HUD, but its snapshot ran too); Delete all removes both rows; A resumes. A's watcher must count its first null read as a deletion: HUD cleared, speech stopped.
     */
    @Test fun aConcurrentTypedAskNeverOverwritesTheVoiceAnswersProofThatItsRowExisted() = runTest {
        // the production rule (VoiceGraph.withdrawnSignal) over a fake ask table: redacted, or gone after it existed (or after the answer's own snapshot)
        val rows = MutableStateFlow(mapOf<String, Boolean>("ask_A" to false, "ask_B" to false)) // id -> redacted
        withdrawnFor = { id, existed -> var seen = existed; rows.map { m -> val r = m[id]; if (r != null) seen = true; r == true || (r == null && seen) } }
        val gate = CompletableDeferred<AnswerResult>(); answer = { gate.await() }
        val p = pipeline()
        p.glassesListenStart(); runCurrent(); engine.last.listener.onFinal(SttResult.Text("milk?")); runCurrent()
        assertTrue(p.state.value is VoiceState.Thinking)
        p.showTypedAnswer("ask_B", "keys?", "On the shelf.", stored = true); runCurrent() // B: snapshot taken, refused over a voice ask in progress
        assertTrue(p.state.value is VoiceState.Thinking)
        rows.value = emptyMap(); runCurrent() // Delete all, after A's snapshot (A's answer text was read from its row)
        gate.complete(AnswerResult.Answered("ask_A", "Milk and eggs.", stored = true)); runCurrent()
        assertEquals(VoiceState.Idle, p.state.value)
        assertNull(p.overlay.value, "the hub sends no frame of A")
        assertTrue(speaker.stops > 0 || speaker.spoken.isEmpty(), "A is not left speaking")
    }
}

