package com.rokid.context.data.runtime

import android.content.Context
import java.io.File

data class MemoryRecord(
    val id: String,
    val memoryType: String,
    val content: String,
    val confidence: Double,
    val createdAtEpochMs: Long,
)

object MemoryRecordStore {
    private const val FILE_NAME = "memory_records.log"
    private val lock = Any()

    fun append(context: Context, record: MemoryRecord) {
        synchronized(lock) {
            val file = file(context)
            file.parentFile?.mkdirs()
            if (!file.exists()) file.createNewFile()
            val safe = record.content.replace("\n", " ").replace("\t", " ").take(260)
            file.appendText("${record.id}\t${record.memoryType}\t${record.confidence}\t${record.createdAtEpochMs}\t$safe\n")
        }
    }

    fun recent(context: Context, limit: Int): List<MemoryRecord> {
        synchronized(lock) {
            val file = file(context)
            if (!file.exists()) return emptyList()
            return file.readLines().asReversed().asSequence().mapNotNull { parse(it) }.take(limit).toList()
        }
    }

    fun purgeNonRokid(context: Context) {
        synchronized(lock) {
            val file = file(context)
            if (!file.exists()) return
            val kept = file.readLines().mapNotNull { parse(it) }.filter {
                it.id.startsWith("rokid_", ignoreCase = true)
            }
            file.writeText("")
            kept.forEach { record ->
                val safe = record.content.replace("\n", " ").replace("\t", " ").take(260)
                file.appendText("${record.id}\t${record.memoryType}\t${record.confidence}\t${record.createdAtEpochMs}\t$safe\n")
            }
        }
    }

    fun purgeLowSignalSummaries(context: Context) {
        synchronized(lock) {
            val file = file(context)
            if (!file.exists()) return
            val lowSignalMarkers = listOf(
                "no visual content",
                "only file references",
                "file references and timestamps",
                "metadata only",
                "no image pixels",
                "no visual content or scene details",
            )
            val kept = file.readLines().mapNotNull { parse(it) }.filter { record ->
                if (record.id.startsWith("rokid_review_", ignoreCase = true)) {
                    return@filter false
                }
                val text = record.content.lowercase()
                !lowSignalMarkers.any { marker -> text.contains(marker) }
            }
            file.writeText("")
            kept.forEach { record ->
                val safe = record.content.replace("\n", " ").replace("\t", " ").take(260)
                file.appendText("${record.id}\t${record.memoryType}\t${record.confidence}\t${record.createdAtEpochMs}\t$safe\n")
            }
        }
    }

    private fun parse(line: String): MemoryRecord? {
        val parts = line.split("\t", limit = 5)
        if (parts.size < 5) return null
        return MemoryRecord(
            id = parts[0],
            memoryType = parts[1],
            confidence = parts[2].toDoubleOrNull() ?: 0.0,
            createdAtEpochMs = parts[3].toLongOrNull() ?: return null,
            content = parts[4],
        )
    }

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)
}
