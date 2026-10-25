package com.rokid.context.orchestration

import android.content.Context
import android.util.JsonWriter
import com.rokid.context.ai.SafeLog
import com.rokid.context.core.log.PcLog
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

    /**
     * GAP-1: the cached memory exports (and an unfinished `.part`) hold memory and ask text verbatim. A Forget, a retention
     * pass and a memory edit remove them all (they are regenerable); the diagnostics export in the same folder is not ours.
     * Best effort: a failure is logged by class only and never turns the Forget into a failure.
     */
    fun deleteCached(context: Context) {
        try {
            // One lock with the publication in write(): the directory is listed AFTER taking it, and an export that started before this call can never publish after it (generation).
            synchronized(publishLock) {
                generation++
                val listed = dir(context).listFiles()?.filter { it.isFile && it.name.startsWith(PREFIX) }
                testAfterEnumerate?.invoke()
                listed?.forEach { it.delete() }
            }
        } catch (e: Exception) {
            PcLog.w("MemoryExport", "cached exports not deleted: ${SafeLog.describe(e)}")
        }
    }

    private val publishLock = Any()
    /** Moves with every [deleteCached]; guarded by [publishLock]. An export that read the library before a Forget must not be published after it. */
    private var generation = 0L
    /** Test seam: runs after the `.part` is complete and before it is published. Always null in production. */
    /** Test seam: runs in [deleteCached] after the directory was listed and before the files are deleted. Always null in production. */
    @Volatile internal var testAfterEnumerate: (() -> Unit)? = null
    @Volatile internal var testBeforePublish: (() -> Unit)? = null

    suspend fun write(context: Context, graph: MemoryGraph = MemoryGraph.get(context), nowMs: Long = System.currentTimeMillis(), pageSize: Int = 500): File =
        write(context, graph.db.exportDao(), nowMs, pageSize)

    /** The shared side of the [DataOpGate]: refused with [DataOpGate.DeleteRunning] while a delete runs. [gate] is a test seam (production: the one process-wide gate). */
    internal suspend fun write(context: Context, dao: ExportDao, nowMs: Long, pageSize: Int = 500, gate: DataOpGate = DataOpGate.shared): File =
        gate.export { writeLocked(context, dao, nowMs, pageSize) }

    /**
     * Streams the library page by page ([pageSize] rows per DAO call, rowid order) through a [JsonWriter], so peak
     * memory is one page (Phase 8 B1). Every ask is exported. The file is written as `.part` and renamed; older memory
     * exports go only after the rename. On any failure or cancellation the `.part` is deleted and the previous export stays.
     * A delete (Forget, Delete all, retention, outbox) cannot run while this does and this cannot start while one runs ([DataOpGate]); the generation check below stays
     * as a cheap defensive check for the one writer the gate does not cover (a memory edit calls [deleteCached] too).
     */
    private suspend fun writeLocked(context: Context, dao: ExportDao, nowMs: Long, pageSize: Int): File = withContext(Dispatchers.IO) {
        val page = pageSize.coerceAtLeast(1)
        val dir = dir(context).apply { mkdirs() }
        val target = File(dir, "$PREFIX$nowMs.json")
        val part = File(dir, "${target.name}.part")
        val startedAt = synchronized(publishLock) { generation }
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
            testBeforePublish?.invoke()
            synchronized(publishLock) {
                if (generation != startedAt) throw IOException("export is stale: a Forget ran while it was written") // its text may be what was just forgotten
                if (!part.renameTo(target)) throw IOException("export rename failed")
                // Keep only the newest memory export; other files here (Task 21's diagnostics export) are not ours (F22).
                dir.listFiles()?.filter { it.isFile && it.name.startsWith(PREFIX) && it.name.endsWith(".json") && it.name != target.name }?.forEach { it.delete() }
            }
        } catch (t: Throwable) {
            part.delete()
            throw t
        }
        target
    }
}
