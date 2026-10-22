package com.rokid.context.memory.forget

import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.db.ReviewState

/**
 * Write-time Forget fence (fix round 1 I1): called inside the transaction that writes model output built from
 * memories. False when any of [ids] was forgotten (deleted or INVALIDATED) since it was read, so the output may hold
 * forgotten text. Shared by asks (AnswerService), derived facts (PipelineRecords) and, for Stream C, nudges.
 *
 * Wave 3 (S1): a `frame:<id>` photo cite ([PHOTO_CITE_PREFIX]) is live while that moment's `frame_meta` row exists (an expired photo keeps it;
 * Forget deletes it), so an answer that commits after a Forget of its photo is fenced like one that cites a forgotten memory.
 */
object ForgetFence {
    suspend fun allLive(db: ContextDatabase, ids: Collection<String>): Boolean {
        val wanted = ids.toSet()
        if (wanted.isEmpty()) return true
        val (cites, memoryIds) = wanted.partition { it.startsWith(PHOTO_CITE_PREFIX) }
        val liveMemories = memoryIds.chunked(500).flatMap { db.forgetDao().memories(it) }.count { it.reviewState != ReviewState.INVALIDATED }
        if (liveMemories != memoryIds.size) return false
        val frameIds = cites.map { it.removePrefix(PHOTO_CITE_PREFIX) }
        return frameIds.chunked(500).flatMap { db.forgetDao().keyframeIds(it) }.toSet().size == frameIds.toSet().size
    }
}
