package com.rokid.context.core.model.context

/** One watch value the phone hub accepted: when, the state the watch reported and its confidence. */
data class WatchSample(val atMs: Long, val state: WatchState, val confidence: Float) {
    /** The motion score 0..1 of this value, or null when it has none (an UNKNOWN state or a non-finite confidence is not a measurement). */
    val score: Double? get() = WatchMotionScore.of(state, confidence)
}

/**
 * How much movement a watch state means, 0..1, times the watch's own confidence (clamped to 0..1). The table is a fixed description, not a tunable: a still or asleep watch is a REAL zero
 * (the wearer is not moving), while UNKNOWN has no score at all (no data, never a zero). The tuning of what counts as moving lives in `proc.motion_model`, not here.
 */
object WatchMotionScore {
    val LEVEL: Map<WatchState, Double> = mapOf(
        WatchState.STILL to 0.0, WatchState.ASLEEP to 0.0, WatchState.IN_VEHICLE to 0.2, WatchState.WALKING to 0.5,
        WatchState.CYCLING to 0.7, WatchState.EXERCISE to 0.8, WatchState.RUNNING to 1.0,
    )

    fun of(state: WatchState, confidence: Float): Double? {
        val level = LEVEL[state] ?: return null
        if (!confidence.isFinite()) return null
        return level * confidence.toDouble().coerceIn(0.0, 1.0)
    }
}

/**
 * The recent watch values the context hub accepted, newest last, in memory only (a restart empties it; nothing is stored). It exists so a canvas run can read "the watch motion of the
 * last N minutes": the fusion keeps only the latest value. Bounded by [max] (the oldest drops first). Thread-safe.
 */
class WatchMotionRing(private val max: Int = MAX) {
    private val items = ArrayDeque<WatchSample>()

    @Synchronized fun add(s: WatchSample) {
        items.addLast(s)
        while (items.size > max) items.removeFirst()
    }

    /** The values with `fromMs <= atMs <= toMs`, in the order they arrived. */
    @Synchronized fun recent(fromMs: Long, toMs: Long): List<WatchSample> = items.filter { it.atMs in fromMs..toMs }

    @Synchronized fun clear() = items.clear()

    companion object { const val MAX = 120 }
}
