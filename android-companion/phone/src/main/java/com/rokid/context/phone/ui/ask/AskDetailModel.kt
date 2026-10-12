package com.rokid.context.phone.ui.ask

import com.rokid.context.memory.db.AskEntity
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.ProposalEntity
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.orchestration.AskSources
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class SourceCard(val memoryId: String, val text: String, val confidence: Double, val framePath: String?)
data class ProposalLine(val title: String, val state: String)

data class AskDetailUiState(
    val loading: Boolean = true,
    val found: Boolean = false,
    val question: String = "",
    val answer: String = "",
    val timeLabel: String = "",
    val redacted: Boolean = false,
    val rating: Int? = null,
    val sources: List<SourceCard> = emptyList(),
    /** Cited ids that no longer resolve to a visible memory (forgotten, invalidated, or never stored). */
    val missingSources: Int = 0,
    val proposal: ProposalLine? = null,
)

object AskDetailModel {
    val NOT_FOUND = AskDetailUiState(loading = false, found = false)
    private val TIME = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", Locale.ENGLISH)

    fun build(ask: AskEntity, memories: List<MemoryEntity>, frames: List<FrameEntity>, proposals: List<ProposalEntity>, zone: ZoneId): AskDetailUiState {
        val ids = if (ask.redacted) emptyList() else AskSources.parse(ask.sourcesJson)
        val byId = memories.associateBy { it.id }
        val paths = frames.associate { it.id to it.path }
        val cards = ids.mapNotNull { id ->
            byId[id]?.takeIf { it.reviewState != ReviewState.INVALIDATED }?.let { m -> SourceCard(m.id, m.text, m.confidence, m.frameId?.let(paths::get)) }
        }
        return AskDetailUiState(
            loading = false, found = true, question = ask.question, answer = ask.answer,
            timeLabel = TIME.format(Instant.ofEpochMilli(ask.ts).atZone(zone)), redacted = ask.redacted, rating = ask.rating,
            sources = cards, missingSources = ids.size - cards.size, proposal = proposals.firstOrNull()?.let(::proposalLine),
        )
    }

    fun proposalLine(p: ProposalEntity) = ProposalLine(
        title = when (p.type) {
            "REMINDER" -> "Reminder"
            "SHOPPING_ADD" -> "Add to shopping list"
            "CALENDAR_EVENT" -> "Calendar event"
            else -> "Suggested action"
        },
        state = when (p.state) {
            "PENDING" -> "Waiting for your answer"
            "EXECUTING" -> "Adding…"
            "EXECUTED" -> "Added"
            "DECLINED" -> "Declined"
            "EXPIRED" -> "Expired"
            "SUPPRESSED" -> "Turned off in Settings"
            "FAILED" -> "Couldn't add"
            else -> ""
        },
    )
}
