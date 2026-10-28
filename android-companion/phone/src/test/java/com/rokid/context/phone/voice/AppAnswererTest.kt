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

    /** v0.9.8 E: a chat answer reaches the HUD by its DeliveryRoute, once; no route keeps today's mirror; a failure never mirrors. */
    @Test fun theChatMirrorsAnAnswerToTheHudOnlyWhenItsRouteSaysSo() {
        val mirrored = mutableListOf<String>()
        fun run(o: AskOutcome) { mirrored.clear(); mirrorTypedAnswer(o) { mirrored += it } }
        run(AskOutcome.Answered("a1")); assertEquals(listOf("a1"), mirrored)
        run(AskOutcome.Answered("a2", route = com.rokid.context.core.model.DeliveryRoute(phone = false, glasses = true))); assertEquals(listOf("a2"), mirrored)
        run(AskOutcome.Answered("a3", route = com.rokid.context.core.model.DeliveryRoute(phone = true, glasses = true))); assertEquals(listOf("a3"), mirrored)
        run(AskOutcome.Answered("a4", route = com.rokid.context.core.model.DeliveryRoute(phone = true, glasses = false))); assertEquals(emptyList<String>(), mirrored)
        run(AskOutcome.Answered("a5", route = com.rokid.context.core.model.DeliveryRoute(phone = true, glasses = false, fallback = "glasses_not_connected"))); assertEquals(emptyList<String>(), mirrored)
        run(AskOutcome.Failed("x")); assertEquals(emptyList<String>(), mirrored)
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

    /** Wave 3 Task 3: a stored photo's capture time becomes the HUD label, in the system zone. */
    @Test fun photoLabelFormatsTime() = runBlocking {
        val at = java.time.LocalDateTime.of(2026, 10, 9, 8, 53).atZone(java.time.ZoneOffset.ofHours(2)).toInstant().toEpochMilli()
        val a = AppAnswerer(ask = { _, _ -> AskOutcome.Answered("a1", photoFrameId = "f1", photoAtMs = at) }, answerText = { "On the desk." }, zone = { java.time.ZoneOffset.ofHours(2) }, now = { at + 3_600_000L })
        assertEquals(AnswerResult.Answered("a1", "On the desk.", photoLabel = "Photo 08:53"), a.answer("keys?", VoiceSource.GLASSES))
        assertEquals(AnswerResult.Answered("a1", "Milk and eggs."), answerer(AskOutcome.Answered("a1")).answer("milk?", VoiceSource.GLASSES))
    }

    /** Fix round 1 (M3): a photo from another day names the day, so "Photo 14:02" never reads as today. */
    @Test fun photoLabelNamesTheDayWhenNotToday() = runBlocking {
        val zone = java.time.ZoneOffset.UTC
        val now = java.time.LocalDateTime.of(2026, 10, 12, 10, 0).toInstant(zone).toEpochMilli()
        val old = java.time.LocalDateTime.of(2026, 10, 9, 14, 2).toInstant(zone).toEpochMilli() // a Friday
        val a = AppAnswerer(ask = { _, _ -> AskOutcome.Answered("a1", photoFrameId = "f1", photoAtMs = old) }, answerText = { "On the desk." }, zone = { zone }, now = { now })
        assertEquals("Photo Fri 14:02", (a.answer("keys?", VoiceSource.GLASSES) as AnswerResult.Answered).photoLabel)
    }
}
