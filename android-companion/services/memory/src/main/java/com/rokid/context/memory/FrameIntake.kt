package com.rokid.context.memory

import androidx.room.withTransaction
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
class FrameIntake(private val db: ContextDatabase) {
    /** [FENCED]: the photo's time lies in a Moment Forget fence (a forgotten second or range), so nothing was stored and its JPEG is queued for deletion in the durable outbox. */
    enum class Outcome { ADDED, DUPLICATE, WIRE_DUPLICATE, FENCED }

    suspend fun commit(frame: FrameEntity, rows: FrameRows): Outcome = db.withTransaction {
        // Late admission is fenced INSIDE the committing transaction (moment-model spec 8.2): a Forget that committed first is seen here, one that commits after deletes the row.
        if (fencedByMomentForget(frame, rows)) {
            val sidecar = frame.path.removeSuffix(".jpg") + ".meta.json"
            db.momentDao().insertOutbox(listOf(frame.path, sidecar).filter { it.isNotBlank() }.distinct().map {
                CleanupOutboxEntity(target = CleanupTarget.PHONE_FILE, payloadJson = CleanupPayload.one("path", it), createdAt = System.currentTimeMillis())
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
                Outcome.ADDED
            }
        }
    }

    /** True when the capture time (or the file time, whichever lies in a forgotten second) touches a Moment Forget range or a tombstoned second. */
    private suspend fun fencedByMomentForget(frame: FrameEntity, rows: FrameRows): Boolean {
        val times = listOfNotNull(frame.ts, rows.meta.capturedAtMs).distinct()
        val s3 = db.canvasSlice3Dao()
        if (times.any { s3.momentFenced(it, it) }) return true
        val ids = times.mapNotNull { runCatching { MomentId.ofMs(it) }.getOrNull() }
        return ids.isNotEmpty() && db.momentDao().tombstonedAmong(ids).isNotEmpty()
    }
}
