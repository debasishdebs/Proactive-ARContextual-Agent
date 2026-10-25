package com.rokid.context.phone.hub

import com.rokid.context.core.model.MarkPhotoReason
import com.rokid.context.core.model.MarkRef
import com.rokid.context.link.MarkLookup
import com.rokid.context.link.MarkState
import com.rokid.context.memory.FrameIntake
import com.rokid.context.memory.db.CanvasSlice3Dao

/** The real stored marks behind the pause exception of a mark photo: OPEN only for a stored mark label that still waits for its photo. */
class RoomMarkLookup(private val dao: CanvasSlice3Dao) : MarkLookup {
    override suspend fun state(markRef: String): MarkState {
        val id = MarkRef.labelId(markRef)
        val l = dao.label(id) ?: return if (dao.meta(FrameIntake.MARK_UNDONE_PREFIX + id) != null) MarkState.CLOSED else MarkState.UNKNOWN
        return if (l.frameId == null && l.photo == MarkPhotoReason.PENDING) MarkState.OPEN else MarkState.CLOSED
    }
}
