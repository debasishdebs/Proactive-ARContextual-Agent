package com.rokid.context.memory.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/** Moment projection, tombstones, the cleanup outbox and the authoritative-store discovery queries of Moment Forget. Callers chunk every list at 500. */
@Dao
abstract class MomentDao : DerivedStaleDao() {
    // --- projection ---
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun upsertMoment(m: MomentEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun insertParts(p: List<MomentPartEntity>)
    @Query("SELECT * FROM moment WHERE momentId = :id") abstract suspend fun moment(id: String): MomentEntity?
    @Query("SELECT * FROM moment ORDER BY momentId") abstract suspend fun allMoments(): List<MomentEntity>
    @Query("SELECT * FROM moment WHERE momentId >= :fromId AND momentId <= :toId ORDER BY momentId") abstract suspend fun momentsBetween(fromId: String, toId: String): List<MomentEntity>
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

    /**
     * Sets the sensor rows of one Moment to [rows] (empty = none). Returns false and writes nothing when the Moment does not exist or the rows are already exactly these; otherwise the old
     * rows are replaced and, in the SAME transaction, the derived fields of the Moment and of its Scenes are hidden and its input revision moves (INVALIDATED, names only). [hide] false is for a
     * Moment that is being created in this very transaction (it has no derived field to hide and its BUILT row is its first input revision).
     */
    @Transaction open suspend fun replaceSensor(momentId: String, rows: List<MomentSensorEntity>, now: Long, step: String, hide: Boolean = true): Boolean {
        require(rows.all { it.momentId == momentId }) { "sensor rows of another Moment" }
        if (momentExists(momentId) == 0) return false
        val old = sensorOf(listOf(momentId))
        if (old == rows.sortedBy { it.phoneSecond }) return false
        deleteSensorOf(listOf(momentId)); insertSensor(rows)
        if (hide) hideAfterSensorChange(listOf(momentId), now, step)
        return true
    }

    /**
     * Removes [ids] and everything that hangs on them (parts, trace deps), and shrinks every Scene that held one of them (its derived fields are hidden in this same transaction).
     * [step] is the audit step name of that shrink: `forget` (the default: Forget and the source cleanup) or `build` (the builder dropped a Moment that no longer qualifies).
     */
    @Transaction open suspend fun remove(ids: List<String>, step: String = "forget") {
        ids.chunked(CHUNK).forEach { deleteParts(it); deleteTraceDeps(it); deleteSensorOf(it); deleteMoments(it); deleteObjectRows(it) }
        if (ids.isNotEmpty()) shrinkScenesHolding(ids, step)
    }
    // The audit and the derived fields of a Moment go WITH the Moment, on every path that removes one (Forget cascade, source cleanup, builder removal).
    @Query("DELETE FROM object_audit WHERE objectKind = 'MOMENT' AND objectId IN (:ids)") protected abstract suspend fun deleteAuditRows(ids: List<String>)
    @Query("DELETE FROM object_derived WHERE objectKind = 'MOMENT' AND objectId IN (:ids)") protected abstract suspend fun deleteDerivedRows(ids: List<String>)
    private suspend fun deleteObjectRows(ids: List<String>) { deleteAuditRows(ids); deleteDerivedRows(ids) }

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
    /** The drainer's bounded read: at most [limit] pending rows, phone files first then oldest, skipping [excludeIds] (rows still inside their backoff; callers pass at most 500). Never the whole table. */
    @Query("SELECT * FROM cleanup_outbox WHERE ackedAt IS NULL AND id NOT IN (:excludeIds) ORDER BY (target != 'PHONE_FILE'), id LIMIT :limit") abstract suspend fun dueOutbox(limit: Int, excludeIds: List<Long>): List<CleanupOutboxEntity>
    @Query("SELECT COUNT(*) FROM cleanup_outbox WHERE ackedAt IS NULL") abstract suspend fun pendingOutboxCount(): Int
    @Query("SELECT id FROM cleanup_outbox WHERE id IN (:ids)") abstract suspend fun outboxIdsAmong(ids: List<Long>): List<Long>
    /** Keeps the newest [keep] pending rows of [target] and deletes the older ones; returns how many went. */
    @Query("DELETE FROM cleanup_outbox WHERE target = :target AND ackedAt IS NULL AND id IN (SELECT id FROM cleanup_outbox WHERE target = :target AND ackedAt IS NULL ORDER BY id DESC LIMIT -1 OFFSET :keep)") abstract suspend fun trimOutbox(target: String, keep: Int): Int
    /** The drainer's confirm: the row goes only after the deletion is confirmed (a row left behind by a crash is simply drained again). */
    @Query("DELETE FROM cleanup_outbox WHERE id = :id") abstract suspend fun removeOutbox(id: Long): Int
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
    /** Those of [ids] that are LIVE photos with a capture time: the frame row exists, it is not tombstoned and `frame_meta.capturedAtMs` is set (the two required parts of a valid Moment). */
    @Query("SELECT f.id FROM frame f JOIN frame_meta m ON m.frameId = f.id WHERE f.id IN (:ids) AND m.capturedAtMs IS NOT NULL AND f.id NOT IN (SELECT frameId FROM forget_tombstone)")
    abstract suspend fun liveCapturedFrameIds(ids: List<String>): List<String>

    /**
     * Memories with a photo provenance whose every source frame is gone (no `frame` row): the complement of the orphan clause of the memory export (`ExportDao.memoryPage`). A memory with no photo
     * provenance (typed, voice, a derived one with no frame source) is never an orphan. Any review state.
     */
    @Query(
        "SELECT m.id FROM memory m WHERE (m.frameId IS NOT NULL OR EXISTS (SELECT 1 FROM frame_source fs WHERE fs.memoryId = m.id)) " +
            "AND NOT EXISTS (SELECT 1 FROM frame f WHERE f.id = m.frameId OR f.id IN (SELECT fs.frameId FROM frame_source fs WHERE fs.memoryId = m.id)) ORDER BY m.id",
    )
    abstract suspend fun orphanMemoryIds(): List<String>

    /** Distinct seconds that hold a live (not tombstoned) frame, for a window rebuild. */
    @Query("SELECT DISTINCT ts / 1000 FROM frame WHERE ts >= :fromMs AND ts <= :toMs AND ts >= 0 AND id NOT IN (SELECT frameId FROM forget_tombstone) ORDER BY 1") abstract suspend fun liveFrameSeconds(fromMs: Long, toMs: Long): List<Long>
    /** Distinct seconds that hold a mark (a `canvas_label` row), for a window rebuild and the one-time catch-up: a mark makes a Moment by itself. */
    @Query("SELECT DISTINCT atMs / 1000 FROM canvas_label WHERE atMs >= :fromMs AND atMs <= :toMs AND atMs >= 0 ORDER BY 1") abstract suspend fun labelSeconds(fromMs: Long, toMs: Long): List<Long>

    /**
     * The marks of a time range, newest first, for the Activity tab (read-only): the mark's own code and time, and the Moment that holds it with its type and no-photo reason (null columns = no
     * Moment holds it yet). Only MOMENT rows: an INTENT row is the word that goes with a mark, not a mark.
     */
    @Query(
        "SELECT l.labelId AS labelId, l.source AS code, l.atMs AS atMs, p.momentId AS momentId, m.momentType AS momentType, m.noPhotoReason AS noPhotoReason " +
            "FROM canvas_label l LEFT JOIN moment_part p ON p.kind = 'MARK' AND p.refId = l.labelId LEFT JOIN moment m ON m.momentId = p.momentId " +
            "WHERE l.kind = 'MOMENT' AND l.atMs >= :fromMs AND l.atMs <= :toMs ORDER BY l.atMs DESC, l.labelId",
    )
    abstract fun marksIn(fromMs: Long, toMs: Long): kotlinx.coroutines.flow.Flow<List<MarkRow>>

    data class MarkRow(
        val labelId: String, val code: String, val atMs: Long, val momentId: String?,
        val momentType: com.rokid.context.memory.moment.MomentType?, val noPhotoReason: com.rokid.context.memory.moment.NoPhotoReason?,
    )

    @Query("SELECT m.id FROM memory m WHERE m.derived = 1 AND m.id IN (:ids)") abstract suspend fun derivedAmong(ids: List<String>): List<String>

    data class WireIdRow(val frameId: String, val wireFrameId: String)

    private companion object { const val CHUNK = 500 }
}
