package com.rokid.context.memory.retrieval

/** Where a reference photo vector of a place comes from. Each kind has its own score range, so each has its own numbers. */
enum class RefKind { OWN, OWN_CENTROID, WEB }

/**
 * HIGH / FLOOR / MARGIN of one reference kind, in milli (cosine x 1000).
 * [high]: at or above it a place is accepted at once (when it also leads by [margin]). [floor]: below it the photo has evidence AGAINST the place and it leaves the candidate list; [FLOOR_OFF] (0) = never
 * drop a place because of this kind. [margin]: the accepted place must lead the best other place by at least this much.
 */
data class KindThresholds(val high: Int, val floor: Int, val margin: Int) {
    /** Always valid: high in [HIGH_MIN, HIGH_MAX]; floor 0 (off) or in [HIGH_MIN, high]; margin in [0, MARGIN_MAX]. */
    fun clamped(): KindThresholds {
        val h = high.coerceIn(HIGH_MIN, HIGH_MAX)
        val f = if (floor <= FLOOR_OFF) FLOOR_OFF else floor.coerceIn(HIGH_MIN, h)
        return KindThresholds(h, f, margin.coerceIn(0, MARGIN_MAX))
    }
    val floorOff: Boolean get() = floor <= FLOOR_OFF

    companion object {
        const val FLOOR_OFF = 0
        const val HIGH_MIN = 500
        const val HIGH_MAX = 999
        const val MARGIN_MAX = 200
    }
}

/**
 * The nine numbers of Layer 1 (3 kinds x HIGH / FLOOR / MARGIN). The defaults are EXAMPLE values from the Mac runs of 26 Nov (`calibration-1.4.4-real-2026-11-26.md`): example, NOT calibrated. The owner recalibrates from
 * the export; [MatchThresholds.VERSION] says which set of defaults a log line was written with.
 */
data class PhotoMatchConfig(val own: KindThresholds = DEFAULT_OWN, val ownCentroid: KindThresholds = DEFAULT_OWN_CENTROID, val web: KindThresholds = DEFAULT_WEB) {
    fun of(kind: RefKind): KindThresholds = when (kind) { RefKind.OWN -> own; RefKind.OWN_CENTROID -> ownCentroid; RefKind.WEB -> web }
    fun clamped() = PhotoMatchConfig(own.clamped(), ownCentroid.clamped(), web.clamped())

    companion object {
        /** example, not calibrated */
        val DEFAULT_OWN = KindThresholds(MatchThresholds.OWN_HIGH_MILLI, MatchThresholds.OWN_FLOOR_MILLI, MatchThresholds.OWN_MARGIN_MILLI)
        /** example, not calibrated */
        val DEFAULT_OWN_CENTROID = KindThresholds(MatchThresholds.CENTROID_HIGH_MILLI, MatchThresholds.CENTROID_FLOOR_MILLI, MatchThresholds.CENTROID_MARGIN_MILLI)
        /** example, not calibrated (no web data at all yet; the floor is off) */
        val DEFAULT_WEB = KindThresholds(MatchThresholds.WEB_HIGH_MILLI, KindThresholds.FLOOR_OFF, MatchThresholds.WEB_MARGIN_MILLI)
        val DEFAULT = PhotoMatchConfig()
    }
}

/** How several frames of one glance become the query. */
enum class QueryMode { MEAN, BEST_OF_K }

/** Multi-frame query settings. Defaults (20 s, 4 extra frames, 920 milli, mean) are first guesses, "example, not calibrated". */
data class MultiFrameConfig(val windowMs: Long = WINDOW_MS, val maxExtra: Int = MAX_EXTRA, val minSimilarityMilli: Int = MIN_SIMILARITY_MILLI, val mode: QueryMode = QueryMode.MEAN) {
    companion object {
        const val WINDOW_MS = 20_000L
        const val MAX_EXTRA = 4
        const val MIN_SIMILARITY_MILLI = 920
    }
}
