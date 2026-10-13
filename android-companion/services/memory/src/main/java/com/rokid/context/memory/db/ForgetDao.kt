package com.rokid.context.memory.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Forget fact / Forget moment cascade queries (spec §6.1.1, R-77), the extraction lock (`forget_tombstone`) and the
 * `memory_source` provenance edges. Callers chunk every list parameter (≤ 500 ids, under SQLite's 999 limit).
 * Schema v1 unchanged.
 */
@Dao
abstract class ForgetDao {
    /**
     * Every memory derived, directly or transitively, from [ids] over `memory_source` (`memoryId` is derived from
     * `sourceMemoryId`). `UNION` (not `UNION ALL`) drops repeats, so a cycle terminates. May include members of [ids].
     */
    @Query(
        "WITH RECURSIVE d(id) AS (SELECT memoryId FROM memory_source WHERE sourceMemoryId IN (:ids) " +
            "UNION SELECT ms.memoryId FROM memory_source ms JOIN d ON ms.sourceMemoryId = d.id) SELECT id FROM d",
    )
    abstract suspend fun descendants(ids: List<String>): List<String>

    @Query("SELECT * FROM memory WHERE id IN (:ids)") abstract suspend fun memories(ids: List<String>): List<MemoryEntity>
    @Query("SELECT id FROM memory WHERE frameId = :frameId") abstract suspend fun memoryIdsOfFrame(frameId: String): List<String>
    @Query("DELETE FROM memory WHERE id IN (:ids)") abstract suspend fun deleteMemories(ids: List<String>): Int
    /** Every embedding space, not only the current one. */
    @Query("DELETE FROM memory_vector WHERE memoryId IN (:ids)") abstract suspend fun deleteVectorsFor(ids: List<String>): Int
    @Query("DELETE FROM memory_source WHERE memoryId IN (:ids)") abstract suspend fun deleteEdgesFrom(ids: List<String>): Int
    @Query("DELETE FROM memory_source WHERE sourceMemoryId IN (:ids)") abstract suspend fun deleteEdgesTo(ids: List<String>): Int
    @Query("UPDATE memory SET reviewState = 'INVALIDATED' WHERE id IN (:ids)") abstract suspend fun invalidate(ids: List<String>): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun insertTombstone(t: ForgetTombstoneEntity): Long
    @Query("SELECT frameId FROM forget_tombstone WHERE frameId IN (:frameIds)") abstract suspend fun tombstoned(frameIds: List<String>): List<String>

    @Query("SELECT frameId FROM forget_tombstone") abstract suspend fun allTombstones(): List<String>

    /** Memories (any review state) that still point at [frameId]. */
    @Query("SELECT COUNT(*) FROM memory WHERE frameId = :frameId") abstract suspend fun remainingRefs(frameId: String): Int
    @Query("SELECT * FROM frame WHERE id = :id") abstract suspend fun frame(id: String): FrameEntity?
    @Query("DELETE FROM frame WHERE id = :id") abstract suspend fun deleteFrame(id: String): Int

    /** Retention: frames older than [beforeMs] that no memory points at. */
    @Query("SELECT id FROM frame WHERE ts < :beforeMs AND NOT EXISTS (SELECT 1 FROM memory m WHERE m.frameId = frame.id) ORDER BY ts LIMIT :limit")
    abstract suspend fun orphanFramesBefore(beforeMs: Long, limit: Int): List<String>
    /** Retention: memories of [tier] created before [beforeMs], in any review state. */
    @Query("SELECT id FROM memory WHERE tier = :tier AND createdAt < :beforeMs ORDER BY createdAt LIMIT :limit")
    abstract suspend fun expiredIds(tier: String, beforeMs: Long, limit: Int): List<String>

    /** [likePattern] escapes `\`, `%` and `_` with `\`. It is only a prefilter: the caller parses the JSON exactly. */
    @Query("SELECT * FROM ask WHERE redacted = 0 AND sourcesJson LIKE :likePattern ESCAPE '\\'") abstract suspend fun asksCiting(likePattern: String): List<AskEntity>
    @Query("SELECT * FROM nudge WHERE redacted = 0 AND sourceIdsJson LIKE :likePattern ESCAPE '\\'") abstract suspend fun nudgesCiting(likePattern: String): List<NudgeEntity>
    @Query("UPDATE ask SET answer = :text, sourcesJson = '[]', redacted = 1 WHERE id = :id") abstract suspend fun redactAsk(id: String, text: String): Int
    @Query("UPDATE nudge SET text = :text, why = '', sourceIdsJson = '[]', redacted = 1 WHERE id = :id") abstract suspend fun redactNudge(id: String, text: String): Int

    // --- provenance edges, used by the reprocess path and the re-summarise worker --------------------------------
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun insertEdges(edges: List<MemorySourceEntity>)
    @Query("SELECT * FROM memory_source WHERE memoryId IN (:ids)") abstract suspend fun edgesFrom(ids: List<String>): List<MemorySourceEntity>
    @Query("SELECT * FROM memory_source WHERE sourceMemoryId IN (:ids)") abstract suspend fun edgesTo(ids: List<String>): List<MemorySourceEntity>
    @Query("SELECT * FROM memory WHERE reviewState = 'INVALIDATED' AND derived = 1 ORDER BY createdAt LIMIT :limit")
    abstract suspend fun invalidatedDerived(limit: Int): List<MemoryEntity>
}
