package com.rokid.context.data.runtime

import android.content.Context
import java.io.File

data class SummaryTrace(
    val summaryId: String,
    val state: String,
    val updatedAtEpochMs: Long,
    val note: String,
)

object SummaryTraceStore {
    private const val FILE_NAME = "summary_trace.log"
    private val lock = Any()

    fun upsert(context: Context, trace: SummaryTrace) {
        synchronized(lock) {
            val current = recent(context, 200).associateBy { it.summaryId }.toMutableMap()
            current[trace.summaryId] = trace
            val sorted = current.values.sortedByDescending { it.updatedAtEpochMs }
            val file = file(context)
            file.parentFile?.mkdirs()
            file.writeText("")
            sorted.forEach { t ->
                val safeNote = t.note.replace("\n", " ").replace("\t", " ")
                file.appendText("${t.summaryId}\t${t.state}\t${t.updatedAtEpochMs}\t$safeNote\n")
            }
        }
    }

    fun recent(context: Context, limit: Int): List<SummaryTrace> {
        synchronized(lock) {
            val file = file(context)
            if (!file.exists()) return emptyList()
            return file.readLines().asSequence().mapNotNull { parse(it) }.take(limit).toList()
        }
    }

    fun purgeSynthetic(context: Context) {
        synchronized(lock) {
            val file = file(context)
            if (!file.exists()) return
            val kept = file.readLines().mapNotNull { parse(it) }.filterNot {
                it.note.contains("placeholder", ignoreCase = true) ||
                    it.summaryId.startsWith("frame_ingest", ignoreCase = true)
            }
            file.writeText("")
            kept.sortedByDescending { it.updatedAtEpochMs }.forEach { t ->
                val safeNote = t.note.replace("\n", " ").replace("\t", " ")
                file.appendText("${t.summaryId}\t${t.state}\t${t.updatedAtEpochMs}\t$safeNote\n")
            }
        }
    }

    private fun parse(line: String): SummaryTrace? {
        val parts = line.split("\t", limit = 4)
        if (parts.size < 4) return null
        return SummaryTrace(
            summaryId = parts[0],
            state = parts[1],
            updatedAtEpochMs = parts[2].toLongOrNull() ?: return null,
            note = parts[3],
        )
    }

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)
}
