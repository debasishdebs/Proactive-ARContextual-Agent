package com.rokid.context.memory.legacy

import android.content.Context
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.db.SummaryTraceEntity
import java.io.File

data class ImportReport(val memories: Int, val frames: Int, val traces: Int, val skipped: Int)

/** One-time copy of the legacy tab-separated logs into Room (spec §6.2). Safe to re-run: inserts ignore existing ids. */
class LegacyImporter(private val context: Context, private val graph: MemoryGraph) {
    private val prefs = context.getSharedPreferences("legacy_import", Context.MODE_PRIVATE)

    fun isDone(): Boolean = prefs.getBoolean(K_DONE, false)

    suspend fun importAll(): ImportReport {
        var skipped = 0
        var memories = 0
        var frames = 0
        var traces = 0

        lines(MEMORY_LOG).forEach { line ->
            val p = line.split("\t", limit = 5)
            val confidence = p.getOrNull(2)?.toDoubleOrNull()
            val createdAt = p.getOrNull(3)?.toLongOrNull()
            if (p.size < 5 || confidence == null || createdAt == null) { skipped++; return@forEach }
            val (id, type, content) = Triple(p[0], p[1], p[4])
            if (!id.startsWith("rokid_", ignoreCase = true) || id.startsWith("rokid_review_", ignoreCase = true) ||
                LOW_SIGNAL.any { content.lowercase().contains(it) }
            ) return@forEach
            if (graph.memories.addIgnore(toMemory(id, type, confidence, createdAt, content))) memories++
        }

        lines(FRAME_LOG).forEach { line ->
            val p = line.split("\t")
            val ts = p.getOrNull(1)?.toLongOrNull()
            if (p.size < 5 || ts == null) { skipped++; return@forEach }
            val (id, source, payload, path) = listOf(p[0], p[2], p[3], p[4])
            if (!isRealPhoto(source, payload, path)) return@forEach
            if (graph.frames.add(FrameEntity(id = id, ts = ts, path = path, source = source, payload = payload))) frames++
        }

        lines(TRACE_LOG).forEach { line ->
            val p = line.split("\t", limit = 4)
            val updated = p.getOrNull(2)?.toLongOrNull()
            if (p.size < 4 || updated == null) { skipped++; return@forEach }
            if (p[3].contains("placeholder", ignoreCase = true) || p[0].startsWith("frame_ingest", ignoreCase = true)) return@forEach
            if (graph.traces.addIgnore(SummaryTraceEntity(p[0], p[1], updated, p[3]))) traces++
        }

        prefs.edit().putBoolean(K_DONE, true).commit()
        return ImportReport(memories, frames, traces, skipped)
    }

    private fun lines(name: String): List<String> =
        File(context.filesDir, name).takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() }.orEmpty()

    private fun isRealPhoto(source: String, payload: String, path: String): Boolean {
        if (!source.startsWith("rokid", ignoreCase = true) || path.isBlank()) return false
        if (listOf(payload, path).any { it.contains("synthetic", true) || it.contains("screenshot", true) }) return false
        if (File(path).extension.lowercase() !in setOf("jpg", "jpeg", "webp")) return false
        return File(path).exists()
    }

    companion object {
        private const val K_DONE = "v1_done"
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
