package com.rokid.context.orchestration

import android.content.Context
import com.rokid.context.data.frames.RokidFrameInbox
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.MemoryMaintenance
import com.rokid.context.memory.db.ContextDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Settings → Memory › Delete all (spec §6.1.1). Endpoints, keys, prompts, settings and user lists are kept. */
object MemoryWipe {
    /**
     * Deletes every memory row (with all vectors and embedding spaces, so no orphan vector survives) and every frame
     * JPEG (the frame rows' paths plus whatever sits in [RokidFrameInbox.FRAME_DIRS]), then VACUUMs, which rebuilds the
     * FTS index crash-safely (MemoryMaintenance). Returns the frame files deleted.
     */
    suspend fun deleteAll(context: Context, db: ContextDatabase = MemoryGraph.get(context).db): Int {
        val wipe = db.wipeDao()
        val paths = wipe.framePaths()
        wipe.wipeMemory()
        val deleted = withContext(Dispatchers.IO) {
            val listed = paths.filter { it.isNotBlank() }.map(::File)
            val folders = RokidFrameInbox.FRAME_DIRS.flatMap { name -> File(context.filesDir, name).walkTopDown().filter { it.isFile }.toList() }
            (listed + folders).distinctBy { it.absolutePath }.count { it.exists() && it.delete() }
        }
        MemoryMaintenance.vacuum(db)
        return deleted
    }
}
