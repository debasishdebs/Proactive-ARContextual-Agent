package com.rokid.context.phone.ui.home

import com.rokid.context.memory.db.AskEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.NudgeEntity
import com.rokid.context.phone.ui.status.StatusUi
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
    /** Phone voice arrives with Phase 6 (R-60/R-67); until then the mic is shown disabled. */
    val voiceAvailable: Boolean = false,
)
