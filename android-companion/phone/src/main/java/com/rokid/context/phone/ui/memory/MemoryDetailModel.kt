package com.rokid.context.phone.ui.memory

import com.rokid.context.memory.DayBucket
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class MemoryDetailUiState(
    val loading: Boolean = true,
    val found: Boolean = false,
    val text: String = "",
    val tags: List<String> = emptyList(),
    val tierLabel: String = "",
    val confidence: Double = 0.0,
    val reviewLabel: String = "",
    val trusted: Boolean = false,
    val createdLabel: String = "",
    val framePath: String? = null,
    val frameId: String? = null,
    val frameDate: LocalDate? = null,
    /** Non-null while editing. */
    val draft: String? = null,
    val message: String? = null,
)

object MemoryDetailModel {
    val NOT_FOUND = MemoryDetailUiState(loading = false, found = false)
    private val CREATED = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", Locale.ENGLISH)

    fun build(m: MemoryEntity, frame: FrameEntity?, zone: ZoneId) = MemoryDetailUiState(
        loading = false,
        found = true,
        text = m.text,
        tags = m.tags.split(',').map { it.trim() }.filter { it.isNotEmpty() },
        tierLabel = when (m.tier) { MemoryTier.SHORT -> "Short-term"; MemoryTier.MEDIUM -> "Medium-term"; MemoryTier.LONG -> "Long-term" },
        confidence = m.confidence,
        reviewLabel = when (m.reviewState) { ReviewState.OK -> "Reviewed"; ReviewState.NEEDS_REVIEW -> "Needs review"; ReviewState.INVALIDATED -> "Hidden" },
        trusted = m.trusted,
        createdLabel = CREATED.format(Instant.ofEpochMilli(m.createdAt).atZone(zone)),
        framePath = frame?.path,
        frameId = frame?.id,
        frameDate = frame?.let { DayBucket.dayOf(it.ts, zone) },
    )
}

/** Pinch-zoom bounds for the memory's frame. */
object Zoom {
    const val MIN = 1f
    const val MAX = 5f
    fun clamp(scale: Float): Float = if (scale.isNaN()) MIN else scale.coerceIn(MIN, MAX)
}
