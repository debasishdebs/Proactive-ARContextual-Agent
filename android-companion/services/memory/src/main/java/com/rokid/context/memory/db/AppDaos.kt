package com.rokid.context.memory.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface EndpointDao {
    @Upsert suspend fun upsert(endpoint: EndpointEntity)
    @Query("SELECT * FROM endpoint WHERE id = :id") suspend fun byId(id: String): EndpointEntity?
    @Query("SELECT * FROM endpoint ORDER BY createdAt ASC") suspend fun all(): List<EndpointEntity>
    @Query("DELETE FROM endpoint WHERE id = :id") suspend fun delete(id: String)
}

@Dao
interface RoleBindingDao {
    @Upsert suspend fun upsert(binding: RoleBindingEntity)
    @Query("SELECT * FROM role_binding WHERE role = :role") suspend fun byRole(role: String): RoleBindingEntity?
    @Query("SELECT * FROM role_binding") suspend fun all(): List<RoleBindingEntity>
    @Query("DELETE FROM role_binding WHERE role = :role") suspend fun delete(role: String)
}

/** Pause set (spec §5.5). `pause_record` = active records; `pause_interval` = retained history (open = effectiveEndMs null). */
@Dao
interface PauseDao {
    @Upsert suspend fun upsertRecord(record: PauseRecordEntity)
    @Query("SELECT * FROM pause_record ORDER BY createdAtMs ASC") suspend fun records(): List<PauseRecordEntity>
    @Query("DELETE FROM pause_record WHERE pauseId IN (:ids)") suspend fun deleteRecords(ids: List<String>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertInterval(interval: PauseIntervalEntity): Long
    @Query("UPDATE pause_interval SET effectiveEndMs = :endMs WHERE pauseId = :pauseId AND effectiveEndMs IS NULL")
    suspend fun closeInterval(pauseId: String, endMs: Long)
    @Query("SELECT COUNT(*) FROM pause_interval WHERE startMs <= :t AND (effectiveEndMs IS NULL OR :t < effectiveEndMs)")
    suspend fun countCovering(t: Long): Int
    @Query("DELETE FROM pause_interval WHERE effectiveEndMs IS NOT NULL AND effectiveEndMs < :before")
    suspend fun pruneEndedBefore(before: Long)
    @Query("SELECT * FROM pause_interval ORDER BY startMs ASC") suspend fun intervals(): List<PauseIntervalEntity>
}
