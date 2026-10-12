package com.rokid.context.phone.ui.status

import android.content.Context
import com.rokid.context.memory.db.DatabaseFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File

/** Memory storage vs the 100 MB soft quota (spec §2.4 Memory) for the §2.2 storage banner. */
object StorageUsage {
    const val QUOTA_BYTES = 100L * 1024 * 1024
    /** App-private frame JPEG folders (RokidFrameInbox). Task 11 adds Phase 3's folder if it differs. */
    val FRAME_DIRS = listOf("rokid_archive", "rokid_inbox")

    fun percent(usedBytes: Long, quotaBytes: Long = QUOTA_BYTES): Int =
        if (quotaBytes <= 0) 0 else ((usedBytes.coerceAtLeast(0) * 100) / quotaBytes).toInt()

    suspend fun usedBytes(context: Context): Long = withContext(Dispatchers.IO) {
        val db = context.getDatabasePath(DatabaseFactory.NAME)
        val dbBytes = listOf(db, File(db.path + "-wal"), File(db.path + "-shm")).sumOf { if (it.isFile) it.length() else 0L }
        dbBytes + FRAME_DIRS.sumOf { name -> File(context.filesDir, name).walkTopDown().filter { it.isFile }.sumOf { it.length() } }
    }

    /** null first (no banner while measuring), then the measured percent. Cold: measured once per collector. */
    fun percentFlow(context: Context): Flow<Int?> = flow { emit(null); emit(percent(usedBytes(context))) }
}
