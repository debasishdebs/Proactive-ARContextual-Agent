package com.rokid.context.canvas.moment

import com.rokid.context.memory.db.CanvasDao
import com.rokid.context.memory.db.CanvasSlice3Dao
import com.rokid.context.memory.db.MomentDao
import com.rokid.context.memory.db.MomentSensorEntity

/** The production [MomentSource]: reads the existing frame, memory and mark tables. Nothing here writes, and a forgotten (tombstoned) frame is never returned. */
class DaoMomentSource(private val dao: CanvasDao, private val dao3: CanvasSlice3Dao, private val moments: MomentDao? = null) : MomentSource {
    override suspend fun frames(fromMs: Long, toMs: Long, limit: Int) = dao.momentFrames(fromMs, toMs, limit).map { MomentFrame(it.id, it.ts, it.rev) }

    override suspend fun framesNewest(fromMs: Long, toMs: Long, limit: Int) = dao.momentFramesNewest(fromMs, toMs, limit).map { MomentFrame(it.id, it.ts, it.rev) }

    override suspend fun memories(frameIds: List<String>): List<Pair<String, MomentRef>> =
        frameIds.chunked(CHUNK).flatMap { c -> dao.momentMemories(c).map { it.frameId to MomentRef(it.id, it.rev) } }

    override suspend fun visibleTexts(frameIds: List<String>): List<MomentText> =
        frameIds.chunked(CHUNK).flatMap { c -> dao.momentVisibleTexts(c).map { MomentText(it.frameId, it.id, it.createdAt, it.text) } }

    override suspend fun marks(fromMs: Long, toMs: Long) = dao3.labelsBetween(fromMs, toMs).map { MomentMark(it.labelId, it.atMs, "${it.kind}:${it.name}:${it.deletionCounter}:${it.provenance}", it.source) }

    /** The stored sensor rows, when this source was given the Moment DAO (the builder's own resolver is not: it never reads them). */
    override suspend fun sensor(momentIds: List<String>): List<MomentSensorEntity> = moments?.let { m -> momentIds.chunked(CHUNK).flatMap { m.sensorOf(it) } } ?: emptyList()

    private companion object { const val CHUNK = 500 }
}
