package com.rokid.context.memory

import androidx.room.withTransaction
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
    enum class Outcome { ADDED, DUPLICATE, WIRE_DUPLICATE }

    suspend fun commit(frame: FrameEntity, rows: FrameRows): Outcome = db.withTransaction {
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
}
