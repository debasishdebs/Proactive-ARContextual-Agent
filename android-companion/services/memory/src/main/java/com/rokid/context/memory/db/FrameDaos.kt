package com.rokid.context.memory.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.MapColumn
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Per-frame processing state (keyframe capture, spec §P2). A claim moves a frame to EXTRACTING with a random claim id;
 * the commit transaction starts with [completeIfClaimed], and zero rows means the claim was abandoned and retaken.
 * Callers chunk `IN (…)` lists (≤ 500).
 */
@Dao
abstract class FrameStateDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun insertIgnore(rows: List<FrameStateEntity>)

    @Query("SELECT * FROM frame_state WHERE frameId IN (:frameIds)") abstract suspend fun byIds(frameIds: List<String>): List<FrameStateEntity>
    @Query("SELECT * FROM frame_state WHERE frameId = :frameId") abstract suspend fun get(frameId: String): FrameStateEntity?

    /** Claimable now: PENDING, FAILED, or an EXTRACTING claim taken before [abandonBeforeMs]. */
    @Query(
        "UPDATE frame_state SET state = 'EXTRACTING', claimedAt = :nowMs, claimId = :claimId, updatedAt = :nowMs " +
            "WHERE frameId IN (:frameIds) AND (state IN ('PENDING','FAILED') OR (state = 'EXTRACTING' AND claimedAt < :abandonBeforeMs)) " +
            "AND NOT EXISTS (SELECT 1 FROM forget_tombstone t WHERE t.frameId = frame_state.frameId)", // a forgotten frame is never extracted again
    )
    protected abstract suspend fun claimRows(frameIds: List<String>, claimId: String, nowMs: Long, abandonBeforeMs: Long): Int

    /** An abandoned claim counts as an attempt (spec §P2): bump it, and the fifth ends in FAILED_FINAL. */
    @Query(
        "UPDATE frame_state SET attempts = attempts + 1, state = CASE WHEN attempts + 1 >= :maxAttempts THEN 'FAILED_FINAL' ELSE 'PENDING' END, " +
            "lastError = 'abandoned', claimedAt = NULL, claimId = NULL, updatedAt = :nowMs " +
            "WHERE frameId IN (:frameIds) AND state = 'EXTRACTING' AND claimedAt < :abandonBeforeMs",
    )
    protected abstract suspend fun abandonRows(frameIds: List<String>, nowMs: Long, abandonBeforeMs: Long, maxAttempts: Int): Int

    @Query("SELECT * FROM frame_state WHERE frameId IN (:frameIds) AND state = 'EXTRACTING' AND claimId = :claimId")
    protected abstract suspend fun claimedBy(frameIds: List<String>, claimId: String): List<FrameStateEntity>

    /** PENDING/FAILED(attempts<5)/abandoned EXTRACTING → EXTRACTING; returns the rows it claimed. */
    @Transaction
    open suspend fun claim(frameIds: List<String>, claimId: String, nowMs: Long, abandonBeforeMs: Long): List<FrameStateEntity> {
        if (frameIds.isEmpty()) return emptyList()
        abandonRows(frameIds, nowMs, abandonBeforeMs, MAX_ATTEMPTS)
        claimRows(frameIds, claimId, nowMs, abandonBeforeMs)
        return claimedBy(frameIds, claimId)
    }

    /** The CAS; returns 1 when the claim still holds and the row moved to [to]. Used inside the caller's transaction. */
    @Query(
        "UPDATE frame_state SET state = :to, claimedAt = NULL, claimId = NULL, lastError = NULL, updatedAt = :nowMs " +
            "WHERE frameId = :frameId AND state = 'EXTRACTING' AND claimId = :claimId",
    )
    abstract suspend fun completeIfClaimed(frameId: String, claimId: String, to: String, nowMs: Long): Int

    /** → FAILED, or FAILED_FINAL on the [maxAttempts]th failure. Returns 0 when the claim no longer holds. */
    @Query(
        "UPDATE frame_state SET attempts = attempts + 1, state = CASE WHEN attempts + 1 >= :maxAttempts THEN 'FAILED_FINAL' ELSE 'FAILED' END, " +
            "lastError = :error, claimedAt = NULL, claimId = NULL, updatedAt = :nowMs " +
            "WHERE frameId = :frameId AND state = 'EXTRACTING' AND claimId = :claimId",
    )
    abstract suspend fun fail(frameId: String, claimId: String, error: String, nowMs: Long, maxAttempts: Int = MAX_ATTEMPTS): Int

    @Query(
        "SELECT * FROM frame_state WHERE (state IN ('PENDING','FAILED') OR (state = 'EXTRACTING' AND claimedAt < :abandonBeforeMs)) " +
            "AND NOT EXISTS (SELECT 1 FROM forget_tombstone t WHERE t.frameId = frame_state.frameId) ORDER BY updatedAt LIMIT :limit",
    )
    protected abstract suspend fun recoverableRows(abandonBeforeMs: Long, limit: Int): List<FrameStateEntity>

    /** PENDING, FAILED (attempts < 5 by construction: the fifth is FAILED_FINAL) and abandoned EXTRACTING rows, oldest first. */
    open suspend fun recoverable(nowMs: Long, abandonBeforeMs: Long, limit: Int): List<FrameStateEntity> = recoverableRows(abandonBeforeMs, limit)

    /**
     * Claimable rows, NEWEST capture first (frame.ts, else the row's updatedAt), at most [limit], capture >= [sinceMs]. A FAILED row
     * inside its backoff (30 s doubling, capped at 30 min, from its last failure) is left out unless [ignoreBackoff] is 1. The
     * limit is applied after the ordering, so a backlog of any size still yields its newest rows (review I4).
     */
    @Query(
        "SELECT s.* FROM frame_state s LEFT JOIN frame f ON f.id = s.frameId " +
            "WHERE (s.state IN ('PENDING','FAILED') OR (s.state = 'EXTRACTING' AND s.claimedAt < :abandonBeforeMs)) " +
            "AND (:ignoreBackoff = 1 OR s.state != 'FAILED' OR s.updatedAt + MIN(30000 << MAX(s.attempts - 1, 0), 1800000) <= :nowMs) " +
            "AND COALESCE(f.ts, s.updatedAt) >= :sinceMs " +
            "AND NOT EXISTS (SELECT 1 FROM forget_tombstone t WHERE t.frameId = s.frameId) " +
            "ORDER BY COALESCE(f.ts, s.updatedAt) DESC LIMIT :limit",
    )
    abstract suspend fun newestClaimable(nowMs: Long, abandonBeforeMs: Long, sinceMs: Long, ignoreBackoff: Int, limit: Int): List<FrameStateEntity>

    /** [newestClaimable] for dwell keyframes only (frame_meta.dwell), so a dwell frame is found however large the backlog. */
    @Query(
        "SELECT s.* FROM frame_state s JOIN frame_meta m ON m.frameId = s.frameId LEFT JOIN frame f ON f.id = s.frameId " +
            "WHERE m.dwell = 1 AND (s.state IN ('PENDING','FAILED') OR (s.state = 'EXTRACTING' AND s.claimedAt < :abandonBeforeMs)) " +
            "AND (s.state != 'FAILED' OR s.updatedAt + MIN(30000 << MAX(s.attempts - 1, 0), 1800000) <= :nowMs) " +
            "AND NOT EXISTS (SELECT 1 FROM forget_tombstone t WHERE t.frameId = s.frameId) " +
            "ORDER BY COALESCE(f.ts, s.updatedAt) DESC LIMIT :limit",
    )
    abstract suspend fun newestDwellClaimable(nowMs: Long, abandonBeforeMs: Long, limit: Int): List<FrameStateEntity>

    /** When the earliest FAILED row leaves its backoff (null: none is waiting). */
    @Query(
        "SELECT MIN(updatedAt + MIN(30000 << MAX(attempts - 1, 0), 1800000)) FROM frame_state WHERE state = 'FAILED' " +
            "AND NOT EXISTS (SELECT 1 FROM forget_tombstone t WHERE t.frameId = frame_state.frameId)",
    )
    abstract suspend fun nextRetryAt(): Long?

    /** Live claims (EXTRACTING, not abandoned) on frames captured at or after [sinceMs]: an ask waits for these (review I4). */
    @Query(
        "SELECT COUNT(*) FROM frame_state s LEFT JOIN frame f ON f.id = s.frameId WHERE s.state = 'EXTRACTING' AND s.claimedAt >= :abandonBeforeMs " +
            "AND COALESCE(f.ts, s.updatedAt) >= :sinceMs",
    )
    abstract suspend fun liveClaimsSince(abandonBeforeMs: Long, sinceMs: Long): Int

    /** An abandoned claim goes back to PENDING and counts as an attempt (spec §P2.5); the fifth ends in FAILED_FINAL. */
    @Query(
        "UPDATE frame_state SET attempts = attempts + 1, state = CASE WHEN attempts + 1 >= :maxAttempts THEN 'FAILED_FINAL' ELSE 'PENDING' END, " +
            "lastError = 'abandoned', claimedAt = NULL, claimId = NULL, updatedAt = :nowMs " +
            "WHERE state = 'EXTRACTING' AND claimedAt < :abandonBeforeMs",
    )
    abstract suspend fun requeueAbandoned(nowMs: Long, abandonBeforeMs: Long, maxAttempts: Int = MAX_ATTEMPTS): Int

    /** Gives a claim back without an attempt (offline, no provider set up, cancelled): the frame is not at fault. */
    @Query(
        "UPDATE frame_state SET state = 'PENDING', claimedAt = NULL, claimId = NULL, updatedAt = :nowMs " +
            "WHERE frameId = :frameId AND state = 'EXTRACTING' AND claimId = :claimId",
    )
    abstract suspend fun release(frameId: String, claimId: String, nowMs: Long): Int

    @Query("SELECT state AS k, COUNT(*) AS v FROM frame_state GROUP BY state")
    abstract fun counts(): Flow<Map<@MapColumn("k") String, @MapColumn("v") Int>>

    @Query("UPDATE frame_state SET state = 'PENDING', attempts = 0, lastError = NULL, claimedAt = NULL, claimId = NULL, updatedAt = :nowMs WHERE state = 'FAILED_FINAL'")
    abstract suspend fun retryAllFinal(nowMs: Long): Int

    @Query("DELETE FROM frame_state WHERE frameId IN (:frameIds)") abstract suspend fun deleteFor(frameIds: List<String>): Int

    companion object { const val MAX_ATTEMPTS = 5 }
}

