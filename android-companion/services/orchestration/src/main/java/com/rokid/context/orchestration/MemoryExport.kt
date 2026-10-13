package com.rokid.context.orchestration

import android.content.Context
import android.util.JsonWriter
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.ExportDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
import kotlin.coroutines.coroutineContext

/** Settings → Memory › Export: memories and asks as JSON in app cache (shared via FileProvider). No photos, no settings, no keys. */
object MemoryExport {
    private const val PREFIX = "proactive-context-memories-"

    /** cache/exports: the only folder the FileProvider shares (res/xml/file_paths.xml); Delete all empties it. */
    fun dir(context: Context): File = File(context.cacheDir, "exports")

    suspend fun write(context: Context, graph: MemoryGraph = MemoryGraph.get(context), nowMs: Long = System.currentTimeMillis(), pageSize: Int = 500): File =
        write(context, graph.db.exportDao(), nowMs, pageSize)

    /**
     * Streams the library page by page ([pageSize] rows per DAO call, rowid order) through a [JsonWriter], so peak
     * memory is one page (Phase 8 B1). Every ask is exported. The file is written as `.part` and renamed; older memory
     * exports go only after the rename. On any failure or cancellation the `.part` is deleted and the previous export stays.
     */
    internal suspend fun write(context: Context, dao: ExportDao, nowMs: Long, pageSize: Int = 500): File = withContext(Dispatchers.IO) {
        val page = pageSize.coerceAtLeast(1)
        val dir = dir(context).apply { mkdirs() }
        val target = File(dir, "$PREFIX$nowMs.json")
        val part = File(dir, "${target.name}.part")
        try {
            JsonWriter(OutputStreamWriter(part.outputStream().buffered(), Charsets.UTF_8)).use { w ->
                w.beginObject()
                w.name("exportedAt").value(nowMs)
                w.name("memories").beginArray()
                var after = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val rows = dao.memoryPage(after, page)
                    for (m in rows) {
                        w.beginObject()
                        w.name("id").value(m.id).name("text").value(m.text).name("type").value(m.type).name("tags").value(m.tags)
                        w.name("tier").value(m.tier.name).name("confidence").value(m.confidence).name("review").value(m.reviewState.name)
                        w.name("createdAt").value(m.createdAt).name("frameId")
                        if (m.frameId == null) w.nullValue() else w.value(m.frameId)
                        w.endObject()
                    }
                    if (rows.size < page) break
                    after = rows.last().rowid
                }
                w.endArray()
                w.name("asks").beginArray()
                after = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val rows = dao.askPage(after, page)
                    for (a in rows) {
                        w.beginObject().name("id").value(a.id).name("at").value(a.ts).name("question").value(a.question).name("answer").value(a.answer).endObject()
                    }
                    if (rows.size < page) break
                    after = rows.last().rowid
                }
                w.endArray()
                w.endObject()
            }
            if (!part.renameTo(target)) throw IOException("export rename failed")
        } catch (t: Throwable) {
            part.delete()
            throw t
        }
        // Keep only the newest memory export; other files here (Task 21's diagnostics export) are not ours (F22).
        dir.listFiles()?.filter { it.isFile && it.name.startsWith(PREFIX) && it.name.endsWith(".json") && it.name != target.name }?.forEach { it.delete() }
        target
    }
}
