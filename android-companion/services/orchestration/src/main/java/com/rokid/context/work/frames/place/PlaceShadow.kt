package com.rokid.context.work.frames.place

import com.rokid.context.memory.ImageTextEmbedder
import com.rokid.context.memory.place.PlaceEntity
import com.rokid.context.memory.place.UserPlace
import com.rokid.context.memory.retrieval.CandidateScorer
import kotlinx.coroutines.CancellationException

/** What the shadow hook gives back for one attempt. [OFF] adds nothing to the log line; [UNAVAILABLE] logs `shadow=na`; an exception or a timeout is logged `shadow=err` by the sampler. */
class ShadowOutcome private constructor(val kind: Kind, val result: CandidateScorer.Result?, val embedMs: Long) {
    /** [DROPPED]: the zone, the switch or a wipe changed while it ran; its result is thrown away and nothing is logged. */
    enum class Kind { OFF, UNAVAILABLE, SCORED, DROPPED }
    companion object {
        val OFF = ShadowOutcome(Kind.OFF, null, -1L)
        val UNAVAILABLE = ShadowOutcome(Kind.UNAVAILABLE, null, -1L)
        val DROPPED = ShadowOutcome(Kind.DROPPED, null, -1L)
        fun scored(r: CandidateScorer.Result, embedMs: Long) = ShadowOutcome(Kind.SCORED, r, embedMs.coerceAtLeast(0L))
    }
}

/**
 * Shadow scoring of the candidates [PlaceSampler] already sends to the model (1.4.4 slice A). It embeds the dwell photo, embeds a text anchor (`name. kind`) per candidate in ONE batch and ranks them with
 * [CandidateScorer]. The photo and its vector live in locals only. The only thing kept is a small in-memory cache of text vectors of PACK places (public names), bounded by [CACHE_MAX], dropped whenever
 * the wipe [generation] changes or the embedding space changes; a user pin's name is never cached. [imageRefs] is the seam for a pin's reference photos (slice B; empty today). The sampler never waits for
 * this class and treats any exception as `shadow=err`; it never touches an outcome.
 */
class PlaceShadow(
    private val embedder: suspend () -> ImageTextEmbedder?,
    /** The dwell photo's JPEG bytes (the one photo the attempt loaded, shared with the model call), or null when it is gone. */
    private val photo: suspend (frameId: String) -> ByteArray?,
    private val imageRefs: suspend (PlaceEntity, ImageTextEmbedder) -> List<FloatArray> = { _, _ -> emptyList() },
    private val elapsed: () -> Long,
    /** The wipe generation (production: `MemoryWipe.generation`); a change empties the text cache. */
    private val generation: () -> Long = { 0L },
) {
    private val cache = object : LinkedHashMap<String, FloatArray>(16, 0.75f, true) { override fun removeEldestEntry(e: MutableMap.MutableEntry<String, FloatArray>?) = size > CACHE_MAX }
    private var cacheGen = Long.MIN_VALUE
    private var cacheSpace = ""

    /** [live] is the attempt's own liveness (ticket, switch, pipeline, wipe generation): asked again after the photo is read and after the embed; false drops the result. */
    suspend fun score(frameId: String, hits: List<Pair<PlaceEntity, Double>>, live: () -> Boolean = { true }): ShadowOutcome {
        val e = embedder() ?: return ShadowOutcome.UNAVAILABLE
        val jpeg = photo(frameId) ?: return ShadowOutcome.UNAVAILABLE
        if (!live()) return ShadowOutcome.DROPPED
        val t0 = elapsed()
        val q = e.embedImage(jpeg)
        val texts = anchors(e, hits)
        val cands = hits.map { (p, d) ->
            CandidateScorer.Candidate(p.id, imageRefs = if (UserPlace.isUserId(p.id)) imageRefs(p, e) else emptyList(), textAnchor = texts[p.id], edgeGapM = (d - p.radiusM).coerceAtLeast(0.0), distanceM = d)
        }
        val result = CandidateScorer.score(q, cands)
        if (!live()) return ShadowOutcome.DROPPED
        return ShadowOutcome.scored(result, elapsed() - t0)
    }

    /** id -> text vector. Cached pack places are not embedded again; the rest go out in ONE batch; a failed batch leaves them without a text channel. */
    private suspend fun anchors(e: ImageTextEmbedder, hits: List<Pair<PlaceEntity, Double>>): Map<String, FloatArray> {
        val out = HashMap<String, FloatArray>()
        val missing = ArrayList<PlaceEntity>()
        synchronized(cache) {
            val g = generation()
            if (g != cacheGen || e.spaceId != cacheSpace) { cache.clear(); cacheGen = g; cacheSpace = e.spaceId }
            for ((p, _) in hits) { val v = if (UserPlace.isUserId(p.id)) null else cache[key(p)]; if (v != null) out[p.id] = v else missing += p }
        }
        if (missing.isEmpty()) return out
        val vecs = try { e.embedTexts(missing.map(::anchorText)) } catch (c: CancellationException) { throw c } catch (_: Exception) { return out }
        if (vecs.size != missing.size) return out
        synchronized(cache) {
            val same = generation() == cacheGen && e.spaceId == cacheSpace
            missing.forEachIndexed { i, p -> out[p.id] = vecs[i]; if (same && !UserPlace.isUserId(p.id)) cache[key(p)] = vecs[i] }
        }
        return out
    }

    private fun key(p: PlaceEntity) = "${p.id}|${p.name}|${p.kind}"

    companion object {
        const val CACHE_MAX = 64

        /** `name. kind` (plan §3.2 without the note or summary: the pack place text, nothing personal beyond the name the model already receives). */
        internal fun anchorText(p: PlaceEntity) = "${p.name.take(120)}. ${p.kind.replace('_', ' ')}"

        /** Log buckets: no raw score. [milli] is cosine x 1000. */
        internal fun scoreBucket(milli: Int): String = when { milli < 200 -> "lt200"; milli < 300 -> "lt300"; milli < 400 -> "lt400"; milli < 500 -> "lt500"; milli < 600 -> "lt600"; milli < 700 -> "lt700"; milli < 800 -> "lt800"; milli < 900 -> "lt900"; else -> "ge900" }
        internal fun marginBucket(milli: Int?): String = when { milli == null -> "na"; milli < 10 -> "lt10"; milli < 25 -> "lt25"; milli < 50 -> "lt50"; milli < 100 -> "lt100"; else -> "ge100" }
    }
}
