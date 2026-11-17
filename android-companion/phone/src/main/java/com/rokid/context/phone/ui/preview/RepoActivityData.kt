package com.rokid.context.phone.ui.preview

import com.rokid.context.memory.ActivityPages
import com.rokid.context.memory.AskRepository
import com.rokid.context.memory.Cursor
import com.rokid.context.memory.MarkRepository
import com.rokid.context.memory.NudgeRepository
import com.rokid.context.memory.TimelineRepository
import com.rokid.context.memory.db.AskEntity
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.NudgeEntity
import com.rokid.context.phone.ui.activity.MarkRowUi
import com.rokid.context.phone.ui.list.ListKind
import com.rokid.context.phone.ui.list.tableChanges
import com.rokid.context.phone.ui.activity.MarkRows
import com.rokid.context.memory.forget.PHOTO_CITE_PREFIX
import com.rokid.context.orchestration.AskSources
import com.rokid.context.phone.ui.shell.PhoneDeps
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import com.rokid.context.memory.DayBucket
import java.time.LocalDate
import java.time.ZoneId

/** Production [ActivityData]: the read repositories and their keyset pages (activity pagination), no schema change. Every list read is one bounded page: nothing is capped at 1,000 or 200 any more. */
class RepoActivityData(
    private val timeline: TimelineRepository, private val asks: AskRepository, private val nudges: NudgeRepository, private val marks: MarkRepository, private val zone: () -> ZoneId,
    /** One nudge row by id, observed. */
    private val nudgeById: (String) -> Flow<NudgeEntity?> = { id -> nudges.observe(id) },
    /** Room's change signal over some tables (null: lists never re-read; tests without a database). */
    private val tableChanges: ((Array<String>) -> Flow<Unit>)? = null,
    /** The pause intervals overlapping `[from, to)` (production: `PauseDao.overlapping`); none when null. */
    private val pauseIntervals: (suspend (Long, Long) -> List<com.rokid.context.memory.db.PauseIntervalEntity>)? = null,
) : ActivityData {
    override suspend fun memories(ids: List<String>): List<MemoryEntity> = timeline.memoriesByIds(ids)
    override suspend fun frames(ids: List<String>): List<FrameEntity> = timeline.framesByIds(ids)
    override suspend fun marks(ids: List<String>): List<MarkRowUi> = MarkRows.rows(marks.byIds(ids), zone(), withDay = true)
    override suspend fun asks(ids: List<String>): List<AskEntity> = asks.byIds(ids)
    override suspend fun nudges(ids: List<String>): List<NudgeEntity> = nudges.byIds(ids)

    override suspend fun dayFrames(date: LocalDate): List<FrameEntity> = timeline.framesIn(DayBucket.bounds(date, zone())).first()

    private val pages = ActivityPages(timeline, asks, nudges, marks, pauseIntervals)

    override suspend fun momentTimes(day: LongRange): List<Long> = pages.momentTimes(day)
    override suspend fun momentHourPage(day: LongRange, hour: LongRange, after: Cursor, n: Int): ActivityPage =
        pages.momentsOfHour(day, hour, after, n).let { r -> ActivityPage(r.map { it.memory.id }, r.map(Cursor::of), PrefilledActivityData(this, memories = r.map { it.memory })) }
    override fun times(kind: ListKind, range: LongRange): Flow<List<Long>> = when (kind) {
        ListKind.MOMENTS, ListKind.MEMORIES -> timeline.visibleMemoryTimesIn(range)
        ListKind.FRAMES -> timeline.frameTimesIn(range)
        ListKind.ANSWERS -> asks.timesIn(range)
        ListKind.NUDGES -> nudges.timesIn(range)
        ListKind.MARKS -> emptyFlow()
    }
    override suspend fun pauses(range: LongRange): List<PauseSpan> =
        pages.pausesIn(range).map { PauseSpan(it.startMs, it.effectiveEndMs, it.pauseId.startsWith("zone:")) }

    /** One keyset page; the rows read are handed on in [ActivityPage.data] so the summary build reads nothing more (no N+1). */
    override suspend fun page(kind: ListKind, range: LongRange, after: Cursor, n: Int): ActivityPage = when (kind) {
        ListKind.FRAMES -> pages.frames(range, after, n).let { r -> ActivityPage(r.map { it.id }, r.map(Cursor::of), PrefilledActivityData(this, frames = r)) }
        ListKind.MEMORIES, ListKind.MOMENTS -> pages.memories(range, after, n).let { r -> ActivityPage(r.map { it.id }, r.map(Cursor::of), PrefilledActivityData(this, memories = r)) }
        ListKind.ANSWERS -> pages.answers(range, after, n).let { r -> ActivityPage(r.map { it.id }, r.map(Cursor::of), PrefilledActivityData(this, asks = r)) }
        ListKind.NUDGES -> pages.nudges(range, after, n).let { r -> ActivityPage(r.map { it.id }, r.map(Cursor::of), PrefilledActivityData(this, nudges = r)) }
        ListKind.MARKS -> pages.marks(range, after, n).let { r -> ActivityPage(r.map { it.labelId }, r.map(Cursor::of), PrefilledActivityData(this, marks = MarkRows.rows(r, zone(), withDay = true))) }
    }

    /** The row itself and the memories it cites: a Forget or a redaction of either re-emits (the deleted detail screens observed the same). */
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun changes(ref: PreviewRef): Flow<Unit> = when (ref.kind) {
        RefKind.MEMORY -> timeline.memory(ref.id).map { }
        RefKind.ASK -> asks.observe(ref.id).flatMapLatest { a -> cited(a?.takeUnless { it.redacted }?.sourcesJson) }
        RefKind.NUDGE -> nudgeById(ref.id).flatMapLatest { n -> cited(n?.takeUnless { it.redacted }?.sourceIdsJson) }
        // A pending mark ("Photo on its way") changes when its photo link, photo state or Moment caption arrives.
        RefKind.MARK -> marks.observeById(ref.id).distinctUntilChanged().map { }
        else -> emptyFlow()
    }

    /** The tables each list's rows and their summaries are read from (a row cites memories and photos). */
    override fun listChanges(kind: ListKind): Flow<Unit> = tableChanges?.invoke(when (kind) {
        ListKind.FRAMES -> arrayOf("frame", "frame_meta")
        ListKind.MEMORIES -> arrayOf("memory", "frame_source", "frame", "moment")
        ListKind.MOMENTS -> arrayOf("memory", "frame_source", "frame", "moment", "pause_interval")
        ListKind.ANSWERS -> arrayOf("ask", "memory")
        ListKind.NUDGES -> arrayOf("nudge", "memory")
        ListKind.MARKS -> arrayOf("canvas_label", "frame", "frame_meta", "moment")
    }) ?: emptyFlow()

    override fun observeFramePath(id: String): Flow<String?> {
        val once = kotlinx.coroutines.flow.flow { emit(frames(listOf(id)).firstOrNull()?.path) }
        val changes = tableChanges?.invoke(arrayOf("frame")) ?: return once
        return changes.onStart { emit(Unit) }.map { frames(listOf(id)).firstOrNull()?.path }.distinctUntilChanged()
    }

    private fun cited(json: String?): Flow<Unit> {
        val ids = json?.let { AskSources.parse(it) }.orEmpty().filterNot { it.startsWith(PHOTO_CITE_PREFIX) }.distinct()
        return if (ids.isEmpty()) flowOf(Unit) else combine(ids.map { timeline.memory(it) }) { }
    }

}

/** Production [ActivityData] over the app's repositories (the previewer and the lists share it). */
fun activityData(deps: PhoneDeps) = RepoActivityData(deps.memory.timeline, deps.memory.asks, deps.memory.nudges, deps.memory.marks, deps.zone, nudgeById = { deps.memory.db.nudgeGateDao().observe(it) },
    tableChanges = { t -> deps.memory.db.tableChanges(*t) }, pauseIntervals = { from, to -> deps.memory.db.pauseDao().overlapping(from, to) })
