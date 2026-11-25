package com.rokid.context.memory

import com.rokid.context.memory.db.AskEntity
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MomentDao
import com.rokid.context.memory.db.MomentRow
import com.rokid.context.memory.db.NudgeEntity
import com.rokid.context.memory.db.PauseIntervalEntity

/**
 * A keyset cursor (activity pagination): the sort key (a time) and the row id of the last row read. The first page starts at [head]. Paging is strictly AFTER the cursor, so a row inserted or deleted
 * between two pages never shifts or repeats the rows already read (unlike OFFSET).
 */
data class Cursor(val key: Long, val id: String) {
    companion object {
        /** Before the newest row of [range] (a range is both-ends inclusive, the queries are half-open). */
        fun head(range: LongRange) = Cursor(range.last + 1, "")
        fun of(t: FrameEntity) = Cursor(t.ts, t.id)
        fun of(t: MemoryEntity) = Cursor(t.createdAt, t.id)
        fun of(t: AskEntity) = Cursor(t.ts, t.id)
        fun of(t: NudgeEntity) = Cursor(t.ts, t.id)
        fun of(t: MomentDao.MarkRow) = Cursor(t.atMs, t.labelId)
        fun of(t: MomentRow) = Cursor(t.shownAt, t.memory.id)
        fun of(t: com.rokid.context.memory.db.SceneEntity) = Cursor(t.startMs, t.sceneId)

        /** List order of every kind but marks: newest first, then id DESC. A negative result: [a] is listed before [b]. */
        val NEWEST_FIRST: Comparator<Cursor> = compareByDescending<Cursor> { it.key }.thenByDescending { it.id }
        /** List order of marks: newest first, then `labelId` ASC (the order of the Activity tab). */
        val MARKS: Comparator<Cursor> = compareByDescending<Cursor> { it.key }.thenBy { it.id }
    }
}

/**
 * One keyset page reader per kind (activity pagination A2), over the existing repositories: every call is index-bounded and returns at most `n` rows, whatever the table size. The filters equal the
 * tile-count filters (visible memories only, no developer eval asks). Nothing caps a list any more (the old 1,000 / 200 caps are gone from this path).
 */
class ActivityPages(
    private val timeline: TimelineRepository, private val asks: AskRepository, private val nudges: NudgeRepository, private val marks: MarkRepository,
    private val pauses: (suspend (fromMs: Long, toMs: Long) -> List<PauseIntervalEntity>)? = null,
) {
    suspend fun frames(range: LongRange, after: Cursor, n: Int): List<FrameEntity> = timeline.framePage(range, after, n)
    suspend fun memories(range: LongRange, after: Cursor, n: Int): List<MemoryEntity> = timeline.visibleMemoryPage(range, after, n)
    suspend fun answers(range: LongRange, after: Cursor, n: Int): List<AskEntity> = asks.page(range, after, n)
    suspend fun nudges(range: LongRange, after: Cursor, n: Int): List<NudgeEntity> = nudges.page(range, after, n)
    suspend fun marks(range: LongRange, after: Cursor, n: Int): List<MomentDao.MarkRow> = marks.page(range, after, n)
    /** The visible memories of [day] shown in [hour], newest first by display time; each with the time it is shown at. */
    suspend fun momentsOfHour(day: LongRange, hour: LongRange, after: Cursor, n: Int): List<MomentRow> = timeline.momentPage(day, hour, after, n)
    /** The display time of every visible memory of [day] (hour counts are bucketed by the caller in the zone's own hours). */
    suspend fun momentTimes(day: LongRange): List<Long> = timeline.momentTimes(day)
    /**
     * The ids of [kind]'s rows in [range] (ids only, no entity is read), newest first, at most [limit]: what "Select this day" resolves (multi-select, ruling P2). Same filter as the kind's pages and tile counts. Answers
     * and nudges have no single-item Forget, so they are not resolved here (empty).
     */
    suspend fun idsIn(kind: IdKind, range: LongRange, limit: Int): List<String> = when (kind) {
        IdKind.FRAMES -> timeline.frameIdsIn(range, limit)
        IdKind.MEMORIES -> timeline.visibleMemoryIdsIn(range, limit)
        IdKind.MARKS -> marks.idsIn(range, limit)
    }
    /** The ids of the memories [momentsOfHour] lists for one hour (ids only), at most [limit]. */
    suspend fun momentIdsOfHour(day: LongRange, hour: LongRange, limit: Int): List<String> = timeline.momentIdsOfHour(day, hour, limit)
    enum class IdKind { FRAMES, MEMORIES, MARKS }
    /** The pause intervals that overlap [range]; empty when no pause source is wired. */
    suspend fun pausesIn(range: LongRange): List<PauseIntervalEntity> = pauses?.invoke(range.first, range.last + 1).orEmpty()
}
