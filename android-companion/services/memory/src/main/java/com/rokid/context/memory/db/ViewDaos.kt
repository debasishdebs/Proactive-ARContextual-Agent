package com.rokid.context.memory.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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
abstract class MemoryViewDao {
    @Query("SELECT * FROM memory WHERE createdAt >= :fromMs AND createdAt < :toMs AND reviewState = 'OK' ORDER BY createdAt DESC")
    abstract fun observeVisibleBetween(fromMs: Long, toMs: Long): Flow<List<MemoryEntity>>

    @Query("SELECT createdAt FROM memory WHERE createdAt >= :fromMs AND createdAt < :toMs AND reviewState = 'OK'")
    abstract fun observeVisibleTimes(fromMs: Long, toMs: Long): Flow<List<Long>>

    @Query("SELECT * FROM memory WHERE reviewState = 'OK' ORDER BY createdAt DESC LIMIT :limit")
    abstract fun observeRecentVisible(limit: Int): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memory WHERE id = :id") abstract fun observe(id: String): Flow<MemoryEntity?>
    @Query("SELECT * FROM memory WHERE id = :id") abstract suspend fun byId(id: String): MemoryEntity?
    @Query("SELECT * FROM memory WHERE id IN (:ids)") abstract suspend fun byIds(ids: List<String>): List<MemoryEntity>

    /** Returns the number of rows changed (0 = unknown id). */
    @Query("UPDATE memory SET trusted = :trusted WHERE id = :id") abstract suspend fun setTrusted(id: String, trusted: Boolean): Int

    @Query("UPDATE memory SET text = :text, searchText = :searchText WHERE id = :id AND reviewState != 'INVALIDATED'")
    protected abstract suspend fun updateText(id: String, text: String, searchText: String): Int

    /**
     * Atomic, field-level text edit: writes only text/searchText so concurrent trust/review/forget writes are never
     * clobbered. UPDATE keeps the rowid, so the FTS triggers re-index. Returns 0 for unknown or INVALIDATED ids.
     */
    @Transaction
    open suspend fun editText(id: String, text: String): Int {
        val current = byId(id) ?: return 0
        return updateText(id, text, SearchNormalizer.normalize("$text ${current.tags}"))
    }
}

@Dao
interface TraceViewDao {
    @Query("SELECT * FROM summary_trace ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SummaryTraceEntity>>
}

@Dao
interface AskDao {
    /** Seeding/tests only: a whole-row write would reset `rating`. Production uses [insertIgnore]; [complete] is for two-phase asks. */
    @Upsert suspend fun upsert(ask: AskEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIgnore(ask: AskEntity): Long
    /** Field-level completion (Phase 2 carry): writes the answer and sources only, never the rating. */
    @Query("UPDATE ask SET answer = :answer, sourcesJson = :sourcesJson WHERE id = :id") suspend fun complete(id: String, answer: String, sourcesJson: String): Int
    @Query("SELECT * FROM ask WHERE id = :id") suspend fun byId(id: String): AskEntity?
    @Query("SELECT * FROM ask WHERE id = :id") fun observe(id: String): Flow<AskEntity?>
    @Query("SELECT * FROM ask ORDER BY ts DESC LIMIT :limit") fun observeRecent(limit: Int): Flow<List<AskEntity>>
    @Query("SELECT ts FROM ask WHERE ts >= :fromMs AND ts < :toMs") fun observeTimes(fromMs: Long, toMs: Long): Flow<List<Long>>
    @Query("UPDATE ask SET rating = :rating WHERE id = :id") suspend fun setRating(id: String, rating: Int?): Int
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
