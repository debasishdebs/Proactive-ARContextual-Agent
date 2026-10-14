package com.rokid.context.phone.voice

import com.rokid.context.orchestration.AskActions
import com.rokid.context.orchestration.AskOutcome
import com.rokid.context.voice.AnswerResult
import com.rokid.context.voice.Answerer
import com.rokid.context.voice.VoiceCopy
import com.rokid.context.voice.VoiceSource

/**
 * Short HUD versions (spec §5.1 step 5: "AI unavailable") and Home versions of the answer path's fixed messages. Any
 * other text maps to the fixed "couldn't answer" copy, so nothing but fixed strings reaches the HUD or Home (R-72).
 */
object HudAskText {
    fun forFailure(phoneMessage: String): String = when (phoneMessage) {
        AskActions.EMPTY_QUESTION -> VoiceCopy.COULDNT_HEAR_HUD
        AskActions.NO_MEMORIES -> "Nothing saved yet"
        AskActions.AI_UNAVAILABLE -> "AI unavailable"
        AskActions.BATTERY_SAVER -> AskActions.BATTERY_SAVER
        else -> VoiceCopy.FAILED_HUD
    }

    fun forPhone(phoneMessage: String): String = when (phoneMessage) {
        AskActions.EMPTY_QUESTION -> VoiceCopy.COULDNT_HEAR_PHONE
        AskActions.NO_MEMORIES, AskActions.AI_UNAVAILABLE, AskActions.FAILED, AskActions.BATTERY_SAVER -> phoneMessage
        else -> AskActions.FAILED
    }
}

/**
 * The voice [Answerer] on the phone: `AskActions.askFrom` (Phase 5 answer path, question hygiene included), then the
 * stored answer text for the HUD and TTS. Failures come back as fixed copy only.
 */
class AppAnswerer(
    private val ask: suspend (question: String, source: String) -> AskOutcome,
    private val answerText: suspend (askId: String) -> String?,
) : Answerer {
    override suspend fun answer(question: String, source: VoiceSource): AnswerResult = when (val r = ask(question, source.askSource)) {
        is AskOutcome.Answered -> answerText(r.askId)?.takeIf { it.isNotBlank() }?.let { AnswerResult.Answered(r.askId, it) }
            ?: AnswerResult.Failed(VoiceCopy.FAILED_HUD, AskActions.FAILED)
        is AskOutcome.Failed -> AnswerResult.Failed(HudAskText.forFailure(r.message), HudAskText.forPhone(r.message))
    }
}
