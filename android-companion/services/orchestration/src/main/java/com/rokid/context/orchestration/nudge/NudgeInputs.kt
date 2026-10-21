package com.rokid.context.orchestration.nudge

import com.rokid.context.connectors.ContextSourceProvider
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.orchestration.AskText
import com.rokid.context.prompt.PromptComposer
import com.rokid.context.prompt.PromptVars
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The NUDGE prompt's inputs (R-61), shared by production and the test bed: OK memories of the last 6 h (newest 10,
 * `id=… · HH:mm · <text ≤ 300>`) as `memories`, the last 24 h of nudge texts as `recent_nudges` (both in
 * `{{recent_context}}`), and `sources.lines(null, now)` as `sources` in `{{context_sources}}`.
 */
class NudgeInputs(
    private val memory: MemoryGraph,
    private val sources: ContextSourceProvider,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    data class Prepared(val vars: PromptVars, val inputIds: Set<String>)

    suspend fun build(nowMs: Long, extraText: String? = null): Prepared {
        val candidates = memory.db.memoryDao().between(nowMs - WINDOW_MS, nowMs + 1).filter { it.reviewState == ReviewState.OK }
        // A memory about an old photo (a night describe or a late backlog extraction: its photo was captured more than the window before the memory
        // was written) is not a live moment, whatever its createdAt says. frame_meta outlives the photo, so the check survives expiry.
        val captured = candidates.mapNotNull { it.frameId }.distinct().chunked(500).flatMap { memory.db.frameMetaDao().byIds(it) }.associate { it.frameId to it.createdAt }
        val recent = candidates.filter { m -> m.frameId?.let { captured[it] }?.let { m.createdAt - it <= WINDOW_MS } ?: true }.take(MAX_MEMORIES)
        val time = TIME.withZone(zone())
        val lines = recent.map { "id=${it.id} \u00b7 ${time.format(Instant.ofEpochMilli(it.createdAt))} \u00b7 ${AskText.clip(AskText.collapse(it.text), MAX_TEXT)}" } +
            listOfNotNull(extraText?.takeIf { it.isNotBlank() })
        val nudges = memory.db.nudgeGateDao().lastNudges(nowMs - DAY_MS).map { AskText.collapse(it.text) }
        return Prepared(
            PromptVars(
                recentContext = PromptComposer.dataBlock("memories", lines) + "\n" + PromptComposer.dataBlock("recent_nudges", nudges),
                contextSources = PromptComposer.dataBlock("sources", sources.lines(null, nowMs)),
            ),
            recent.map { it.id }.toSet(),
        )
    }

    companion object {
        private const val WINDOW_MS = 6 * 3_600_000L
        private const val DAY_MS = 24 * 3_600_000L
        private const val MAX_MEMORIES = 10
        private const val MAX_TEXT = 300
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    }
}
