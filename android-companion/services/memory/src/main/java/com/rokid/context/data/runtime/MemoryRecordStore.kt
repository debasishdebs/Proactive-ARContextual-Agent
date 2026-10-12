package com.rokid.context.data.runtime

import android.content.Context

data class MemoryRecord(
    val id: String,
    val memoryType: String,
    val content: String,
    val confidence: Double,
    val createdAtEpochMs: Long,
)

/** Transitional facade over Room (Phase 2); deleted with the legacy UI in Phase 4. */
object MemoryRecordStore {
    private val lowSignalMarkers = listOf(
        "no visual content", "only file references", "file references and timestamps", "metadata only", "no image pixels",
        "no visual content or scene details",
    )

    private fun <T> io(context: Context, block: suspend (com.rokid.context.memory.MemoryGraph) -> T): T {
        com.rokid.context.memory.MemoryGraph.ensureLegacyImported(context)
        val graph = com.rokid.context.memory.MemoryGraph.get(context)
        return kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) { block(graph) }
    }

    fun append(context: Context, record: MemoryRecord) {
        io(context) {
            val safe = record.content.replace("\n", " ").replace("\t", " ").take(260)
            it.memories.add(
                com.rokid.context.memory.legacy.LegacyImporter.toMemory(record.id, record.memoryType, record.confidence, record.createdAtEpochMs, safe),
            )
        }
    }

    fun recent(context: Context, limit: Int): List<MemoryRecord> = io(context) { g ->
        g.memories.recent(limit).map { MemoryRecord(it.id, it.type, it.text, it.confidence, it.createdAt) }
    }

    fun purgeNonRokid(context: Context) {
        io(context) { g ->
            g.memories.deleteByIds(g.memories.all().filterNot { it.id.startsWith("rokid_", ignoreCase = true) }.map { it.id })
        }
    }

    fun purgeLowSignalSummaries(context: Context) {
        io(context) { g ->
            g.memories.deleteByIds(
                g.memories.all().filter { m ->
                    m.id.startsWith("rokid_review_", ignoreCase = true) || lowSignalMarkers.any { m.text.lowercase().contains(it) }
                }.map { it.id },
            )
        }
    }
}
