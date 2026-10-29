package com.rokid.context.phone.ui.preview

import com.rokid.context.memory.AskRepository
import com.rokid.context.memory.MarkRepository
import com.rokid.context.memory.NudgeRepository
import com.rokid.context.memory.TimelineRepository
import com.rokid.context.memory.db.AskEntity
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.NudgeEntity
import com.rokid.context.phone.ui.activity.MarkRowUi
import com.rokid.context.phone.ui.activity.MarkRows
import com.rokid.context.memory.forget.PHOTO_CITE_PREFIX
import com.rokid.context.orchestration.AskSources
import com.rokid.context.phone.ui.shell.PhoneDeps
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import com.rokid.context.memory.DayBucket
import java.time.LocalDate
import java.time.ZoneId

/** Production [ActivityData]: the existing read repositories, nothing new in the database. Answers and nudges are read from the most recent [RECENT] (there is no by-id read for them). */
class RepoActivityData(
    private val timeline: TimelineRepository, private val asks: AskRepository, private val nudges: NudgeRepository, private val marks: MarkRepository, private val zone: () -> ZoneId,
    /** One nudge row by id, observed (not limited to the recent ones). */
    private val nudgeById: (String) -> Flow<NudgeEntity?> = { id -> nudges.recent(RECENT).map { rows -> rows.firstOrNull { it.id == id } } },
) : ActivityData {
    override suspend fun memories(ids: List<String>): List<MemoryEntity> = timeline.memoriesByIds(ids)
    override suspend fun frames(ids: List<String>): List<FrameEntity> = timeline.framesByIds(ids)
    override suspend fun marks(ids: List<String>): List<MarkRowUi> = MarkRows.rows(marks.byIds(ids), zone(), withDay = true)
    override suspend fun asks(ids: List<String>): List<AskEntity> = ids.distinct().mapNotNull { asks.observe(it).first() }
    override suspend fun nudges(ids: List<String>): List<NudgeEntity> = ids.distinct().mapNotNull { nudgeById(it).first() }

    override suspend fun dayFrames(date: LocalDate): List<FrameEntity> = timeline.framesIn(DayBucket.bounds(date, zone())).first()

    /** The row itself and the memories it cites: a Forget or a redaction of either re-emits (the deleted detail screens observed the same). */
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun changes(ref: PreviewRef): Flow<Unit> = when (ref.kind) {
        RefKind.MEMORY -> timeline.memory(ref.id).map { }
        RefKind.ASK -> asks.observe(ref.id).flatMapLatest { a -> cited(a?.takeUnless { it.redacted }?.sourcesJson) }
        RefKind.NUDGE -> nudgeById(ref.id).flatMapLatest { n -> cited(n?.takeUnless { it.redacted }?.sourceIdsJson) }
        else -> emptyFlow()
    }

    private fun cited(json: String?): Flow<Unit> {
        val ids = json?.let { AskSources.parse(it) }.orEmpty().filterNot { it.startsWith(PHOTO_CITE_PREFIX) }.distinct()
        return if (ids.isEmpty()) flowOf(Unit) else combine(ids.map { timeline.memory(it) }) { }
    }

    companion object { const val RECENT = 1000 }
}

/** Production [ActivityData] over the app's repositories (the previewer and the lists share it). */
fun activityData(deps: PhoneDeps) = RepoActivityData(deps.memory.timeline, deps.memory.asks, deps.memory.nudges, deps.memory.marks, deps.zone, nudgeById = { deps.memory.db.nudgeGateDao().observe(it) })
