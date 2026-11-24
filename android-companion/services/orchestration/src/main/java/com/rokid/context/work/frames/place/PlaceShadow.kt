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
 * the wipe [generation] changes or the embedding space changes; a user pin's name is never cached. [imageRefs] is the seam for a pin's reference photo vectors (slice B1, see [PinReferenceVectors]); a candidate with one is scored on the IMAGE channel, a pack place keeps its text anchor, and the decision channel (`shCh`) is image as soon as one candidate has a reference. The sampler never waits for
 * this class and treats any exception as `shadow=err`; it never touches an outcome.
 */
class PlaceShadow(
    private val embedder: suspend () -> ImageTextEmbedder?,
    /** The dwell photo's JPEG bytes (the one photo the attempt loaded, shared with the model call), or null when it is gone. */
    private val photo: suspend (frameId: String) -> ByteArray?,
    /** The reference photo vectors of the user pins among the candidates, asked ONCE per score (slice B1: [PinReferenceVectors], in memory only, revalidated per use); a pin missing or empty = no image channel for it. [live] is the attempt's fence. Never asked when there is no user pin, never for a pack place. */
    private val imageRefs: suspend (List<PlaceEntity>, ImageTextEmbedder, () -> Boolean) -> Map<String, List<FloatArray>> = { _, _, _ -> emptyMap() },
    /** Asked once after scoring with what [imageRefs] returned: false = a reference photo was Forgotten (or its link, or a wipe) while this ran, and the score is dropped. */
    private val refsStillValid: suspend (Map<String, List<FloatArray>>, ImageTextEmbedder) -> Boolean = { _, _ -> true },
    private val elapsed: () -> Long,
    /** The wipe generation (production: `MemoryWipe.generation`); a change empties the text cache. */
    private val generation: () -> Long = { 0L },
) {
    private val cache = object : LinkedHashMap<String, FloatArray>(16, 0.75f, true) { override fun removeEldestEntry(e: MutableMap.MutableEntry<String, FloatArray>?) = size > CACHE_MAX }
    private var cacheGen = Long.MIN_VALUE
    private var cacheSpace = ""

    /** Empties the pack-place text vectors now (the eager clear, see [ShadowCaches]). */
    fun clear() { synchronized(cache) { cache.clear(); cacheGen = Long.MIN_VALUE; cacheSpace = "" } }

    /** [live] is the attempt's own liveness (ticket, switch, pipeline, wipe generation): asked again after the photo is read and after the embed; false drops the result. */
    suspend fun score(frameId: String, hits: List<Pair<PlaceEntity, Double>>, live: () -> Boolean = { true }): ShadowOutcome {
        if (!live()) return ShadowOutcome.DROPPED // before the embedder is acquired (it may load a model) and before anything is allocated: a fenced attempt, or a flag turned OFF after launch, does no work
        val e = embedder() ?: return ShadowOutcome.UNAVAILABLE
        val jpeg = photo(frameId) ?: return ShadowOutcome.UNAVAILABLE
        if (!live()) return ShadowOutcome.DROPPED
        val t0 = elapsed()
        val q = e.embedImage(jpeg)
        if (!live()) return ShadowOutcome.DROPPED // before the text batch and the cache
        val texts = anchors(e, hits)
        // The pins' reference vectors are asked together, so one cold miss costs one wait, not one per pin. Any failure of one pin leaves only that pin without an image channel.
        val pins = hits.map { it.first }.filter { UserPlace.isUserId(it.id) }
        val refs: Map<String, List<FloatArray>> = if (pins.isEmpty()) emptyMap() else try { imageRefs(pins, e, live) } catch (c: CancellationException) { throw c } catch (_: Exception) { emptyMap() }
        if (!live()) return ShadowOutcome.DROPPED
        val cands = hits.map { (p, d) ->
            CandidateScorer.Candidate(p.id, imageRefs = refs[p.id].orEmpty(), textAnchor = texts[p.id], edgeGapM = (d - p.radiusM).coerceAtLeast(0.0), distanceM = d)
        }
        val result = CandidateScorer.score(q, cands)
        if (!live()) return ShadowOutcome.DROPPED
        // The last word before a score leaves: the reference photos are asked about once more, so a Forget that landed since they were read (single Moment, link change, wipe) drops this score.
        if (refs.values.any { it.isNotEmpty() } && !(try { refsStillValid(refs, e) } catch (c: CancellationException) { throw c } catch (_: Exception) { false })) return ShadowOutcome.DROPPED
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
