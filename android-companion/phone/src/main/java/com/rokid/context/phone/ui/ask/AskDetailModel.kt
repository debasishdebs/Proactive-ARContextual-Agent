package com.rokid.context.phone.ui.ask

import com.rokid.context.memory.db.AskEntity
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.ProposalEntity
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.forget.PHOTO_CITE_PREFIX
import com.rokid.context.orchestration.AskSources
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A cited memory, or (wave 3) a cited photo: [memoryId] is then `frame:<id>`. [expired] = the photo is gone (metadata only); [photoFrameId] and
 * [photoDate] (yyyy-MM-dd) open the frame viewer and are null for an expired photo.
 */
data class SourceCard(
    val memoryId: String, val text: String, val confidence: Double, val framePath: String?,
    val expired: Boolean = false, val photoFrameId: String? = null, val photoDate: String? = null,
    /** A captioned photo's memory (the usual cite of a photo): its card shows the large thumbnail (spec 3.4). */
    val largeThumb: Boolean = false,
) {
    val isPhotoCite: Boolean get() = memoryId.startsWith(PHOTO_CITE_PREFIX)
    /** The frame id of a photo cite (null for a memory). */
    val citedFrameId: String? get() = memoryId.takeIf { isPhotoCite }?.removePrefix(PHOTO_CITE_PREFIX)
}
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

    /** [photoFrames]: the frame rows of `frame:<id>` sources by frame id; a missing key or null = the photo expired. */
    fun build(
        ask: AskEntity, memories: List<MemoryEntity>, frames: List<FrameEntity>, proposals: List<ProposalEntity>, zone: ZoneId,
        photoFrames: Map<String, FrameEntity?> = emptyMap(),
    ): AskDetailUiState {
        val ids = if (ask.redacted) emptyList() else AskSources.parse(ask.sourcesJson)
        val byId = memories.associateBy { it.id }
        val paths = frames.associate { it.id to it.path }
        val cards = ids.mapNotNull { id ->
            if (id.startsWith(PHOTO_CITE_PREFIX)) return@mapNotNull photoCard(id, photoFrames[id.removePrefix(PHOTO_CITE_PREFIX)], zone)
            byId[id]?.takeIf { it.reviewState != ReviewState.INVALIDATED }?.let { m -> SourceCard(m.id, m.text, m.confidence, m.frameId?.let(paths::get), largeThumb = m.frameId != null && m.type == "caption") }
        }
        return AskDetailUiState(
            loading = false, found = true, question = ask.question, answer = ask.answer,
            timeLabel = TIME.format(Instant.ofEpochMilli(ask.ts).atZone(zone)), redacted = ask.redacted, rating = ask.rating,
            sources = cards, missingSources = ids.size - cards.size, proposal = proposals.firstOrNull()?.let(::proposalLine),
        )
    }

    /** A cited photo is never "missing": a gone photo keeps its card as the metadata-only "Photo expired" state (a Forget redacts the whole answer instead). */
    private fun photoCard(id: String, frame: FrameEntity?, zone: ZoneId) = SourceCard(
        id, "Photo", 1.0, frame?.path, expired = frame == null, photoFrameId = frame?.id,
        photoDate = frame?.let { Instant.ofEpochMilli(it.ts).atZone(zone).toLocalDate().toString() },
    )

    fun proposalLine(p: ProposalEntity) = ProposalLine(
        title = when (p.type) {
            "REMINDER" -> "Reminder"
            "SHOPPING_ADD" -> "Add to shopping list"
            "CALENDAR_EVENT" -> "Calendar event"
            "NOTE_ADD" -> "Save note"
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
