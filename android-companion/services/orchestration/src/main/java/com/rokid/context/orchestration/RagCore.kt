package com.rokid.context.orchestration

import android.content.Context
import com.rokid.context.core.log.PcLog
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.NudgeEntity
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.forget.ForgetFence
import com.rokid.context.memory.forget.PHOTO_CITE_PREFIX
import com.rokid.context.memory.retrieval.FusionStrategy
import com.rokid.context.memory.retrieval.MemoryRetriever
import com.rokid.context.memory.retrieval.RetrievalMode
import com.rokid.context.memory.retrieval.RetrievalSettings
import com.rokid.context.memory.retrieval.ScoredMemory
import com.rokid.context.model.ModelImageInput
import com.rokid.context.work.frames.recall.PhotoHit
import com.rokid.context.work.frames.recall.PhotoRecall
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/** What a query is searched with: words (the memory store, and photos by their words) or a ready image-space embedding (photos only). */
enum class RagType { TEXT, IMAGE }

/**
 * One retrieval request: [text] and/or [vector] (a photo's image embedding, e.g. a later Nudge trigger). With a [vector] the photo store is searched
 * by it; with only [text] by words (what an Ask does for its photo lines). [type] says which kind the query is.
 */
class RagQuery(val text: String? = null, val vector: FloatArray? = null) {
    val type: RagType get() = if (vector != null) RagType.IMAGE else RagType.TEXT
}

/**
 * Where a retrieval reads from: lazy descriptors, nothing is materialised until [RagCore.retrieve] runs them. Every source passes through the same
 * Forget fence, photo gate and bounds inside the core, so a new wrapper cannot skip them.
 */
sealed interface RagSource {
    /** The memory store through the user's retrieval mode (lexical/semantic/hybrid), the newest trusted memories when nothing matches. [notAfterMs]: the Ask debug view's cutoff (S34), before the retriever's limits. Needs a text query. */
    data class MemoryStore(val notAfterMs: Long? = null) : RagSource
    /**
     * The photo store (image vectors; words or a vector query), behind the photo gate. [notAfterMs]: the cutoff (the debug view's, S34) and [windowMs] (only photos of the last [windowMs]) both go into
     * the search and the selection, so they apply BEFORE top-k and the relevance margin (the caption fallback is not searched: captionK = 0).
     */
    data class PhotoStore(val notAfterMs: Long? = null, val windowMs: Long? = null) : RagSource
    /** OK memories of the last [windowMs], newest [RagParams.maxRecent], minus those about an old photo: what just happened (no query). */
    data class RecentWindow(val windowMs: Long) : RagSource
    /** The texts of the nudges of the last [windowMs] (what was already said). */
    data class NudgeHistory(val windowMs: Long) : RagSource
    /** A caller-held list of memory ids (a pipeline step's earlier output); loaded and fenced like any other. */
    data class Explicit(val memoryIds: List<String>) : RagSource
}

/** How the lexical and semantic rankings are combined (the hybrid mode's fusion; [RetrievalSettings.fusion]). */
enum class RagFusion { WEIGHTED, RRF, COMB_MNZ }

/**
 * The typed knobs of a retrieval, every one optional: null = the user's retrieval setting (and, for [maxChars] and [maxRecent], the built-in default),
 * so [RagParams] with nothing set reproduces today's behaviour exactly. Values are clamped like the settings are. [SCHEMA] describes each field
 * (type, range, default) for the canvas editor to render and validate.
 */
