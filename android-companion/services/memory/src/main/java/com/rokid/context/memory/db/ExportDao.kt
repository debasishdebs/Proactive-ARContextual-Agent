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
 * (Phase 8 B1). The query itself excludes every memory that is not valid and visible: forgotten (INVALIDATED), hidden (NEEDS_REVIEW, out of feeds and retrieval), stale-hidden (a derived memory whose sources are all forgotten) and orphaned (its source frame is gone or forgotten). DAO only: no entity, no schema change.
 */
@Dao
interface ExportDao {
    @Query(
        "SELECT rowid, id, text, type, tags, tier, confidence, reviewState, createdAt, frameId FROM memory " +
            "WHERE rowid > :after AND reviewState = 'OK' " +
            // a memory with a photo provenance needs a LIVE source frame (the frame row is present: a forgotten PHOTO deletes its row, while a tombstone alone only locks extraction, also after Forget FACT or a photo expiry); one with none (typed, voice) stays
            "AND ((frameId IS NULL AND NOT EXISTS (SELECT 1 FROM frame_source fs WHERE fs.memoryId = memory.id)) OR EXISTS (SELECT 1 FROM frame f WHERE " +
            "(f.id = memory.frameId OR f.id IN (SELECT fs.frameId FROM frame_source fs WHERE fs.memoryId = memory.id)))) " +
            // a derived memory needs at least one source memory that is not forgotten (stale-hidden otherwise)
            "AND (derived = 0 OR NOT EXISTS (SELECT 1 FROM memory_source s WHERE s.memoryId = memory.id) OR EXISTS (SELECT 1 FROM memory_source s JOIN memory m2 ON m2.id = s.sourceMemoryId " +
            "WHERE s.memoryId = memory.id AND m2.reviewState != 'INVALIDATED')) ORDER BY rowid LIMIT :limit",
    )
    suspend fun memoryPage(after: Long, limit: Int): List<MemoryExportRow>

    @Query("SELECT rowid, id, ts, question, answer FROM ask WHERE rowid > :after ORDER BY rowid LIMIT :limit")
    suspend fun askPage(after: Long, limit: Int): List<AskExportRow>
}