@Dao
interface FrameMetaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(row: FrameMetaEntity)
    @Query("SELECT * FROM frame_meta WHERE frameId = :frameId") suspend fun get(frameId: String): FrameMetaEntity?
    @Query("SELECT * FROM frame_meta WHERE frameId IN (:frameIds)") suspend fun byIds(frameIds: List<String>): List<FrameMetaEntity>
    @Query("SELECT COUNT(*) FROM frame_meta WHERE createdAt >= :sinceMs") suspend fun countSince(sinceMs: Long): Int
    @Query("SELECT * FROM frame_meta WHERE wireFrameId = :wireFrameId") suspend fun byWireId(wireFrameId: String): FrameMetaEntity?
    @Query("DELETE FROM frame_meta WHERE frameId IN (:frameIds)") suspend fun deleteFor(frameIds: List<String>): Int
}

@Dao
interface FrameSourceDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAll(rows: List<FrameSourceEntity>)
    @Query("SELECT * FROM frame_source WHERE memoryId = :memoryId") suspend fun forMemory(memoryId: String): List<FrameSourceEntity>
    @Query("SELECT memoryId FROM frame_source WHERE frameId = :frameId") suspend fun memoriesForFrame(frameId: String): List<String>
    @Query("DELETE FROM frame_source WHERE memoryId = :memoryId") suspend fun deleteForMemory(memoryId: String): Int
}

