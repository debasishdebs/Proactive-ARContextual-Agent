package com.rokid.context.work.frames.recall

import android.content.Context
import com.rokid.context.core.log.PcLog
import com.rokid.context.ai.AiGraph
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.retrieval.RetrievalSettingsStore
import com.rokid.context.memory.retrieval.ScoredMemory
import com.rokid.context.memory.retrieval.VectorMath
import com.rokid.context.work.frames.scene.ImageSpaceTarget
import com.rokid.context.work.frames.scene.ImageSpaces
import com.rokid.context.work.frames.scene.SpaceImageSpaces
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class PhotoSource { IMAGE, CAPTION }
enum class ImageSearchState { RAN, NO_IMAGE_SPACE, FAILED, TIMED_OUT }
/** RAN also covers "skipped" ([PhotoRecall.search] with captionK = 0); FAILED = the caption retriever threw, so an empty list is not "no matches". */
enum class CaptionSearchState { RAN, FAILED }

/**
 * One recalled photo. [ts] is the capture time: `frame.ts` while the photo is stored, else `frame_meta.capturedAtMs` (null on a pre-v5 row).
 * [receivedAt] is `frame_meta.createdAt` (the phone's receive time, hours after the capture when delivery was late; S9), the fallback "when".
 * [photoStored] false = "Photo expired" (frame row gone; wave-2 Ruling 11).
 */
data class PhotoHit(
    val frameId: String,
    val source: PhotoSource,
    val score: Double,
    val ts: Long?,
    val receivedAt: Long?,
    val photoStored: Boolean,
    val described: Boolean,
    val captionMemoryId: String?,
    val captionText: String?,
    val clusterId: String?,
)

data class RecallResult(val image: List<PhotoHit>, val captions: List<PhotoHit>, val imageState: ImageSearchState, val scanned: Int, val truncated: Boolean, val captionState: CaptionSearchState = CaptionSearchState.RAN)

/**
 * Words to photos (wave 3, spec §3.1): a brute-force cosine scan of the ACTIVE space's image vectors (R1) next to a caption fallback through the
 * user's retrieval mode (R3). The two lists are never merged (R2). Never throws, except for cancellation. Never logs the query.
 */
