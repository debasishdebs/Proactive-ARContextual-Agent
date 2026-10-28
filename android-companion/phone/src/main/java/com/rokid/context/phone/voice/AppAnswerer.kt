package com.rokid.context.phone.voice

import com.rokid.context.orchestration.AskActions
import com.rokid.context.orchestration.AskOutcome
import com.rokid.context.voice.AnswerResult
import com.rokid.context.voice.Answerer
import com.rokid.context.voice.VoiceCopy
import com.rokid.context.voice.VoiceSource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Short HUD versions (spec §5.1 step 5: "AI unavailable") and Home versions of the answer path's fixed messages. Any
 * other text maps to the fixed "couldn't answer" copy, so nothing but fixed strings reaches the HUD or Home (R-72).
 */
object HudAskText {
    fun forFailure(phoneMessage: String): String = when (phoneMessage) {
        AskActions.EMPTY_QUESTION -> VoiceCopy.COULDNT_HEAR_HUD
        AskActions.NO_MEMORIES -> "Nothing on that yet \u2014 I'll check your next photos"
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
 * stored answer text for the HUD and TTS. Failures come back as fixed copy only. [offNotice] is the fixed "<type> is off"
 * copy when the ask's proposal was suppressed (its type is turned off): it replaces the model's answer ("Noted."), so the
 * wearer hears and sees why nothing happened.
 */
class AppAnswerer(
    private val ask: suspend (question: String, source: String) -> AskOutcome,
    private val answerText: suspend (askId: String) -> String?,
    private val offNotice: suspend (proposalId: String) -> String? = { null },
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val now: () -> Long = System::currentTimeMillis,
) : Answerer {
    override suspend fun answer(question: String, source: VoiceSource): AnswerResult = when (val r = ask(question, source.askSource)) {
        is AskOutcome.Answered -> (r.proposalId?.let { offNotice(it) } ?: answerText(r.askId))?.takeIf { it.isNotBlank() }?.let { AnswerResult.Answered(r.askId, it, photoLabel = r.photoAtMs?.let(::photoLabel), route = r.route) }
            ?: AnswerResult.Failed(VoiceCopy.FAILED_HUD, AskActions.FAILED)
        is AskOutcome.Failed -> AnswerResult.Failed(HudAskText.forFailure(r.message), HudAskText.forPhone(r.message))
    }

    /** "Photo HH:mm" (or "Photo Fri 14:02" when not from today) in the system zone: the capture time of the first cited stored photo (fixed copy plus a time, never provider text). */
    private fun photoLabel(atMs: Long): String {
        val z = zone()
        val at = Instant.ofEpochMilli(atMs).atZone(z)
        val sameDay = at.toLocalDate() == Instant.ofEpochMilli(now()).atZone(z).toLocalDate()
        return "Photo " + (if (sameDay) LABEL_TIME else LABEL_DAY_TIME).format(at)
    }

    private companion object {
        val LABEL_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
        /** A photo from another day names the weekday, so the label never reads as today (M3). */
        val LABEL_DAY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.US)
    }
}
