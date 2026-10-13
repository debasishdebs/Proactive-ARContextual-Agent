package com.rokid.context.memory.db

import androidx.room.Dao
import androidx.room.Query

/** One exported memory row; [rowid] is the keyset cursor for the next page. */
data class MemoryExportRow(
    val rowid: Long,
    val id: String,
    val text: String,
    val type: String,
    val tags: String,
    val tier: MemoryTier,
    val confidence: Double,
    val reviewState: ReviewState,
    val createdAt: Long,
    val frameId: String?,
)

/** One exported ask row; [rowid] is the keyset cursor for the next page. */
data class AskExportRow(val rowid: Long, val id: String, val ts: Long, val question: String, val answer: String)

/**
 * Settings > Memory > Export reads the library in rowid keyset pages, so the export never holds more than one page
 * (Phase 8 B1). Forgotten (INVALIDATED) memories are excluded by the query itself. DAO only: no entity, no schema change.
 */
@Dao
interface ExportDao {
    @Query(
        "SELECT rowid, id, text, type, tags, tier, confidence, reviewState, createdAt, frameId FROM memory " +
            "WHERE rowid > :after AND reviewState != 'INVALIDATED' ORDER BY rowid LIMIT :limit",
    )
    suspend fun memoryPage(after: Long, limit: Int): List<MemoryExportRow>

    @Query("SELECT rowid, id, ts, question, answer FROM ask WHERE rowid > :after ORDER BY rowid LIMIT :limit")
    suspend fun askPage(after: Long, limit: Int): List<AskExportRow>
}
