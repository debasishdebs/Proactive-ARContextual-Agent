package com.rokid.context.canvas.moment

import com.rokid.context.memory.db.AskEntity
import com.rokid.context.memory.db.CanvasLabelEntity
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.DeletionEpoch
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.MomentPartEntity
import com.rokid.context.memory.db.ReviewState

/** Seeds the authoritative stores of a test database. Times are phone-clock milliseconds. */
class MomentFixture(val db: ContextDatabase) {
    companion object { const val SEC = 1_761_000_000L; const val MS = SEC * 1000 }

    suspend fun frame(id: String, ts: Long) = db.frameDao().insertIgnore(FrameEntity(id, ts, "/$id.jpg", "rokid_glasses", phash = "p$id"))
    suspend fun memory(id: String, frameId: String?, derived: Boolean = false, createdAt: Long = 1) =
        db.memoryDao().insert(MemoryEntity(id, frameId, "text of $id", "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, createdAt, derived, "text of $id"))
    suspend fun mark(id: String, atMs: Long) {
        val e = DeletionEpoch("i", 1, 0)
        db.canvasSlice3Dao().insertLabelFenced(CanvasLabelEntity(id, "MOMENT", "tap", "X", atMs, createdAt = 1), DaoFence(0, e, { 0 }, { e }))
    }
    suspend fun ask(id: String, citesFrame: String) = db.askDao().upsert(AskEntity(id, 1, "q", "a", "[\"frame:$citesFrame\"]", null, "test", false))

    /** The whole projection as comparable text, `builtAt` excluded. */
    suspend fun snapshot(): List<String> {
        val d = db.momentDao()
        return d.allMoments().flatMap { m ->
            listOf("M|${m.momentId}|${m.buildRev}|${m.contentRev}|${m.contentHash}") + d.parts(m.momentId).map { p: MomentPartEntity -> "P|${p.momentId}|${p.kind}|${p.refId}|${p.partRev}|${p.partTimeMs}" }
        }
    }
}
