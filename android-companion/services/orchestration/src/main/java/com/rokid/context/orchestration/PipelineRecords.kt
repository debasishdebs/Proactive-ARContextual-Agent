package com.rokid.context.orchestration

import android.content.Context
import android.util.Log
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.MemoryEntity
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
    private val LOW_SIGNAL = listOf(
        "no visual content", "only file references", "file references and timestamps", "metadata only", "no image pixels",
        "no visual content or scene details",
    )

    data class PurgeResult(val kept: Int, val dropped: Int, val deletedFiles: Int)

    private suspend fun graph(context: Context): MemoryGraph {
        withContext(Dispatchers.IO) { MemoryGraph.ensureLegacyImported(context) }
        return MemoryGraph.get(context)
    }

    private val LINE_BREAKS = Regex("[\\s\\p{Cntrl}\\u2028\\u2029\\u0085]+")
    private fun oneLine(s: String) = s.replace(LINE_BREAKS, " ").trim()

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

    private suspend fun existing(list: List<FrameEntity>): List<FrameEntity> = withContext(Dispatchers.IO) {
        list.filter { it.source.startsWith("rokid", ignoreCase = true) && it.path.isNotBlank() && File(it.path).exists() }
    }

    suspend fun recentFrames(context: Context, limit: Int): List<FrameEntity> {
        val n = limit.coerceAtLeast(1)
        return existing(graph(context).frames.recent(n * 2)).take(n)
    }

    suspend fun allFrames(context: Context): List<FrameEntity> = existing(graph(context).frames.all())

    suspend fun framesByIds(context: Context, ids: List<String>): List<FrameEntity> {
        val wanted = ids.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        return if (wanted.isEmpty()) emptyList() else existing(graph(context).frames.byIds(wanted))
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
     * One extracted fact (spec §4.4 VISION_EXTRACT / EXPLICIT_REMEMBER) or a summary, indexed for search.
     * Ids must keep the `rokid_` prefix (and not `rokid_review_`) or [purgeNonRokidMemories] / [purgeLowSignalMemories] drop them.
     */
    suspend fun appendFact(
        context: Context, id: String, frameId: String?, text: String, type: String, tags: List<String>, confidence: Double,
        tier: MemoryTier, reviewState: ReviewState, createdAt: Long = System.currentTimeMillis(),
    ) {
        graph(context).memories.add(
            MemoryEntity(
                id = id, frameId = frameId, text = oneLine(text).take(260), type = oneLine(type).take(20).ifEmpty { "note" },
                tags = tags.map { oneLine(it).lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.take(5).joinToString(" "),
                tier = tier, confidence = confidence.coerceIn(0.0, 1.0), reviewState = reviewState, trusted = false,
                createdAt = createdAt, derived = type == "summary", searchText = "",
            ),
        )
    }

    suspend fun recentMemories(context: Context, limit: Int): List<MemoryEntity> = graph(context).memories.recent(limit)

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
