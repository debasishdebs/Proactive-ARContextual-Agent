package com.rokid.context.work.frames.place

import com.rokid.context.memory.retrieval.CandidateScorer
import com.rokid.context.memory.retrieval.MatchThresholds

/**
 * v18 WP12b: the Place Guide CENTROID shadow. A captured frame's vector is scored against each user pin's Scene centroid (the centroid reference mode of `MatchCalibration`: one vector per place, the mean of its
 * Scene's frames) and ONE counts-only `place shadow3` line is logged. Behind the Developer flag `placeShadowCentroid` ([enabled], default OFF). It never changes a card, a call, an outcome or anything the user
 * sees; a failure is swallowed by the caller. The line carries NO id, name, coordinate or per-place score: counts, a score bucket and margin bucket of the top-1 only, and the candidate band as a word.
 * Thresholds stay unset ([MatchThresholds.VERSION] 0): the owner chooses them after real data (offline margin sweep in `MatchCalibration`).
 */
class CentroidShadow(
    private val enabled: () -> Boolean,
    /** pinId -> the pin's CURRENT Scene centroid in the embedding space [spaceId] (stale rows are never returned), plus how many of the asked pins had none. */
    private val centroids: suspend (spaceId: String, pinIds: List<String>) -> PinCentroids,
    private val log: (String) -> Unit,
    private val config: CentroidShadowConfig = CentroidShadowConfig(),
) {
    class PinCentroids(val byPin: Map<String, FloatArray>)

    suspend fun observe(query: FloatArray, spaceId: String, pinIds: List<String>, live: () -> Boolean) {
        if (!enabled() || pinIds.isEmpty() || !live()) return
        val found = centroids(spaceId, pinIds).byPin
        if (!live()) return
        val r = CandidateScorer.score(query, found.map { (id, v) -> CandidateScorer.Candidate(id, imageRefs = listOf(v)) })
        val top = r.top1?.milli
        val space = MatchThresholds.spaceOf(spaceId)
        val spaceName = space?.name?.lowercase() ?: "na"
        val band = if (space == null) "na" else MatchThresholds.band(spaceId, r).name.lowercase()
        val medium = top != null && top >= config.mediumLowMilli && top < config.mediumHighMilli
        val scoreBucket = top?.let { PlaceShadow.scoreBucket(it) } ?: "na"
        val marginBucket = PlaceShadow.marginBucket(r.margin)
        val noCentroid = pinIds.size - found.size
        // One literal (LogContentScan): counts, enum words and buckets only; never an id, a name, a coordinate or a per-place score.
        log("place shadow3 v=1 thrV=${MatchThresholds.VERSION} space=$spaceName pins=${pinIds.size} withCentroid=${found.size} noCentroid=$noCentroid ranked=${r.ranked.size} score=$scoreBucket margin=$marginBucket band=$band medium=$medium")
    }
}

/**
 * The cut-offs of the medium-score candidate band, as named fields so a later Settings screen (and canvas v2) can set them; no UI now. Owner (26 Nov, round 5): the defaults are decided now and the values
 * configurable later. These are STARTING values for the shadow line's `medium=` flag only, NOT accept thresholds (those are [MatchThresholds], unset).
 */
data class CentroidShadowConfig(val mediumLowMilli: Int = MEDIUM_LOW_MILLI, val mediumHighMilli: Int = MEDIUM_HIGH_MILLI) {
    companion object {
        const val MEDIUM_LOW_MILLI = 500
        const val MEDIUM_HIGH_MILLI = 800
    }
}
