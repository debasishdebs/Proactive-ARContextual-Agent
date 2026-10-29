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
import kotlinx.coroutines.flow.first
import com.rokid.context.memory.DayBucket
import java.time.LocalDate
import java.time.ZoneId

/** Production [ActivityData]: the existing read repositories, nothing new in the database. Answers and nudges are read from the most recent [RECENT] (there is no by-id read for them). */
class RepoActivityData(
    private val timeline: TimelineRepository, private val asks: AskRepository, private val nudges: NudgeRepository, private val marks: MarkRepository, private val zone: () -> ZoneId,
) : ActivityData {
    override suspend fun memories(ids: List<String>): List<MemoryEntity> = timeline.memoriesByIds(ids)
    override suspend fun frames(ids: List<String>): List<FrameEntity> = timeline.framesByIds(ids)
    override suspend fun marks(ids: List<String>): List<MarkRowUi> = MarkRows.rows(marks.byIds(ids), zone(), withDay = true)
    override suspend fun asks(ids: List<String>): List<AskEntity> = asks.recent(RECENT).first().filter { it.id in ids }
    override suspend fun nudges(ids: List<String>): List<NudgeEntity> = nudges.recent(RECENT).first().filter { it.id in ids }

    override suspend fun dayFrames(date: LocalDate): List<FrameEntity> = timeline.framesIn(DayBucket.bounds(date, zone())).first()

    companion object { const val RECENT = 1000 }
}