@Dao
interface FrameVectorDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(rows: List<FrameVectorEntity>)
    @Query("SELECT * FROM frame_vector WHERE spaceId = :spaceId ORDER BY frameId LIMIT :limit OFFSET :offset")
    suspend fun forSpace(spaceId: String, limit: Int, offset: Int): List<FrameVectorEntity>
    @Query("DELETE FROM frame_vector WHERE spaceId = :spaceId") suspend fun deleteSpace(spaceId: String): Int
    @Query("SELECT COUNT(*) FROM frame_vector WHERE spaceId = :spaceId") suspend fun countForSpace(spaceId: String): Int
    @Query("DELETE FROM frame_vector WHERE frameId IN (:frameIds)") suspend fun deleteFor(frameIds: List<String>): Int
}

@Dao
interface FrameClusterDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(row: FrameClusterEntity)
    @Query("SELECT * FROM frame_cluster WHERE closedAt IS NULL ORDER BY openedAt DESC LIMIT 1") suspend fun open(): FrameClusterEntity?
}

/** A frame row still awaiting extraction when v2 adopts it: its id and the JPEG path (the caller checks the file). */
data class AwaitingFrame(val id: String, val path: String)

/** One-shot pre-v2 adoption queries (keyframe capture P1). Rows are never changed; only `frame_state` gets inserts. */
@Dao
interface FrameAdoptionDao {
    /**
     * Keyset page of glasses frames with no `frame_state` row, no tombstone and no memory (or `frame_source` row): those still
     * await extraction, including frames whose 24 h queue job expired.
     */
    @Query(
        "SELECT f.id AS id, f.path AS path FROM frame f WHERE f.source LIKE 'rokid%' AND f.id > :after " +
            "AND NOT EXISTS (SELECT 1 FROM frame_state s WHERE s.frameId = f.id) " +
            "AND NOT EXISTS (SELECT 1 FROM forget_tombstone t WHERE t.frameId = f.id) " +
            "AND NOT EXISTS (SELECT 1 FROM memory m WHERE m.frameId = f.id) " +
            "AND NOT EXISTS (SELECT 1 FROM frame_source fs WHERE fs.frameId = f.id) ORDER BY f.id LIMIT :limit",
    )
    suspend fun awaiting(after: String, limit: Int): List<AwaitingFrame>

    /** Frames that already produced a memory (or a `frame_source` row) and have no tombstone: EXTRACTED, never re-queued. */
    @Query(
        "SELECT f.id FROM frame f WHERE f.source LIKE 'rokid%' AND f.id > :after " +
            "AND NOT EXISTS (SELECT 1 FROM frame_state s WHERE s.frameId = f.id) " +
            "AND NOT EXISTS (SELECT 1 FROM forget_tombstone t WHERE t.frameId = f.id) " +
            "AND (EXISTS (SELECT 1 FROM memory m WHERE m.frameId = f.id) OR EXISTS (SELECT 1 FROM frame_source fs WHERE fs.frameId = f.id)) " +
            "ORDER BY f.id LIMIT :limit",
    )
    suspend fun extracted(after: String, limit: Int): List<String>
}
