package com.rokid.context.memory.db

import androidx.room.ColumnInfo
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
            "AND COALESCE(lastError, '') != 'describe_before_delete' " + // a describe-before-delete photo is the night job's only (Task 9 fix I2)
            "AND NOT EXISTS (SELECT 1 FROM forget_tombstone t WHERE t.frameId = frame_state.frameId)", // a forgotten frame is never extracted again
    )
    protected abstract suspend fun claimRows(frameIds: List<String>, claimId: String, nowMs: Long, abandonBeforeMs: Long): Int

    /** An abandoned claim counts as an attempt (spec §P2): bump it, and the fifth ends in FAILED_FINAL. */
    @Query(
        "UPDATE frame_state SET attempts = attempts + 1, state = CASE WHEN lastError = 'describe_before_delete' THEN CASE WHEN attempts + 1 >= 5 THEN 'FAILED_FINAL' ELSE 'CAPTION_QUEUED' END " +
            "ELSE CASE WHEN attempts + 1 >= :maxAttempts THEN 'FAILED_FINAL' ELSE 'PENDING' END END, " +
            "lastError = CASE WHEN lastError = 'describe_before_delete' THEN CASE WHEN attempts + 1 >= 5 THEN 'describe_failed' ELSE lastError END ELSE 'abandoned' END, claimedAt = CASE WHEN lastError = 'describe_before_delete' AND attempts + 1 < 5 THEN :nowMs + (86400000 << attempts) ELSE NULL END, claimId = NULL, updatedAt = :nowMs " +
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
        "UPDATE frame_state SET state = :to, claimedAt = NULL, claimId = NULL, " +
            // Task 9: a photo described for describe-before-delete keeps its marker (its delete deadline carries the 3-day grace).
            "lastError = CASE WHEN lastError = 'describe_before_delete' THEN lastError ELSE NULL END, updatedAt = :nowMs " +
            "WHERE frameId = :frameId AND state = 'EXTRACTING' AND claimId = :claimId",
    )
    abstract suspend fun completeIfClaimed(frameId: String, claimId: String, to: String, nowMs: Long): Int

    /** → FAILED, or FAILED_FINAL on the [maxAttempts]th failure. Returns 0 when the claim no longer holds. */
    @Query(
        "UPDATE frame_state SET attempts = attempts + 1, " +
            // A describe-before-delete photo never goes to FAILED (the live loop would take it): it keeps its marker, waits 1, 2, 4, 8 days
            // (claimedAt = not-before) and after 5 attempts is parked as describe_failed (never deleted, never retried by itself).
            "state = CASE WHEN lastError = 'describe_before_delete' THEN CASE WHEN attempts + 1 >= 5 THEN 'FAILED_FINAL' ELSE 'CAPTION_QUEUED' END " +
            "ELSE CASE WHEN attempts + 1 >= :maxAttempts THEN 'FAILED_FINAL' ELSE 'FAILED' END END, " +
            "lastError = CASE WHEN lastError = 'describe_before_delete' THEN CASE WHEN attempts + 1 >= 5 THEN 'describe_failed' ELSE lastError END ELSE :error END, " +
            "claimedAt = CASE WHEN lastError = 'describe_before_delete' AND attempts + 1 < 5 THEN :nowMs + (86400000 << attempts) ELSE NULL END, claimId = NULL, updatedAt = :nowMs " +
            "WHERE frameId = :frameId AND state = 'EXTRACTING' AND claimId = :claimId",
    )
    abstract suspend fun fail(frameId: String, claimId: String, error: String, nowMs: Long, maxAttempts: Int = MAX_ATTEMPTS): Int

    @Query(
        "SELECT s.* FROM frame_state s WHERE (s.state IN ('PENDING','FAILED') OR (s.state = 'EXTRACTING' AND s.claimedAt < :abandonBeforeMs)) " +
            "AND (s.state != 'PENDING' OR NOT EXISTS (SELECT 1 FROM frame_meta m2 WHERE m2.frameId = s.frameId AND m2.routedAt IS NULL) OR s.updatedAt <= :nowMs - 600000) " + // wave 2: an unrouted frame is the scene router's for 10 min
            "AND COALESCE(s.lastError, '') != 'describe_before_delete' AND NOT EXISTS (SELECT 1 FROM forget_tombstone t WHERE t.frameId = s.frameId) ORDER BY s.updatedAt LIMIT :limit",
    )
    protected abstract suspend fun recoverableRows(nowMs: Long, abandonBeforeMs: Long, limit: Int): List<FrameStateEntity>

    /** PENDING, FAILED (attempts < 5 by construction: the fifth is FAILED_FINAL) and abandoned EXTRACTING rows, oldest first. */
    open suspend fun recoverable(nowMs: Long, abandonBeforeMs: Long, limit: Int): List<FrameStateEntity> = recoverableRows(nowMs, abandonBeforeMs, limit)

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
            "AND (s.state != 'PENDING' OR NOT EXISTS (SELECT 1 FROM frame_meta m2 WHERE m2.frameId = s.frameId AND m2.routedAt IS NULL) OR s.updatedAt <= :nowMs - 600000) " + // wave 2: unrouted frames wait 10 min for the scene router
            "AND COALESCE(s.lastError, '') != 'describe_before_delete' AND NOT EXISTS (SELECT 1 FROM forget_tombstone t WHERE t.frameId = s.frameId) " +
            "ORDER BY COALESCE(f.ts, s.updatedAt) DESC LIMIT :limit",
    )
    abstract suspend fun newestClaimable(nowMs: Long, abandonBeforeMs: Long, sinceMs: Long, ignoreBackoff: Int, limit: Int): List<FrameStateEntity>

    /** [newestClaimable] for dwell keyframes only (frame_meta.dwell), so a dwell frame is found however large the backlog. */
    @Query(
        "SELECT s.* FROM frame_state s JOIN frame_meta m ON m.frameId = s.frameId LEFT JOIN frame f ON f.id = s.frameId " +
            "WHERE m.dwell = 1 AND (s.state IN ('PENDING','FAILED') OR (s.state = 'EXTRACTING' AND s.claimedAt < :abandonBeforeMs)) " +
            "AND (s.state != 'FAILED' OR s.updatedAt + MIN(30000 << MAX(s.attempts - 1, 0), 1800000) <= :nowMs) " +
            "AND (s.state != 'PENDING' OR NOT EXISTS (SELECT 1 FROM frame_meta m2 WHERE m2.frameId = s.frameId AND m2.routedAt IS NULL) OR s.updatedAt <= :nowMs - 600000) " +
            "AND COALESCE(s.lastError, '') != 'describe_before_delete' AND NOT EXISTS (SELECT 1 FROM forget_tombstone t WHERE t.frameId = s.frameId) " +
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
        "UPDATE frame_state SET attempts = attempts + 1, state = CASE WHEN lastError = 'describe_before_delete' THEN CASE WHEN attempts + 1 >= 5 THEN 'FAILED_FINAL' ELSE 'CAPTION_QUEUED' END " +
            "ELSE CASE WHEN attempts + 1 >= :maxAttempts THEN 'FAILED_FINAL' ELSE 'PENDING' END END, " +
            "lastError = CASE WHEN lastError = 'describe_before_delete' THEN CASE WHEN attempts + 1 >= 5 THEN 'describe_failed' ELSE lastError END ELSE 'abandoned' END, claimedAt = CASE WHEN lastError = 'describe_before_delete' AND attempts + 1 < 5 THEN :nowMs + (86400000 << attempts) ELSE NULL END, claimId = NULL, updatedAt = :nowMs " +
            "WHERE state = 'EXTRACTING' AND claimedAt < :abandonBeforeMs",
    )
    abstract suspend fun requeueAbandoned(nowMs: Long, abandonBeforeMs: Long, maxAttempts: Int = MAX_ATTEMPTS): Int

    /**
     * Process start: no live worker can own a claim (claims never survive the process), so every EXTRACTING row goes back to
     * PENDING at once, attempts unchanged. A forgotten (tombstoned) frame is left alone: it is never extracted again.
     * Fix wave I5: only claims made before [claimedBeforeMs] (the process start): a worker of THIS process may already have
     * claimed (the legacy backstop claims without the pipeline), and that claim is live.
     */
    @Query(
        "UPDATE frame_state SET " +
            // A night claim that a dead process never settled counts an attempt and a cooldown (a photo that kills the process every night is bounded: 5 attempts, then parked).
            "attempts = CASE WHEN lastError = 'describe_before_delete' THEN attempts + 1 ELSE attempts END, " +
            "state = CASE WHEN lastError = 'describe_before_delete' THEN CASE WHEN attempts + 1 >= 5 THEN 'FAILED_FINAL' ELSE 'CAPTION_QUEUED' END ELSE 'PENDING' END, " +
            "lastError = CASE WHEN lastError = 'describe_before_delete' AND attempts + 1 >= 5 THEN 'describe_failed' ELSE lastError END, " +
            "claimedAt = CASE WHEN lastError = 'describe_before_delete' AND attempts + 1 < 5 THEN :nowMs + (86400000 << attempts) ELSE NULL END, claimId = NULL, updatedAt = :nowMs WHERE state = 'EXTRACTING' " +
            "AND (claimedAt IS NULL OR claimedAt < :claimedBeforeMs) " +
            "AND NOT EXISTS (SELECT 1 FROM forget_tombstone t WHERE t.frameId = frame_state.frameId)",
    )
    abstract suspend fun releaseAllExtracting(nowMs: Long, claimedBeforeMs: Long = Long.MAX_VALUE): Int

    /** Gives a claim back without an attempt (offline, no provider set up, cancelled): the frame is not at fault. */
    @Query(
        "UPDATE frame_state SET state = CASE WHEN lastError = 'describe_before_delete' THEN 'CAPTION_QUEUED' ELSE 'PENDING' END, claimedAt = NULL, claimId = NULL, updatedAt = :nowMs " +
            "WHERE frameId = :frameId AND state = 'EXTRACTING' AND claimId = :claimId",
    )
    abstract suspend fun release(frameId: String, claimId: String, nowMs: Long): Int

    @Query("SELECT COUNT(*) FROM frame_state WHERE state = 'PENDING'")
    abstract suspend fun pendingCount(): Int

    /**
     * Fix wave K5, backlog shedding: PENDING frames beyond the NEWEST [keepNewest] (by capture time) that are not dwell keyframes
     * go to NEAR_DUP with lastError 'shed_backlog' (no schema change): they are never extracted, and their photos then follow the
     * normal retention of skipped photos. A shed frame with frame_meta.salience >= 0.5 goes to CAPTION_QUEUED instead (wave 2, Ruling 8). Dwell keyframes and tombstoned frames are left alone. Returns how many rows moved.
     */
    @Query(
        "UPDATE frame_state SET state = CASE WHEN COALESCE((SELECT m.salience FROM frame_meta m WHERE m.frameId = frame_state.frameId), 0) >= 0.5 THEN 'CAPTION_QUEUED' ELSE 'NEAR_DUP' END, " +
            "lastError = 'shed_backlog', claimedAt = NULL, claimId = NULL, updatedAt = :nowMs " +
            "WHERE state = 'PENDING' AND frameId IN (" +
            "SELECT p.frameId FROM frame_state p LEFT JOIN frame f ON f.id = p.frameId WHERE p.state = 'PENDING' " +
            "ORDER BY COALESCE(f.ts, p.updatedAt) DESC LIMIT -1 OFFSET :keepNewest) " +
            "AND frameId NOT IN (SELECT frameId FROM frame_meta WHERE dwell = 1) " +
            "AND NOT EXISTS (SELECT 1 FROM forget_tombstone t WHERE t.frameId = frame_state.frameId)",
    )
    abstract suspend fun shedOldestNonDwell(keepNewest: Int, nowMs: Long): Int

    /** Frames skipped by [shedOldestNonDwell] (still counted until retention deletes them). */
    @Query("SELECT COUNT(*) FROM frame_state WHERE state = 'NEAR_DUP' AND lastError = 'shed_backlog'")
    abstract fun shedCount(): Flow<Int>

    @Query("SELECT state AS k, COUNT(*) AS v FROM frame_state GROUP BY state")
    abstract fun counts(): Flow<Map<@MapColumn("k") String, @MapColumn("v") Int>>

    @Query("UPDATE frame_state SET state = 'PENDING', attempts = 0, lastError = NULL, claimedAt = NULL, claimId = NULL, updatedAt = :nowMs WHERE state = 'FAILED_FINAL' AND COALESCE(lastError, '') != 'describe_failed'")
    abstract suspend fun retryAllFinal(nowMs: Long): Int

    /** Photos parked as `describe_failed` (5 failed night describes): they go back into the night queue's reach (never to the live loop). Returns how many. */
    @Query("UPDATE frame_state SET state = 'FAILED_FINAL', attempts = 0, lastError = NULL, claimedAt = NULL, claimId = NULL, updatedAt = :nowMs WHERE state = 'FAILED_FINAL' AND lastError = 'describe_failed'")
    abstract suspend fun retryDescribeFailed(nowMs: Long): Int

    /** Diagnostics: photos waiting in the night queue / parked after five failed describes. */
    @Query("SELECT COUNT(*) FROM frame_state WHERE state = 'CAPTION_QUEUED' AND lastError = 'describe_before_delete'")
    abstract suspend fun describeWaiting(): Int
    @Query("SELECT COUNT(*) FROM frame_state WHERE state = 'FAILED_FINAL' AND lastError = 'describe_failed'")
    abstract suspend fun describeFailed(): Int
    /** Photos described by the night job so far that a memory still cites (the night job logs the difference before and after its drain). */
    @Query(
        "SELECT COUNT(*) FROM frame_state s WHERE s.state = 'EXTRACTED' AND s.lastError = 'describe_before_delete' AND " +
            "(EXISTS (SELECT 1 FROM memory m WHERE m.frameId = s.frameId) OR EXISTS (SELECT 1 FROM frame_source fs WHERE fs.frameId = s.frameId))",
    )
    abstract suspend fun describedCount(): Int

    /**
     * "Reprocess old photos": EXTRACTED / FAILED / FAILED_FINAL frames back to PENDING (attempts 0, claim cleared) so the pipeline
     * extracts them again. Only frames with no live memory left (nothing to duplicate), never a tombstoned (forgotten) frame, and
     * never a frame being worked on. Returns how many rows moved.
     */
    @Query(
        "UPDATE frame_state SET state = 'PENDING', attempts = 0, lastError = NULL, claimedAt = NULL, claimId = NULL, updatedAt = :nowMs " +
            "WHERE frameId IN (:frameIds) AND state IN ('EXTRACTED','FAILED','FAILED_FINAL') " +
            "AND NOT EXISTS (SELECT 1 FROM forget_tombstone t WHERE t.frameId = frame_state.frameId) " +
            "AND NOT EXISTS (SELECT 1 FROM frame_source fs WHERE fs.frameId = frame_state.frameId) " +
            "AND NOT EXISTS (SELECT 1 FROM memory m WHERE m.frameId = frame_state.frameId)",
    )
    abstract suspend fun resetForReprocess(frameIds: List<String>, nowMs: Long): Int

    @Query("DELETE FROM frame_state WHERE frameId IN (:frameIds)") abstract suspend fun deleteFor(frameIds: List<String>): Int

    /** Guarded move from [from] to [to] (no claim involved): routing and cluster settlement. Returns rows changed. */
    @Query(
        "UPDATE frame_state SET state = :to, lastError = :lastError, claimedAt = NULL, claimId = NULL, updatedAt = :nowMs " +
            "WHERE frameId IN (:frameIds) AND state = :from",
    )
    abstract suspend fun move(frameIds: List<String>, from: String, to: String, lastError: String?, nowMs: Long): Int

    /** Members of [clusterId] still IN_CLUSTER → [to] (CLUSTERED, NEAR_DUP or FAILED_FINAL). */
    @Query(
        "UPDATE frame_state SET state = :to, lastError = :lastError, claimedAt = NULL, claimId = NULL, updatedAt = :nowMs " +
            "WHERE state = 'IN_CLUSTER' AND frameId IN (SELECT frameId FROM frame_meta WHERE clusterId = :clusterId)",
    )
    abstract suspend fun settleCluster(clusterId: String, to: String, lastError: String?, nowMs: Long): Int

    /** An ask claims PENDING frames (routed or not) and the IN_CLUSTER members of an OPEN/CLOSED cluster (a SUMMARIZING one is mid-call). */
    @Query(
        "UPDATE frame_state SET state = 'EXTRACTING', claimedAt = :nowMs, claimId = :claimId, updatedAt = :nowMs " +
            "WHERE frameId IN (:frameIds) AND (state = 'PENDING' OR (state = 'IN_CLUSTER' AND EXISTS (" +
            "SELECT 1 FROM frame_meta m JOIN frame_cluster c ON c.clusterId = m.clusterId WHERE m.frameId = frame_state.frameId AND c.state IN ('OPEN','CLOSED')))) " +
            "AND NOT EXISTS (SELECT 1 FROM forget_tombstone t WHERE t.frameId = frame_state.frameId)",
    )
    protected abstract suspend fun claimAskRows(frameIds: List<String>, claimId: String, nowMs: Long): Int

    /** Ask path: [frameIds] (the caller already limited them to frames captured since its window) in PENDING or IN_CLUSTER of an OPEN/CLOSED cluster → EXTRACTING. */
    @Transaction
    open suspend fun claimForAsk(frameIds: List<String>, claimId: String, nowMs: Long): List<FrameStateEntity> {
        if (frameIds.isEmpty()) return emptyList()
        claimAskRows(frameIds, claimId, nowMs)
        return claimedBy(frameIds, claimId)
    }

    /**
     * Frames an ask may claim: captured since [sinceMs], PENDING (routed or not) or IN_CLUSTER in an OPEN/CLOSED cluster (a SUMMARIZING
     * cluster is mid-call), newest first.
     */
    @Query(
        "SELECT s.* FROM frame_state s LEFT JOIN frame f ON f.id = s.frameId WHERE COALESCE(f.ts, s.updatedAt) >= :sinceMs AND (s.state = 'PENDING' OR (s.state = 'IN_CLUSTER' AND EXISTS (" +
            "SELECT 1 FROM frame_meta m JOIN frame_cluster c ON c.clusterId = m.clusterId WHERE m.frameId = s.frameId AND c.state IN ('OPEN','CLOSED')))) " +
            "AND COALESCE(s.lastError, '') != 'describe_before_delete' AND NOT EXISTS (SELECT 1 FROM forget_tombstone t WHERE t.frameId = s.frameId) ORDER BY COALESCE(f.ts, s.updatedAt) DESC LIMIT :limit",
    )
    abstract suspend fun askCandidates(sinceMs: Long, limit: Int): List<FrameStateEntity>

    /**
     * Key-moment photos whose caption is still owed, oldest first (lowest priority; aging is the pipeline's job). Photos queued by the night
     * job for describe-before-delete (`lastError = describe_before_delete`) are the night job's only: see [describeQueued].
     */
    @Query(
        "SELECT * FROM frame_state WHERE state = 'CAPTION_QUEUED' AND COALESCE(lastError, '') != 'describe_before_delete' " +
            "AND NOT EXISTS (SELECT 1 FROM forget_tombstone t WHERE t.frameId = frame_state.frameId) ORDER BY updatedAt LIMIT :limit",
    )
    abstract suspend fun captionQueued(limit: Int): List<FrameStateEntity>

    /** The night job's queue: every CAPTION_QUEUED photo, describe-before-delete ones first (by their queue order = deadline), then oldest first. */
    @Query(
        "SELECT * FROM frame_state WHERE state = 'CAPTION_QUEUED' " +
            "AND NOT EXISTS (SELECT 1 FROM forget_tombstone t WHERE t.frameId = frame_state.frameId) " +
            // a describe-before-delete photo in its cooldown (claimedAt = not-before after a failure) is skipped, so failures never starve the rest
            "AND (COALESCE(lastError, '') != 'describe_before_delete' OR claimedAt IS NULL OR claimedAt <= :nowMs) " +
            // fresh photos before retries (fewest failed attempts first), then deadline order: a failing photo never starves the ones behind it
            "ORDER BY CASE WHEN lastError = 'describe_before_delete' THEN 0 ELSE 1 END, attempts, updatedAt LIMIT :limit",
    )
    abstract suspend fun describeQueued(nowMs: Long, limit: Int): List<FrameStateEntity>

    /**
     * Night job (Task 9): [frameId] (a settled, undescribed, non-skipped keyframe that is due soon) → CAPTION_QUEUED with attempts 0 and
     * lastError `describe_before_delete`; [orderMs] sets the queue order (deadline order). Guarded: still in one of the settled states,
     * not forgotten, and still described by no memory (`ForgetDao.STATE_DESCRIBED`). Returns 1 when it moved.
     */
    @Query(
        "UPDATE frame_state SET state = 'CAPTION_QUEUED', attempts = CASE WHEN lastError = 'describe_before_delete' THEN attempts ELSE 0 END, lastError = 'describe_before_delete', claimedAt = NULL, claimId = NULL, updatedAt = :orderMs " +
            "WHERE frameId = :frameId AND state IN ('FAILED_FINAL','EXTRACTED','NEAR_DUP','CLUSTERED') AND COALESCE(lastError, '') != 'describe_failed' " +
            "AND (COALESCE(lastError, '') != 'describe_before_delete' OR claimedAt IS NULL OR claimedAt <= :orderMs) " +
            "AND NOT EXISTS (SELECT 1 FROM forget_tombstone t WHERE t.frameId = frame_state.frameId) " +
            "AND NOT " + ForgetDao.STATE_DESCRIBED, // codex r12: the shared description rule; a photo only canvas/derived/member rows cite is still queued
    )
    abstract suspend fun queueForDescribe(frameId: String, orderMs: Long): Int

    /**
     * A night describe that saved no memory (a legacy prompt, an empty caption) counts as a failed attempt: the marker photo that is EXTRACTED,
     * described by no memory (`ForgetDao.STATE_DESCRIBED`) and not yet noted (claimedAt NULL) gets attempts + 1 and its cooldown (1, 2, 4, 8 days), or `describe_failed` at five.
     */
    @Query(
        "UPDATE frame_state SET attempts = attempts + 1, state = CASE WHEN attempts + 1 >= 5 THEN 'FAILED_FINAL' ELSE state END, " +
            "lastError = CASE WHEN attempts + 1 >= 5 THEN 'describe_failed' ELSE lastError END, " +
            "claimedAt = CASE WHEN attempts + 1 < 5 THEN :nowMs + (86400000 << attempts) ELSE NULL END, updatedAt = :nowMs " +
            "WHERE state = 'EXTRACTED' AND lastError = 'describe_before_delete' AND claimedAt IS NULL " +
            "AND NOT " + ForgetDao.STATE_DESCRIBED, // codex r13: "saved no description" by the shared rule; an excluded citation does not count
    )
    abstract suspend fun noteUncitedDescribed(nowMs: Long): Int

    @Query(
        "UPDATE frame_state SET state = 'EXTRACTING', claimedAt = :nowMs, claimId = :claimId, updatedAt = :nowMs " +
            "WHERE frameId IN (:frameIds) AND state = 'CAPTION_QUEUED' " +
            "AND NOT EXISTS (SELECT 1 FROM forget_tombstone t WHERE t.frameId = frame_state.frameId)",
    )
    protected abstract suspend fun claimCaptionRows(frameIds: List<String>, claimId: String, nowMs: Long): Int

    /** CAPTION_QUEUED → EXTRACTING; returns the rows it claimed. */
    @Transaction
    open suspend fun claimCaptionQueued(frameIds: List<String>, claimId: String, nowMs: Long): List<FrameStateEntity> {
        if (frameIds.isEmpty()) return emptyList()
        claimCaptionRows(frameIds, claimId, nowMs)
        return claimedBy(frameIds, claimId)
    }

    companion object {
        const val MAX_ATTEMPTS = 5
        /** frame_state.lastError of a photo the night job queued (or described) for describe-before-delete. */
        const val DESCRIBE_BEFORE_DELETE = "describe_before_delete"
    }
}

