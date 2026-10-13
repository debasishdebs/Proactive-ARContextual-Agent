package com.rokid.context.orchestration

import android.content.Context
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.ReviewState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/** Settings → Memory › Export: memories and asks as JSON in app cache (shared via FileProvider). No photos, no settings, no keys. */
object MemoryExport {
    private const val PREFIX = "proactive-context-memories-"

    /** cache/exports: the only folder the FileProvider shares (res/xml/file_paths.xml); Delete all empties it. */
    fun dir(context: Context): File = File(context.cacheDir, "exports")

    suspend fun write(context: Context, graph: MemoryGraph = MemoryGraph.get(context), nowMs: Long = System.currentTimeMillis()): File {
        // Forgotten (INVALIDATED) rows keep their text until Phase 7's redaction; never export it (review M3).
        val memories = graph.memories.all().filter { it.reviewState != ReviewState.INVALIDATED }
        val asks = graph.asks.recent(10_000).first()
        val json = JsonObject(mapOf(
            "exportedAt" to JsonPrimitive(nowMs),
            "memories" to JsonArray(memories.map { m ->
                JsonObject(mapOf(
                    "id" to JsonPrimitive(m.id), "text" to JsonPrimitive(m.text), "type" to JsonPrimitive(m.type), "tags" to JsonPrimitive(m.tags),
                    "tier" to JsonPrimitive(m.tier.name), "confidence" to JsonPrimitive(m.confidence), "review" to JsonPrimitive(m.reviewState.name),
                    "createdAt" to JsonPrimitive(m.createdAt), "frameId" to (m.frameId?.let { JsonPrimitive(it) } ?: JsonNull),
                ))
            }),
            "asks" to JsonArray(asks.map { a ->
                JsonObject(mapOf("id" to JsonPrimitive(a.id), "at" to JsonPrimitive(a.ts), "question" to JsonPrimitive(a.question), "answer" to JsonPrimitive(a.answer)))
            }),
        ))
        return withContext(Dispatchers.IO) {
            val dir = dir(context).apply { mkdirs() }
            // Keep only the newest memory export; other files here (Task 21's diagnostics export) are not ours (F22).
            dir.listFiles()?.filter { it.isFile && it.name.startsWith(PREFIX) && it.name.endsWith(".json") }?.forEach { it.delete() }
            File(dir, "$PREFIX$nowMs.json").apply { writeText(json.toString()) }
        }
    }
}
