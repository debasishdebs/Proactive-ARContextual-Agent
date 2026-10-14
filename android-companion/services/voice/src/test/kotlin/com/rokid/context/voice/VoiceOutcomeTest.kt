package com.rokid.context.voice

import com.rokid.context.core.model.Overlay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** BUG-R3-3: every voice ask ends in exactly one `outcome=<CODE> source=<SOURCE>` log line, never with user text. */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceOutcomeTest {
    private val engine = FakeSpeechEngine()
    private val caps = MutableStateFlow(VoiceCapabilities(VoiceStatus.SUPPORTED, VoiceStatus.SUPPORTED, canDownloadPack = true))
    private val logs = mutableListOf<String>()
    private var answer: suspend (String) -> AnswerResult = { AnswerResult.Answered("ask_1", "Milk and eggs.") }
    private val pcm = ByteArray(3_200) { 9 }

    private fun TestScope.pipeline() = VoicePipeline(
        backgroundScope, engine, caps, { SpeechLanguage.ENGLISH },
        Answerer { q, _ -> answer(q) }, FakeSpeaker(), bluetoothMedia = { false }, log = { logs += it },
    ).also { runCurrent() }

    private fun outcomes() = logs.filter { it.startsWith("outcome=") }

    private fun assertNoUserText(vararg text: String) =
        text.forEach { t -> assertFalse(logs.any { it.contains(t, ignoreCase = true) }, "user text in the log: $logs") }

    @Test fun aGlassesNoSpeechFinalLogsItsOutcomeAndToastsCouldntHear() = runTest {
        val p = pipeline()
        p.glassesListenStart(); p.glassesAudio(pcm); p.glassesListenEnd()
        engine.last.listener.onFinal(SttResult.NoSpeech); runCurrent()
        assertEquals(listOf("outcome=NO_SPEECH source=GLASSES"), outcomes())
        assertEquals(Overlay.Toast(VoiceCopy.COULDNT_HEAR_HUD), p.overlay.value)
        assertTrue("listen end GLASSES" in logs, "pc_listen_end reaching the pipeline is logged")
    }

    @Test fun aPhoneRecognizerFailureLogsItsOutcomeAndShowsCouldntHearOnHome() = runTest {
        val p = pipeline()
        p.phoneStart(); p.phoneStop()
        engine.last.listener.onFinal(SttResult.Failed); runCurrent()
        assertEquals(listOf("outcome=STT_FAILED source=PHONE"), outcomes())
        assertEquals(VoiceCopy.COULDNT_HEAR_PHONE, p.phone.value.error)
    }

    @Test fun anAnsweredAskLogsAnsweredWithoutTheQuestionOrAnswer() = runTest {
        val p = pipeline()
        p.glassesListenStart(); engine.last.listener.onPartial("where are my keys")
        p.glassesListenEnd(); engine.last.listener.onFinal(SttResult.Text("where are my keys")); runCurrent()
        assertEquals(listOf("outcome=ANSWERED source=GLASSES"), outcomes())
        assertNoUserText("keys", "milk")
    }

    @Test fun everyOtherEndHasItsOwnCode() = runTest {
        val p = pipeline()
        engine.failStart = true; p.glassesListenStart(); runCurrent()
        engine.failStart = false
        advanceTimeBy(4_001)
        p.glassesListenStart(); engine.last.listener.onFinal(SttResult.PackMissing); runCurrent()
        p.glassesListenStart(); engine.last.listener.onFinal(SttResult.NoPermission); runCurrent()
        p.glassesListenStart(); engine.last.listener.onFinal(SttResult.FallbackSuspected); runCurrent()
        p.glassesListenStart(); p.glassesCancel()
        p.glassesListenStart(); p.linkLost()
        answer = { AnswerResult.Failed(VoiceCopy.FAILED_HUD, VoiceCopy.FAILED_PHONE) }
        p.glassesListenStart(); engine.last.listener.onFinal(SttResult.Text("milk?")); runCurrent()
        p.glassesListenStart(); engine.last.listener.onFinal(SttResult.Text("   ")); runCurrent()
        caps.value = VoiceCapabilities(VoiceStatus.NOT_VERIFIED, VoiceStatus.SUPPORTED, canDownloadPack = true); runCurrent()
        p.glassesListenStart()
        assertEquals(
            listOf("RECOGNIZER_FAILED", "PACK_MISSING", "NO_PERMISSION", "FALLBACK_SUSPECTED", "CANCELLED", "LINK_LOST", "ANSWER_FAILED", "NO_SPEECH", "BLOCKED")
                .map { "outcome=$it source=GLASSES" },
            outcomes(),
        )
    }

    @Test fun aSlowAnswerLogsAnswerTimeout() = runTest {
        answer = { kotlinx.coroutines.awaitCancellation() }
        val p = pipeline()
        p.glassesListenStart(); engine.last.listener.onFinal(SttResult.Text("milk?")); runCurrent()
        advanceTimeBy(60_001); runCurrent()
        assertEquals(listOf("outcome=ANSWER_TIMEOUT source=GLASSES"), outcomes())
    }

    /** (c) The glasses sent audio and pc_listen_end, but the recognizer never produced a partial or a final. */
    @Test fun audioTheRecognizerNeverAnsweredEndsAsNoFinalWithinSixSeconds() = runTest {
        val p = pipeline()
        p.glassesListenStart(); repeat(20) { p.glassesAudio(pcm) }; p.glassesListenEnd()
        advanceTimeBy(5_999); runCurrent()
        assertTrue(outcomes().isEmpty(), "still waiting for the final")
        advanceTimeBy(2); runCurrent()
        assertEquals(listOf("outcome=NO_FINAL source=GLASSES"), outcomes())
        assertEquals(Overlay.Toast(VoiceCopy.COULDNT_HEAR_HUD), p.overlay.value)
        assertEquals(1, engine.last.cancels, "the silent recognizer is released")
    }

    /** (c) The glasses' pc_listen_end never arrived (and no final): 3 s of no audio, then the 6 s final wait. */
    @Test fun aLostListenEndWithNoFinalEndsAsNoFinalWithinNineSeconds() = runTest {
        val p = pipeline()
        p.glassesListenStart(); repeat(5) { p.glassesAudio(pcm) }
        advanceTimeBy(9_001); runCurrent()
        assertEquals(listOf("outcome=NO_FINAL source=GLASSES"), outcomes())
        assertTrue("glasses audio stopped" in logs)
    }

    /** (c) Audio keeps arriving but nothing ends it and no final comes: capped at 20 s intake + 6 s final wait. */
    @Test fun endlessAudioWithNoFinalIsBoundedAtTwentySixSeconds() = runTest {
        val p = pipeline()
        p.glassesListenStart()
        repeat(259) { p.glassesAudio(pcm); advanceTimeBy(100) }
        runCurrent()
        assertTrue(outcomes().isEmpty())
        repeat(2) { p.glassesAudio(pcm); advanceTimeBy(100) }
        runCurrent()
        assertEquals(listOf("outcome=NO_FINAL source=GLASSES"), outcomes())
    }

    /** A final that never came but a partial did: the ask is answered from the partial (ANSWERED, not NO_FINAL). */
    @Test fun noFinalWithAPartialIsAnswered() = runTest {
        val p = pipeline()
        p.glassesListenStart(); engine.last.listener.onPartial("where did I park"); p.glassesListenEnd()
        advanceTimeBy(6_001); runCurrent()
        assertEquals(listOf("outcome=ANSWERED source=GLASSES"), outcomes())
        assertNoUserText("park")
    }
}
