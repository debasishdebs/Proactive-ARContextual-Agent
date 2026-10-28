package com.rokid.context.orchestration.nudge

import com.rokid.context.connectors.ContextSourceProvider
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.orchestration.RagCore
import com.rokid.context.orchestration.RagParams
import com.rokid.context.orchestration.RagQuery
import com.rokid.context.orchestration.RagSource
import com.rokid.context.prompt.PromptComposer
import com.rokid.context.prompt.PromptVars
import java.time.ZoneId

/**
 * The NUDGE prompt's inputs (R-61), shared by production and the test bed: OK memories of the last 6 h, read through [RagCore] (newest 10,
 * `id=… · HH:mm · <text ≤ 300>`) as `memories`, the last 24 h of nudge texts as `recent_nudges` (both in
 * `{{recent_context}}`), and `sources.lines(null, now)` as `sources` in `{{context_sources}}`.
 */
class NudgeInputs(
    private val memory: MemoryGraph,
    private val sources: ContextSourceProvider,
    /** The shared core (v0.9.8); null = one that reads recent memories and nudge history, with no photo store behind it. */
    rag: RagCore? = null,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val core = rag ?: RagCore(memory, zone = zone)

    /** [inputIds]: what the prompt may cite. [fenceIds]: also the sources of the nudges it shows; the commit fence checks both. */
    data class Prepared(val vars: PromptVars, val inputIds: Set<String>, val fenceIds: Set<String> = emptySet())

    /**
     * [photoVector]: a photo's image embedding (a later Nudge trigger, not wired yet). The photos near it join the memory lines as photo lines
     * (same gates as an Ask's photos) and their cite ids join the inputs.
     */
    suspend fun build(nowMs: Long, extraText: String? = null, photoVector: FloatArray? = null): Prepared {
        val sourceList = listOfNotNull(RagSource.RecentWindow(WINDOW_MS), RagSource.NudgeHistory(DAY_MS), RagSource.PhotoStore().takeIf { photoVector != null })
        val c = core.retrieve(RagQuery(vector = photoVector), sourceList, RagParams(maxRecent = MAX_MEMORIES, maxChars = NO_BOUND), nowMs)
        val lines = c.recentLines + c.photoLines + listOfNotNull(extraText?.takeIf { it.isNotBlank() })
        return Prepared(
            PromptVars(
                recentContext = PromptComposer.dataBlock("memories", lines) + "\n" + PromptComposer.dataBlock("recent_nudges", c.history),
                contextSources = PromptComposer.dataBlock("sources", sources.lines(null, nowMs)),
            ),
            (c.recent.map { it.id } + c.photos.map { it.citeId }).toSet(), c.historyIds,
        )
    }

    companion object {
        private const val WINDOW_MS = 6 * 3_600_000L
        private const val DAY_MS = 24 * 3_600_000L
        private const val MAX_MEMORIES = 10
        /** The nudge inputs are bounded by their own counts (10 memories, 20 nudges), so no shared char bound applies. */
        private const val NO_BOUND = 1_000_000
    }
}
