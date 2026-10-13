package com.rokid.context.memory.forget

import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.db.ReviewState

/**
 * Write-time Forget fence (fix round 1 I1): called inside the transaction that writes model output built from
 * memories. False when any of [ids] was forgotten (deleted or INVALIDATED) since it was read, so the output may hold
 * forgotten text. Shared by asks (AnswerService), derived facts (PipelineRecords) and, for Stream C, nudges.
 */
object ForgetFence {
    suspend fun allLive(db: ContextDatabase, ids: Collection<String>): Boolean {
        val wanted = ids.toSet()
        if (wanted.isEmpty()) return true
        val live = wanted.toList().chunked(500).flatMap { db.forgetDao().memories(it) }.count { it.reviewState != ReviewState.INVALIDATED }
        return live == wanted.size
    }
}
