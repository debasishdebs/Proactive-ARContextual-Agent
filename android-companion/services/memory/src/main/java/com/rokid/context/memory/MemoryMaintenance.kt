package com.rokid.context.memory

import com.rokid.context.memory.db.ContextDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object MemoryMaintenance {
    /** VACUUM may renumber `memory` rowids (no INTEGER PRIMARY KEY), which the external-content FTS index points at: rebuild it. */
    suspend fun vacuum(db: ContextDatabase) = withContext(Dispatchers.IO) {
        val w = db.openHelper.writableDatabase
        w.execSQL("VACUUM")
        w.execSQL("INSERT INTO memory_fts(memory_fts) VALUES('rebuild')")
    }
}
