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
        AskActions.ZONE_PAUSED -> AskActions.ZONE_PAUSED // Z1: the refusal, on the HUD and spoken
        else -> VoiceCopy.FAILED_HUD
    }

    fun forPhone(phoneMessage: String): String = when (phoneMessage) {
        AskActions.EMPTY_QUESTION -> VoiceCopy.COULDNT_HEAR_PHONE
        AskActions.NO_MEMORIES, AskActions.AI_UNAVAILABLE, AskActions.FAILED, AskActions.BATTERY_SAVER, AskActions.ZONE_PAUSED -> phoneMessage
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
    /** Z1: true = a pause zone is active (or unsure) NOW: the answer, though saved, is not delivered to the glasses (HUD or voice); the refusal is shown instead. */
    private val deliveryBlocked: () -> Boolean = { false },
) : Answerer {
    override suspend fun answer(question: String, source: VoiceSource): AnswerResult = when (val r = ask(question, source.askSource)) {
        is AskOutcome.Answered -> if (r.deliveryWithheld || deliveryBlocked()) zoneRefusal() else {
            val notice = r.proposalId?.let { offNotice(it) }
            // stored: the text was read from the ask's own row (an off notice comes from the proposal, which proves nothing about the ask row)
            val text = (notice ?: answerText(r.askId))?.takeIf { it.isNotBlank() }
            // Codex z1 r1 P1-3: offNotice and answerText SUSPEND (database reads): the zone is asked again here, after the last of them, as the last step before the result leaves
            if (deliveryBlocked()) zoneRefusal()
            else text?.let { AnswerResult.Answered(r.askId, it, photoLabel = r.photoAtMs?.let(::photoLabel), route = r.route, stored = notice == null) }
                ?: AnswerResult.Failed(VoiceCopy.FAILED_HUD, AskActions.FAILED)
        }
        is AskOutcome.Failed -> AnswerResult.Failed(HudAskText.forFailure(r.message), HudAskText.forPhone(r.message))
    }

    private fun zoneRefusal() = AnswerResult.Failed(HudAskText.forFailure(AskActions.ZONE_PAUSED), HudAskText.forPhone(AskActions.ZONE_PAUSED))

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

/**
 * The Home chat's delivery to the glasses HUD (v0.9.8 slice E): a typed ask's answer is mirrored when its [AskOutcome.Answered.route] puts it on the glasses
 * ([com.rokid.context.core.model.DeliveryRoute.mirrorsChatToHud]; no route = today's mirror), the same DeliveryRoute the voice pipeline follows. At most one mirror per answer.
 */
fun mirrorTypedAnswer(outcome: AskOutcome, blocked: () -> Boolean = { false }, mirror: (askId: String) -> Unit) {
    // Z1: withheld while a pause zone is active (the answer stays readable on the phone)
    if (outcome is AskOutcome.Answered && !outcome.deliveryWithheld && !blocked() && (outcome.route?.mirrorsChatToHud ?: true)) mirror(outcome.askId)
}
