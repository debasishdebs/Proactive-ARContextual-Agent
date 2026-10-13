package com.rokid.context.orchestration

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.rokid.context.core.model.TextHygiene
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemorySourceEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.db.SummaryTraceEntity
import com.rokid.context.memory.legacy.LegacyImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * The legacy pipeline's record rules over the Room repositories (replaces the Phase 2 frame / memory / summary-trace
 * store facades). Suspend and main-safe; every call first waits for the one-time legacy import.
 */
object PipelineRecords {
    private const val TAG = "PipelineRecords"
    private const val MAX_IN = 500
    private val LOW_SIGNAL = listOf(
        "no visual content", "only file references", "file references and timestamps", "metadata only", "no image pixels",
        "no visual content or scene details",
    )

    data class PurgeResult(val kept: Int, val dropped: Int, val deletedFiles: Int)

    private suspend fun graph(context: Context): MemoryGraph {
        withContext(Dispatchers.IO) { MemoryGraph.ensureLegacyImported(context) }
        return MemoryGraph.get(context)
    }

    private fun oneLine(s: String) = s.replace(TextHygiene.SEPARATORS, " ").trim()

    // --- frames -------------------------------------------------------------------------------------------------

    /** Only `rokid*` sources with a JPEG that exists; payload kept to one line (≤ 220 chars). False = rejected or duplicate id. */
    suspend fun appendFrame(context: Context, frame: FrameEntity): Boolean {
        if (!frame.source.startsWith("rokid", ignoreCase = true)) { Log.w(TAG, "append rejected non-rokid source=${frame.source}"); return false }
        val file = frame.path.takeIf { it.isNotBlank() }?.let(::File)
        if (file == null || !withContext(Dispatchers.IO) { file.exists() }) { Log.w(TAG, "append rejected preview missing frameId=${frame.id}"); return false }
        val ok = graph(context).frames.add(frame.copy(path = file.absolutePath, payload = oneLine(frame.payload).take(220)))
        Log.i(TAG, "append ok=$ok frameId=${frame.id}")
        return ok
    }

    /** Real, on-disk `rokid*` frames that no Forget has tombstoned (the extraction lock, spec §6.1.1). */
    private suspend fun existing(g: MemoryGraph, list: List<FrameEntity>): List<FrameEntity> {
        val locked = list.map { it.id }.distinct().chunked(MAX_IN).flatMap { g.db.forgetDao().tombstoned(it) }.toSet()
        return withContext(Dispatchers.IO) {
            list.filter { it.id !in locked && it.source.startsWith("rokid", ignoreCase = true) && it.path.isNotBlank() && File(it.path).exists() }
        }
    }

    suspend fun recentFrames(context: Context, limit: Int): List<FrameEntity> {
        val n = limit.coerceAtLeast(1)
        val g = graph(context)
        return existing(g, g.frames.recent(n * 2)).take(n)
    }

    suspend fun allFrames(context: Context): List<FrameEntity> = graph(context).let { g -> existing(g, g.frames.all()) }

    suspend fun framesByIds(context: Context, ids: List<String>): List<FrameEntity> {
        val wanted = ids.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (wanted.isEmpty()) return emptyList()
        val g = graph(context)
        return existing(g, wanted.chunked(MAX_IN).flatMap { g.frames.byIds(it) }.sortedBy { it.ts })
    }

    suspend fun purgeNonRokidFrames(context: Context) {
        val g = graph(context)
        g.frames.deleteByIds(g.frames.all().filter { !it.source.startsWith("rokid", ignoreCase = true) || it.path.isBlank() }.map { it.id })
    }

    suspend fun purgeNonPhotoAndSynthetic(context: Context): PurgeResult {
        val g = graph(context)
        val (kept, dropped) = g.frames.all().partition(::isRealPhotoFrame)
        g.frames.deleteByIds(dropped.map { it.id })
        val deleted = withContext(Dispatchers.IO) {
            dropped.map { it.path }.filter { it.isNotBlank() }.distinct().count { File(it).let { f -> f.exists() && f.delete() } }
        }
        return PurgeResult(kept = kept.size, dropped = dropped.size, deletedFiles = deleted)
    }

    /** Same rule as the legacy facade (kept verbatim; the "match file name only" refinement is a Phase 2 deferred minor). */
    fun isRealPhotoFrame(frame: FrameEntity): Boolean {
        if (!frame.source.startsWith("rokid", ignoreCase = true)) return false
        if (frame.path.isBlank()) return false
        val payload = frame.payload.lowercase()
        val path = frame.path.lowercase()
        if (payload.contains("synthetic") || path.contains("synthetic")) return false
        if (payload.contains("screenshot") || path.contains("screenshot")) return false
        return File(frame.path).extension.lowercase() in setOf("jpg", "jpeg", "webp")
    }

    // --- memories -----------------------------------------------------------------------------------------------

    suspend fun appendMemory(context: Context, id: String, type: String, content: String, confidence: Double, createdAt: Long) {
        graph(context).memories.add(LegacyImporter.toMemory(id, type, confidence, createdAt, oneLine(content).take(260)))
    }