@Dao
interface FrameMetaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(row: FrameMetaEntity)
    @Query("SELECT * FROM frame_meta WHERE frameId = :frameId") suspend fun get(frameId: String): FrameMetaEntity?
    @Query("SELECT * FROM frame_meta WHERE frameId IN (:frameIds)") suspend fun byIds(frameIds: List<String>): List<FrameMetaEntity>
    @Query("SELECT COUNT(*) FROM frame_meta WHERE createdAt >= :sinceMs") suspend fun countSince(sinceMs: Long): Int
    @Query("SELECT * FROM frame_meta WHERE wireFrameId = :wireFrameId") suspend fun byWireId(wireFrameId: String): FrameMetaEntity?
    @Query("DELETE FROM frame_meta WHERE frameId IN (:frameIds)") suspend fun deleteFor(frameIds: List<String>): Int

    /** PENDING frames the scene router has not seen (routedAt null), no tombstone, oldest capture first. */
    @Query(
        "SELECT m.* FROM frame_meta m JOIN frame_state s ON s.frameId = m.frameId LEFT JOIN frame f ON f.id = m.frameId " +
            "WHERE s.state = 'PENDING' AND m.routedAt IS NULL " +
            "AND NOT EXISTS (SELECT 1 FROM forget_tombstone t WHERE t.frameId = m.frameId) " +
            "ORDER BY COALESCE(f.ts, m.createdAt) ASC LIMIT :limit",
    )
    suspend fun unroutedPending(limit: Int): List<FrameMetaEntity>

    /** PENDING frames the scene router has not routed yet (Diagnostics). */
    @Query("SELECT COUNT(*) FROM frame_meta m JOIN frame_state s ON s.frameId = m.frameId WHERE s.state = 'PENDING' AND m.routedAt IS NULL")
    suspend fun unroutedCount(): Int

    @Query("SELECT * FROM frame_meta WHERE clusterId = :clusterId ORDER BY createdAt, frameId") suspend fun members(clusterId: String): List<FrameMetaEntity>

    @Query(
        "UPDATE frame_meta SET salience = :salience, salienceTerms = :salienceTerms, nearDupOf = :nearDupOf, clusterId = :clusterId, " +
            "imageVectorSpace = :imageVectorSpace, routedAt = :routedAt WHERE frameId = :frameId",
    )
    suspend fun markRouted(frameId: String, salience: Float?, salienceTerms: String?, nearDupOf: String?, clusterId: String?, imageVectorSpace: String?, routedAt: Long)

    /** Wave B: records the image-intent result of a photo. Returns the rows changed (0 = no such frame). */
    @Query("UPDATE frame_meta SET intentLabel = :label, intentConf = :conf, intentAt = :at WHERE frameId = :frameId")
    suspend fun setIntent(frameId: String, label: String, conf: Float, at: Long): Int

    /**
     * The canvas's write: only when the photo has no label yet (the sampler's dedicated INTENT_CLASSIFY label always wins) and the frame is not
     * tombstoned, in ONE statement so a Forget cannot slip in between a check and the write. Rows changed: 0 = labelled, tombstoned or gone.
     */
    @Query("UPDATE frame_meta SET intentLabel = :label, intentConf = :conf, intentAt = :at WHERE frameId = :frameId AND intentLabel IS NULL " +
        "AND NOT EXISTS (SELECT 1 FROM forget_tombstone t WHERE t.frameId = :frameId)")
    suspend fun setIntentIfUnlabelled(frameId: String, label: String, conf: Float, at: Long): Int

    /** The newest photo received at or after [sinceMs] that has no intent result yet. */
    @Query("SELECT * FROM frame_meta WHERE createdAt >= :sinceMs AND intentLabel IS NULL ORDER BY createdAt DESC, frameId LIMIT 1")
    suspend fun newestUnlabelled(sinceMs: Long): FrameMetaEntity?

    /** Wave 3: sets the capture time of a photo (phone clock). Returns the rows changed. */
    @Query("UPDATE frame_meta SET capturedAtMs = :capturedAtMs WHERE frameId = :frameId")
    suspend fun setCapturedAt(frameId: String, capturedAtMs: Long): Int
}

