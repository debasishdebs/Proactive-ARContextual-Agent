package com.rokid.context.orchestration.forget

import android.content.Context
import android.util.Log
import com.rokid.context.data.frames.RokidFrameInbox
import com.rokid.context.memory.MemoryGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Durable photo deletion (fix round 1 I3). A Forget lists JPEGs to delete after its transaction; a delete that fails
 * (or a process death before it) is caught here. The tombstone has no path column (schema v1), so the path is derived:
 * the frame row's `path` when the row is kept, otherwise any file in [RokidFrameInbox.FRAME_DIRS] whose name without
 * extension is the frame id (how frames are named on ingest). A tombstoned frame that a memory still points at keeps
 * its JPEG. Runs at start-up and in every retention pass.
 */
object FrameSweep {
    private const val TAG = "FrameSweep"

    /** Deletes [paths]; each failure is logged (class only) and left for [sweep]. Returns how many were deleted. */
    suspend fun delete(paths: Collection<String>): Int = withContext(Dispatchers.IO) {
        paths.distinct().count { p ->
            val f = File(p)
            val ok = !f.exists() || runCatching { f.delete() }.getOrDefault(false)
            if (!ok) Log.w(TAG, "a forgotten photo could not be deleted yet; the sweep retries")
            ok && f.exists().not()
        }
    }

    suspend fun sweep(context: Context): Int = withContext(Dispatchers.IO) {
        val dao = MemoryGraph.get(context).db.forgetDao()
        val locked = dao.allTombstones().toSet()
        if (locked.isEmpty()) return@withContext 0
        val paths = mutableListOf<String>()
        val rowless = mutableSetOf<String>()
        for (id in locked) {
            val frame = dao.frame(id)
            if (frame == null) rowless += id else if (dao.remainingRefs(id) == 0 && frame.path.isNotBlank()) paths += frame.path
        }
        if (rowless.isNotEmpty()) {
            RokidFrameInbox.FRAME_DIRS.forEach { dir ->
                File(context.filesDir, dir).listFiles()?.filter { it.isFile && it.nameWithoutExtension in rowless }?.forEach { paths += it.absolutePath }
            }
        }
        delete(paths.filter { File(it).exists() })
    }
}