    /**
     * One extracted fact (spec §4.4 VISION_EXTRACT / EXPLICIT_REMEMBER) or a summary, indexed for search. False (nothing
     * written) when Delete all ran after [generation] was read: a queue job passes the value it read before its claim.
     * Ids must keep the `rokid_` prefix (and not `rokid_review_`) or [purgeNonRokidMemories] / [purgeLowSignalMemories] drop them.
     * [sourceIds] (Phase 7): the memories this one was derived from. Their `memory_source` edges are written in the
     * same transaction, so a later Forget of a source cascades here. When any source was forgotten (missing or
     * INVALIDATED) since the caller read it, nothing is written: the output may hold forgotten text (the Forget fence).
     */
    suspend fun appendFact(
        context: Context, id: String, frameId: String?, text: String, type: String, tags: List<String>, confidence: Double,
        tier: MemoryTier, reviewState: ReviewState, createdAt: Long = System.currentTimeMillis(),
        generation: Long = MemoryWipe.generation.get(),
        derived: Boolean = type == "summary",
        sourceIds: List<String> = emptyList(),
    ): Boolean {
        val g = graph(context)
        // Review I1: the Delete-all check and the insert share one transaction, and Delete all bumps the generation
        // before its own transaction, so a fact from work begun before the wipe can never land after it.
        return g.db.withTransaction {
            if (MemoryWipe.generation.get() != generation) {
                Log.i(TAG, "fact dropped: Delete all ran since this job started")
                return@withTransaction false
            }
            val sources = sourceIds.distinct()
            if (sources.isNotEmpty()) {
                val alive = sources.chunked(MAX_IN).flatMap { g.db.forgetDao().memories(it) }.count { it.reviewState != ReviewState.INVALIDATED }
                if (alive != sources.size) {
                    Log.i(TAG, "fact dropped: a source was forgotten since this job started")
                    return@withTransaction false
                }
            }
            g.memories.add(
            MemoryEntity(
                id = id, frameId = frameId, text = oneLine(text).take(260), type = oneLine(type).take(20).ifEmpty { "note" },
                tags = tags.map { oneLine(it).lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.take(5).joinToString(" "),
                tier = tier, confidence = confidence.coerceIn(0.0, 1.0), reviewState = reviewState, trusted = false,
                createdAt = createdAt, derived = derived, searchText = "",
            ),
        )
            if (sources.isNotEmpty()) g.db.forgetDao().insertEdges(sources.map { MemorySourceEntity(id, it) })
            true
        }
    }

    /** Fix M5: before a job (re)writes facts `<prefix>0..keep-1`, drop its rows `<prefix>keep…` left by an earlier run. */
    suspend fun dropFactsFrom(context: Context, prefix: String, keep: Int) {
        val g = graph(context)
        g.memories.deleteByIds(
            g.memories.idsWithPrefix(prefix).filter { id -> id.removePrefix(prefix).toIntOrNull()?.let { it >= keep } == true },
        )
    }

    suspend fun recentMemories(context: Context, limit: Int): List<MemoryEntity> = graph(context).memories.recent(limit)

    /** Re-read at run time for a model call: missing and INVALIDATED (forgotten) memories are dropped. Order of [ids]. */
    suspend fun liveMemories(context: Context, ids: List<String>): List<MemoryEntity> {
        val byId = graph(context).timeline.memoriesByIds(ids).filter { it.reviewState != ReviewState.INVALIDATED }.associateBy { it.id }
        return ids.distinct().mapNotNull { byId[it] }
    }

    suspend fun purgeNonRokidMemories(context: Context) {
        val g = graph(context)
        g.memories.deleteByIds(g.memories.all().filterNot { it.id.startsWith("rokid_", ignoreCase = true) }.map { it.id })
    }

    suspend fun purgeLowSignalMemories(context: Context) {
        val g = graph(context)
        g.memories.deleteByIds(
            g.memories.all().filter { m ->
                m.id.startsWith("rokid_review_", ignoreCase = true) || LOW_SIGNAL.any { m.text.lowercase().contains(it) }
            }.map { it.id },
        )
    }

    // --- summary traces -----------------------------------------------------------------------------------------

    suspend fun upsertTrace(context: Context, summaryId: String, state: String, note: String, updatedAt: Long = System.currentTimeMillis()) {
        graph(context).traces.upsert(SummaryTraceEntity(summaryId, state, updatedAt, oneLine(note)))
    }

    suspend fun recentTraces(context: Context, limit: Int): List<SummaryTraceEntity> = graph(context).traces.recent(limit)

    suspend fun purgeSyntheticTraces(context: Context) {
        val g = graph(context)
        g.traces.deleteByIds(
            g.traces.recent(Int.MAX_VALUE).filter { it.note.contains("placeholder", ignoreCase = true) || it.summaryId.startsWith("frame_ingest", ignoreCase = true) }
                .map { it.summaryId },
        )
    }
}