data class RagParams(
    val mode: RetrievalMode? = null,
    val fusion: RagFusion? = null, val semanticWeight: Double? = null, val rrfK: Int? = null,
    val topK: Int? = null, val poolSize: Int? = null, val minConfidence: Double? = null,
    val recencyBoost: Boolean? = null, val halfLifeDays: Double? = null,
    val maxChars: Int? = null, val maxRecent: Int? = null,
) {
    internal val charBound: Int get() = (maxChars ?: DEFAULT_MAX_CHARS).coerceAtLeast(0)
    internal val recentCap: Int get() = (maxRecent ?: DEFAULT_MAX_RECENT).coerceAtLeast(0)

    /** The settings this retrieval runs with: [base] with every set field replacing its own, clamped as the settings are. */
    internal fun over(base: RetrievalSettings): RetrievalSettings {
        val kind = fusion ?: when (base.fusion) { is FusionStrategy.WeightedScore -> RagFusion.WEIGHTED; is FusionStrategy.Rrf -> RagFusion.RRF; FusionStrategy.CombMnz -> RagFusion.COMB_MNZ }
        val f = when (kind) {
            RagFusion.WEIGHTED -> FusionStrategy.WeightedScore(semanticWeight ?: (base.fusion as? FusionStrategy.WeightedScore)?.semanticWeight ?: FusionStrategy.WeightedScore().semanticWeight)
            RagFusion.RRF -> FusionStrategy.Rrf(rrfK ?: (base.fusion as? FusionStrategy.Rrf)?.k ?: FusionStrategy.Rrf().k)
            RagFusion.COMB_MNZ -> FusionStrategy.CombMnz
        }
        return base.copy(
            mode = mode ?: base.mode, fusion = f, topK = topK ?: base.topK, minConfidence = minConfidence ?: base.minConfidence,
            recencyBoost = recencyBoost ?: base.recencyBoost, halfLifeDays = halfLifeDays ?: base.halfLifeDays,
        ).clamped()
    }

    /** One editable parameter. [type]: `int`, `number`, `bool` or `enum`; [options] lists an enum's values; [default] is the built-in value an unset field means when the user's setting does not decide. */
    data class Field(val name: String, val type: String, val min: Double? = null, val max: Double? = null, val options: List<String> = emptyList(), val default: String, val doc: String)

    companion object {
        const val DEFAULT_MAX_CHARS = 5_000
        const val DEFAULT_MAX_RECENT = 10
        private val D = RetrievalSettings()
        val SCHEMA: List<Field> = listOf(
            Field("mode", "enum", options = RetrievalMode.entries.map { it.name }, default = D.mode.name, doc = "lexical, semantic or hybrid search"),
            Field("fusion", "enum", options = RagFusion.entries.map { it.name }, default = RagFusion.WEIGHTED.name, doc = "how hybrid search combines the two rankings"),
            Field("semanticWeight", "number", 0.0, 1.0, default = "0.6", doc = "weight of the semantic score under WEIGHTED fusion"),
            Field("rrfK", "int", 1.0, 200.0, default = "60", doc = "rank constant under RRF fusion"),
            Field("topK", "int", 1.0, 20.0, default = D.topK.toString(), doc = "memories the retriever keeps"),
            Field("poolSize", "int", 1.0, null, default = "unset", doc = "candidate pool replacing topK in the retriever (recall); unset keeps topK"),
            Field("minConfidence", "number", 0.0, 1.0, default = D.minConfidence.toString(), doc = "lowest memory confidence kept by the fallback"),
            Field("recencyBoost", "bool", default = D.recencyBoost.toString(), doc = "boost newer memories"),
            Field("halfLifeDays", "number", 0.25, 30.0, default = D.halfLifeDays.toString(), doc = "recency half-life"),
            Field("maxChars", "int", 0.0, null, default = DEFAULT_MAX_CHARS.toString(), doc = "prompt bound of the memory lines (photo lines come off it first)"),
            Field("maxRecent", "int", 0.0, null, default = DEFAULT_MAX_RECENT.toString(), doc = "most memories a recent window gives"),
        )
    }
}

/** A photo in the prompt. [citeId] = the caption memory's id, else `frame:<frameId>`. [ts] is the capture time (or `frame_meta.capturedAtMs` once expired). */
data class PhotoCite(val citeId: String, val frameId: String, val ts: Long?, val stored: Boolean, val receivedAt: Long? = null)

/**
 * What a retrieval hands to its caller, which builds its own prompt vars. [memoryLines] are the lines of [memories] (same order, already bounded);
 * [recentLines] those of [recent] (RecentWindow and Explicit memories, the short `id · HH:mm · text` form); [history] the nudge texts.
 * [photoLines] come with the image note first when [images] is not empty (`image=<n>` is a 1-based index into it). [hits], [mode], [semanticUnavailable]
 * and [fallback] are what the Ask debug view shows; [fallback] = no hits, the newest trusted memories stand in.
 */
