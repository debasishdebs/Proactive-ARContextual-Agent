package com.rokid.context.memory.forget

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.CanvasLabelEntity
import com.rokid.context.memory.db.CanvasReplayEntity
import com.rokid.context.memory.db.CanvasSweepEntity
import com.rokid.context.memory.db.CanvasTraceEntity
import com.rokid.context.memory.db.CanvasTraceEventEntity
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.DeletionEpoch
import com.rokid.context.memory.db.TraceSource
import com.rokid.context.memory.db.TraceSources
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CanvasSlice3ForgetTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val dao = db.canvasSlice3Dao()
    private val epoch = DeletionEpoch("i", 1, 0)
    private val fence = DaoFence(0, epoch, { 0 }, { epoch })
    private val forget = CanvasSlice3Forget(db) { 9_000 }
    @After fun close() = db.close()

    private suspend fun trace(id: String, from: Long, to: Long) {
        dao.acceptTraceFenced(CanvasTraceEntity(id, "IMU", "COMPLETE", "{\"a\":1}", 0, installId = "i", deletionCounter = 1, startedAtMs = from, endedAtMs = to, createdAt = 1), fence)
        dao.appendEventsFenced(id, listOf(CanvasTraceEventEntity(id, 0, from, "CTX", "{}")), fence)
    }
    private suspend fun replayOf(id: String, trace: String) = dao.insertReplay(
        CanvasReplayEntity(id, null, "{}", "EXACT", "{}", sourcesJson = TraceSources.encode(listOf(TraceSource(trace, 0, 0, 1))), labelRev = dao.labelRev(), jobState = "DONE", fromMs = 0, toMs = 1, createdAt = 1))

    @Test fun rangeTombstonesOverlappingTracesDeletesMarksAndInvalidatesResults() = runBlocking {
        trace("in", 1_000, 2_000); trace("edge", 2_000, 3_000); trace("out", 5_000, 6_000)
        dao.insertLabelFenced(CanvasLabelEntity("l-in", "MOMENT", "tap", "MOMENT", 1_500, createdAt = 1), fence)
        dao.insertLabelFenced(CanvasLabelEntity("l-out", "MOMENT", "tap", "MOMENT", 5_500, createdAt = 1), fence)
        val rev = dao.labelRev()
        val r = forget.forgetRange(1_500, 2_000)
        assertEquals(setOf("in", "edge"), r.tombstonedTraceIds.toSet())
        assertEquals(1, r.labelsDeleted)
        assertTrue(dao.trace("in")!!.tombstoned); assertTrue(dao.trace("edge")!!.tombstoned); assertFalse(dao.trace("out")!!.tombstoned)
        assertEquals("events of a tombstoned trace are gone", 0, dao.eventCount("in"))
        assertEquals(1, dao.eventCount("out"))
        assertNull(dao.label("l-in")); assertNotNull(dao.label("l-out"))
        assertTrue(dao.labelRev() > rev)
    }

    @Test fun aResultThatReadATombstonedTraceIsInvalidatedWithItsResultDeleted() = runBlocking {
        trace("t1", 1_000, 2_000); replayOf("r1", "t1")
        forget.forgetRange(1_000, 2_000)
        assertTrue(dao.replay("r1")!!.invalidated); assertNull(dao.replay("r1")!!.resultJson)
    }

    @Test fun lateChunkForATombstonedTraceIsRejected() = runBlocking {
        trace("t1", 1_000, 2_000)
        forget.forgetRange(0, 10_000)
        val late = CanvasTraceEntity("t1", "IMU", "COMPLETE", "{\"late\":1}", 0, installId = "i", deletionCounter = 1, startedAtMs = 1_000, createdAt = 2)
        assertFalse(dao.acceptTraceFenced(late, fence))
        assertFalse(dao.appendEventsFenced("t1", listOf(CanvasTraceEventEntity("t1", 5, 1, "CTX", "{}")), fence))
        assertEquals("{}", dao.trace("t1")!!.manifestJson)
    }

    @Test fun labelRouteDeleteInvalidatesResults() = runBlocking {
        dao.insertLabelFenced(CanvasLabelEntity("l1", "MOMENT", "tap", "MOMENT", 5, createdAt = 1), fence)
        dao.insertReplay(CanvasReplayEntity("r1", null, "{}", "EXACT", "{\"recall\":1}", labelRev = dao.labelRev(), jobState = "DONE", createdAt = 1))
        assertTrue(dao.deleteLabel("l1"))
        assertTrue(dao.replay("r1")!!.invalidated); assertNull(dao.replay("r1")!!.resultJson)
        assertFalse("deleting nothing invalidates nothing", dao.deleteLabel("l1"))
    }

    @Test fun correctingALabelInvalidatesResultsToo() = runBlocking {
        dao.insertLabelFenced(CanvasLabelEntity("l1", "INTENT", "tap", "A", 5, createdAt = 1), fence)
        dao.insertReplay(CanvasReplayEntity("r1", null, "{}", "EXACT", "{}", labelRev = dao.labelRev(), jobState = "DONE", createdAt = 1))
        dao.correctLabel("l1", "B")
        assertTrue(dao.replay("r1")!!.invalidated)
    }

    @Test fun aReplayCannotCommitOverASourceOfAnOlderEpoch() = runBlocking {
        trace("t-old-0001", 1_000, 2_000) // recorded under epoch (i, 1)
        dao.insertReplay(CanvasReplayEntity("r1", null, "{}", "EXACT", null, sourcesJson = TraceSources.encode(listOf(TraceSource("t-old-0001", 0, 1_000, 2_000))), labelRev = dao.labelRev(), createdAt = 1))
        val e2 = DeletionEpoch("i", 2, 0) // a Delete all whose cleanup never ran
        val newer = DaoFence(0, e2, { 0 }, { e2 })
        assertFalse(dao.commitReplayFenced("r1", "{}", null, listOf(TraceSource("t-old-0001", 0, 1_000, 2_000)), dao.labelRev(), newer, 5))
    }

    @Test fun forgettingTwiceIsIdempotent() = runBlocking {
        trace("t1", 1_000, 2_000)
        forget.forgetRange(0, 10_000)
        val again = forget.forgetRange(0, 10_000)
        assertTrue("already tombstoned: nothing more to do", again.tombstonedTraceIds.isEmpty())
    }

    @Test fun anOpenEndedTraceOverlapsEverythingAfterItsStart() = runBlocking {
        dao.acceptTraceFenced(CanvasTraceEntity("open", "IMU", "PARTIAL", "{}", 0, installId = "i", deletionCounter = 1, startedAtMs = 1_000, endedAtMs = null, createdAt = 1), fence)
        assertEquals(listOf("open"), forget.forgetRange(50_000, 60_000).tombstonedTraceIds)
    }

    @Test fun aSweepOverlappingTheRangeIsInvalidatedToo() = runBlocking {
        dao.insertSweep(CanvasSweepEntity("sw", "{}", resultJson = "{}", jobState = "DONE", fromMs = 1_000, toMs = 2_000, createdAt = 1))
        forget.forgetRange(1_500, 1_600)
        assertTrue(dao.sweep("sw")!!.invalidated)
    }
}
