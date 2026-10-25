package com.rokid.context.memory.forget

import com.rokid.context.memory.db.CleanupOutboxEntity
import com.rokid.context.memory.db.CleanupPayload
import com.rokid.context.memory.db.CleanupTarget
import com.rokid.context.memory.db.MomentDao

/**
 * THE one writer of content-free `cleanup_outbox` rows for a deletion (phone files, glasses frames, tombstoned glasses traces, glasses time ranges). Every path that deletes a
 * photo, frame, trace or derived artefact ([MomentForget], `ForgetService.forgetMoment`, `forgetFacts`, `expire`, `expirePhotos`) calls this INSIDE its own transaction, so the row
 * exists if and only if the delete committed. The unique (target, payload) key makes a repeat a no-op. Payloads hold ids, paths and ranges only. The helper's copies are queued by
 * `CanvasSlice3Dao.forgetRangeTx` / `deleteSliceOutputsForFrames` (`canvas_helper_cleanup`) in the same transaction.
 * Returns the rows actually inserted.
 */
internal object CleanupOutbox {
    suspend fun queue(
        dao: MomentDao, now: Long,
        phoneFiles: Collection<String> = emptyList(), wireFrameIds: Collection<String> = emptyList(),
        traceIds: Collection<String> = emptyList(), ranges: Collection<LongRange> = emptyList(),
    ): Int {
        val rows = ArrayList<CleanupOutboxEntity>()
        fun row(target: String, json: String) = rows.add(CleanupOutboxEntity(target = target, payloadJson = json, createdAt = now))
        phoneFiles.filter { it.isNotBlank() }.distinct().sorted().forEach { row(CleanupTarget.PHONE_FILE, CleanupPayload.one("path", it)) }
        wireFrameIds.distinct().sorted().forEach { row(CleanupTarget.GLASSES_FRAME, CleanupPayload.one("wireFrameId", it)) }
        traceIds.distinct().sorted().forEach { row(CleanupTarget.GLASSES_TRACE, CleanupPayload.one("traceId", it)) }
        ranges.forEach { row(CleanupTarget.GLASSES_RANGE, CleanupPayload.range(it.first, it.last)) }
        return if (rows.isEmpty()) 0 else dao.insertOutbox(rows).count { it != -1L }
    }
}
