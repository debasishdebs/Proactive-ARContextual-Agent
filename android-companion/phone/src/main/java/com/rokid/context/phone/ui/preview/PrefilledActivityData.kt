package com.rokid.context.phone.ui.preview

import com.rokid.context.memory.db.AskEntity
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.NudgeEntity
import com.rokid.context.phone.ui.activity.MarkRowUi

/**
 * [base] with the rows a keyset page already read: a summary factory asks for its page's ids and gets them from here, so a page of 100 rows costs the one page query and no per-row read (activity
 * pagination, no N+1). An id that is not pre-filled (a frame a memory cites) is read from [base] in one batch.
 */
class PrefilledActivityData(
    private val base: ActivityData,
    private val memories: List<MemoryEntity> = emptyList(), private val frames: List<FrameEntity> = emptyList(), private val marks: List<MarkRowUi> = emptyList(),
    private val asks: List<AskEntity> = emptyList(), private val nudges: List<NudgeEntity> = emptyList(),
) : ActivityData by base {
    private suspend fun <T> serve(ids: List<String>, have: List<T>, id: (T) -> String, rest: suspend (List<String>) -> List<T>): List<T> {
        val known = have.associateBy(id)
        val missing = ids.filter { it !in known }
        return if (missing.isEmpty()) ids.mapNotNull { known[it] } else (ids.mapNotNull { known[it] } + rest(missing))
    }
    override suspend fun memories(ids: List<String>) = serve(ids, memories, { it.id }, base::memories)
    override suspend fun frames(ids: List<String>) = serve(ids, frames, { it.id }, base::frames)
    override suspend fun marks(ids: List<String>) = serve(ids, marks, { it.labelId }, base::marks)
    override suspend fun asks(ids: List<String>) = serve(ids, asks, { it.id }, base::asks)
    override suspend fun nudges(ids: List<String>) = serve(ids, nudges, { it.id }, base::nudges)
}
