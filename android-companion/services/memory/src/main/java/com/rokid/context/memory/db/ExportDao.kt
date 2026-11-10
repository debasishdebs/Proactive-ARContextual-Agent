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
        "SELECT m.rowid AS rowid, m.id, m.text, m.type, m.tags, m.tier, m.confidence, m.reviewState, m.createdAt, m.frameId FROM memory m " +
            "WHERE m.rowid > :after AND m.reviewState = 'OK' " +
            // not an orphan: photo retention deletes the `frame` row but keeps `frame_meta` (its memories are still exported); a Forget-removed frame has neither (one definition, shared with the one-time purge)
            "AND NOT (${MemoryOrphan.SQL}) " +
            // a derived memory needs at least one source memory that is not forgotten (stale-hidden otherwise)
            "AND (m.derived = 0 OR NOT EXISTS (SELECT 1 FROM memory_source s WHERE s.memoryId = m.id) OR EXISTS (SELECT 1 FROM memory_source s JOIN memory m2 ON m2.id = s.sourceMemoryId " +
            "WHERE s.memoryId = m.id AND m2.reviewState != 'INVALIDATED')) ORDER BY m.rowid LIMIT :limit",
    )
    suspend fun memoryPage(after: Long, limit: Int): List<MemoryExportRow>

    @Query("SELECT rowid, id, ts, question, answer FROM ask WHERE rowid > :after ORDER BY rowid LIMIT :limit")
    suspend fun askPage(after: Long, limit: Int): List<AskExportRow>
}

/** The ONE definition of an orphan memory (alias `m` = `memory`): a photo provenance (its `frameId` or a `frame_source` row) and, for every source frame, neither a `frame` row nor a `frame_meta` row. Used by the export and the one-time purge. */
object MemoryOrphan {
    // codex r16: a CARRIED row is not the memory's photo provenance (only what it may be derived from), so it never makes a memory non-orphan
    const val SQL = "((m.frameId IS NOT NULL OR EXISTS (SELECT 1 FROM frame_source fs WHERE fs.memoryId = m.id AND fs.role != 'CARRIED')) " +
        "AND NOT EXISTS (SELECT 1 FROM frame f WHERE f.id = m.frameId OR f.id IN (SELECT fs.frameId FROM frame_source fs WHERE fs.memoryId = m.id AND fs.role != 'CARRIED')) " +
        "AND NOT EXISTS (SELECT 1 FROM frame_meta fm WHERE fm.frameId = m.frameId OR fm.frameId IN (SELECT fs.frameId FROM frame_source fs WHERE fs.memoryId = m.id AND fs.role != 'CARRIED')))"
}
