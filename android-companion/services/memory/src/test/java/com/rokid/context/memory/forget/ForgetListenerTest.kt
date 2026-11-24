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
import org.junit.Assert.assertNull
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

    @Test fun aForgottenPhotoTombstonesTheTracesAndDeletesTheMarksAroundItAndTellsTheListener() = runBlocking {
        val photoAt = 500_000L
        db.frameDao().insertIgnore(FrameEntity("p1", photoAt, "/p1.jpg", "rokid_glasses")); mem("mp1", "p1")
        val dao = db.canvasSlice3Dao()
        dao.acceptTraceFenced(com.rokid.context.memory.db.CanvasTraceEntity("trace-near-01", "IMU", "COMPLETE", "{}", 0, installId = "i", deletionCounter = 1, startedAtMs = photoAt - 3_000, endedAtMs = photoAt + 3_000, createdAt = 1), fence())
        dao.acceptTraceFenced(com.rokid.context.memory.db.CanvasTraceEntity("trace-far-001", "IMU", "COMPLETE", "{}", 0, installId = "i", deletionCounter = 1, startedAtMs = 5_000_000, endedAtMs = 5_001_000, createdAt = 1), fence())
        dao.insertLabelFenced(com.rokid.context.memory.db.CanvasLabelEntity("mk-near", "MOMENT", "tap", "MOMENT", photoAt + 4_000, createdAt = 1), fence()) // a mark carries no frame id
        dao.insertLabelFenced(com.rokid.context.memory.db.CanvasLabelEntity("mk-far", "MOMENT", "tap", "MOMENT", 5_000_500, createdAt = 1), fence())
        val ranges = ArrayList<Triple<Long, Long, List<String>>>()
        ForgetListeners.register(object : ForgetListener { override suspend fun onRangeForgotten(fromMs: Long, toMs: Long, traceIds: List<String>) { ranges += Triple(fromMs, toMs, traceIds) } })
        service.forgetMoment("p1")
        assertNull(dao.label("mk-near")); assertTrue(dao.label("mk-far") != null)
        assertTrue(dao.trace("trace-near-01")!!.tombstoned); assertTrue(!dao.trace("trace-far-001")!!.tombstoned)
        assertEquals(listOf(Triple(photoAt - 10_000, photoAt + 10_000, listOf("trace-near-01"))), ranges)
        assertTrue("a mark redelivered for that moment is refused", !dao.insertLabelFenced(com.rokid.context.memory.db.CanvasLabelEntity("mk-near", "MOMENT", "tap", "MOMENT", photoAt + 4_000, createdAt = 2), fence()))
    }

    @Test fun retentionExpiryOfAPhotoDoesTheSame() = runBlocking {
        db.frameDao().insertIgnore(FrameEntity("p2", 700_000, "/p2.jpg", "rokid_glasses")); mem("mp2", "p2")
        db.canvasSlice3Dao().insertLabelFenced(com.rokid.context.memory.db.CanvasLabelEntity("mk", "MOMENT", "tap", "MOMENT", 701_000, createdAt = 1), fence())
        service.expirePhotos(listOf("p2"))
        assertNull(db.canvasSlice3Dao().label("mk"))
    }

    // ---- B1: retention expiry has its own callbacks, defaulting to the Forget ones ----

    @Test fun existingListeners_stillGetFramesForgottenAndRangeForgottenOnExpiry() = runBlocking {
        val ranges = ArrayList<Pair<Long, Long>>()
        ForgetListeners.register(object : ForgetListener { override suspend fun onRangeForgotten(fromMs: Long, toMs: Long, traceIds: List<String>) { ranges += fromMs to toMs } })
        frame("e1"); mem("me1", "e1")
        service.expirePhotos(listOf("e1"))
        assertEquals("the default of onPhotosExpired is onFramesForgotten", listOf("e1"), seen.flatten())
        assertEquals("the default of onRangeExpired is onRangeForgotten", 1, ranges.size)
    }

    @Test fun aListenerThatOverridesTheExpiryCallbacksIsToldAboutExpiryOnlyThere() = runBlocking {
        val forgotten = ArrayList<String>(); val expired = ArrayList<String>(); val rangeForgotten = ArrayList<Long>(); val rangeExpired = ArrayList<Long>()
        ForgetListeners.register(object : ForgetListener {
            override suspend fun onFramesForgotten(frameIds: List<String>) { forgotten += frameIds }
            override suspend fun onPhotosExpired(frameIds: List<String>) { expired += frameIds }
            override suspend fun onRangeForgotten(fromMs: Long, toMs: Long, traceIds: List<String>) { rangeForgotten += fromMs }
            override suspend fun onRangeExpired(fromMs: Long, toMs: Long, traceIds: List<String>) { rangeExpired += fromMs }
        })
        frame("x1"); mem("mx1", "x1"); frame("x2"); mem("mx2", "x2")
        service.expirePhotos(listOf("x1"))
        assertEquals(listOf("x1"), expired); assertTrue(forgotten.isEmpty()); assertTrue(rangeForgotten.isEmpty()); assertEquals(1, rangeExpired.size)
        expired.clear(); rangeExpired.clear()
        service.forgetMoment("x2")
        assertEquals(listOf("x2"), forgotten); assertTrue(expired.isEmpty()); assertEquals(1, rangeForgotten.size); assertTrue(rangeExpired.isEmpty())
    }
}