@Dao
interface FrameSourceDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAll(rows: List<FrameSourceEntity>)
    @Query("SELECT * FROM frame_source WHERE memoryId = :memoryId") suspend fun forMemory(memoryId: String): List<FrameSourceEntity>
    @Query("SELECT memoryId FROM frame_source WHERE frameId = :frameId") suspend fun memoriesForFrame(frameId: String): List<String>
    @Query("DELETE FROM frame_source WHERE memoryId = :memoryId") suspend fun deleteForMemory(memoryId: String): Int
    @Query("DELETE FROM frame_source WHERE memoryId IN (:memoryIds)") suspend fun deleteForMemories(memoryIds: List<String>): Int
    @Query("DELETE FROM frame_source WHERE frameId IN (:frameIds)") suspend fun deleteForFrames(frameIds: List<String>): Int

    /** Those of [frameIds] that are described: a live (not INVALIDATED) memory cites them, or a FACT / CLUSTER_REP `frame_source` row. Callers chunk (≤ 500). */
    @Query(
        "SELECT frameId FROM memory WHERE frameId IN (:frameIds) AND reviewState != 'INVALIDATED' " +
            "UNION SELECT frameId FROM frame_source WHERE frameId IN (:frameIds) AND role IN ('FACT','CLUSTER_REP')",
    )
    suspend fun describedAmong(frameIds: List<String>): List<String>

    /** Per frame, its newest live `caption` memory (one row per frame). Callers chunk (≤ 500). */
    @Query(
        "SELECT m.frameId AS frameId, m.id AS memoryId, m.text AS text, m.createdAt AS createdAt FROM memory m " +
            "WHERE m.frameId IN (:frameIds) AND m.type = 'caption' AND m.reviewState != 'INVALIDATED' " +
            "AND m.id = (SELECT x.id FROM memory x WHERE x.frameId = m.frameId AND x.type = 'caption' AND x.reviewState != 'INVALIDATED' " +
            "ORDER BY x.createdAt DESC, x.id DESC LIMIT 1)",
    )
    suspend fun newestCaptions(frameIds: List<String>): List<FrameCaptionRow>

    /** As [newestCaptions] for a historical view: only captions created at or before [notAfter] exist (Ask debug, S34). */
    @Query(
        "SELECT m.frameId AS frameId, m.id AS memoryId, m.text AS text, m.createdAt AS createdAt FROM memory m " +
            "WHERE m.frameId IN (:frameIds) AND m.type = 'caption' AND m.reviewState != 'INVALIDATED' AND m.createdAt <= :notAfter " +
            "AND m.id = (SELECT x.id FROM memory x WHERE x.frameId = m.frameId AND x.type = 'caption' AND x.reviewState != 'INVALIDATED' AND x.createdAt <= :notAfter " +
            "ORDER BY x.createdAt DESC, x.id DESC LIMIT 1)",
    )
    suspend fun newestCaptionsNotAfter(frameIds: List<String>, notAfter: Long): List<FrameCaptionRow>
}

