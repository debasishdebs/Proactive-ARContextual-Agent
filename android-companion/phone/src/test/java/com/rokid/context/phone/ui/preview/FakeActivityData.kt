package com.rokid.context.phone.ui.preview

import com.rokid.context.memory.db.AskEntity
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.NudgeEntity
import com.rokid.context.phone.ui.activity.MarkRowUi
import java.time.LocalDate

/** A fixed set of rows behind [ActivityData], for the presenter and screen tests. */
class FakeActivityData(
        val mems: List<MemoryEntity> = emptyList(), val frames: List<FrameEntity> = emptyList(), val marks: List<MarkRowUi> = emptyList(),
        val asks: List<AskEntity> = emptyList(), val nudges: List<NudgeEntity> = emptyList(),
    ) : ActivityData {
        override suspend fun memories(ids: List<String>) = mems.filter { it.id in ids }
        override suspend fun frames(ids: List<String>) = frames.filter { it.id in ids }
        override suspend fun marks(ids: List<String>) = marks.filter { it.labelId in ids }
        override suspend fun asks(ids: List<String>) = asks.filter { it.id in ids }
        override suspend fun nudges(ids: List<String>) = nudges.filter { it.id in ids }
        override suspend fun dayFrames(date: LocalDate) = frames.sortedBy { it.ts }
    }
