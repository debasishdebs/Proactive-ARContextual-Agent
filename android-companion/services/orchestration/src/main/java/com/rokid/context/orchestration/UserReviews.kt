package com.rokid.context.orchestration

import android.content.Context
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.SummaryTraceEntity

/**
 * Memories a 👎 sent to NEEDS_REVIEW (fix round 1 I7). They belong to the critic review, so the periodic critic scan
 * never feeds them to a summary. Kept as `summary_trace` rows `user_review:<memoryId>` (ids only; schema v1 has no
 * column for it; Delete all clears them with the traces). Cleared when the critic review passes.
 */
object UserReviews {
    private const val PREFIX = "user_review:"
    const val STATE = "USER_REVIEW"

    suspend fun mark(context: Context, ids: Collection<String>, nowMs: Long = System.currentTimeMillis()) {
        val traces = MemoryGraph.get(context).db.traceDao()
        ids.forEach { traces.upsert(SummaryTraceEntity("$PREFIX$it", STATE, nowMs, "Sent to review by an unhelpful answer")) }
    }

    suspend fun clear(context: Context, ids: Collection<String>) {
        if (ids.isNotEmpty()) MemoryGraph.get(context).db.traceDao().deleteByIds(ids.map { "$PREFIX$it" })
    }

    suspend fun marked(context: Context): Set<String> =
        MemoryGraph.get(context).db.traceDao().recent(Int.MAX_VALUE).filter { it.summaryId.startsWith(PREFIX) }.map { it.summaryId.removePrefix(PREFIX) }.toSet()
}
