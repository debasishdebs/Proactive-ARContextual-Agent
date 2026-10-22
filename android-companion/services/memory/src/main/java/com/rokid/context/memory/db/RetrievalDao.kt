package com.rokid.context.memory.db

import androidx.room.Dao
import androidx.room.Query

@Dao
interface RetrievalDao {
    /**
     * Every trusted-for-retrieval FTS match in one cursor (Phase 8 B2): the FTS doclists are read once per query.
     * Unranked; the caller BM25-scores each row into a bounded heap and closes the cursor.
     */
    @Query(LEXICAL_CURSOR_SQL)
    fun lexicalCursor(match: String, minConfidence: Double): android.database.Cursor

    /** [lexicalCursor] over the memories created at or before [notAfterMs] (S34: the bound is in the query, so it applies before the candidate limit). */
    @Query(LEXICAL_CURSOR_UNTIL_SQL)
    fun lexicalCursorUntil(match: String, minConfidence: Double, notAfterMs: Long): android.database.Cursor

    companion object {
        const val LEXICAL_CURSOR_SQL = "SELECT memory_fts.rowid AS rowid, memory.id AS id, matchinfo(memory_fts, 'pcnalx') AS mi FROM memory_fts " +
            "JOIN memory ON memory.rowid = memory_fts.rowid WHERE memory_fts MATCH :match AND memory.reviewState = 'OK' " +
            "AND memory.confidence >= :minConfidence"
        const val LEXICAL_CURSOR_UNTIL_SQL = LEXICAL_CURSOR_SQL + " AND memory.createdAt <= :notAfterMs"
    }
}
