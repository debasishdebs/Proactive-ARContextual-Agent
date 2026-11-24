package com.rokid.context.orchestration.forget

import android.content.Context
import com.rokid.context.data.frames.RokidFrameInbox
import com.rokid.context.memory.db.DatabaseFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** How many bytes the app's memory uses: the database (with its WAL and shm) plus the photo folders. The one measure behind the storage banner and the quota eviction (retention v3, K6). */
object StorageMeter {
    suspend fun usedBytes(context: Context): Long = withContext(Dispatchers.IO) {
        val db = context.getDatabasePath(DatabaseFactory.NAME)
        val dbBytes = listOf(db, File(db.path + "-wal"), File(db.path + "-shm")).sumOf { if (it.isFile) it.length() else 0L }
        dbBytes + RokidFrameInbox.FRAME_DIRS.sumOf { name -> File(context.filesDir, name).walkTopDown().filter { it.isFile }.sumOf { it.length() } }
    }
}