/** One stored image vector with its insertion rowid (the scan's keyset). */
data class FrameVectorRow(@ColumnInfo(name = "rowid") val rowid: Long, val frameId: String, val blob: ByteArray)

/** The newest live caption memory of a frame. */
data class FrameCaptionRow(val frameId: String, val memoryId: String, val text: String, val createdAt: Long)

@Dao
interface FrameVectorDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(rows: List<FrameVectorEntity>)
    @Query("SELECT * FROM frame_vector WHERE spaceId = :spaceId ORDER BY frameId LIMIT :limit OFFSET :offset")
    suspend fun forSpace(spaceId: String, limit: Int, offset: Int): List<FrameVectorEntity>
    @Query("DELETE FROM frame_vector WHERE spaceId = :spaceId") suspend fun deleteSpace(spaceId: String): Int
    @Query("SELECT COUNT(*) FROM frame_vector WHERE spaceId = :spaceId") suspend fun countForSpace(spaceId: String): Int
    @Query("DELETE FROM frame_vector WHERE frameId IN (:frameIds)") suspend fun deleteFor(frameIds: List<String>): Int
    @Query("SELECT * FROM frame_vector WHERE frameId = :frameId AND spaceId = :spaceId") suspend fun get(frameId: String, spaceId: String): FrameVectorEntity?

    /** Vectors of [spaceId] for frames captured in [fromTs, toTs] (by frame.ts). */
    @Query("SELECT v.* FROM frame_vector v JOIN frame f ON f.id = v.frameId WHERE v.spaceId = :spaceId AND f.ts BETWEEN :fromTs AND :toTs ORDER BY f.ts")
    suspend fun window(spaceId: String, fromTs: Long, toTs: Long): List<FrameVectorEntity>

    /**
     * Recall scan page: rowid keyset, most recently indexed first. Forgotten frames have no `frame_meta`, so the JOIN drops them.
     * First page: [beforeRowid] = Long.MAX_VALUE.
     */
    @Query(
        "SELECT v.rowid AS rowid, v.frameId AS frameId, v.blob AS blob FROM frame_vector v JOIN frame_meta m ON m.frameId = v.frameId " +
            "WHERE v.spaceId = :spaceId AND v.rowid < :beforeRowid ORDER BY v.rowid DESC LIMIT :limit",
    )
    suspend fun pageDesc(spaceId: String, beforeRowid: Long, limit: Int): List<FrameVectorRow>

    /** As [pageDesc] for a historical view: only frames captured (frame.ts, else the capture time, else the receive time) AND received (frame_meta.createdAt) at or before [notAfter] are scanned, so the cutoff applies BEFORE top-k. */
    @Query(
        "SELECT v.rowid AS rowid, v.frameId AS frameId, v.blob AS blob FROM frame_vector v JOIN frame_meta m ON m.frameId = v.frameId LEFT JOIN frame f ON f.id = v.frameId " +
            "WHERE v.spaceId = :spaceId AND v.rowid < :beforeRowid AND COALESCE(f.ts, m.capturedAtMs, m.createdAt) <= :notAfter AND m.createdAt <= :notAfter ORDER BY v.rowid DESC LIMIT :limit",
    )
    suspend fun pageDescNotAfter(spaceId: String, beforeRowid: Long, limit: Int, notAfter: Long): List<FrameVectorRow>

    /** As [pageDescNotAfter] with a lower bound too: only frames captured in [notBefore, notAfter] are scanned (a time window, applied BEFORE top-k). */
    @Query(
        "SELECT v.rowid AS rowid, v.frameId AS frameId, v.blob AS blob FROM frame_vector v JOIN frame_meta m ON m.frameId = v.frameId LEFT JOIN frame f ON f.id = v.frameId " +
            "WHERE v.spaceId = :spaceId AND v.rowid < :beforeRowid AND COALESCE(f.ts, m.capturedAtMs, m.createdAt) BETWEEN :notBefore AND :notAfter AND m.createdAt <= :notAfter ORDER BY v.rowid DESC LIMIT :limit",
    )
    suspend fun pageDescBetween(spaceId: String, beforeRowid: Long, limit: Int, notBefore: Long, notAfter: Long): List<FrameVectorRow>

    @Query("SELECT * FROM frame_vector WHERE spaceId = :spaceId AND frameId IN (:frameIds)") suspend fun forFrames(spaceId: String, frameIds: List<String>): List<FrameVectorEntity>
}

