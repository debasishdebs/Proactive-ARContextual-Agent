package com.rokid.context.phone.voice

import com.rokid.context.orchestration.AskActions
import com.rokid.context.orchestration.AskOutcome
import com.rokid.context.voice.AnswerResult
import com.rokid.context.voice.VoiceSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AppAnswererTest {
    private val asked = mutableListOf<Pair<String, String>>()
    private fun answerer(outcome: AskOutcome, text: String? = "Milk and eggs.") =
        AppAnswerer(ask = { q, source -> asked += q to source; outcome }, answerText = { text })

    @Test fun anAnswerCarriesItsTextAndTheVoiceSource() = runBlocking {
        assertEquals(AnswerResult.Answered("a1", "Milk and eggs."), answerer(AskOutcome.Answered("a1")).answer("milk?", VoiceSource.GLASSES))
        assertEquals(listOf("milk?" to "glasses_voice"), asked)
    }

    /** "note that…" (user notes): an ask whose action is turned off answers (HUD and TTS) with the off notice. */
    @Test fun aTurnedOffActionAnswersWithTheOffNotice() = runBlocking {
        val a = AppAnswerer(ask = { _, _ -> AskOutcome.Answered("a1", proposalId = "p1") }, answerText = { "Noted." }, offNotice = { id -> "Notes are off.".takeIf { id == "p1" } })
        assertEquals(AnswerResult.Answered("a1", "Notes are off."), a.answer("note that x", VoiceSource.GLASSES))
        val on = AppAnswerer(ask = { _, _ -> AskOutcome.Answered("a1", proposalId = "p1") }, answerText = { "Noted." }, offNotice = { null })
        assertEquals(AnswerResult.Answered("a1", "Noted."), on.answer("note that x", VoiceSource.GLASSES))
    }

    @Test fun failuresBecomeShortHudTextAndThePhonesFixedMessage() = runBlocking {
        assertEquals(AnswerResult.Failed("AI unavailable", AskActions.AI_UNAVAILABLE), answerer(AskOutcome.Failed(AskActions.AI_UNAVAILABLE)).answer("q", VoiceSource.PHONE))
        assertEquals(AnswerResult.Failed("Nothing on that yet \u2014 I'll check your next photos", AskActions.NO_MEMORIES), answerer(AskOutcome.Failed(AskActions.NO_MEMORIES)).answer("q", VoiceSource.PHONE))
        assertEquals(AnswerResult.Failed("Couldn't hear that", "Couldn't hear that \u2014 try again"), answerer(AskOutcome.Failed(AskActions.EMPTY_QUESTION)).answer("q", VoiceSource.PHONE))
        assertEquals(AnswerResult.Failed("Couldn't answer that", AskActions.FAILED), answerer(AskOutcome.Failed(AskActions.FAILED)).answer("q", VoiceSource.PHONE))
        assertEquals(AnswerResult.Failed("Couldn't answer that", AskActions.FAILED), answerer(AskOutcome.Answered("a1"), text = null).answer("q", VoiceSource.PHONE))
    }

    /** Carry (Task 8): only fixed copy reaches the HUD or Home, even if a failure ever carried other text. */
    @Test fun anUnknownFailureMessageNeverReachesTheHudOrHome() = runBlocking {
        val r = answerer(AskOutcome.Failed("HTTP 500: upstream said something private")).answer("q", VoiceSource.GLASSES)
        assertEquals(AnswerResult.Failed("Couldn't answer that", AskActions.FAILED), r)
        assertEquals(AnswerResult.Failed("Couldn't answer that", AskActions.FAILED), answerer(AskOutcome.Answered("a1"), text = "  ").answer("q", VoiceSource.PHONE))
    }
}
