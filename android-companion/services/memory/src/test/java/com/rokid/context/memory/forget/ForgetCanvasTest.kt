package com.rokid.context.memory.forget

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.db.CanvasRunItemEntity
import com.rokid.context.memory.db.CanvasScoreEntity
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.FrameSourceEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Spec §7: Forget deletes the canvas outputs of the forgotten photos, in the same transaction as the tombstone. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ForgetCanvasTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val service = ForgetService(db, ProposalRepository(db.proposalDao(), db.actionDao(), db)) { 1_000 }
    private val canvas = db.canvasDao()
    @After fun close() = db.close()

    private suspend fun frame(id: String) = db.frameDao().insertIgnore(FrameEntity(id, 1, "/$id.jpg", "rokid_glasses"))
    private suspend fun mem(id: String, frame: String?) =
        db.memoryDao().insert(MemoryEntity(id, frame, "text of $id", "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, 1, false, "text of $id"))
    private suspend fun canvasRows(run: String, frame: String) {
        canvas.upsertItem(CanvasRunItemEntity(run, frame, "n", "OK", null, "{}", null, null, null, null, null, null, null))
        canvas.upsertScore(CanvasScoreEntity(run, frame, "n", 1, "", 1))
    }
    private suspend fun left(run: String, frame: String) = canvas.items(run).count { it.frameId == frame } + canvas.scores(run).count { it.frameId == frame }

    @Test fun forgetMomentDeletesCanvasItemsAndScores() = runBlocking {
        frame("f1"); mem("m1", "f1"); canvasRows("r", "f1")
        service.forgetMoment("f1")
        assertEquals(0, left("r", "f1"))
    }

    @Test fun forgetFactsWithLockDeletesCanvasForLockedFrames() = runBlocking {
        frame("f1"); frame("f2"); mem("A", null); db.frameSourceDao().insertAll(listOf(FrameSourceEntity("A", "f1", null, "FACT"), FrameSourceEntity("A", "f2", null, "FACT")))
        canvasRows("r", "f1"); canvasRows("r", "f2")
        service.forgetFacts(listOf("A"), lockFrames = true)
        assertEquals(0, left("r", "f1")); assertEquals(0, left("r", "f2"))
    }

    @Test fun otherFramesUntouched() = runBlocking {
        frame("f1"); frame("f2"); mem("m1", "f1"); mem("m2", "f2"); canvasRows("r", "f1"); canvasRows("r", "f2")
        service.forgetMoment("f1")
        assertEquals(2, left("r", "f2"))
        service.forgetFacts(listOf("m2"), lockFrames = false)
        assertEquals("no lock, no canvas delete", 2, left("r", "f2"))
    }

    @Test fun orphanCanvasRowsOfAnIdWithNothingElseAreDropped() = runBlocking {
        canvasRows("r", "ghost"); canvasRows("r", "other")
        service.forgetMoment("ghost")
        assertEquals(0, left("r", "ghost")); assertEquals(2, left("r", "other"))
    }
}
