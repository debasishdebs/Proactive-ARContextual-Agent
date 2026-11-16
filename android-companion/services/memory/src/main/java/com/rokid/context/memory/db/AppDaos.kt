package com.rokid.context.memory.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface EndpointDao {
    @Upsert suspend fun upsert(endpoint: EndpointEntity)
    @Query("SELECT * FROM endpoint WHERE id = :id") suspend fun byId(id: String): EndpointEntity?
    @Query("SELECT * FROM endpoint ORDER BY createdAt ASC") suspend fun all(): List<EndpointEntity>
    @Query("SELECT * FROM endpoint ORDER BY createdAt ASC") fun observeAll(): Flow<List<EndpointEntity>>
    @Query("DELETE FROM endpoint WHERE id = :id") suspend fun delete(id: String)
}

@Dao
interface RoleBindingDao {
    @Upsert suspend fun upsert(binding: RoleBindingEntity)
    @Query("SELECT * FROM role_binding WHERE role = :role") suspend fun byRole(role: String): RoleBindingEntity?
    @Query("SELECT * FROM role_binding") suspend fun all(): List<RoleBindingEntity>
    @Query("SELECT * FROM role_binding") fun observeAll(): Flow<List<RoleBindingEntity>>
    @Query("DELETE FROM role_binding WHERE role = :role") suspend fun delete(role: String)
}

