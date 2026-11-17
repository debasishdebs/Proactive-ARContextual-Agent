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

    /**
     * [lexicalCursor] over the memories whose EVENT time (the photo's capture time, else when the memory was written) is in `[fromMs, toMs)`, and that
     * were created at or before [notAfterMs] (Long.MAX_VALUE = no cutoff). The bounds are in the query, so they apply before the candidate limit.
     */
    @Query(LEXICAL_CURSOR_WINDOW_SQL)
    fun lexicalCursorWindow(match: String, minConfidence: Double, notAfterMs: Long, fromMs: Long, toMs: Long): android.database.Cursor

    /** The event time of each memory in [ids] (retrieval-quality spec section 4): `frame_meta.capturedAtMs` when known, else `createdAt`. */
    @Query("SELECT m.id AS id, COALESCE(fm.capturedAtMs, m.createdAt) AS at FROM memory m LEFT JOIN frame_meta fm ON fm.frameId = m.frameId WHERE m.id IN (:ids)")
    suspend fun eventTimes(ids: List<String>): List<EventTimeRow>

    companion object {
        /** The event time of a memory row, as a SQL expression over the aliases `memory` and `fm` (frame_meta). */
        const val EVENT_TIME_SQL = "COALESCE(fm.capturedAtMs, memory.createdAt)"
        const val LEXICAL_CURSOR_WINDOW_SQL = "SELECT memory_fts.rowid AS rowid, memory.id AS id, matchinfo(memory_fts, 'pcnalx') AS mi FROM memory_fts " +
            "JOIN memory ON memory.rowid = memory_fts.rowid LEFT JOIN frame_meta fm ON fm.frameId = memory.frameId " +
            "WHERE memory_fts MATCH :match AND memory.reviewState = 'OK' AND memory.confidence >= :minConfidence AND memory.createdAt <= :notAfterMs " +
            "AND $EVENT_TIME_SQL >= :fromMs AND $EVENT_TIME_SQL < :toMs"
        const val LEXICAL_CURSOR_SQL = "SELECT memory_fts.rowid AS rowid, memory.id AS id, matchinfo(memory_fts, 'pcnalx') AS mi FROM memory_fts " +
            "JOIN memory ON memory.rowid = memory_fts.rowid WHERE memory_fts MATCH :match AND memory.reviewState = 'OK' " +
            "AND memory.confidence >= :minConfidence"
        const val LEXICAL_CURSOR_UNTIL_SQL = LEXICAL_CURSOR_SQL + " AND memory.createdAt <= :notAfterMs"
    }
}

/** One memory's event time ([RetrievalDao.eventTimes]). */
class EventTimeRow(val id: String, val at: Long)
