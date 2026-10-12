package com.rokid.context.data.runtime

import android.content.Context

data class SummaryTrace(
    val summaryId: String,
    val state: String,
    val updatedAtEpochMs: Long,
    val note: String,
)

object SummaryTraceStore {
    private fun <T> io(context: Context, block: suspend (com.rokid.context.memory.MemoryGraph) -> T): T {
        com.rokid.context.memory.MemoryGraph.ensureLegacyImported(context)
        val graph = com.rokid.context.memory.MemoryGraph.get(context)
        return kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) { block(graph) }
    }

    fun upsert(context: Context, trace: SummaryTrace) {
        io(context) {
            it.traces.upsert(com.rokid.context.memory.db.SummaryTraceEntity(trace.summaryId, trace.state, trace.updatedAtEpochMs, trace.note.replace("\n", " ").replace("\t", " ")))
        }
    }

    fun recent(context: Context, limit: Int): List<SummaryTrace> = io(context) { g ->
        g.traces.recent(limit).map { SummaryTrace(it.summaryId, it.state, it.updatedAt, it.note) }
    }

    fun purgeSynthetic(context: Context) {
        io(context) { g ->
            g.traces.deleteByIds(g.traces.recent(Int.MAX_VALUE).filter { it.note.contains("placeholder", true) || it.summaryId.startsWith("frame_ingest", true) }.map { it.summaryId })
        }
    }
}
