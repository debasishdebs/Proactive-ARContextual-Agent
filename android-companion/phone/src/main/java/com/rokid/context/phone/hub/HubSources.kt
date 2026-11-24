package com.rokid.context.phone.hub

import android.content.Context
import com.rokid.context.core.model.Health
import com.rokid.context.core.model.MemoryLine
import com.rokid.context.data.runtime.QueueRuntimeStore
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.orchestration.PipelineRecords

/** What a [StateFrame][com.rokid.context.core.model.StateFrame] reads from the rest of the app. Called on IO. */
interface HubSources {
    suspend fun today(): List<MemoryLine>
    suspend fun queueDepth(): Int
    suspend fun aiHealth(nowMs: Long): Health
}

/** Phase 3 sources: Room memories, the legacy queue file and summary traces. */
class LegacyHubSources(private val context: Context) : HubSources {
    // Retention v3 WP3: a demoted fact (SHORT, past the Quick recall window, no vector) is not a Home line; it stays in Moment / photo detail.
    override suspend fun today(): List<MemoryLine> = MemoryGraph.get(context).db.retrievalDao().recentVisible(
        24, Long.MAX_VALUE,
        com.rokid.context.memory.retrieval.RecallWindow.of(com.rokid.context.memory.MemorySettingsStore.get(context)).sinceMs(System.currentTimeMillis()) ?: com.rokid.context.memory.db.Demotion.NO_WINDOW,
    ).asSequence()
        .filter { it.reviewState == ReviewState.OK && !it.type.contains("review", ignoreCase = true) }
        .take(8)
        .map { MemoryLine(it.id, it.createdAt, it.text.take(80)) }
        .toList()

    override suspend fun queueDepth(): Int = QueueRuntimeStore.snapshot(context).totalPending

    override suspend fun aiHealth(nowMs: Long): Health = AiHealthRule.from(PipelineRecords.recentTraces(context, 20), nowMs)
}
