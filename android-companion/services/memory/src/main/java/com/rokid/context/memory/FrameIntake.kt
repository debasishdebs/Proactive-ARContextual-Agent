package com.rokid.context.memory

import androidx.room.withTransaction
import com.rokid.context.core.model.MarkRef
import com.rokid.context.memory.moment.MomentId
import com.rokid.context.memory.db.CleanupOutboxEntity
import com.rokid.context.memory.db.CleanupPayload
import com.rokid.context.memory.db.CleanupTarget
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.FrameMetaEntity
import com.rokid.context.memory.db.FrameStateEntity

/** The per-frame rows written with the `frame` row at ingest (keyframe capture P1). */
data class FrameRows(val meta: FrameMetaEntity, val state: FrameStateEntity)

/**
 * The ingest commit: the frame row, its `frame_meta` and its PENDING `frame_state` in ONE Room transaction, so a crash
 * leaves all three or none. A resend of a glasses frame under another database id (same `wireFrameId`) is not stored twice.
 */
class FrameIntake(private val db: ContextDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    /** [FENCED]: the photo's time lies in a Moment Forget fence (a forgotten second or range), so nothing was stored and its JPEG is queued for deletion in the durable outbox. */
    enum class Outcome { ADDED, DUPLICATE, WIRE_DUPLICATE, FENCED }

    suspend fun commit(frame: FrameEntity, rows: FrameRows): Outcome = db.withTransaction {
        // Late admission is fenced INSIDE the committing transaction (moment-model spec 8.2): a Forget that committed first is seen here, one that commits after deletes the row.
        if (fencedByMomentForget(frame, rows)) {
            val sidecar = frame.path.removeSuffix(".jpg") + ".meta.json"
            db.momentDao().insertOutbox(listOf(frame.path, sidecar).filter { it.isNotBlank() }.distinct().map {
                CleanupOutboxEntity(target = CleanupTarget.PHONE_FILE, payloadJson = CleanupPayload.one("path", it), createdAt = clock())
            })
            return@withTransaction Outcome.FENCED
        }
        // v0.9.6 slice C: the photo of a mark that was UNDONE before it arrived is refused like a forgotten second (the JPEG is queued for deletion).
        val markRef = rows.meta.markRef?.takeIf { MarkRef.isValid(it) }
        if (markRef != null && db.canvasSlice3Dao().meta(MARK_UNDONE_PREFIX + MarkRef.labelId(markRef)) != null) {
            val sidecar = frame.path.removeSuffix(".jpg") + ".meta.json"
            db.momentDao().insertOutbox(listOf(frame.path, sidecar).filter { it.isNotBlank() }.distinct().map {
                CleanupOutboxEntity(target = CleanupTarget.PHONE_FILE, payloadJson = CleanupPayload.one("path", it), createdAt = clock())
            })
            return@withTransaction Outcome.FENCED
        }
        val wire = rows.meta.wireFrameId
        val other = wire?.let { db.frameMetaDao().byWireId(it) }
        when {
            other != null && other.frameId != frame.id -> Outcome.WIRE_DUPLICATE
            db.frameDao().insertIgnore(frame) == -1L -> Outcome.DUPLICATE
            else -> {
                db.frameMetaDao().upsert(rows.meta)
                db.frameStateDao().insertIgnore(listOf(rows.state))
                // a mark touches capture: the photo that names its mark is linked to it in THIS transaction (a mark that is not here yet finds the photo when it is recorded)
                if (markRef != null) db.canvasSlice3Dao().linkMarkPhoto(MarkRef.labelId(markRef))
                Outcome.ADDED
            }
        }
    }

    /**
     * True when the photo may lie in a forgotten interval. USABLE capture evidence is `capturedAtMs` only; the file time `frame.ts` is not (a delayed frame keeps a late file
     * time). With evidence the frame is fenced when its capture time (or file time) touches a Moment Forget range or tombstoned second. WITHOUT evidence it cannot be proven
     * outside a fence, so it is also refused while any Moment Forget happened within [UNKNOWN_TIME_FENCE_MS] (conservative: unknown capture time is never admitted next to a Forget).
     */
    private suspend fun fencedByMomentForget(frame: FrameEntity, rows: FrameRows): Boolean {
        val captured = rows.meta.capturedAtMs
        val times = listOfNotNull(frame.ts, captured).distinct()
        val s3 = db.canvasSlice3Dao()
        if (times.any { s3.momentFenced(it, it) }) return true
        val ids = times.mapNotNull { runCatching { MomentId.ofMs(it) }.getOrNull() }
        if (ids.isNotEmpty() && db.momentDao().tombstonedAmong(ids).isNotEmpty()) return true
        return captured == null && db.momentDao().tombstonesSince(clock() - UNKNOWN_TIME_FENCE_MS) > 0
    }

    companion object {
        const val UNKNOWN_TIME_FENCE_MS = 24L * 3600 * 1000
        /** `canvas_meta` key prefix of an undone mark whose photo may still be on its way (the value is a time, no content). */
        const val MARK_UNDONE_PREFIX = "mark_undone:"
    }
}
