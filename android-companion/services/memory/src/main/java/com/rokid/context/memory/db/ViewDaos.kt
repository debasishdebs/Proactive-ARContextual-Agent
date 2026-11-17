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

    /** Keyset page (activity pagination A1): newest first, ties by id DESC, rows strictly after the cursor `(cKey, cId)`. The first page passes `(toMs, "")`. Bounded by [n], served by the `ts` index. */
    @Query("SELECT * FROM frame WHERE ts >= :fromMs AND ts < :toMs AND (ts < :cKey OR (ts = :cKey AND id < :cId)) ORDER BY ts DESC, id DESC LIMIT :n")
    suspend fun pageIn(fromMs: Long, toMs: Long, cKey: Long, cId: String, n: Int): List<FrameEntity>

    @Query("SELECT * FROM frame WHERE id = :id") suspend fun byId(id: String): FrameEntity?
    @Query("SELECT * FROM frame WHERE id IN (:ids)") suspend fun byIds(ids: List<String>): List<FrameEntity>
}

/** A visible memory with the time it is SHOWN at on the broken-down view (its photo's time, else `createdAt`): the keyset key of [MemoryViewDao.momentPageIn]. */
data class MomentRow(@androidx.room.Embedded val memory: MemoryEntity, val shownAt: Long)

@Dao
abstract class MemoryViewDao : DerivedStaleDao() {
    @Query("SELECT * FROM memory WHERE createdAt >= :fromMs AND createdAt < :toMs AND reviewState = 'OK' ORDER BY createdAt DESC")
    abstract fun observeVisibleBetween(fromMs: Long, toMs: Long): Flow<List<MemoryEntity>>

    @Query("SELECT createdAt FROM memory WHERE createdAt >= :fromMs AND createdAt < :toMs AND reviewState = 'OK'")
    abstract fun observeVisibleTimes(fromMs: Long, toMs: Long): Flow<List<Long>>

    /** Keyset page of the visible memories by `createdAt` (list view), newest first, ties by id DESC. Same filter as [observeVisibleTimes]. */
    @Query("SELECT * FROM memory WHERE createdAt >= :fromMs AND createdAt < :toMs AND reviewState = 'OK' AND (createdAt < :cKey OR (createdAt = :cKey AND id < :cId)) ORDER BY createdAt DESC, id DESC LIMIT :n")
    abstract suspend fun visiblePageIn(fromMs: Long, toMs: Long, cKey: Long, cId: String, n: Int): List<MemoryEntity>

    /**
     * One page of the visible memories of a day for the broken-down view: a memory sits at its frame's `ts`, else its `createdAt` ([visibleMomentTimes]). `createdAt` within the day uses the index, then the
     * hour `[hFromMs, hToMs)` is tested on the display time. Newest first, ties by id DESC. The rows whose display time falls before the day ("Earlier photos") are `hFromMs = Long.MIN_VALUE, hToMs = dayFromMs`.
     */
    @Query(
        "SELECT m.*, COALESCE(f.ts, m.createdAt) AS shownAt FROM memory m LEFT JOIN frame f ON f.id = m.frameId WHERE m.createdAt >= :dayFromMs AND m.createdAt < :dayToMs AND m.reviewState = 'OK' " +
            "AND COALESCE(f.ts, m.createdAt) >= :hFromMs AND COALESCE(f.ts, m.createdAt) < :hToMs " +
            "AND (COALESCE(f.ts, m.createdAt) < :cKey OR (COALESCE(f.ts, m.createdAt) = :cKey AND m.id < :cId)) ORDER BY COALESCE(f.ts, m.createdAt) DESC, m.id DESC LIMIT :n",
    )
    abstract suspend fun momentPageIn(dayFromMs: Long, dayToMs: Long, hFromMs: Long, hToMs: Long, cKey: Long, cId: String, n: Int): List<MomentRow>

    /** The display time of every visible memory whose `createdAt` is in the day: the hour counts of the broken-down view (bucketed in Kotlin, so a DST day is right). */
    @Query("SELECT COALESCE(f.ts, m.createdAt) FROM memory m LEFT JOIN frame f ON f.id = m.frameId WHERE m.createdAt >= :dayFromMs AND m.createdAt < :dayToMs AND m.reviewState = 'OK'")
    abstract suspend fun visibleMomentTimes(dayFromMs: Long, dayToMs: Long): List<Long>

    @Query("SELECT * FROM memory WHERE reviewState = 'OK' ORDER BY createdAt DESC LIMIT :limit")
    abstract fun observeRecentVisible(limit: Int): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memory WHERE reviewState = 'NEEDS_REVIEW' ORDER BY createdAt DESC LIMIT :limit")
    abstract fun observeNeedsReview(limit: Int): Flow<List<MemoryEntity>>

    @Query("SELECT COUNT(*) FROM memory WHERE reviewState = 'NEEDS_REVIEW'")
    abstract fun observeNeedsReviewCount(): Flow<Int>

