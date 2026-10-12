package com.rokid.context.memory.db

import androidx.room.Dao
import androidx.room.Query

/** One FTS hit with its `matchinfo(…, 'pcnalx')` blob (native-order uint32s) and its docid (the keyset cursor). */
class LexicalRow(val id: String, val mi: ByteArray, val rowid: Long)

@Dao
interface RetrievalDao {
    /**
     * One keyset page of FTS matches by docid after [afterRowid] (0 = first page). Trusted-for-retrieval rows only
     * (review state OK, confidence ≥ [minConfidence]). Unranked: callers stream every page and rank with BM25 in
     * Kotlin (spec §4.6), so no match is cut before it is scored.
     */
    @Query(
        "SELECT memory_fts.rowid AS rowid, memory.id AS id, matchinfo(memory_fts, 'pcnalx') AS mi FROM memory_fts " +
            "JOIN memory ON memory.rowid = memory_fts.rowid WHERE memory_fts MATCH :match AND memory_fts.rowid > :afterRowid " +
            "AND memory.reviewState = 'OK' AND memory.confidence >= :minConfidence ORDER BY memory_fts.rowid LIMIT :limit",
    )
    suspend fun lexicalPage(match: String, afterRowid: Long, minConfidence: Double, limit: Int): List<LexicalRow>
}
