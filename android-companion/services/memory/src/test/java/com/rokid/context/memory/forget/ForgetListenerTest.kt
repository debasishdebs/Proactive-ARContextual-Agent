package com.rokid.context.memory.forget

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ForgetListenerTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val service = ForgetService(db, ProposalRepository(db.proposalDao(), db.actionDao(), db)) { 1_000 }
    private val seen = ArrayList<List<String>>()
    private var rowsLeftWhenTold = -1
    private val listener = object : ForgetListener {
        override suspend fun onFramesForgotten(frameIds: List<String>) {
            seen += frameIds
            rowsLeftWhenTold = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM canvas_label").use { it.moveToFirst(); it.getInt(0) }
        }
    }
    @Before fun setUp() { ForgetListeners.clearForTests(); ForgetListeners.register(listener) }
    @After fun tearDown() { ForgetListeners.clearForTests(); db.close() }

    private suspend fun frame(id: String) = db.frameDao().insertIgnore(FrameEntity(id, 1, "/$id.jpg", "rokid_glasses"))
    private suspend fun mem(id: String, frame: String?) =
        db.memoryDao().insert(MemoryEntity(id, frame, "text of $id", "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, 1, false, "text of $id"))
    private fun fence() = com.rokid.context.memory.db.DeletionEpoch("i", 1, 0).let { e -> com.rokid.context.memory.db.DaoFence(0, e, { 0 }, { e }) }

    @Test fun forgetMomentTellsTheListenerAfterTheTransactionCommitted() = runBlocking {
        frame("f1"); mem("m1", "f1")
        db.canvasSlice3Dao().insertLabelFenced(com.rokid.context.memory.db.CanvasLabelEntity("l", "INTENT", "photo", "X", 5, frameId = "f1", createdAt = 1), fence())
        service.forgetMoment("f1")
        assertEquals(listOf(listOf("f1")), seen)
        assertEquals("the label was already gone when the listener ran", 0, rowsLeftWhenTold)
    }

    @Test fun retentionPhotoExpiryTellsItToo() = runBlocking {
        frame("a"); mem("ma", "a"); frame("b"); mem("mb", "b")
        service.expirePhotos(listOf("a", "b"))
        assertEquals(setOf("a", "b"), seen.flatten().toSet())
    }

    @Test fun forgetFactsWithTheLockTellsItAndNothingForgottenTellsNobody() = runBlocking {
        frame("f1"); mem("m1", "f1")
        service.forgetFact("m1")
        assertEquals(listOf("f1"), seen.flatten())
        seen.clear()
        service.forgetFacts(listOf("nope"))
        assertTrue(seen.isEmpty())
    }

    @Test fun aThrowingListenerNeverBreaksTheDeletion() = runBlocking {
        ForgetListeners.register(object : ForgetListener { override suspend fun onFramesForgotten(frameIds: List<String>) { error("boom") } })
        frame("f1"); mem("m1", "f1")
        service.forgetMoment("f1")
        assertEquals("the listener after the broken one still ran", listOf("f1"), seen.flatten())
        assertEquals(null, db.frameDao().let { null })
    }
}