    @Query("UPDATE memory SET reviewState = :to WHERE id = :id AND reviewState = :from")
    protected abstract suspend fun moveReviewStateRow(id: String, from: String, to: String): Int

    /** A review move changes what the Moment shows of the memory: the derived fields of the Moments holding it are hidden in the same transaction. */
    @Transaction open suspend fun moveReviewState(id: String, from: String, to: String): Int =
        moveReviewStateRow(id, from, to).also { if (it > 0) staleMomentsHolding(MEMORY_PARTS, listOf(id)) }

    @Query("SELECT * FROM memory WHERE id = :id") abstract fun observe(id: String): Flow<MemoryEntity?>
    @Query("SELECT * FROM memory WHERE id = :id") abstract suspend fun byId(id: String): MemoryEntity?
    @Query("SELECT * FROM memory WHERE id IN (:ids)") abstract suspend fun byIds(ids: List<String>): List<MemoryEntity>

    /** Returns the number of rows changed (0 = unknown id). */
    @Query("UPDATE memory SET trusted = :trusted WHERE id = :id") protected abstract suspend fun setTrustedRow(id: String, trusted: Boolean): Int
    @Transaction open suspend fun setTrusted(id: String, trusted: Boolean): Int = setTrustedRow(id, trusted).also { if (it > 0) staleMomentsHolding(MEMORY_PARTS, listOf(id)) }

    @Query("UPDATE memory SET text = :text, searchText = :searchText WHERE id = :id AND reviewState != 'INVALIDATED'")
    protected abstract suspend fun updateText(id: String, text: String, searchText: String): Int

    /**
     * Atomic, field-level text edit: writes only text/searchText so concurrent trust/review/forget writes are never
     * clobbered. UPDATE keeps the rowid, so the FTS triggers re-index. Returns 0 for unknown or INVALIDATED ids.
     */
    @Transaction
    open suspend fun editText(id: String, text: String): Int {
        val current = byId(id) ?: return 0
        return updateText(id, text, SearchNormalizer.normalize("$text ${current.tags}")).also { if (it > 0) staleMomentsHolding(MEMORY_PARTS, listOf(id)) }
    }

    private companion object { val MEMORY_PARTS = listOf(MomentPartKind.MEMORY, MomentPartKind.SUMMARY) }
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
    @Query("SELECT * FROM ask WHERE id IN (:ids)") suspend fun byIds(ids: List<String>): List<AskEntity>
    /** Keyset page (A1): newest first, ties by id DESC. Same filter as [observeTimes]: a developer eval ask is never listed. */
    @Query("SELECT * FROM ask WHERE ts >= :fromMs AND ts < :toMs AND source != 'canvas_eval' AND (ts < :cKey OR (ts = :cKey AND id < :cId)) ORDER BY ts DESC, id DESC LIMIT :n")
    suspend fun pageIn(fromMs: Long, toMs: Long, cKey: Long, cId: String, n: Int): List<AskEntity>
    @Query("SELECT * FROM ask WHERE id = :id") suspend fun byId(id: String): AskEntity?
    @Query("SELECT * FROM ask WHERE id = :id") fun observe(id: String): Flow<AskEntity?>
    /** The user's asks: a developer eval ask (source `canvas_eval`, AskActions.EVAL_SOURCE) is never listed. */
    @Query("SELECT * FROM ask WHERE source != 'canvas_eval' ORDER BY ts DESC LIMIT :limit") fun observeRecent(limit: Int): Flow<List<AskEntity>>
    @Query("SELECT ts FROM ask WHERE ts >= :fromMs AND ts < :toMs AND source != 'canvas_eval'") fun observeTimes(fromMs: Long, toMs: Long): Flow<List<Long>>
    /** The user asked a forgotten-source question again: the old row says so (no second button). Only a row in the SOURCE_FORGOTTEN state changes. */
    @Query("UPDATE ask SET sourceStates = :states WHERE id = :id AND redacted = 1 AND sourceStates LIKE '%\"SOURCE_FORGOTTEN\"%'") suspend fun markReasked(id: String, states: String): Int
    @Query("UPDATE ask SET rating = :rating WHERE id = :id") suspend fun setRating(id: String, rating: Int?): Int
}

@Dao
interface NudgeDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIgnore(nudge: NudgeEntity): Long
    @Query("SELECT * FROM nudge WHERE id IN (:ids)") suspend fun byIds(ids: List<String>): List<NudgeEntity>
    @Query("SELECT * FROM nudge WHERE id = :id") fun observe(id: String): Flow<NudgeEntity?>
    /** Keyset page (A1): newest first, ties by id DESC; no other filter (as [observeTimes]). */
    @Query("SELECT * FROM nudge WHERE ts >= :fromMs AND ts < :toMs AND (ts < :cKey OR (ts = :cKey AND id < :cId)) ORDER BY ts DESC, id DESC LIMIT :n")
    suspend fun pageIn(fromMs: Long, toMs: Long, cKey: Long, cId: String, n: Int): List<NudgeEntity>
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