data class RagContext(
    val memories: List<MemoryEntity> = emptyList(), val memoryLines: List<String> = emptyList(),
    val recent: List<MemoryEntity> = emptyList(), val recentLines: List<String> = emptyList(), val history: List<String> = emptyList(),
    val photoLines: List<String> = emptyList(), val photos: List<PhotoCite> = emptyList(), val images: List<ModelImageInput> = emptyList(),
    val imageEndpointId: String? = null, val fallback: Boolean = false, val historyIds: Set<String> = emptySet(),
    val hits: List<ScoredMemory> = emptyList(), val mode: RetrievalMode? = null, val semanticUnavailable: Boolean = false,
) {
    /** Everything the prompt rests on, including the sources of the historical nudges in [history]: a caller's commit fence covers all of it. */
    val ids: Set<String> get() = (memories + recent).map { it.id }.toSet() + photos.map { it.citeId } + historyIds
}

/**
 * v0.9.8: the one retrieval core under Ask and Nudge. It reads the memory store and the photo store and never calls an LLM; each caller keeps its own
 * gates (Forget fence, quiet hours, cap, dedupe, pause) and its own synthesis (ANSWER or NUDGE role). With no [retriever] a TEXT query has no hits
 * (the fallback applies).
 */
class RagCore(
    private val memory: MemoryGraph,
    private val retriever: MemoryRetriever? = null,
    private val settings: () -> RetrievalSettings = { RetrievalSettings() },
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    /** The photos that match the question by image vector. Never fails the caller: an error or a timeout is "none". */
    private val photos: suspend (question: String, nowMs: Long) -> List<PhotoHit> = { _, _ -> emptyList() },
    /** [PhotoRecall.search] with the ask time as the cutoff, applied BEFORE top-k (vectors and captions). */
    private val photosAsOf: suspend (question: String, notAfterMs: Long) -> List<PhotoHit> = { _, _ -> emptyList() },
    /** The photos near a ready image embedding (IMAGE queries with a vector). */
    private val photosByVector: suspend (vector: FloatArray, nowMs: Long, notAfterMs: Long?, notBeforeMs: Long?) -> List<PhotoHit> = { _, _, _, _ -> emptyList() },
    /** Asked before every photo search ([AskPhotoPolicy.allowed]). False = no scan, no image, no photo line. */
    private val photosAllowed: suspend () -> Boolean = { false },
    /** The endpoint the gate checked (the ANSWER primary): images are sent only there, never to a fallback. */
    private val imageEndpointId: suspend () -> String? = { null },
    /** Decrypted in memory only (`FramePhotos.load`); a missing key = unreadable photo, which is skipped. */
    private val photoImages: suspend (frameIds: List<String>) -> Map<String, ModelImageInput> = { emptyMap() },
) {
    /**
     * Runs every [sources] entry against [query] with [params]. Whatever a source returns is fenced (a memory or photo that was forgotten is dropped)
     * and the photo gate is asked before any photo search. A source the query cannot feed (a MemoryStore without text) gives nothing.
     * [RagParams.maxChars] is ONE budget for all the lines, spent in a fixed order whatever the order of [sources]: photo lines, memory lines, recent
     * lines, nudge history.
     */
    suspend fun retrieve(query: RagQuery, sources: List<RagSource>, params: RagParams, nowMs: Long): RagContext {
        if (params.charBound == 0) return RagContext() // a zero bound is no context at all
        val s = params.over(settings())
        val photoSrc = sources.filterIsInstance<RagSource.PhotoStore>().firstOrNull()
        val recalled = if (photoSrc == null) emptyList() else when {
            query.vector != null -> recalledByVector(query.vector, nowMs, photoSrc.notAfterMs, photoSrc.windowMs?.let { nowMs - it })
            query.text != null -> recalledPhotos(query.text, nowMs, photoSrc.notAfterMs, photoSrc.windowMs?.let { nowMs - it })
            else -> emptyList()
        }
        val photoPart = photoPart(fenceCites(recalled))
        var ctx = RagContext(photoLines = photoPart.lines, photos = photoPart.photos, images = photoPart.images, imageEndpointId = photoPart.endpoint)
        var left = params.charBound - photoPart.lines.sumOf { it.length + 1 }
        // Gather (the order of [sources] decides only which memories come first within a kind), then spend the one budget in the fixed order.
        var store: MemoryStoreResult? = null
        val recentFound = mutableListOf<MemoryEntity>()
        var history: List<Pair<NudgeEntity, List<String>>>? = null
        for (src in sources) when (src) {
            is RagSource.MemoryStore -> if (query.text != null && store == null) store = memoryStore(query.text, src, s, params, nowMs, ctx.photos.map { it.citeId }.toSet())
            is RagSource.RecentWindow -> recentFound += recent(nowMs, src.windowMs, params.recentCap)
            is RagSource.Explicit -> recentFound += explicit(src.memoryIds)
            is RagSource.NudgeHistory -> if (history == null) history = nudgeHistory(nowMs - src.windowMs)
            is RagSource.PhotoStore -> Unit
        }
        store?.let { m ->
            val lines = AskText.bounded(m.pool.map(::line), left)
            left -= lines.sumOf { it.length + 1 }
            ctx = ctx.copy(memories = m.pool.take(lines.size), memoryLines = lines, fallback = m.fallback, hits = m.result?.hits.orEmpty(), mode = m.result?.modeUsed, semanticUnavailable = m.result?.semanticUnavailable ?: false)
        }
        if (recentFound.isNotEmpty()) {
            val fresh = recentFound.distinctBy { it.id }.take(params.recentCap)
            val lines = AskText.bounded(fresh.map(::recentLine), left)
            left -= lines.sumOf { it.length + 1 }
            ctx = ctx.copy(recent = fresh.take(lines.size), recentLines = lines)
        }
        history?.let { h ->
            val texts = h.map { AskText.collapse(it.first.text) }
            val kept = AskText.bounded(texts, left).size
            ctx = ctx.copy(history = texts.take(kept), historyIds = h.take(kept).flatMap { it.second }.toSet())
        }
        return ctx
    }

    private class MemoryStoreResult(val pool: List<MemoryEntity>, val fallback: Boolean, val result: com.rokid.context.memory.retrieval.RetrievalResult?)

    private suspend fun memoryStore(q: String, src: RagSource.MemoryStore, s: RetrievalSettings, params: RagParams, nowMs: Long, citeIds: Set<String>): MemoryStoreResult {
        val r = retriever?.retrieve(q, s, nowMs, poolOverride = params.poolSize, notAfterMs = src.notAfterMs)
        val fallback = r == null || r.hits.isEmpty()
        // No hits: the newest trusted memories, under the same minConfidence and top-k the retriever applies.
        val poolAll = if (!fallback) r!!.hits.map { it.memory } else
            memory.memories.recent(30, src.notAfterMs).filter { it.reviewState == ReviewState.OK && it.confidence >= s.minConfidence }.take(minOf(FALLBACK, s.topK))
        // A photo whose caption is already a retrieved memory is listed once, as the photo line.
        return MemoryStoreResult(fence(poolAll).filter { it.id !in citeIds }, fallback, r)
    }

    /**
     * The nudges since [sinceMs], each with its sources and dependencies. A nudge whose own sources were forgotten is dropped (Forget redacts it; its
     * text must not propagate); the sources of the ones kept end up in [RagContext.historyIds] so the caller's commit fence covers them.
     */
    private suspend fun nudgeHistory(sinceMs: Long): List<Pair<NudgeEntity, List<String>>> =
        memory.db.nudgeGateDao().lastNudges(sinceMs).map { it to AskSources.parseAll(it.sourceIdsJson) }.filter { (_, ids) -> ForgetFence.allLive(memory.db, ids) }

    private suspend fun explicit(ids: List<String>): List<MemoryEntity> {
        val byId = ids.distinct().chunked(500).flatMap { memory.db.forgetDao().memories(it) }.filter { it.reviewState == ReviewState.OK }.associateBy { it.id }
        return fence(ids.mapNotNull { byId[it] })
    }

    /** The Forget fence for everything the core hands out: all live = untouched, else the forgotten ones are dropped one by one. */
    private suspend fun fence(found: List<MemoryEntity>): List<MemoryEntity> =
        if (ForgetFence.allLive(memory.db, found.map { it.id })) found else found.filter { ForgetFence.allLive(memory.db, listOf(it.id)) }

    private suspend fun fenceCites(found: List<Pair<PhotoHit, String>>): List<Pair<PhotoHit, String>> =
        if (ForgetFence.allLive(memory.db, found.map { it.second })) found else found.filter { ForgetFence.allLive(memory.db, listOf(it.second)) }

    private class PhotoPart(val lines: List<String> = emptyList(), val photos: List<PhotoCite> = emptyList(), val images: List<ModelImageInput> = emptyList(), val endpoint: String? = null)

    private suspend fun photoPart(recalled: List<Pair<PhotoHit, String>>): PhotoPart {
        // Fail closed: the endpoint the gate checked is resolved BEFORE any image is decrypted; no endpoint (or a lookup that throws) = no attachment.
        val endpoint = if (recalled.none { it.first.photoStored }) null else try { imageEndpointId() } catch (c: CancellationException) { throw c } catch (_: Exception) { null }
        val images = mutableListOf<ModelImageInput>()
        val imageNo = HashMap<String, Int>()
        for ((hit, citeId) in recalled) {
            if (endpoint == null || images.size >= MAX_PHOTO_IMAGES || !hit.photoStored) continue
            val image = try { photoImages(listOf(hit.frameId))[hit.frameId] } catch (c: CancellationException) { throw c } catch (e: Exception) { null }
            if (image != null) { images += image; imageNo[citeId] = images.size }
        }
        val lines = recalled.map { (hit, citeId) -> photoLine(hit, citeId, imageNo[citeId]) }.let { if (images.isEmpty()) it else listOf(IMAGE_NOTE) + it }
        return PhotoPart(
            lines, recalled.map { (hit, citeId) -> PhotoCite(citeId, hit.frameId, hit.ts, hit.photoStored, hit.receivedAt) }, images,
            endpoint.takeIf { images.isNotEmpty() },
        )
    }

    /** The gated photo search (words to photos), cutoff and relevance floor applied: the hit with its cite id. */
    internal suspend fun recalledPhotos(q: String, nowMs: Long, notAfterMs: Long? = null, notBeforeMs: Long? = null): List<Pair<PhotoHit, String>> =
        select(guarded { if (notAfterMs != null) photosAsOf(q, notAfterMs) else photos(q, nowMs) }, notAfterMs, notBeforeMs)

    private suspend fun recalledByVector(v: FloatArray, nowMs: Long, notAfterMs: Long?, notBeforeMs: Long?): List<Pair<PhotoHit, String>> =
        select(guarded { photosByVector(v, nowMs, notAfterMs, notBeforeMs) }, notAfterMs, notBeforeMs)

    private suspend fun guarded(search: suspend () -> List<PhotoHit>): List<PhotoHit> =
        try { if (!photosAllowed()) emptyList() else search() } catch (c: CancellationException) { throw c } catch (e: Exception) {
            PcLog.i(TAG, "photo recall failed (${e.javaClass.simpleName})"); emptyList()
        }

    private fun select(hits: List<PhotoHit>, notAfterMs: Long?, notBeforeMs: Long? = null): List<Pair<PhotoHit, String>> {
        // The relevance floor: below it a photo gets neither an image nor a line (provisional values: AskPhotoPolicy).
        // Ask debug only: a photo known to be captured (else received) after the ask did not exist for it.
        // A window's lower bound likewise: before the floor and the margin, so an old photo never sets the top score.
        val windowed = if (notBeforeMs == null) hits else hits.filter { h -> (h.ts ?: h.receivedAt)?.let { it >= notBeforeMs } ?: true }
        val visible = if (notAfterMs == null) windowed else windowed.filter { h -> ((h.ts ?: h.receivedAt)?.let { it <= notAfterMs } ?: true) && (h.receivedAt?.let { it <= notAfterMs } ?: true) }
        return AskPhotoPolicy.select(visible.distinctBy { it.frameId }).map { it to (it.captionMemoryId ?: (PHOTO_CITE_PREFIX + it.frameId)) }
    }

    /** The Ask debug view's live photo lines: [recalledPhotos] with a cutoff, the images capped, the lines numbered. */
    internal suspend fun debugRecall(q: String, nowMs: Long, endpointId: suspend () -> String?): DebugRecall? {
        val recalled = fenceCites(recalledPhotos(q, nowMs, notAfterMs = nowMs)) // re-checks the gate before any search; photos after the ask are dropped
        if (recalled.isEmpty()) return null
        val endpoint = endpointId()
        val withImage = if (endpoint == null) emptyList() else recalled.filter { it.first.photoStored }.take(MAX_PHOTO_IMAGES)
        val imageNo = withImage.mapIndexed { i, (_, cite) -> cite to i + 1 }.toMap()
        val lines = recalled.map { (hit, cite) -> photoLine(hit, cite, imageNo[cite]) }.let { if (imageNo.isEmpty()) it else listOf(IMAGE_NOTE) + it }
        return DebugRecall(recalled, withImage, lines, endpoint)
    }

    internal class DebugRecall(val recalled: List<Pair<PhotoHit, String>>, val withImage: List<Pair<PhotoHit, String>>, val lines: List<String>, val endpoint: String?)

    /**
     * OK memories of the last [windowMs] (newest [max]), minus those about an old photo: a memory whose photo was captured more than the window before
     * the memory was written (a night describe or a late backlog extraction) is not a live moment, whatever its createdAt says. frame_meta outlives the
     * photo, so the check survives expiry. This is the Nudge's memory input (no query: what just happened).
     */
    private suspend fun recent(nowMs: Long, windowMs: Long, max: Int): List<MemoryEntity> {
        val candidates = memory.db.memoryDao().between(nowMs - windowMs, nowMs + 1).filter { it.reviewState == ReviewState.OK }
        val captured = candidates.mapNotNull { it.frameId }.distinct().chunked(500).flatMap { memory.db.frameMetaDao().byIds(it) }.associate { it.frameId to it.createdAt }
        return fence(candidates.filter { m -> m.frameId?.let { captured[it] }?.let { m.createdAt - it <= windowMs } ?: true }.take(max))
    }

    /**
     * `id=<cite> · <time> · photo[ · image=<n>] · <caption | no caption yet>`. The time is the capture time; an expired photo without one falls back to
     * the receive time and says so ("received", user ruling 2026-10-22 over S9).
     */
    internal fun photoLine(hit: PhotoHit, citeId: String, imageNo: Int?): String {
        val fmt = TIME.withZone(zone())
        val at = hit.ts?.let { fmt.format(Instant.ofEpochMilli(it)) } ?: hit.receivedAt?.let { "received " + fmt.format(Instant.ofEpochMilli(it)) } ?: "time unknown"
        val caption = hit.captionText?.let { AskText.collapse(it) }?.takeIf { it.isNotEmpty() }?.let { AskText.clip(it, MAX_PHOTO_CAPTION) } ?: "no caption yet"
        return "id=$citeId · $at · photo" + (imageNo?.let { " · image=$it" } ?: "") + " · $caption"
    }

    /** The short form of a recent memory: `id=<id> · HH:mm · <text ≤ 300>`. */
    private fun recentLine(m: MemoryEntity): String = "id=${m.id} \u00b7 ${RECENT_TIME.withZone(zone()).format(Instant.ofEpochMilli(m.createdAt))} \u00b7 ${AskText.clip(AskText.collapse(m.text), MAX_RECENT_CHARS)}"

    private fun line(m: MemoryEntity): String =
        "id=${m.id} · ${TIME.withZone(zone()).format(Instant.ofEpochMilli(m.createdAt))} · confidence ${"%.2f".format(Locale.US, m.confidence)} · " +
            "frame=${m.frameId ?: "none"} · ${AskText.clip(AskText.collapse(m.text), MAX_MEMORY_CHARS)}"

    companion object {
        private const val TAG = "RagCore"
        private const val MAX_MEMORY_CHARS = 500
        private const val FALLBACK = 10
        private const val MAX_RECENT_CHARS = 300
        private val RECENT_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
        internal const val MAX_PHOTO_IMAGES = AskPhotoPolicy.MAX_PHOTOS
        private const val MAX_PHOTO_CAPTION = 300
        internal const val IMAGE_NOTE = "note: image=N on a photo line is the Nth attached image"
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)

        /** The production image-vector search: the Ask's photo budget and timeout, a ready embedding instead of words. */
        fun photosByVectorFrom(context: Context): suspend (FloatArray, Long, Long?, Long?) -> List<PhotoHit> = { v, _, notAfter, notBefore ->
            kotlinx.coroutines.withTimeoutOrNull(AskPhotoPolicy.ASK_TIMEOUT_MS) {
                PhotoRecall.forApp(context).search("", imageK = AskPhotoPolicy.MAX_PHOTOS, captionK = 0, budgetMs = AskPhotoPolicy.SCAN_BUDGET_MS, notAfterMs = notAfter, queryVector = v, notBeforeMs = notBefore).image
            }.orEmpty()
        }
    }
}