class PhotoRecall(
    private val db: ContextDatabase,
    private val images: ImageSpaces,
    /** `MemoryRetriever.retrieve(query, settings, now, poolOverride = pool)`: the pool goes past the UI's topK clamp (S8). */
    private val captions: suspend (query: String, pool: Int, nowMs: Long) -> List<ScoredMemory>,
    private val clock: () -> Long = System::currentTimeMillis,
    /** `MemoryRetriever.retrieve(..., notAfterMs = ...)`: the caption fallback of a historical search. Without it a search with a cutoff has no caption fallback. */
    private val captionsAsOf: (suspend (query: String, pool: Int, nowMs: Long, notAfterMs: Long) -> List<ScoredMemory>)? = null,
) {
    /** [imageK] = 0 skips the image scan; [captionK] = 0 skips the fallback. [queryVector]: a ready image-space embedding (a photo, v0.9.8): the scan skips the text embed. [notBeforeMs]: a window's lower bound, applied to the scan BEFORE top-k (image scan only). */
    suspend fun search(query: String, imageK: Int = 24, captionK: Int = 24, budgetMs: Long = ImageScan.BUDGET_MS, notAfterMs: Long? = null, queryVector: FloatArray? = null, notBeforeMs: Long? = null): RecallResult {
        val t0 = clock()
        var state = ImageSearchState.RAN
        var imageIds = emptyList<Pair<String, Double>>()
        var scanned = 0
        var truncated = false
        if (imageK > 0) {
            val scan = imageScan(query, imageK, budgetMs, t0, notAfterMs, queryVector, notBeforeMs)
            state = scan.state; imageIds = scan.hits; scanned = scan.scanned; truncated = scan.truncated
        }
        val imageSet = imageIds.map { it.first }.toSet()
        var captionState = CaptionSearchState.RAN
        val captionHits = if (captionK > 0) captionScores(query, imageSet, notAfterMs) ?: run { captionState = CaptionSearchState.FAILED; emptyList() } else emptyList()
        val image = enrich(imageIds, PhotoSource.IMAGE, notAfterMs)
        val cap = enrich(captionHits, PhotoSource.CAPTION, notAfterMs).take(captionK) // cut AFTER frames without frame_meta are dropped
        PcLog.i("PcRecall", "img=${image.size} cap=${cap.size} scanned=$scanned ms=${clock() - t0} state=$state")
        return RecallResult(image, cap, state, scanned, truncated, captionState)
    }

    /**
     * Re-reads the stored/described/caption state of the frames in [r] (same order and scores), dropping frames whose `frame_meta` is gone (a Forget). The open
     * photo screen calls it on a database change instead of re-scanning (S11). Never throws, except for cancellation.
     */
    suspend fun reenrich(r: RecallResult): RecallResult = try {
        RecallResult(
            enrich(r.image.map { it.frameId to it.score }, PhotoSource.IMAGE),
            enrich(r.captions.map { it.frameId to it.score }, PhotoSource.CAPTION),
            r.imageState, r.scanned, r.truncated, r.captionState,
        )
    } catch (c: CancellationException) { throw c } catch (_: Exception) { r }

    private class ImageScanOutcome(val state: ImageSearchState, val hits: List<Pair<String, Double>>, val scanned: Int, val truncated: Boolean)

    private suspend fun imageScan(query: String, k: Int, budgetMs: Long, startMs: Long, notAfterMs: Long?, queryVector: FloatArray? = null, notBeforeMs: Long? = null): ImageScanOutcome {
        fun failed() = ImageScanOutcome(ImageSearchState.FAILED, emptyList(), 0, false)
        val target: ImageSpaceTarget = try {
            images.targets().firstOrNull { it.active } // S7: the ACTIVE one by flag, never "the first"
        } catch (c: CancellationException) { throw c } catch (_: Exception) { return failed() }
            ?: return ImageScanOutcome(ImageSearchState.NO_IMAGE_SPACE, emptyList(), 0, false)
        // The query embed (a cold on-device model load can block) gets the scan's own budget, on a detached coroutine: awaiting it is cancellable even when
        // the embed itself is not, so the ask is never held past the budget. Whatever it finishes later is dropped.
        val embed = CoroutineScope(SupervisorJob() + Dispatchers.Default).async { queryVector ?: target.embedQueryFull(query) }
        val full = try { withTimeoutOrNull(budgetMs) { embed.await() } } catch (c: CancellationException) { embed.cancel(); throw c } catch (_: Exception) { return failed() }
        if (full == null) { embed.cancel(); return ImageScanOutcome(ImageSearchState.TIMED_OUT, emptyList(), 0, false) }
        // One deadline for the embed and the scan: what the embed used is not given to the scan again.
        val remaining = budgetMs - (clock() - startMs)
        if (remaining <= 0) { embed.cancel(); return ImageScanOutcome(ImageSearchState.TIMED_OUT, emptyList(), 0, false) }
        val q = (if (full.size < target.imageDims) null else VectorMath.l2(full.copyOf(target.imageDims)).takeIf { VectorMath.usable(it) }) ?: return failed()
        return try {
            val r = withContext(Dispatchers.Default) {
                ImageScan.topK(q, k, { before -> (if (notBeforeMs != null) db.frameVectorDao().pageDescBetween(target.spaceId, before, ImageScan.PAGE, notBeforeMs, notAfterMs ?: Long.MAX_VALUE) else if (notAfterMs == null) db.frameVectorDao().pageDesc(target.spaceId, before, ImageScan.PAGE) else db.frameVectorDao().pageDescNotAfter(target.spaceId, before, ImageScan.PAGE, notAfterMs)).map { it.rowid to (it.frameId to it.blob) } }, clock, budgetMs = remaining)
            }
            ImageScanOutcome(if (r.timedOut) ImageSearchState.TIMED_OUT else ImageSearchState.RAN, r.hits, r.scanned, r.truncated)
        } catch (c: CancellationException) { throw c } catch (_: Exception) { failed() }
    }

    /** Null = the retriever failed. */
    private suspend fun captionScores(query: String, skip: Set<String>, notAfterMs: Long?): List<Pair<String, Double>>? {
        val hits = try { if (notAfterMs == null) captions(query, CAPTION_POOL, clock()) else (captionsAsOf ?: return null).invoke(query, CAPTION_POOL, clock(), notAfterMs) } catch (c: CancellationException) { throw c } catch (_: Exception) { return null }
        val best = HashMap<String, Double>()
        for (h in hits) { val f = h.memory.frameId ?: continue; if (f !in skip) best.merge(f, h.score, ::maxOf) }
        return best.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key }).map { it.key to it.value }
    }

    private suspend fun enrich(hits: List<Pair<String, Double>>, source: PhotoSource, notAfterMs: Long? = null): List<PhotoHit> {
        if (hits.isEmpty()) return emptyList()
        val ids = hits.map { it.first }.distinct()
        val metas = ids.chunked(CHUNK).flatMap { db.frameMetaDao().byIds(it) }.associateBy { it.frameId }
        val frames = ids.chunked(CHUNK).flatMap { db.frameDao().byIds(it) }.associateBy { it.id }
        val described = ids.chunked(CHUNK).flatMap { db.frameSourceDao().describedAmong(it) }.toSet()
        val caps = ids.chunked(CHUNK).flatMap { if (notAfterMs == null) db.frameSourceDao().newestCaptions(it) else db.frameSourceDao().newestCaptionsNotAfter(it, notAfterMs) }.associateBy { it.frameId }
        return hits.mapNotNull { (id, score) ->
            val meta = metas[id] ?: return@mapNotNull null // forgotten mid-search
            val frame = frames[id]
            val cap = caps[id]
            PhotoHit(id, source, score, ts = frame?.ts ?: meta.capturedAtMs, receivedAt = meta.createdAt, photoStored = frame != null,
                described = id in described, captionMemoryId = cap?.memoryId, captionText = cap?.text, clusterId = meta.clusterId)
        }
    }

    companion object {
        private const val CAPTION_POOL = 60
        private const val CHUNK = 500

        fun forApp(context: Context): PhotoRecall {
            val app = context.applicationContext
            val mem = MemoryGraph.get(app)
            val ai = AiGraph.get(app)
            val store = RetrievalSettingsStore.get(app)
            return PhotoRecall(mem.db, SpaceImageSpaces(mem.spaces, ai.embedders), { q, pool, now -> ai.retriever.retrieve(q, store.settings.value, now, poolOverride = pool).hits },
                captionsAsOf = { q, pool, now, notAfter -> ai.retriever.retrieve(q, store.settings.value, now, poolOverride = pool, notAfterMs = notAfter).hits })
        }
    }
}
