package com.rokid.context.work.frames.place

import com.rokid.context.memory.ImageTextEmbedder
import com.rokid.context.memory.place.PlaceEntity
import com.rokid.context.memory.place.UserPlace
import com.rokid.context.memory.retrieval.CandidateScorer
import kotlinx.coroutines.CancellationException

/** What the shadow hook gives back for one attempt. [OFF] adds nothing to the log line; [UNAVAILABLE] logs `shadow=na`; an exception or a timeout is logged `shadow=err` by the sampler. */
class ShadowOutcome private constructor(val kind: Kind, val result: CandidateScorer.Result?, val embedMs: Long) {
    enum class Kind { OFF, UNAVAILABLE, SCORED }
    companion object {
        val OFF = ShadowOutcome(Kind.OFF, null, -1L)
        val UNAVAILABLE = ShadowOutcome(Kind.UNAVAILABLE, null, -1L)
        fun scored(r: CandidateScorer.Result, embedMs: Long) = ShadowOutcome(Kind.SCORED, r, embedMs.coerceAtLeast(0L))
    }
}

/**
 * Shadow scoring of the candidates [PlaceSampler] already sends to the model (1.4.4 slice A). It reads the dwell photo, embeds it, embeds a text anchor (`name. kind`) per candidate and ranks them with
 * [CandidateScorer]. Nothing is stored: the vectors live in locals only, the photo is not kept and no cache exists. [imageRefs] is the seam for a pin's reference photos (slice B; empty today, so only the text
 * channel is scored). The sampler bounds the call with a timeout and treats any exception as `shadow=err`; this class never touches an outcome.
 */
class PlaceShadow(
    private val enabled: () -> Boolean,
    private val embedder: suspend () -> ImageTextEmbedder?,
    /** The dwell photo's JPEG bytes, or null when it is gone (forgotten, deleted, unreadable). */
    private val photo: suspend (frameId: String) -> ByteArray?,
    private val imageRefs: suspend (PlaceEntity, ImageTextEmbedder) -> List<FloatArray> = { _, _ -> emptyList() },
    private val elapsed: () -> Long,
) {
    suspend fun score(frameId: String, hits: List<Pair<PlaceEntity, Double>>): ShadowOutcome {
        if (!enabled()) return ShadowOutcome.OFF
        val e = embedder() ?: return ShadowOutcome.UNAVAILABLE
        val jpeg = photo(frameId) ?: return ShadowOutcome.UNAVAILABLE
        val t0 = elapsed()
        val q = e.embedImage(jpeg)
        val cands = hits.map { (p, d) ->
            val text = try { e.embedText(anchorText(p)) } catch (c: CancellationException) { throw c } catch (_: Exception) { null }
            CandidateScorer.Candidate(p.id, imageRefs = if (UserPlace.isUserId(p.id)) imageRefs(p, e) else emptyList(), textAnchor = text, edgeGapM = (d - p.radiusM).coerceAtLeast(0.0), distanceM = d)
        }
        return ShadowOutcome.scored(CandidateScorer.score(q, cands), elapsed() - t0)
    }

    companion object {
        /** `name. kind` (plan §3.2 without the note or summary: the pack place text, nothing personal beyond the name the model already receives). */
        internal fun anchorText(p: PlaceEntity) = "${p.name.take(120)}. ${p.kind.replace('_', ' ')}"

        /** Log buckets: no raw score. [milli] is cosine x 1000. */
        internal fun scoreBucket(milli: Int): String = when { milli < 200 -> "lt200"; milli < 300 -> "lt300"; milli < 400 -> "lt400"; milli < 500 -> "lt500"; milli < 600 -> "lt600"; milli < 700 -> "lt700"; milli < 800 -> "lt800"; milli < 900 -> "lt900"; else -> "ge900" }
        internal fun marginBucket(milli: Int?): String = when { milli == null -> "na"; milli < 10 -> "lt10"; milli < 25 -> "lt25"; milli < 50 -> "lt50"; milli < 100 -> "lt100"; else -> "ge100" }
    }
}
