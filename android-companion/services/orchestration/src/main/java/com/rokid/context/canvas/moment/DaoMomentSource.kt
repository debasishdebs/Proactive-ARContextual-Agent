package com.rokid.context.canvas.moment

import com.rokid.context.core.model.MarkPhotoReason
import com.rokid.context.memory.db.CanvasDao
import com.rokid.context.memory.db.CanvasSlice3Dao
import com.rokid.context.memory.db.MomentDao
import com.rokid.context.memory.db.MomentSensorEntity

/** The production [MomentSource]: reads the existing frame, memory and mark tables. Nothing here writes, and a forgotten (tombstoned) frame is never returned. */
class DaoMomentSource(
    private val dao: CanvasDao, private val dao3: CanvasSlice3Dao, private val moments: MomentDao? = null,
    /** Phone clock: a mark still waiting for its photo is held back until [PHOTO_WAIT_MS] after it was received. */
    private val clock: () -> Long = System::currentTimeMillis,
) : MomentSource {
    override suspend fun frames(fromMs: Long, toMs: Long, limit: Int) = dao.momentFrames(fromMs, toMs, limit).map { MomentFrame(it.id, it.ts, it.rev) }

    override suspend fun framesNewest(fromMs: Long, toMs: Long, limit: Int) = dao.momentFramesNewest(fromMs, toMs, limit).map { MomentFrame(it.id, it.ts, it.rev) }

    override suspend fun memories(frameIds: List<String>): List<Pair<String, MomentRef>> =
        frameIds.chunked(CHUNK).flatMap { c -> dao.momentMemories(c).map { it.frameId to MomentRef(it.id, it.rev) } }

    override suspend fun visibleTexts(frameIds: List<String>): List<MomentText> =
        frameIds.chunked(CHUNK).flatMap { c -> dao.momentVisibleTexts(c).map { MomentText(it.frameId, it.id, it.createdAt, it.text) } }

    /**
     * The marks of a range by their EFFECTIVE time (v0.9.6 slice C): a mark whose photo arrived belongs to the second of that photo, a mark with no photo to its own second; a mark still waiting for its photo
     * is not read yet, one whose wait ran out reads as `photo_timeout`; the intent row of a mark follows it. A mark whose photo is gone makes no Moment.
     */
    override suspend fun marks(fromMs: Long, toMs: Long) = dao3.momentMarksBetween(fromMs, toMs, clock() - PHOTO_WAIT_MS).map {
        val state = if (it.photo == MarkPhotoReason.PENDING) MarkPhotoReason.TIMEOUT else it.photo
        // a mark from before v15 (no photo state) keeps its old revision text, so no stored Moment of an old mark is rebuilt for nothing
        MomentMark(it.labelId, it.atMs, "${it.kind}:${it.name}:${it.deletionCounter}:${it.provenance}" + (state?.let { s -> ":$s" } ?: ""), it.source, state)
    }

    /** The stored sensor rows, when this source was given the Moment DAO (the builder's own resolver is not: it never reads them). */
    override suspend fun sensor(momentIds: List<String>): List<MomentSensorEntity> = moments?.let { m -> momentIds.chunked(CHUNK).flatMap { m.sensorOf(it) } } ?: emptyList()

    companion object {
        private const val CHUNK = 500
        /** How long a mark may wait for its photo before it is the fallback mark-only Moment (the glasses take it within seconds; a photo that arrives later still upgrades the Moment). */
        const val PHOTO_WAIT_MS = 120_000L
    }
}
