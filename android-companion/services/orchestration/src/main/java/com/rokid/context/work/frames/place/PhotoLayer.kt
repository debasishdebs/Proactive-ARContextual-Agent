package com.rokid.context.work.frames.place

import com.rokid.context.memory.ImageTextEmbedder
import com.rokid.context.memory.place.PlaceEntity
import com.rokid.context.memory.place.UserPlace
import com.rokid.context.memory.retrieval.MultiFrameConfig
import com.rokid.context.memory.retrieval.MultiFrameQuery
import com.rokid.context.memory.retrieval.PhotoConfirm
import com.rokid.context.memory.retrieval.PhotoMatchConfig
import com.rokid.context.memory.retrieval.QueryMode
import com.rokid.context.memory.retrieval.RefKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/** The recent frames of the glasses session of the dwell frame (only those that already have a vector in the active space) and whether the glasses moved meanwhile. */
class Glance(val atMs: Long, val others: List<MultiFrameQuery.Frame>, val moved: Boolean)

/**
 * LAYER 1 of the layered place confirmation (design 2026-11-27), the production source of the vectors for [PhotoConfirm]: the dwell photo (its stored vector when it has one, else ONE embed) plus up to four recent
 * frames of the same glance that already have a vector ([MultiFrameQuery]) against the reference vectors of the candidates: a user pin's reference Moment photo (kind OWN) and its Scene centroid (OWN_CENTROID, the
 * centroid shadow's reader). Seeded places have no reference yet (web photos come with step B2) and so stay in the list. In memory only, nothing stored, nothing logged here: the sampler logs counts and buckets.
 *
 * Cost: at most one image embed per attempt; none at all when no candidate has a reference. A hot or low-power phone ([mayEmbed] false) skips the embed (a stored vector is still used). The whole call is bounded by
 * [budgetMs]; on a timeout the attempt goes on to the other layers (the embed itself is detached in [scope], its late result is dropped).
 */
internal class PhotoLayer(
    private val embedder: suspend () -> ImageTextEmbedder?,
    private val photo: suspend (frameId: String) -> ByteArray?,
    /** pinId -> reference vectors of the pin's Moment photo (kind OWN), as [PinReferenceVectors.vectorsForAll]. */
    private val ownRefs: suspend (pins: List<PlaceEntity>, e: ImageTextEmbedder, live: () -> Boolean) -> Map<String, List<FloatArray>>,
    /** Asked once after scoring: false = a reference photo was Forgotten while this ran, the result is dropped. */
    private val refsStillValid: suspend (Map<String, List<FloatArray>>, ImageTextEmbedder) -> Boolean = { _, _ -> true },
    /** pinId -> the pin's current Scene centroid in [spaceId] (kind OWN_CENTROID). */
    private val centroids: suspend (spaceId: String, pinIds: List<String>) -> Map<String, FloatArray> = { _, _ -> emptyMap() },
    /** The stored vector of a frame in [spaceId], or null. */
    private val storedVector: suspend (frameId: String, spaceId: String) -> FloatArray? = { _, _ -> null },
    private val glance: suspend (frameId: String, spaceId: String, windowMs: Long) -> Glance? = { _, _, _ -> null },
    private val config: () -> PhotoMatchConfig = { PhotoMatchConfig.DEFAULT },
    /** Null = multi-frame OFF (only the dwell frame); else the settings (window, frames, similarity, mean or best-of-k). */
    private val multiFrame: () -> MultiFrameConfig? = { MultiFrameConfig() },
    private val mayEmbed: () -> Boolean = { true },
    private val scope: CoroutineScope,
    private val budgetMs: Long = BUDGET_MS,
) {
    suspend fun score(frameId: String, hits: List<Pair<PlaceEntity, Double>>, live: () -> Boolean): PhotoLayerOutcome {
        if (!live()) return PhotoLayerOutcome.Skipped("fenced")
        val job = scope.async { try { run(frameId, hits, live) } catch (c: CancellationException) { throw c } catch (_: Exception) { PhotoLayerOutcome.Skipped("err") } }
        return withTimeoutOrNull(budgetMs) { job.await() } ?: PhotoLayerOutcome.Skipped("timeout").also { job.cancel() }
    }

    private suspend fun run(frameId: String, hits: List<Pair<PlaceEntity, Double>>, live: () -> Boolean): PhotoLayerOutcome {
        val ids = hits.map { it.first.id }
        val pins = hits.map { it.first }.filter { UserPlace.isUserId(it.id) }
        val e = embedder() ?: return PhotoLayerOutcome.Skipped("no_model")
        val own: Map<String, List<FloatArray>> = if (pins.isEmpty()) emptyMap() else ownRefs(pins, e, live)
        val cent: Map<String, FloatArray> = if (pins.isEmpty()) emptyMap() else centroids(e.spaceId, pins.map { it.id })
        if (!live()) return PhotoLayerOutcome.Skipped("fenced")
        // No candidate has a reference: nothing to compare with, and no embed is spent.
        if (own.values.all { it.isEmpty() } && cent.isEmpty()) return PhotoLayerOutcome.Decided(PhotoConfirm.noReferences(ids), 0)
        val dwell = storedVector(frameId, e.spaceId) ?: run {
            if (!mayEmbed()) return PhotoLayerOutcome.Skipped("hot")
            val jpeg = photo(frameId) ?: return PhotoLayerOutcome.Skipped("no_photo")
            if (!live()) return PhotoLayerOutcome.Skipped("fenced")
            e.embedImage(jpeg)
        }
        if (!live()) return PhotoLayerOutcome.Skipped("fenced")
        val mf = multiFrame()
        val g = if (mf == null) null else glance(frameId, e.spaceId, mf.windowMs)
        val q = MultiFrameQuery.build(dwell, frameId, g?.atMs ?: 0L, g?.others.orEmpty(), g?.moved ?: false, mf ?: MultiFrameConfig(maxExtra = 0)) ?: return PhotoLayerOutcome.Skipped("bad_vector")
        val places = ids.distinct().map { id ->
            val refs = ArrayList<PhotoConfirm.Ref>()
            own[id].orEmpty().forEach { refs += PhotoConfirm.Ref(RefKind.OWN, it) }
            cent[id]?.let { refs += PhotoConfirm.Ref(RefKind.OWN_CENTROID, it) }
            PhotoConfirm.PlaceRefs(id, refs)
        }
        val result = PhotoConfirm.decide(q.scoringVectors(), places, config())
        if (!live()) return PhotoLayerOutcome.Skipped("fenced")
        // The last word before a result leaves: a reference photo Forgotten while this ran drops it.
        if (own.values.any { it.isNotEmpty() } && !refsStillValid(own, e)) return PhotoLayerOutcome.Skipped("reference_gone")
        return PhotoLayerOutcome.Decided(result, q.k)
    }

    companion object { const val BUDGET_MS = 3_000L }
}
