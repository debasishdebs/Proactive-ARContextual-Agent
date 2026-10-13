package com.rokid.context.phone.ui.home

import com.rokid.context.memory.db.AskEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.NudgeEntity
import com.rokid.context.phone.ui.status.StatusUi
import com.rokid.context.voice.PhoneVoiceState
import com.rokid.context.voice.VoiceStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

sealed interface FeedItem {
    val id: String
    val atMs: Long
    val timeLabel: String

    data class Ask(override val id: String, override val atMs: Long, override val timeLabel: String, val question: String, val answer: String, val redacted: Boolean) : FeedItem
    data class Nudge(override val id: String, override val atMs: Long, override val timeLabel: String, val text: String, val why: String, val redacted: Boolean) : FeedItem
}

/** "For you" feed (spec §2.4 Home): nudges and past asks, newest first. */
object Feed {
    private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val DATE = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

    fun label(atMs: Long, today: LocalDate, zone: ZoneId): String {
        val t = Instant.ofEpochMilli(atMs).atZone(zone)
        return if (t.toLocalDate() == today) TIME.format(t) else DATE.format(t)
    }

    fun ask(a: AskEntity, today: LocalDate, zone: ZoneId) = FeedItem.Ask(a.id, a.ts, label(a.ts, today, zone), a.question, a.answer, a.redacted)
    fun nudge(n: NudgeEntity, today: LocalDate, zone: ZoneId) = FeedItem.Nudge(n.id, n.ts, label(n.ts, today, zone), n.text, n.why, n.redacted)

    fun build(asks: List<AskEntity>, nudges: List<NudgeEntity>, today: LocalDate, zone: ZoneId, limit: Int = 30): List<FeedItem> =
        (asks.map { ask(it, today, zone) } + nudges.map { nudge(it, today, zone) })
            .sortedWith(compareByDescending<FeedItem> { it.atMs }.thenBy { it.id })
            .take(limit)
}

/** Three chips for the hero (spec §2.4): topics from recent memory tags (English or Hindi words), then static fallbacks. */
object SuggestedQuestions {
    val FALLBACKS = listOf("What do I need from the store?", "Where did I leave my keys?", "What did I eat today?")

    private fun isWordChar(c: Char): Boolean {
        val type = Character.getType(c)
        return Character.isLetter(c) || c == ' ' || type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt()
    }

    fun from(recent: List<MemoryEntity>): List<String> {
        val topics = recent.asSequence()
            .flatMap { it.tags.split(',').asSequence() }
            .map { it.trim().lowercase() }
            .filter { it.length in 2..24 && it.all(::isWordChar) }
            .distinct()
            .take(3)
            .map { "What about $it?" }
            .toList()
        return (topics + FALLBACKS).distinct().take(3)
    }
}

data class HomeUiState(
    val status: StatusUi = StatusUi.Initial,
    val suggestions: List<String> = SuggestedQuestions.FALLBACKS,
    val feed: List<FeedItem> = emptyList(),
    val question: String = "",
    val asking: Boolean = false,
    val askError: String? = null,
    val lastAnswer: FeedItem.Ask? = null,
    /** The hero mic (spec §2.4, §5.1.1): phone voice through the on-device recognizer only. */
    val voice: HomeVoiceUi = HomeVoiceUi(),
)

/**
 * What the hero mic shows. [enabled] with [needsPermission] = a tap asks for RECORD_AUDIO instead of listening.
 * [listening] (the user is still speaking) and [thinking] (the words are sent) never hold together; while thinking the
 * mic cancels. [blocked] = permanently denied: the mic opens the app's settings instead.
 */
data class HomeVoiceUi(
    val enabled: Boolean = false,
    val listening: Boolean = false,
    val thinking: Boolean = false,
    val partial: String = "",
    val needsPermission: Boolean = false,
    val hint: String = HINT_TYPE,
    val error: String? = null,
    val micOff: Boolean = false,
    val blocked: Boolean = false,
) {
    companion object {
        const val HINT_READY = "or tap your glasses"
        const val HINT_TYPE = "Type a question below, or tap your glasses"
        const val HINT_LISTENING = "Listening\u2026 tap the mic to send"
        const val HINT_THINKING = "Thinking\u2026"
        const val HINT_PERMISSION = "Allow the microphone to ask by voice"
        const val HINT_PACK = "Download the speech pack in Settings to ask by voice"
        const val HINT_OFF = "The phone mic is off in Settings \u2014 type a question below"
        const val HINT_LOADING = "Voice is still loading\u2026"
        const val HINT_BLOCKED = "The microphone is blocked for this app"
        const val OPEN_APP_SETTINGS = "Open app settings"

        /** [micBlocked]: RECORD_AUDIO was permanently denied (only meaningful while the gate still needs it). */
        fun from(v: PhoneVoiceState, micBlocked: Boolean = false): HomeVoiceUi {
            val off = v.micOff && !v.settingsLoading
            return when {
                v.listening -> HomeVoiceUi(enabled = true, listening = true, partial = v.partial, hint = HINT_LISTENING)
                v.thinking -> HomeVoiceUi(enabled = true, thinking = true, hint = HINT_THINKING, error = v.error, micOff = off)
                v.settingsLoading -> HomeVoiceUi(hint = HINT_LOADING, error = v.error)
                v.micOff -> HomeVoiceUi(hint = HINT_OFF, error = v.error, micOff = true)
                v.status == VoiceStatus.SUPPORTED -> HomeVoiceUi(enabled = true, hint = HINT_READY, error = v.error)
                v.status == VoiceStatus.NEEDS_MIC_PERMISSION -> HomeVoiceUi(
                    enabled = true, needsPermission = true, blocked = micBlocked,
                    hint = if (micBlocked) HINT_BLOCKED else HINT_PERMISSION, error = v.error,
                )
                v.status == VoiceStatus.NEEDS_SPEECH_PACK -> HomeVoiceUi(hint = HINT_PACK, error = v.error)
                else -> HomeVoiceUi(hint = HINT_TYPE, error = v.error)
            }
        }
    }
}
