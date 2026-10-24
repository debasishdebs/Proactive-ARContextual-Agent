package com.rokid.context.memory.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/** Moment projection, tombstones, the cleanup outbox and the authoritative-store discovery queries of Moment Forget. Callers chunk every list at 500. */
@Dao
abstract class MomentDao {
    // --- projection ---
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun upsertMoment(m: MomentEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun insertParts(p: List<MomentPartEntity>)
    @Query("SELECT * FROM moment WHERE momentId = :id") abstract suspend fun moment(id: String): MomentEntity?
    @Query("SELECT * FROM moment ORDER BY momentId") abstract suspend fun allMoments(): List<MomentEntity>
    @Query("SELECT * FROM moment_part WHERE momentId = :id ORDER BY kind, refId") abstract suspend fun parts(id: String): List<MomentPartEntity>
    @Query("SELECT * FROM moment_part WHERE momentId IN (:ids) ORDER BY momentId, kind, refId") abstract suspend fun partsOf(ids: List<String>): List<MomentPartEntity>
    @Query("SELECT momentId FROM moment WHERE momentId >= :fromId AND momentId <= :toId ORDER BY momentId") abstract suspend fun momentIdsBetween(fromId: String, toId: String): List<String>
    @Query("SELECT DISTINCT momentId FROM moment_part WHERE kind IN (:kinds) AND refId IN (:refIds)") abstract suspend fun momentIdsHolding(kinds: List<String>, refIds: List<String>): List<String>
    @Query("DELETE FROM moment_part WHERE momentId IN (:ids)") abstract suspend fun deleteParts(ids: List<String>)
    @Query("DELETE FROM moment WHERE momentId IN (:ids)") abstract suspend fun deleteMoments(ids: List<String>)
    @Query("DELETE FROM moment_trace_dep WHERE momentId IN (:ids)") abstract suspend fun deleteTraceDeps(ids: List<String>)
    /** Reserved: no production writer in phase 1 (tests only). */
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun insertTraceDeps(d: List<MomentTraceDepEntity>)
    @Query("SELECT COUNT(*) FROM moment_trace_dep") abstract suspend fun traceDepCount(): Int

    /** Replaces one Moment and all its parts in the caller's transaction. */
    @Transaction open suspend fun replace(m: MomentEntity, parts: List<MomentPartEntity>) {
        deleteParts(listOf(m.momentId))
        upsertMoment(m)
        insertParts(parts)
    }

    /** Removes [ids] and everything that hangs on them (parts, trace deps). */
    @Transaction open suspend fun remove(ids: List<String>) = ids.chunked(CHUNK).forEach { deleteParts(it); deleteTraceDeps(it); deleteMoments(it) }

    /**
     * Source-to-projection cleanup: removes every stored Moment that holds one of these deleted (or invalidated) sources, so a projection never keeps a reference to something Forget
     * removed. The projection is rebuildable, so removing the whole Moment (not one part) also drops its now-stale content hash. Returns the Moment ids removed.
     */
    @Transaction open suspend fun dropHolding(frameIds: Collection<String>, memoryIds: Collection<String>, labelIds: Collection<String> = emptyList()): List<String> {
        val ids = LinkedHashSet<String>()
        frameIds.toList().chunked(CHUNK).forEach { c -> ids += momentIdsHolding(listOf(MomentPartKind.FRAME, MomentPartKind.CITE), c + c.map { "frame:$it" }) }
        memoryIds.toList().chunked(CHUNK).forEach { c -> ids += momentIdsHolding(listOf(MomentPartKind.MEMORY, MomentPartKind.SUMMARY), c) }
        labelIds.toList().chunked(CHUNK).forEach { c -> ids += momentIdsHolding(listOf(MomentPartKind.MARK), c) }
        remove(ids.toList())
        return ids.toList()
    }

    /** Removes every stored Moment whose second lies in `[fromMs, toMs]` (a Forget of that time). Returns the Moment ids removed. */
    @Transaction open suspend fun dropBetween(fromMs: Long, toMs: Long): List<String> {
        if (toMs < 0) return emptyList()
        val lo = com.rokid.context.memory.moment.MomentId.format(Math.floorDiv(maxOf(fromMs, 0L), 1000L).coerceAtMost(com.rokid.context.memory.moment.MomentId.MAX_SECOND))
        val hi = com.rokid.context.memory.moment.MomentId.format(Math.floorDiv(toMs, 1000L).coerceAtMost(com.rokid.context.memory.moment.MomentId.MAX_SECOND))
        val ids = momentIdsBetween(lo, hi)
        remove(ids)
        return ids
    }

    // --- tombstones ---
    /** Upsert: a repeated Forget REFRESHES `createdAt` (the newest wins), because the recency window of unknown-capture-time frames is measured from it. */
    @Transaction open suspend fun insertTombstones(t: List<MomentTombstoneEntity>) = t.forEach { upsertTombstone(it.momentId, it.createdAt) }
    @Query("INSERT INTO moment_tombstone (momentId, createdAt) VALUES (:id, :at) ON CONFLICT(momentId) DO UPDATE SET createdAt = MAX(createdAt, excluded.createdAt)") protected abstract suspend fun upsertTombstone(id: String, at: Long)
    @Query("SELECT momentId FROM moment_tombstone WHERE momentId IN (:ids)") abstract suspend fun tombstonedAmong(ids: List<String>): List<String>
    @Query("SELECT COUNT(*) FROM moment_tombstone WHERE createdAt >= :since") abstract suspend fun tombstonesSince(since: Long): Int
    /** Frames among [ids] with no capture time on `frame_meta` (no usable capture evidence: file or receipt time only). */
    @Query("SELECT f.id FROM frame f LEFT JOIN frame_meta m ON m.frameId = f.id WHERE f.id IN (:ids) AND m.capturedAtMs IS NULL") abstract suspend fun framesWithoutCaptureTime(ids: List<String>): List<String>
    @Query("SELECT COUNT(*) FROM moment_tombstone") abstract suspend fun tombstoneCount(): Int

    // --- cleanup outbox ---
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun insertOutbox(rows: List<CleanupOutboxEntity>): List<Long>
    @Query("SELECT * FROM cleanup_outbox WHERE ackedAt IS NULL ORDER BY id") abstract suspend fun pendingOutbox(): List<CleanupOutboxEntity>
    @Query("SELECT COUNT(*) FROM cleanup_outbox") abstract suspend fun outboxCount(): Int
    @Query("UPDATE cleanup_outbox SET ackedAt = :now WHERE id = :id AND ackedAt IS NULL") abstract suspend fun ack(id: Long, now: Long): Int

    // --- discovery in the AUTHORITATIVE stores (never the projection) ---
    @Query("SELECT id FROM frame WHERE ts >= :fromMs AND ts <= :toMs") abstract suspend fun frameIdsBetween(fromMs: Long, toMs: Long): List<String>
    /** Photos retention already expired (no `frame` row) still have their capture time on `frame_meta`. */
    @Query("SELECT frameId FROM frame_meta WHERE capturedAtMs IS NOT NULL AND capturedAtMs >= :fromMs AND capturedAtMs <= :toMs") abstract suspend fun metaFrameIdsBetween(fromMs: Long, toMs: Long): List<String>
    @Query("SELECT id FROM memory WHERE createdAt >= :fromMs AND createdAt <= :toMs") abstract suspend fun memoryIdsCreatedBetween(fromMs: Long, toMs: Long): List<String>
    @Query("SELECT id FROM memory WHERE frameId IN (:frameIds) UNION SELECT memoryId FROM frame_source WHERE frameId IN (:frameIds)") abstract suspend fun memoryIdsOfFrames(frameIds: List<String>): List<String>
    @Query("SELECT frameId, wireFrameId FROM frame_meta WHERE frameId IN (:frameIds) AND wireFrameId IS NOT NULL") abstract suspend fun wireIds(frameIds: List<String>): List<WireIdRow>
    @Query("SELECT path FROM frame WHERE id IN (:frameIds) AND path != ''") abstract suspend fun framePaths(frameIds: List<String>): List<String>
    @Query("SELECT labelId FROM canvas_label WHERE atMs >= :fromMs AND atMs <= :toMs ORDER BY labelId") abstract suspend fun labelIdsBetween(fromMs: Long, toMs: Long): List<String>
    @Query("DELETE FROM canvas_label WHERE labelId IN (:ids)") abstract suspend fun deleteLabels(ids: List<String>)
    @Query("SELECT labelId FROM canvas_label WHERE labelId IN (:ids)") abstract suspend fun labelIdsAmong(ids: List<String>): List<String>
    /** Distinct seconds that hold a live (not tombstoned) frame, for a window rebuild. */
    @Query("SELECT DISTINCT ts / 1000 FROM frame WHERE ts >= :fromMs AND ts <= :toMs AND ts >= 0 AND id NOT IN (SELECT frameId FROM forget_tombstone) ORDER BY 1") abstract suspend fun liveFrameSeconds(fromMs: Long, toMs: Long): List<Long>
    @Query("SELECT m.id FROM memory m WHERE m.derived = 1 AND m.id IN (:ids)") abstract suspend fun derivedAmong(ids: List<String>): List<String>

    data class WireIdRow(val frameId: String, val wireFrameId: String)

    private companion object { const val CHUNK = 500 }
}
