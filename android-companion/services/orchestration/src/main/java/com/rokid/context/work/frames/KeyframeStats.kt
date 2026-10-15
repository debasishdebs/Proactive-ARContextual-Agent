package com.rokid.context.work.frames

import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.model.CallClass
import com.rokid.context.work.budget.BudgetStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** What Settings > Diagnostics shows for the keyframe pipeline (spec §P2.1 Diagnostics); the UI is P6b. */
data class KeyframeStats(
    /** Rows per `frame_state` state (PENDING, EXTRACTING, EXTRACTED, FAILED, FAILED_FINAL ...). */
    val counts: Map<String, Int> = emptyMap(),
    /** AI calls started in the last hour, by class. */
    val callsPerHour: Map<CallClass, Int> = emptyMap(),
    val keyframesPerHour: Int = 0,
    /** Frames waiting: PENDING + FAILED + EXTRACTING. */
    val backlog: Int = 0,
    /** Capture time (offset-corrected) to memory saved. Null until a frame has been processed. */
    val latencyP50Ms: Long? = null,
    val latencyP95Ms: Long? = null,
)

/** The last [max] capture-to-memory latencies; percentiles are nearest-rank. */
class LatencyTracker(private val max: Int = 200) {
    private val state = MutableStateFlow<List<Long>>(emptyList())
    val samples: StateFlow<List<Long>> = state.asStateFlow()

    /**
     * [receiptLagMs] = receipt time minus capture time. A frame that reached the phone long after it was taken (an adopted or
     * backfilled legacy frame) says nothing about capture-to-memory latency and is left out; null (no receipt time) is left out too.
     */
    fun record(ms: Long, receiptLagMs: Long? = 0L) {
        if (receiptLagMs == null || receiptLagMs > MAX_RECEIPT_LAG_MS) return
        state.update { (it + ms.coerceAtLeast(0L)).takeLast(max) } // atomic: the ask and the loop record at once
    }

    companion object {
        const val MAX_RECEIPT_LAG_MS = 10 * 60_000L

        fun percentile(values: List<Long>, p: Int): Long? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            return sorted[((p / 100.0) * sorted.size).toInt().coerceAtLeast(1).coerceAtMost(sorted.size) - 1]
        }
    }
}

object KeyframeStatsReporter {
    fun create(scope: CoroutineScope, db: ContextDatabase, budget: StateFlow<BudgetStats>, latency: LatencyTracker, clock: () -> Long): StateFlow<KeyframeStats> =
        combine(db.frameStateDao().counts(), budget, latency.samples) { counts, b, lat ->
            KeyframeStats(
                counts = counts,
                callsPerHour = b.callsLastHour,
                keyframesPerHour = db.frameMetaDao().countSince(clock() - 3_600_000L),
                backlog = listOf("PENDING", "FAILED", "EXTRACTING").sumOf { counts[it] ?: 0 },
                latencyP50Ms = LatencyTracker.percentile(lat, 50),
                latencyP95Ms = LatencyTracker.percentile(lat, 95),
            )
        }.stateIn(scope, SharingStarted.Eagerly, KeyframeStats())
}
