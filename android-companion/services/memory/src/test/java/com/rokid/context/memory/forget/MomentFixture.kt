package com.rokid.context.memory.forget

import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.db.AskEntity
import com.rokid.context.memory.db.CanvasLabelEntity
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.DeletionEpoch
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.FrameMetaEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.MemorySourceEntity
import com.rokid.context.memory.db.MomentEntity
import com.rokid.context.memory.db.MomentPartEntity
import com.rokid.context.memory.moment.MomentId

/** Seeds the authoritative stores and the projection of a test database. Times are phone-clock milliseconds. */
class MomentForgetFixture(val db: ContextDatabase, now: Long = 1_000) {
    companion object { const val SEC = 1_761_000_000L; const val MS = SEC * 1000 }
    val service = ForgetService(db, ProposalRepository(db.proposalDao(), db.actionDao(), db)) { now }
    val forget = MomentForget(db, service) { now }
    val id = MomentId.format(SEC)

    suspend fun frame(id: String, ts: Long, wire: String? = null, capturedAt: Long? = null) {
        db.frameDao().insertIgnore(FrameEntity(id, ts, "/p/$id.jpg", "rokid_glasses", phash = "p$id"))
        if (wire != null || capturedAt != null) db.frameMetaDao().upsert(FrameMetaEntity(id, wire, null, "STILL", false, null, null, null, null, null, null, null, null, null, 1, capturedAtMs = capturedAt))
    }
    suspend fun memory(id: String, frameId: String?, derived: Boolean = false, createdAt: Long = 1) =
        db.memoryDao().insert(MemoryEntity(id, frameId, "text of $id", "note", "", MemoryTier.SHORT, 0.8, com.rokid.context.memory.db.ReviewState.OK, false, createdAt, derived, "text of $id"))
    suspend fun derivedFrom(memoryId: String, sourceId: String) = db.forgetDao().insertEdges(listOf(MemorySourceEntity(memoryId, sourceId)))
    suspend fun mark(id: String, atMs: Long) {
        val e = DeletionEpoch("i", 1, 0)
        db.canvasSlice3Dao().insertLabelFenced(CanvasLabelEntity(id, "MOMENT", "tap", "X", atMs, createdAt = 1), DaoFence(0, e, { 0 }, { e }))
    }
    suspend fun ask(id: String, citesFrame: String) = db.askDao().upsert(AskEntity(id, 1, "q", "a", "[\"frame:$citesFrame\"]", null, "test", false))
    /** A projection row set, written directly (so a test can make it stale or leave it empty). */
    suspend fun project(momentId: String, vararg parts: Pair<String, String>) = db.momentDao().replace(MomentEntity(momentId, 1, 1, "h", 1), parts.map { MomentPartEntity(momentId, it.first, it.second, "r", 1) })

    suspend fun frameIds() = db.frameDao().all().map { it.id }.sorted()
    suspend fun memoryIds() = db.forgetDao().memories(listOf("m1", "m2", "s1", "x1", "x2", "o1")).filter { it.reviewState != com.rokid.context.memory.db.ReviewState.INVALIDATED }.map { it.id }.sorted()
    suspend fun markIds() = db.canvasSlice3Dao().labels().map { it.labelId }.sorted()
    suspend fun outbox() = db.momentDao().pendingOutbox().map { it.target to it.payloadJson }
}
