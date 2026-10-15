package com.rokid.context.memory.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface FrameDao {
    /** Returns the new rowid, or -1 when a frame with this id already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIgnore(frame: FrameEntity): Long
    @Query("SELECT * FROM frame ORDER BY ts DESC LIMIT :limit") suspend fun recent(limit: Int): List<FrameEntity>
    @Query("SELECT * FROM frame ORDER BY ts ASC") suspend fun all(): List<FrameEntity>
    @Query("SELECT * FROM frame WHERE id IN (:ids) ORDER BY ts ASC") suspend fun byIds(ids: List<String>): List<FrameEntity>
    @Query("SELECT * FROM frame WHERE ts >= :fromMs AND ts < :toMs ORDER BY ts DESC") suspend fun between(fromMs: Long, toMs: Long): List<FrameEntity>
    @Query("DELETE FROM frame WHERE id IN (:ids)") suspend fun deleteByIds(ids: List<String>)
    /** Points a row at a surviving copy of its photo (a wire-id duplicate whose first file is gone). */
    @Query("UPDATE frame SET path = :path WHERE id = :id") suspend fun updatePath(id: String, path: String): Int
    @Query("SELECT COUNT(*) FROM frame") suspend fun count(): Int
}

@Dao
interface MemoryDao {
    /** Upsert (update in place) so FTS content-sync UPDATE triggers fire; REPLACE would orphan the old FTS docid. */
    @Upsert suspend fun insert(memory: MemoryEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIgnore(memory: MemoryEntity): Long
    @Query("SELECT * FROM memory ORDER BY createdAt DESC LIMIT :limit") suspend fun recent(limit: Int): List<MemoryEntity>
    @Query("SELECT * FROM memory ORDER BY createdAt ASC") suspend fun all(): List<MemoryEntity>
    @Query("SELECT * FROM memory WHERE createdAt >= :fromMs AND createdAt < :toMs AND reviewState != 'INVALIDATED' ORDER BY createdAt DESC")
    suspend fun between(fromMs: Long, toMs: Long): List<MemoryEntity>
    @Query(
        "SELECT memory.* FROM memory JOIN memory_fts ON memory.rowid = memory_fts.rowid " +
            "WHERE memory_fts MATCH :matchQuery AND memory.reviewState != 'INVALIDATED' ORDER BY memory.createdAt DESC LIMIT :limit",
    )
    suspend fun search(matchQuery: String, limit: Int): List<MemoryEntity>
    @Query("DELETE FROM memory WHERE id IN (:ids)") suspend fun deleteByIds(ids: List<String>)
    /** [escapedPrefix] has `\\`, `%` and `_` escaped with `\\` (MemoryRepository.idsWithPrefix). */
    @Query("SELECT id FROM memory WHERE id LIKE :escapedPrefix || '%' ESCAPE '\\'") suspend fun idsLike(escapedPrefix: String): List<String>
    @Query("SELECT COUNT(*) FROM memory") suspend fun count(): Int
}

@Dao
interface TraceDao {
    @Upsert suspend fun upsert(trace: SummaryTraceEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIgnore(trace: SummaryTraceEntity): Long
    @Query("SELECT * FROM summary_trace ORDER BY updatedAt DESC LIMIT :limit") suspend fun recent(limit: Int): List<SummaryTraceEntity>
    @Query("DELETE FROM summary_trace WHERE summaryId IN (:ids)") suspend fun deleteByIds(ids: List<String>)
}
