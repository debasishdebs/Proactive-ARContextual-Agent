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
    fun lexicalCursor(match: String, minConfidence: Double, recallSince: Long): android.database.Cursor

    /** [lexicalCursor] over the memories created at or before [notAfterMs] (S34: the bound is in the query, so it applies before the candidate limit). */
    @Query(LEXICAL_CURSOR_UNTIL_SQL)
    fun lexicalCursorUntil(match: String, minConfidence: Double, notAfterMs: Long, recallSince: Long): android.database.Cursor

    /**
     * [lexicalCursor] over the memories whose EVENT time (the photo's capture time, else when the memory was written) is in `[fromMs, toMs)`, and that
     * were created at or before [notAfterMs] (Long.MAX_VALUE = no cutoff). The bounds are in the query, so they apply before the candidate limit.
     */
    @Query(LEXICAL_CURSOR_WINDOW_SQL)
    fun lexicalCursorWindow(match: String, minConfidence: Double, notAfterMs: Long, fromMs: Long, toMs: Long): android.database.Cursor

    /** The event time of each memory in [ids] (retrieval-quality spec section 4): `frame_meta.capturedAtMs` when known, else `createdAt`. */
    @Query("SELECT m.id AS id, COALESCE(fm.capturedAtMs, m.createdAt) AS at FROM memory m LEFT JOIN frame_meta fm ON fm.frameId = m.frameId WHERE m.id IN (:ids)")
    suspend fun eventTimes(ids: List<String>): List<EventTimeRow>

    /**
     * The newest OK memories (confidence >= [minConfidence]) whose EVENT time is in `[fromMs, toMs)`, newest first, at most [limit]. No text match: the canvas ordering rows that
     * ignore relevance (the last capture of each night) read the window itself. The caller fences the ids (ProvenanceGate) before use.
     */
    @Query("SELECT m.id AS id, COALESCE(fm.capturedAtMs, m.createdAt) AS at FROM memory m LEFT JOIN frame_meta fm ON fm.frameId = m.frameId " +
        "WHERE m.reviewState = 'OK' AND m.confidence >= :minConfidence AND COALESCE(fm.capturedAtMs, m.createdAt) >= :fromMs AND COALESCE(fm.capturedAtMs, m.createdAt) < :toMs " +
        "ORDER BY COALESCE(fm.capturedAtMs, m.createdAt) DESC LIMIT :limit")
    suspend fun inWindow(minConfidence: Double, fromMs: Long, toMs: Long, limit: Int): List<EventTimeRow>

    /** Which of [ids] are demoted at [recallSince] (Home / Activity lists fold these; provenance and detail views never filter). */
    @Query("SELECT memory.id FROM memory WHERE memory.id IN (:ids) AND ${Demotion.DEMOTED_SQL}")
    suspend fun demotedAmong(ids: List<String>, recallSince: Long): List<String>

    /** How many facts are demoted at [recallSince] (counts-only logs, the retention preview). */
    @Query("SELECT COUNT(*) FROM memory WHERE ${Demotion.DEMOTED_SQL}")
    suspend fun countDemoted(recallSince: Long): Int

    /** The newest [limit] memories that are not demoted, created at or before [notAfterMs] (Long.MAX_VALUE = none): the Home "today" list. */
    @Query("SELECT * FROM memory WHERE createdAt <= :notAfterMs AND ${Demotion.NOT_DEMOTED_SQL} ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recentVisible(limit: Int, notAfterMs: Long, recallSince: Long): List<MemoryEntity>

    companion object {
        /** The event time of a memory row, as a SQL expression over the aliases `memory` and `fm` (frame_meta). */
        const val EVENT_TIME_SQL = "COALESCE(fm.capturedAtMs, memory.createdAt)"
        const val LEXICAL_CURSOR_WINDOW_SQL = "SELECT memory_fts.rowid AS rowid, memory.id AS id, matchinfo(memory_fts, 'pcnalx') AS mi FROM memory_fts " +
            "JOIN memory ON memory.rowid = memory_fts.rowid LEFT JOIN frame_meta fm ON fm.frameId = memory.frameId " +
            "WHERE memory_fts MATCH :match AND memory.reviewState = 'OK' AND memory.confidence >= :minConfidence AND memory.createdAt <= :notAfterMs " +
            "AND $EVENT_TIME_SQL >= :fromMs AND $EVENT_TIME_SQL < :toMs"
        const val LEXICAL_CURSOR_SQL = "SELECT memory_fts.rowid AS rowid, memory.id AS id, matchinfo(memory_fts, 'pcnalx') AS mi FROM memory_fts " +
            "JOIN memory ON memory.rowid = memory_fts.rowid WHERE memory_fts MATCH :match AND memory.reviewState = 'OK' " +
            "AND memory.confidence >= :minConfidence AND ${Demotion.NOT_DEMOTED_SQL}"
        const val LEXICAL_CURSOR_UNTIL_SQL = LEXICAL_CURSOR_SQL + " AND memory.createdAt <= :notAfterMs"
    }
}

/** One memory's event time ([RetrievalDao.eventTimes]). */
class EventTimeRow(val id: String, val at: Long)