/** Pause set (spec §5.5). `pause_record` = active records; `pause_interval` = retained history (open = effectiveEndMs null). */
@Dao
interface PauseDao {
    @Upsert suspend fun upsertRecord(record: PauseRecordEntity)
    @Query("SELECT * FROM pause_record ORDER BY createdAtMs ASC") suspend fun records(): List<PauseRecordEntity>
    @Query("DELETE FROM pause_record WHERE pauseId IN (:ids)") suspend fun deleteRecords(ids: List<String>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertInterval(interval: PauseIntervalEntity): Long
    /** An interval never ends before it starts (a clock moved backwards between its start and its end). */
    @Query("UPDATE pause_interval SET effectiveEndMs = CASE WHEN :endMs < startMs THEN startMs ELSE :endMs END WHERE pauseId = :pauseId AND effectiveEndMs IS NULL")
    suspend fun closeInterval(pauseId: String, endMs: Long)
    @Query("SELECT COUNT(*) FROM pause_interval WHERE startMs <= :t AND (effectiveEndMs IS NULL OR :t < effectiveEndMs)")
    suspend fun countCovering(t: Long): Int
    /** Pause zones: intervals of `zone:` records covering [t] (open or closed). */
    @Query("SELECT COUNT(*) FROM pause_interval WHERE pauseId LIKE 'zone:%' AND startMs <= :t AND (effectiveEndMs IS NULL OR :t < effectiveEndMs)")
    suspend fun countZoneCovering(t: Long): Int
    /** Pause zones: `zone:` intervals that overlap [fromMs, toMs) (open ones included): the capture of a photo that took time, or a clock-rollback-widened frame time. */
    @Query("SELECT COUNT(*) FROM pause_interval WHERE pauseId LIKE 'zone:%' AND startMs < :toMs AND (effectiveEndMs IS NULL OR effectiveEndMs > :fromMs)")
    suspend fun countZoneOverlapping(fromMs: Long, toMs: Long): Int
    @Query("DELETE FROM pause_interval WHERE effectiveEndMs IS NOT NULL AND effectiveEndMs < :before")
    suspend fun pruneEndedBefore(before: Long)
    @Query("SELECT * FROM pause_interval ORDER BY startMs ASC") suspend fun intervals(): List<PauseIntervalEntity>
    @Query("SELECT * FROM pause_interval WHERE pauseId = :pauseId") suspend fun intervalFor(pauseId: String): PauseIntervalEntity?
    @Query("SELECT * FROM pause_record WHERE pauseId = :pauseId") suspend fun recordFor(pauseId: String): PauseRecordEntity?
    @Query("UPDATE pause_interval SET effectiveEndMs = :endMs WHERE effectiveEndMs IS NULL AND pauseId NOT IN (SELECT pauseId FROM pause_record)")
    suspend fun closeOrphanIntervals(endMs: Long)

    /**
     * Atomic union of one record (most restrictive wins; a pauseId whose interval is closed is a tombstone and is never
     * resurrected). [activeNow]: the record has not expired by [now].
     */
    @Transaction
    suspend fun addMerged(record: PauseRecordEntity, activeNow: Boolean, now: Long) {
        val interval = intervalFor(record.pauseId)
        if (interval != null && interval.effectiveEndMs != null) return
        if (interval == null) insertInterval(PauseIntervalEntity(record.pauseId, record.origin, record.createdAtMs, null))
        val existing = recordFor(record.pauseId)
        val until = when {
            existing == null -> record.untilMs
            existing.untilMs == null || record.untilMs == null -> null
            else -> maxOf(existing.untilMs, record.untilMs)
        }
        if (existing != null) {
            upsertRecord(existing.copy(untilMs = until))
        } else if (activeNow) {
            upsertRecord(record)
        } else {
            closeInterval(record.pauseId, until ?: now)
        }
    }

    @Query("UPDATE pause_record SET createdAtMs = :startMs WHERE pauseId = :pauseId AND createdAtMs > :startMs")
    suspend fun lowerRecordStart(pauseId: String, startMs: Long)
    @Query("UPDATE pause_interval SET startMs = :startMs WHERE pauseId = :pauseId AND startMs > :startMs")
    suspend fun lowerIntervalStart(pauseId: String, startMs: Long)

    /** Moves a pause's start earlier (never later), on its record and its interval, open or closed. */
    @Transaction
    suspend fun widenStart(pauseId: String, startMs: Long) {
        lowerRecordStart(pauseId, startMs)
        lowerIntervalStart(pauseId, startMs)
    }

    /** Closes each expired record's interval at its end and deletes the records, in one transaction. */
    @Transaction
    suspend fun expire(ids: List<String>, ends: Map<String, Long>) {
        if (ids.isEmpty()) return
        ids.forEach { id -> ends[id]?.let { closeInterval(id, it) } }
        deleteRecords(ids)
    }

    /** A quiet-hours interval (open or closed) at or overlapping the occurrence [startMs, endMs), if one was ever created. */
    @Query(
        "SELECT * FROM pause_interval WHERE origin = 'QUIET_HOURS' AND (startMs = :startMs OR " +
            "(startMs < :endMs AND (effectiveEndMs IS NULL OR effectiveEndMs > :startMs))) LIMIT 1",
    )
    suspend fun quietOccurrence(startMs: Long, endMs: Long): PauseIntervalEntity?

    /** History older than [before] becomes an id-only tombstone: zero length, so it covers no frame. */
    @Query("UPDATE pause_interval SET startMs = effectiveEndMs WHERE effectiveEndMs IS NOT NULL AND effectiveEndMs < :before AND startMs < effectiveEndMs")
    suspend fun collapseEndedBefore(before: Long)

    /** Ids whose interval closed at or after [since] and that have no live record, newest 200. */
    @Query(
        "SELECT pauseId FROM pause_interval WHERE effectiveEndMs IS NOT NULL AND effectiveEndMs >= :since " +
            "AND pauseId NOT IN (SELECT pauseId FROM pause_record) ORDER BY effectiveEndMs DESC LIMIT 200",
    )
    suspend fun resumedSince(since: Long): List<String>

    /** Atomically ends the named records that exist. */
    @Transaction
    suspend fun resumeNamed(clears: Set<String>, now: Long) {
        val known = records().map { it.pauseId }.filter { it in clears }
        known.forEach { closeInterval(it, now) }
        if (known.isNotEmpty()) deleteRecords(known)
    }
}
