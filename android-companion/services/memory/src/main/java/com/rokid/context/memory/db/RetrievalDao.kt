package com.rokid.context.memory.db

import androidx.room.Dao
import androidx.room.Query

/** One FTS hit with its `matchinfo(…, 'pcnalx')` blob (native-order uint32s). */
class LexicalRow(val id: String, val mi: ByteArray)

@Dao
interface RetrievalDao {
    /** Trusted-for-retrieval rows only (review state OK). Unranked: BM25 is computed in Kotlin (spec §4.6). */
    @Query(
        "SELECT memory.id AS id, matchinfo(memory_fts, 'pcnalx') AS mi FROM memory_fts JOIN memory ON memory.rowid = memory_fts.rowid " +
            "WHERE memory_fts MATCH :match AND memory.reviewState = 'OK' LIMIT :limit",
    )
    suspend fun lexical(match: String, limit: Int): List<LexicalRow>
}
