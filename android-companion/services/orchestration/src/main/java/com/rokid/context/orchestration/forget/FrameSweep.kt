package com.rokid.context.orchestration.forget

import android.content.Context
import com.rokid.context.core.log.PcLog
import com.rokid.context.data.frames.RokidFrameInbox
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.orchestration.WipeFence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Durable photo deletion (fix round 1 I3). A Forget lists JPEGs to delete after its transaction; a delete that fails
 * (or a process death before it) is caught here. The tombstone has no path column (schema v1), so the path is derived:
 * the frame row's `path` when the row is kept, otherwise any file in [RokidFrameInbox.FRAME_DIRS] whose name without
 * extension is the frame id (how frames are named on ingest). A tombstoned frame that a memory still points at keeps
 * its JPEG. Runs at start-up and in every retention pass, and prunes tombstones nothing needs (final review I2).
 */
object FrameSweep {
    private const val TAG = "FrameSweep"

    /** Deletes [paths]; each failure is logged (class only) and left for [sweep]. Returns how many were deleted. */
    suspend fun delete(paths: Collection<String>): Int = withContext(Dispatchers.IO) {
        // Under the fence lock (Gaps G1): an encryption swap in progress can never bring a forgotten photo back.
        WipeFence.mutex.withLock { deleteNow(paths) }
    }

    private fun deleteNow(paths: Collection<String>): Int =
        paths.distinct().count { p ->
            val f = File(p)
            val ok = !f.exists() || runCatching { f.delete() }.getOrDefault(false)
            if (!ok) PcLog.w(TAG, "a forgotten photo could not be deleted yet; the sweep retries")
            ok && f.exists().not()
        }

    /**
     * Final review I2 ruling (spec §6.1.1): a tombstone is pruned once nothing can need it: no frame row (a kept row is
     * a live frame's extraction lock and keeps its tombstone), no memory with that frameId (the late-fact fence), no
     * JPEG of that id left on disk, and older than this. 8 days = the glasses' 7-day resend window (FrameAdmission
     * HISTORY_MS acks an older frame TOO_OLD, so a forgotten frame can't be re-ingested) + 1 day for in-flight queue
     * jobs (24 h TTL) that still carry the id. Delete all drops every tombstone as before.
     */
    const val TOMBSTONE_KEEP_MS = 8 * 24 * 3_600_000L
    private const val CHUNK = 500

    /**
     * Retention v3 (M1, bounded): the expiry markers of the tombstones this prune is about to delete go in the SAME transaction. A tombstone is pruned only when no `frame` row, memory or `frame_source` row remains
     * (a skipped photo, never a described one), so a marker never outlives what it explains. The candidates are read with the prune's own conditions before it runs.
     */
    internal suspend fun pruneWithMarkers(db: com.rokid.context.memory.db.ContextDatabase, beforeMs: Long, keep: List<String>): Int = androidx.room.withTransaction(db) {
        val dao = db.forgetDao()
        val going = dao.prunableTombstones(beforeMs, keep)
        val n = dao.pruneTombstones(beforeMs, keep)
        if (n > 0) going.chunked(CHUNK).forEach { com.rokid.context.memory.forget.ExpiryMarks.clear(db.canvasSlice3Dao(), it) }
        n
    }

    /**
     * Deletes leftover JPEGs of tombstoned frames, then prunes tombstones nothing needs ([TOMBSTONE_KEEP_MS]). Set-based
     * (final review I2): one query for kept rows, one per [CHUNK] files on disk for rowless ids, one delete; no per-tombstone
     * queries.
     */
    suspend fun sweep(context: Context, nowMs: Long = System.currentTimeMillis()): Int = withContext(Dispatchers.IO) {
        val dao = MemoryGraph.get(context).db.forgetDao()
        val paths = dao.orphanTombstonedFramePaths().toMutableList()
        val onDisk = RokidFrameInbox.FRAME_DIRS.flatMap { dir -> File(context.filesDir, dir).listFiles()?.filter { it.isFile }.orEmpty() }
        // A v2 sidecar `<id>.meta.json` belongs to frame `<id>` (its JPEG's name without extension).
        val byId = onDisk.groupBy { it.nameWithoutExtension.removeSuffix(".meta") }
        val rowless = byId.keys.toList().chunked(CHUNK).flatMap { dao.rowlessTombstones(it) }
        rowless.forEach { id -> byId[id]?.forEach { paths += it.absolutePath } }
        val deleted = delete(paths.filter { File(it).exists() })
        // A rowless frame's JPEG that could not be deleted keeps its tombstone, so the next sweep still finds it.
        val stuck = rowless.filter { id -> byId[id].orEmpty().any { it.exists() } }
        if (stuck.size <= CHUNK) {
            runCatching { pruneWithMarkers(MemoryGraph.get(context).db, nowMs - TOMBSTONE_KEEP_MS, stuck.ifEmpty { listOf("") }) }
                .onFailure { PcLog.w(TAG, "tombstones not pruned (next sweep retries): ${it.javaClass.simpleName}") }
        }
        deleted
    }
}
