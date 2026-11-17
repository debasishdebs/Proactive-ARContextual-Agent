package com.rokid.context.memory

import androidx.room.withTransaction
import com.rokid.context.memory.db.ActionDao
import com.rokid.context.memory.db.ActionLogEntity
import com.rokid.context.memory.db.AskDao
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.db.AskEntity
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.FrameViewDao
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryViewDao
import com.rokid.context.memory.db.MomentRow
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
    /** Keyset pages (A2), newest first, strictly after [after] (the first page is `Cursor.head(range)`). */
    suspend fun framePage(range: LongRange, after: Cursor, n: Int): List<FrameEntity> = frames.pageIn(range.first, range.last + 1, after.key, after.id, n)
    suspend fun visibleMemoryPage(range: LongRange, after: Cursor, n: Int): List<MemoryEntity> = memories.visiblePageIn(range.first, range.last + 1, after.key, after.id, n)
    /** One page of a day's visible memories shown in [hour] (display time: the frame's time, else `createdAt`); [day] bounds `createdAt`. */
    suspend fun momentPage(day: LongRange, hour: LongRange, after: Cursor, n: Int): List<MomentRow> = memories.momentPageIn(day.first, day.last + 1, hour.first, hour.last + 1, after.key, after.id, n)
    suspend fun momentTimes(day: LongRange): List<Long> = memories.visibleMomentTimes(day.first, day.last + 1)
    /** Ids only, at most [limit] (multi-select): the photos / visible memories of [range], and the memories shown in [hour] of a [day]. */
    suspend fun frameIdsIn(range: LongRange, limit: Int): List<String> = frames.idsIn(range.first, range.last + 1, limit)
    suspend fun visibleMemoryIdsIn(range: LongRange, limit: Int): List<String> = memories.visibleIdsIn(range.first, range.last + 1, limit)
    suspend fun momentIdsOfHour(day: LongRange, hour: LongRange, limit: Int): List<String> = memories.momentIdsIn(day.first, day.last + 1, hour.first, hour.last + 1, limit)
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
    /** The Settings badge: a COUNT, so no row cap. */
    fun needsReviewCount(): Flow<Int> = view.observeNeedsReviewCount().distinctUntilChanged()

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
    /** The asks named by [ids], one query per chunk (no N+1). */
    suspend fun byIds(ids: List<String>): List<AskEntity> = ids.distinct().chunked(MAX_IN).flatMap { dao.byIds(it) }
    /** Keyset page (A2): newest first, a developer eval ask never listed. */
    suspend fun page(range: LongRange, after: Cursor, n: Int): List<AskEntity> = dao.pageIn(range.first, range.last + 1, after.key, after.id, n)
    private companion object { const val MAX_IN = 900 }
    fun recent(limit: Int): Flow<List<AskEntity>> = dao.observeRecent(limit).distinctUntilChanged()
    fun timesIn(range: LongRange): Flow<List<Long>> = dao.observeTimes(range.first, range.last + 1).distinctUntilChanged()
    suspend fun setRating(id: String, rating: Int?): Boolean = dao.setRating(id, rating) > 0
    /** M5: the answer in the forgotten-source state was asked again as [newAskId]. */
    suspend fun markReasked(id: String, newAskId: String): Boolean = dao.markReasked(id, com.rokid.context.memory.forget.AskSourceStates.reasked(newAskId)) > 0
}

class NudgeRepository(private val dao: NudgeDao) {
    suspend fun byIds(ids: List<String>): List<NudgeEntity> = ids.distinct().chunked(MAX_IN).flatMap { dao.byIds(it) }
    fun observe(id: String): Flow<NudgeEntity?> = dao.observe(id).distinctUntilChanged()
    suspend fun page(range: LongRange, after: Cursor, n: Int): List<NudgeEntity> = dao.pageIn(range.first, range.last + 1, after.key, after.id, n)
    private companion object { const val MAX_IN = 900 }
    suspend fun addIgnore(nudge: NudgeEntity): Boolean = dao.insertIgnore(nudge) != -1L
    fun recent(limit: Int): Flow<List<NudgeEntity>> = dao.observeRecent(limit).distinctUntilChanged()
    fun timesIn(range: LongRange): Flow<List<Long>> = dao.observeTimes(range.first, range.last + 1).distinctUntilChanged()
}

/**
 * Proposal state machine (spec §5.3, R-62). Every write runs in one transaction and moves state only by compare-and-set,
 * so HUD, phone, notification and expiry racing on one row end in exactly one terminal state. Each successful
 * transition appends `action_log(transition = "<FROM>\u2192<TO>", detail = by)`.
 */
class ProposalRepository(private val dao: ProposalDao, private val actions: ActionDao, private val db: ContextDatabase) {
    /** Seeding/tests only: no action_log row. Production code uses [create]. */
    suspend fun addIgnore(proposal: ProposalEntity): Boolean = dao.insertIgnore(proposal) != -1L

