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
        var asks: List<AskEntity> = emptyList(), val nudges: List<NudgeEntity> = emptyList(),
    ) : ActivityData {
        override suspend fun memories(ids: List<String>) = mems.filter { it.id in ids }
        override suspend fun frames(ids: List<String>) = frames.filter { it.id in ids }
        override suspend fun marks(ids: List<String>) = marks.filter { it.labelId in ids }
        /** Set to hold the next [asks] read open (a slow load); each read takes the gate present when it starts. */
        var asksGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        override suspend fun asks(ids: List<String>): List<AskEntity> { val snapshot = asks; asksGate?.await(); return snapshot.filter { it.id in ids } }
        override suspend fun nudges(ids: List<String>) = nudges.filter { it.id in ids }
        /** Emit to say the item changed (the previewer reads it again). */
    val changed = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(replay = 1).also { it.tryEmit(Unit) }
    override fun changes(ref: PreviewRef) = changed
    override suspend fun dayFrames(date: LocalDate) = frames.sortedBy { it.ts }
    }
