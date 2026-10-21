package com.rokid.context.work.frames.recall

import android.content.Context
import android.util.Log
import com.rokid.context.ai.AiGraph
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.retrieval.RetrievalSettingsStore
import com.rokid.context.memory.retrieval.ScoredMemory
import com.rokid.context.work.frames.scene.ImageSpaceTarget
import com.rokid.context.work.frames.scene.ImageSpaces
import com.rokid.context.work.frames.scene.SpaceImageSpaces
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

enum class PhotoSource { IMAGE, CAPTION }
enum class ImageSearchState { RAN, NO_IMAGE_SPACE, FAILED, TIMED_OUT }

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

data class RecallResult(val image: List<PhotoHit>, val captions: List<PhotoHit>, val imageState: ImageSearchState, val scanned: Int, val truncated: Boolean)

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
) {
    /** [imageK] = 0 skips the image scan; [captionK] = 0 skips the fallback. */
    suspend fun search(query: String, imageK: Int = 24, captionK: Int = 24, budgetMs: Long = ImageScan.BUDGET_MS): RecallResult {
        val t0 = clock()
        var state = ImageSearchState.RAN
        var imageIds = emptyList<Pair<String, Double>>()
        var scanned = 0
        var truncated = false
        if (imageK > 0) {
            val scan = imageScan(query, imageK, budgetMs)
            state = scan.state; imageIds = scan.hits; scanned = scan.scanned; truncated = scan.truncated
        }
        val imageSet = imageIds.map { it.first }.toSet()
        val captionHits = if (captionK > 0) captionScores(query, imageSet, captionK) else emptyList()
        val image = enrich(imageIds, PhotoSource.IMAGE)
        val cap = enrich(captionHits, PhotoSource.CAPTION)
        Log.i("PcRecall", "img=${image.size} cap=${cap.size} scanned=$scanned ms=${clock() - t0} state=$state")
        return RecallResult(image, cap, state, scanned, truncated)
    }

    private class ImageScanOutcome(val state: ImageSearchState, val hits: List<Pair<String, Double>>, val scanned: Int, val truncated: Boolean)

    private suspend fun imageScan(query: String, k: Int, budgetMs: Long): ImageScanOutcome {
        fun failed() = ImageScanOutcome(ImageSearchState.FAILED, emptyList(), 0, false)
        val target: ImageSpaceTarget = try {
            images.targets().firstOrNull { it.active } // S7: the ACTIVE one by flag, never "the first"
        } catch (c: CancellationException) { throw c } catch (_: Exception) { return failed() }
            ?: return ImageScanOutcome(ImageSearchState.NO_IMAGE_SPACE, emptyList(), 0, false)
        val q = try {
            val full = target.embedQueryFull(query)
            if (full.size < target.imageDims) null else unit(full.copyOf(target.imageDims))
        } catch (c: CancellationException) { throw c } catch (_: Exception) { null } ?: return failed()
        return try {
            val r = withContext(Dispatchers.Default) {
                ImageScan.topK(q, k, { before -> db.frameVectorDao().pageDesc(target.spaceId, before, ImageScan.PAGE).map { it.rowid to (it.frameId to it.blob) } }, clock, budgetMs = budgetMs)
            }
            ImageScanOutcome(if (r.timedOut) ImageSearchState.TIMED_OUT else ImageSearchState.RAN, r.hits, r.scanned, r.truncated)
        } catch (c: CancellationException) { throw c } catch (_: Exception) { failed() }
    }

    private suspend fun captionScores(query: String, skip: Set<String>, k: Int): List<Pair<String, Double>> {
        val hits = try { captions(query, CAPTION_POOL, clock()) } catch (c: CancellationException) { throw c } catch (_: Exception) { return emptyList() }
        val best = HashMap<String, Double>()
        for (h in hits) { val f = h.memory.frameId ?: continue; if (f !in skip) best.merge(f, h.score, ::maxOf) }
        return best.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key }).take(k).map { it.key to it.value }
    }

    private suspend fun enrich(hits: List<Pair<String, Double>>, source: PhotoSource): List<PhotoHit> {
        if (hits.isEmpty()) return emptyList()
        val ids = hits.map { it.first }.distinct()
        val metas = ids.chunked(CHUNK).flatMap { db.frameMetaDao().byIds(it) }.associateBy { it.frameId }
        val frames = ids.chunked(CHUNK).flatMap { db.frameDao().byIds(it) }.associateBy { it.id }
        val described = ids.chunked(CHUNK).flatMap { db.frameSourceDao().describedAmong(it) }.toSet()
        val caps = ids.chunked(CHUNK).flatMap { db.frameSourceDao().newestCaptions(it) }.associateBy { it.frameId }
        return hits.mapNotNull { (id, score) ->
            val meta = metas[id] ?: return@mapNotNull null // forgotten mid-search
            val frame = frames[id]
            val cap = caps[id]
            PhotoHit(id, source, score, ts = frame?.ts ?: meta.capturedAtMs, receivedAt = meta.createdAt, photoStored = frame != null,
                described = id in described, captionMemoryId = cap?.memoryId, captionText = cap?.text, clusterId = meta.clusterId)
        }
    }

    private fun unit(v: FloatArray): FloatArray? {
        var s = 0.0
        for (x in v) { if (x.isNaN() || x.isInfinite()) return null; s += x * x }
        val n = sqrt(s).toFloat()
        return if (n == 0f) null else FloatArray(v.size) { v[it] / n }
    }

    companion object {
        private const val CAPTION_POOL = 60
        private const val CHUNK = 500

        fun forApp(context: Context): PhotoRecall {
            val app = context.applicationContext
            val mem = MemoryGraph.get(app)
            val ai = AiGraph.get(app)
            val store = RetrievalSettingsStore.get(app)
            return PhotoRecall(mem.db, SpaceImageSpaces(mem.spaces, ai.embedders), { q, pool, now -> ai.retriever.retrieve(q, store.settings.value, now, poolOverride = pool).hits })
        }
    }
}
