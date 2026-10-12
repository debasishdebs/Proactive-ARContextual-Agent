package com.rokid.context.memory.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Read-side queries for the phone screens (Phase 4). Ranges are [fromMs, toMs). Schema v1 unchanged. */
@Dao
interface FrameViewDao {
    @Query("SELECT * FROM frame WHERE ts >= :fromMs AND ts < :toMs ORDER BY ts ASC")
    fun observeBetween(fromMs: Long, toMs: Long): Flow<List<FrameEntity>>

    @Query("SELECT ts FROM frame WHERE ts >= :fromMs AND ts < :toMs")
    fun observeTimes(fromMs: Long, toMs: Long): Flow<List<Long>>

    @Query("SELECT * FROM frame WHERE id = :id") suspend fun byId(id: String): FrameEntity?
    @Query("SELECT * FROM frame WHERE id IN (:ids)") suspend fun byIds(ids: List<String>): List<FrameEntity>
}

@Dao
interface MemoryViewDao {
    @Query("SELECT * FROM memory WHERE createdAt >= :fromMs AND createdAt < :toMs AND reviewState = 'OK' ORDER BY createdAt DESC")
    fun observeVisibleBetween(fromMs: Long, toMs: Long): Flow<List<MemoryEntity>>

    @Query("SELECT createdAt FROM memory WHERE createdAt >= :fromMs AND createdAt < :toMs AND reviewState = 'OK'")
    fun observeVisibleTimes(fromMs: Long, toMs: Long): Flow<List<Long>>

    @Query("SELECT * FROM memory WHERE reviewState = 'OK' ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecentVisible(limit: Int): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memory WHERE id = :id") fun observe(id: String): Flow<MemoryEntity?>
    @Query("SELECT * FROM memory WHERE id = :id") suspend fun byId(id: String): MemoryEntity?
    @Query("SELECT * FROM memory WHERE id IN (:ids)") suspend fun byIds(ids: List<String>): List<MemoryEntity>

    /** Returns the number of rows changed (0 = unknown id). */
    @Query("UPDATE memory SET trusted = :trusted WHERE id = :id") suspend fun setTrusted(id: String, trusted: Boolean): Int
}

@Dao
interface TraceViewDao {
    @Query("SELECT * FROM summary_trace ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SummaryTraceEntity>>
}

@Dao
interface AskDao {
    @Upsert suspend fun upsert(ask: AskEntity)
    @Query("SELECT * FROM ask WHERE id = :id") suspend fun byId(id: String): AskEntity?
    @Query("SELECT * FROM ask WHERE id = :id") fun observe(id: String): Flow<AskEntity?>
    @Query("SELECT * FROM ask ORDER BY ts DESC LIMIT :limit") fun observeRecent(limit: Int): Flow<List<AskEntity>>
    @Query("SELECT ts FROM ask WHERE ts >= :fromMs AND ts < :toMs") fun observeTimes(fromMs: Long, toMs: Long): Flow<List<Long>>
    @Query("UPDATE ask SET rating = :rating WHERE id = :id") suspend fun setRating(id: String, rating: Int?)
}

@Dao
interface NudgeDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIgnore(nudge: NudgeEntity): Long
    @Query("SELECT * FROM nudge ORDER BY ts DESC LIMIT :limit") fun observeRecent(limit: Int): Flow<List<NudgeEntity>>
    @Query("SELECT ts FROM nudge WHERE ts >= :fromMs AND ts < :toMs") fun observeTimes(fromMs: Long, toMs: Long): Flow<List<Long>>
}

@Dao
interface ProposalDao {
    /** Seeding/tests only; Phase 7 adds the compare-and-set writes (spec §5.3). */
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIgnore(proposal: ProposalEntity): Long
    @Query("SELECT * FROM proposal WHERE originId = :originId ORDER BY createdAt DESC")
    fun observeForOrigin(originId: String): Flow<List<ProposalEntity>>
}
