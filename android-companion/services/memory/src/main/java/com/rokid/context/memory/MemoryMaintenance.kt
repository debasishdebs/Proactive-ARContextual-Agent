package com.rokid.context.memory

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.rokid.context.memory.db.ContextDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object MemoryMaintenance {
    /**
     * "FTS rebuild needed", kept in the database header's `PRAGMA application_id` (Room leaves it at 0), so it lives and
     * dies with the very file whose index it describes and survives a crash. Set before VACUUM, cleared after the rebuild.
     */
    private const val FTS_REBUILD_PENDING = 0x46545352 // "FTSR"

    /** VACUUM may renumber `memory` rowids (no INTEGER PRIMARY KEY), which the external-content FTS index points at: rebuild it. */
    suspend fun vacuum(db: ContextDatabase) = withContext(Dispatchers.IO) {
        val w = db.openHelper.writableDatabase
        markRebuildPending(w)
        w.execSQL("VACUUM")
        rebuildFts(w)
    }

    /** Added to every database build: a marker still set at open means the last VACUUM never reached its rebuild. */
    val openCheck: RoomDatabase.Callback = object : RoomDatabase.Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) { if (rebuildPending(db)) rebuildFts(db) }
    }

    /** Moves the WAL into the main file and truncates it, so deleted pages don't linger in `-wal` (Delete all). */
    suspend fun checkpoint(db: ContextDatabase) = withContext(Dispatchers.IO) {
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        Unit
    }

    internal fun markRebuildPending(w: SupportSQLiteDatabase) = w.execSQL("PRAGMA application_id = $FTS_REBUILD_PENDING")

    internal fun rebuildPending(w: SupportSQLiteDatabase): Boolean =
        w.query("PRAGMA application_id").use { c -> c.moveToFirst() && c.getInt(0) == FTS_REBUILD_PENDING }

    private fun rebuildFts(w: SupportSQLiteDatabase) {
        w.execSQL("INSERT INTO memory_fts(memory_fts) VALUES('rebuild')")
        w.execSQL("PRAGMA application_id = 0")
    }
}
