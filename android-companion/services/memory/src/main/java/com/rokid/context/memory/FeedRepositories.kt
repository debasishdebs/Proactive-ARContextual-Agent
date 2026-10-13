package com.rokid.context.memory

import com.rokid.context.memory.db.AskDao
import com.rokid.context.memory.db.AskEntity
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.FrameViewDao
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryViewDao
import com.rokid.context.memory.db.NudgeDao
import com.rokid.context.memory.db.NudgeEntity
import com.rokid.context.memory.db.ProposalDao
import com.rokid.context.memory.db.ProposalEntity
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.db.SummaryTraceEntity
import com.rokid.context.memory.db.TraceViewDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Phone read model (spec §2.5). Ranges come from [DayBucket.bounds]: `first` and `last` are both inclusive. */
class TimelineRepository(private val frames: FrameViewDao, private val memories: MemoryViewDao, private val traces: TraceViewDao) {
    fun framesIn(range: LongRange): Flow<List<FrameEntity>> = frames.observeBetween(range.first, range.last + 1)
    fun frameTimesIn(range: LongRange): Flow<List<Long>> = frames.observeTimes(range.first, range.last + 1)
    fun visibleMemoriesIn(range: LongRange): Flow<List<MemoryEntity>> = memories.observeVisibleBetween(range.first, range.last + 1)
    fun visibleMemoryTimesIn(range: LongRange): Flow<List<Long>> = memories.observeVisibleTimes(range.first, range.last + 1)
    fun recentVisibleMemories(limit: Int): Flow<List<MemoryEntity>> = memories.observeRecentVisible(limit)
    fun memory(id: String): Flow<MemoryEntity?> = memories.observe(id)
    suspend fun frame(id: String): FrameEntity? = frames.byId(id)
    /** Chunked by [MAX_IN]: SQLite's bound-parameter limit is 999 on older devices. Empty input never queries. */
    suspend fun memoriesByIds(ids: List<String>): List<MemoryEntity> = ids.distinct().chunked(MAX_IN).flatMap { memories.byIds(it) }
    suspend fun framesByIds(ids: List<String>): List<FrameEntity> = ids.distinct().chunked(MAX_IN).flatMap { frames.byIds(it) }
    fun recentTraces(limit: Int): Flow<List<SummaryTraceEntity>> = traces.observeRecent(limit)

    private companion object { const val MAX_IN = 900 }
}

/** Memory detail writes (spec §2.4): Trust and Edit. Forget is Phase 7 (spec §6.1.1, R-77). */
class MemoryEdits(private val view: MemoryViewDao, private val memories: MemoryRepository) {
    suspend fun trust(id: String): Boolean = view.setTrusted(id, true) > 0

    /** Single-line, whitespace-collapsed, trimmed, ≤ 500 chars. Field-level atomic update (see [MemoryViewDao.editText]); false for blank text, unknown or INVALIDATED id. */
    suspend fun editText(id: String, text: String): Boolean {
        val clean = text.replace(Regex("\\s+"), " ").trim().take(500)
        if (clean.isEmpty()) return false
        return view.editText(id, clean) > 0
    }

    fun needsReview(limit: Int): Flow<List<MemoryEntity>> = view.observeNeedsReview(limit).distinctUntilChanged()

    /** Review queue "Keep": NEEDS_REVIEW → OK (back into feeds and retrieval). Anything else is untouched. */
    suspend fun approve(id: String): Boolean = view.moveReviewState(id, ReviewState.NEEDS_REVIEW.name, ReviewState.OK.name) > 0
}

class AskRepository(private val dao: AskDao) {
    /** Seeding/tests only (see [AskDao.upsert]). */
    suspend fun upsert(ask: AskEntity) = dao.upsert(ask)
    /** False when an ask with this id already exists. */
    suspend fun add(ask: AskEntity): Boolean = dao.insertIgnore(ask) != -1L
    suspend fun complete(id: String, answer: String, sourcesJson: String): Boolean = dao.complete(id, answer, sourcesJson) > 0
    suspend fun byId(id: String): AskEntity? = dao.byId(id)
    fun observe(id: String): Flow<AskEntity?> = dao.observe(id).distinctUntilChanged()
    fun recent(limit: Int): Flow<List<AskEntity>> = dao.observeRecent(limit).distinctUntilChanged()
    fun timesIn(range: LongRange): Flow<List<Long>> = dao.observeTimes(range.first, range.last + 1).distinctUntilChanged()
    suspend fun setRating(id: String, rating: Int?): Boolean = dao.setRating(id, rating) > 0
}

class NudgeRepository(private val dao: NudgeDao) {
    suspend fun addIgnore(nudge: NudgeEntity): Boolean = dao.insertIgnore(nudge) != -1L
    fun recent(limit: Int): Flow<List<NudgeEntity>> = dao.observeRecent(limit).distinctUntilChanged()
    fun timesIn(range: LongRange): Flow<List<Long>> = dao.observeTimes(range.first, range.last + 1).distinctUntilChanged()
}

class ProposalRepository(private val dao: ProposalDao) {
    suspend fun addIgnore(proposal: ProposalEntity): Boolean = dao.insertIgnore(proposal) != -1L
    fun forOrigin(originId: String): Flow<List<ProposalEntity>> = dao.observeForOrigin(originId)
}
