package com.rokid.context.memory.legacy

import android.content.Context
import androidx.room.withTransaction
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.db.SummaryTraceEntity
import java.io.File

data class ImportReport(val memories: Int, val frames: Int, val traces: Int, val skipped: Int)

/**
 * One-time copy of the legacy tab-separated logs into Room (spec §6.2). Safe to re-run: inserts ignore existing ids.
 * P8 D5: each file is streamed ([useLines]) and written in [inTransaction] batches of [BATCH] rows.
 */
class LegacyImporter(
    private val context: Context,
    private val graph: MemoryGraph,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit = { block -> graph.db.withTransaction { block() } },
) {
    private val prefs = prefs(context)

    fun isDone(): Boolean = prefs.getBoolean(K_DONE, false)

    suspend fun importAll(): ImportReport {
        var skipped = 0
        var memories = 0
        var frames = 0
        var traces = 0

        forEachBatch(MEMORY_LOG) { line ->
            val p = line.split("\t", limit = 5)
            // P8 D5: a non-numeric or blank confidence imports as 0.0, as the legacy reader tolerated, instead of being lost.
            val confidence = p.getOrNull(2)?.toDoubleOrNull() ?: 0.0
            val createdAt = p.getOrNull(3)?.toLongOrNull()
            if (p.size < 5 || createdAt == null) { skipped++; return@forEachBatch }
            val (id, type, content) = Triple(p[0], p[1], p[4])
            if (!id.startsWith("rokid_", ignoreCase = true) || id.startsWith("rokid_review_", ignoreCase = true) ||
                LOW_SIGNAL.any { content.lowercase().contains(it) }
            ) return@forEachBatch
            if (graph.memories.addIgnore(toMemory(id, type, confidence, createdAt, content))) memories++
        }

        forEachBatch(FRAME_LOG) { line ->
            val p = line.split("\t")
            val ts = p.getOrNull(1)?.toLongOrNull()
            if (p.size < 5 || ts == null) { skipped++; return@forEachBatch }
            val (id, source, payload, path) = listOf(p[0], p[2], p[3], p[4])
            if (!isRealPhoto(source, payload, path)) return@forEachBatch
            if (graph.frames.add(FrameEntity(id = id, ts = ts, path = path, source = source, payload = payload))) frames++
        }

        forEachBatch(TRACE_LOG) { line ->
            val p = line.split("\t", limit = 4)
            val updated = p.getOrNull(2)?.toLongOrNull()
            if (p.size < 4 || updated == null) { skipped++; return@forEachBatch }
            if (p[3].contains("placeholder", ignoreCase = true) || p[0].startsWith("frame_ingest", ignoreCase = true)) return@forEachBatch
            if (graph.traces.addIgnore(SummaryTraceEntity(p[0], p[1], updated, p[3]))) traces++
        }

        prefs.edit().putBoolean(K_DONE, true).commit()
        return ImportReport(memories, frames, traces, skipped)
    }

    /** Streams [name]'s non-blank lines, [BATCH] per transaction; a missing file is nothing to import. */
    private suspend fun forEachBatch(name: String, row: suspend (String) -> Unit) {
        val file = File(context.filesDir, name)
        if (!file.exists()) return
        file.useLines { lines ->
            lines.filter { it.isNotBlank() }.chunked(BATCH).forEach { batch -> inTransaction { batch.forEach { row(it) } } }
        }
    }

    /** The "synthetic"/"screenshot" test looks at the payload and the file name only, never the folders (P8 D5). */
    private fun isRealPhoto(source: String, payload: String, path: String): Boolean {
        if (!source.startsWith("rokid", ignoreCase = true) || path.isBlank()) return false
        val name = File(path).name
        if (listOf(payload, name).any { it.contains("synthetic", true) || it.contains("screenshot", true) }) return false
        if (File(path).extension.lowercase() !in setOf("jpg", "jpeg", "webp")) return false
        return File(path).exists()
    }

    companion object {
        private const val BATCH = 500
        private const val K_DONE = "v1_done"
        private fun prefs(context: Context) = context.getSharedPreferences("legacy_import", Context.MODE_PRIVATE)

        /** Only through MemoryGraph.markLegacyImportDone (under the import lock). */
        internal fun markDone(context: Context) { prefs(context).edit().putBoolean(K_DONE, true).commit() }
        private const val MEMORY_LOG = "memory_records.log"
        private const val FRAME_LOG = "captured_frames.log"
        private const val TRACE_LOG = "summary_trace.log"
        /** The legacy plaintext logs in filesDir (kept one release, spec §6.2); Delete all removes them. */
        val LEGACY_FILES: List<String> = listOf(MEMORY_LOG, FRAME_LOG, TRACE_LOG)
        /** Same markers as `PipelineRecords.purgeLowSignalMemories` (orchestration). */
        private val LOW_SIGNAL = listOf(
            "no visual content", "only file references", "file references and timestamps", "metadata only", "no image pixels",
            "no visual content or scene details",
        )

        fun legacyTier(type: String): MemoryTier = when (type.lowercase()) {
            "medium_term" -> MemoryTier.MEDIUM
            "long_term" -> MemoryTier.LONG
            else -> MemoryTier.SHORT
        }

        fun legacyReviewState(type: String): ReviewState = if (type.equals("review", true)) ReviewState.NEEDS_REVIEW else ReviewState.OK

        fun toMemory(id: String, type: String, confidence: Double, createdAt: Long, content: String) = MemoryEntity(
            id = id, frameId = null, text = content, type = type, tags = "", tier = legacyTier(type), confidence = confidence,
            reviewState = legacyReviewState(type), trusted = false, createdAt = createdAt, derived = false, searchText = "",
        )
    }
}