    /** False when the id already exists. Logs `CREATED:<state>`. */
    suspend fun create(p: ProposalEntity, nowMs: Long): Boolean = db.withTransaction {
        val inserted = dao.insertIgnore(p) != -1L
        if (inserted) actions.log(ActionLogEntity(ts = nowMs, proposalId = p.id, transition = "CREATED:${p.state}", detail = p.origin))
        inserted
    }

    /** PENDING and `expiresAt > now` → EXECUTING (approve) or DECLINED; PENDING but expired → EXPIRED and `Lost(EXPIRED)`. */
    suspend fun resolve(id: String, d: Decision, by: String, nowMs: Long): Resolve = db.withTransaction {
        val row = actions.proposal(id) ?: return@withTransaction Resolve.Lost(null)
        val current = stateOf(row.state)
        if (current != ProposalState.PENDING) return@withTransaction Resolve.Lost(current)
        if (row.expiresAt <= nowMs) {
            move(id, ProposalState.PENDING, ProposalState.EXPIRED, EXPIRY, nowMs)
            return@withTransaction Resolve.Lost(ProposalState.EXPIRED)
        }
        val to = if (d == Decision.APPROVE) ProposalState.EXECUTING else ProposalState.DECLINED
        if (move(id, ProposalState.PENDING, to, by, nowMs)) Resolve.Won(to) else Resolve.Lost(actions.proposal(id)?.state?.let(::stateOf))
    }

    /** EXECUTING → EXECUTED; records [createdRef] (what the executor created, used by Undo). */
    suspend fun markExecuted(id: String, createdRef: String, nowMs: Long): Boolean = db.withTransaction {
        val ok = actions.casCreated(id, ProposalState.EXECUTING.name, ProposalState.EXECUTED.name, createdRef) == 1
        if (ok) log(id, ProposalState.EXECUTING, ProposalState.EXECUTED, "", nowMs)
        ok
    }

    /** EXECUTING → FAILED; [detailCode] is a fixed code (never provider text). `resolvedBy` is kept. */
    suspend fun markFailed(id: String, detailCode: String, nowMs: Long): Boolean = db.withTransaction {
        val ok = actions.casState(id, ProposalState.EXECUTING.name, ProposalState.FAILED.name) == 1
        if (ok) log(id, ProposalState.EXECUTING, ProposalState.FAILED, detailCode, nowMs)
        ok
    }

    /** EXECUTED → DECLINED with `resolvedBy = "undo:<by>"`; logs `UNDONE`. */
    suspend fun markUndone(id: String, by: String, nowMs: Long): Boolean = db.withTransaction {
        val ok = actions.cas(id, ProposalState.EXECUTED.name, ProposalState.DECLINED.name, "undo:$by") == 1
        if (ok) actions.log(ActionLogEntity(ts = nowMs, proposalId = id, transition = UNDONE, detail = by))
        ok
    }

    /** PENDING with `expiresAt ≤ now` → EXPIRED. Returns the ids this call expired. */
    suspend fun expireDue(nowMs: Long): List<String> = db.withTransaction {
        actions.dueIds(ProposalState.PENDING.name, nowMs).filter { move(it, ProposalState.PENDING, ProposalState.EXPIRED, EXPIRY, nowMs) }
    }

    /** Forget: every PENDING proposal of [originIds] → EXPIRED, in chunks of [CHUNK] ids. Returns how many moved. */
    suspend fun expireForOrigins(originIds: Collection<String>, nowMs: Long): Int {
        var moved = 0
        for (chunk in originIds.distinct().chunked(CHUNK)) {
            moved += db.withTransaction {
                actions.idsForOrigins(ProposalState.PENDING.name, chunk).count { move(it, ProposalState.PENDING, ProposalState.EXPIRED, FORGET, nowMs) }
            }
        }
        return moved
    }

    suspend fun inState(s: ProposalState): List<ProposalEntity> = actions.inState(s.name)
    suspend fun byId(id: String): ProposalEntity? = actions.proposal(id)
    fun forOrigin(originId: String): Flow<List<ProposalEntity>> = dao.observeForOrigin(originId)
    fun pending(): Flow<List<ProposalEntity>> = actions.observeInState(ProposalState.PENDING.name).distinctUntilChanged()

    private suspend fun move(id: String, from: ProposalState, to: ProposalState, by: String, nowMs: Long): Boolean {
        val ok = actions.cas(id, from.name, to.name, by) == 1
        if (ok) log(id, from, to, by, nowMs)
        return ok
    }

    private suspend fun log(id: String, from: ProposalState, to: ProposalState, detail: String, nowMs: Long) =
        actions.log(ActionLogEntity(ts = nowMs, proposalId = id, transition = "${from.name}\u2192${to.name}", detail = detail))

    /** An unknown stored state reads as null (treated like an unknown row: never resolvable). */
    private fun stateOf(s: String): ProposalState? = ProposalState.entries.firstOrNull { it.name == s }

    private companion object {
        const val CHUNK = 500
        const val EXPIRY = "expiry"
        const val FORGET = "forget"
        const val UNDONE = "UNDONE"
    }
}