/**
 * Scene clusters (wave 2). The summary call's claim lives on the cluster row (not on its members): CLOSED → SUMMARIZING (claimId) →
 * SUMMARIZED, with the same CAS, backoff and attempt rules as [FrameStateDao]. Callers chunk `IN (…)` lists (≤ 500).
 */
@Dao
abstract class FrameClusterDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun upsert(row: FrameClusterEntity)
    @Query("SELECT * FROM frame_cluster WHERE state = 'OPEN' ORDER BY openedAt DESC LIMIT 1") abstract suspend fun open(): FrameClusterEntity?
    @Query("SELECT * FROM frame_cluster WHERE clusterId = :clusterId") abstract suspend fun get(clusterId: String): FrameClusterEntity?

    /** CLOSED, FAILED past its backoff (30 s doubling, capped at 30 min), or SUMMARIZING abandoned before [abandonBeforeMs]; oldest closedAt first. */
    @Query(
        "SELECT * FROM frame_cluster WHERE (state = 'CLOSED' " +
            "OR (state = 'FAILED' AND updatedAt + MIN(30000 << MAX(attempts - 1, 0), 1800000) <= :nowMs) " +
            "OR (state = 'SUMMARIZING' AND claimedAt < :abandonBeforeMs)) ORDER BY closedAt, clusterId LIMIT :limit",
    )
    abstract suspend fun claimable(nowMs: Long, abandonBeforeMs: Long, limit: Int): List<FrameClusterEntity>

    @Query(
        "UPDATE frame_cluster SET state = 'SUMMARIZING', claimedAt = :nowMs, claimId = :claimId, updatedAt = :nowMs " +
            "WHERE clusterId IN (:ids) AND state IN ('CLOSED','FAILED')",
    )
    protected abstract suspend fun claimRows(ids: List<String>, claimId: String, nowMs: Long): Int

    /** An abandoned claim counts as an attempt: back to CLOSED, or FAILED_FINAL on the fifth. */
    @Query(
        "UPDATE frame_cluster SET attempts = attempts + 1, state = CASE WHEN attempts + 1 >= :maxAttempts THEN 'FAILED_FINAL' ELSE 'CLOSED' END, " +
            "lastError = 'abandoned', claimedAt = NULL, claimId = NULL, updatedAt = :nowMs " +
            "WHERE clusterId IN (:ids) AND state = 'SUMMARIZING' AND claimedAt < :abandonBeforeMs",
    )
    protected abstract suspend fun abandonRows(ids: List<String>, nowMs: Long, abandonBeforeMs: Long, maxAttempts: Int): Int

    @Query("SELECT * FROM frame_cluster WHERE clusterId IN (:ids) AND state = 'SUMMARIZING' AND claimId = :claimId")
    protected abstract suspend fun claimedBy(ids: List<String>, claimId: String): List<FrameClusterEntity>

    /** CLOSED / FAILED / abandoned SUMMARIZING → SUMMARIZING with [claimId]; returns the rows it claimed (an abandoned one counts an attempt first). */
    @Transaction
    open suspend fun claim(ids: List<String>, claimId: String, nowMs: Long, abandonBeforeMs: Long): List<FrameClusterEntity> {
        if (ids.isEmpty()) return emptyList()
        abandonRows(ids, nowMs, abandonBeforeMs, FrameStateDao.MAX_ATTEMPTS)
        claimRows(ids, claimId, nowMs)
        return claimedBy(ids, claimId)
    }

    /** The CAS: SUMMARIZING + [claimId] → SUMMARIZED with [memoryId]; 1, or 0 when the claim no longer holds. Runs first in the commit transaction. */
    @Query(
        "UPDATE frame_cluster SET state = 'SUMMARIZED', memoryId = :memoryId, claimedAt = NULL, claimId = NULL, lastError = NULL, updatedAt = :nowMs " +
            "WHERE clusterId = :clusterId AND state = 'SUMMARIZING' AND claimId = :claimId",
    )
    abstract suspend fun completeIfClaimed(clusterId: String, claimId: String, memoryId: String, nowMs: Long): Int

    /** → FAILED, or FAILED_FINAL on the [maxAttempts]th failure. 0 when the claim no longer holds. */
    @Query(
        "UPDATE frame_cluster SET attempts = attempts + 1, state = CASE WHEN attempts + 1 >= :maxAttempts THEN 'FAILED_FINAL' ELSE 'FAILED' END, " +
            "lastError = :error, claimedAt = NULL, claimId = NULL, updatedAt = :nowMs " +
            "WHERE clusterId = :clusterId AND state = 'SUMMARIZING' AND claimId = :claimId",
    )
    abstract suspend fun fail(clusterId: String, claimId: String, error: String, nowMs: Long, maxAttempts: Int = FrameStateDao.MAX_ATTEMPTS): Int

    /** Gives a claim back without an attempt (offline, no provider, cancelled). */
    @Query(
        "UPDATE frame_cluster SET state = 'CLOSED', claimedAt = NULL, claimId = NULL, updatedAt = :nowMs " +
            "WHERE clusterId = :clusterId AND state = 'SUMMARIZING' AND claimId = :claimId",
    )
    abstract suspend fun release(clusterId: String, claimId: String, nowMs: Long): Int

    /** Process start: SUMMARIZING claims taken before [claimedBeforeMs] (an earlier process) go back to CLOSED, attempts unchanged. */
    @Query(
        "UPDATE frame_cluster SET state = 'CLOSED', claimedAt = NULL, claimId = NULL, updatedAt = :nowMs " +
            "WHERE state = 'SUMMARIZING' AND (claimedAt IS NULL OR claimedAt < :claimedBeforeMs)",
    )
    abstract suspend fun releaseAllSummarizing(nowMs: Long, claimedBeforeMs: Long): Int

    /** Abandoned SUMMARIZING claims go back to CLOSED and count an attempt; the fifth ends in FAILED_FINAL. */
    @Query(
        "UPDATE frame_cluster SET attempts = attempts + 1, state = CASE WHEN attempts + 1 >= :maxAttempts THEN 'FAILED_FINAL' ELSE 'CLOSED' END, " +
            "lastError = 'abandoned', claimedAt = NULL, claimId = NULL, updatedAt = :nowMs " +
            "WHERE state = 'SUMMARIZING' AND claimedAt < :abandonBeforeMs",
    )
    abstract suspend fun requeueAbandoned(nowMs: Long, abandonBeforeMs: Long, maxAttempts: Int = FrameStateDao.MAX_ATTEMPTS): Int

    @Query("SELECT state AS k, COUNT(*) AS v FROM frame_cluster GROUP BY state")
    abstract fun counts(): Flow<Map<@MapColumn("k") String, @MapColumn("v") Int>>

    /** FAILED_FINAL clusters that still have IN_CLUSTER members (the Ruling-10 split has not run for them). */
    @Query(
        "SELECT clusterId FROM frame_cluster WHERE state = 'FAILED_FINAL' AND EXISTS (" +
            "SELECT 1 FROM frame_meta m JOIN frame_state s ON s.frameId = m.frameId WHERE m.clusterId = frame_cluster.clusterId AND s.state = 'IN_CLUSTER')",
    )
    abstract suspend fun finalWithOpenMembers(): List<String>

    /** When the earliest FAILED cluster leaves its backoff (null: none is waiting). */
    @Query("SELECT MIN(updatedAt + MIN(30000 << MAX(attempts - 1, 0), 1800000)) FROM frame_cluster WHERE state = 'FAILED'")
    abstract suspend fun nextRetryAt(): Long?

    /** Clusters summarized at or after [sinceMs]: the memory id (its claim id is the call) and how many photos the summary covers. */
    @Query("SELECT memoryId, memberCount FROM frame_cluster WHERE state = 'SUMMARIZED' AND memoryId IS NOT NULL AND updatedAt >= :sinceMs")
    abstract suspend fun summarizedSince(sinceMs: Long): List<SummarizedCluster>
}

/** A summarized cluster for Diagnostics: [memoryId] is `rokid_cl_<claimId>_<n>`. */
data class SummarizedCluster(val memoryId: String, val memberCount: Int)

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
