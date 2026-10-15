package com.rokid.context.memory.forget

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.FrameSourceEntity
import com.rokid.context.memory.db.FrameStateEntity
import com.rokid.context.memory.db.FrameVectorEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Keyframe capture: a photo is cited by `memory.frameId` OR a `frame_source` row; Forget and every sweep honour both. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ForgetFrameSourceTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val service = ForgetService(db, ProposalRepository(db.proposalDao(), db.actionDao(), db)) { 1_000 }
    private val dao = db.forgetDao()
    @After fun close() = db.close()

    private suspend fun frame(id: String) = db.frameDao().insertIgnore(FrameEntity(id, 1, "/$id.jpg", "rokid_glasses"))
    /** A batch memory: no memory.frameId, it cites its photos only through frame_source. */
    private suspend fun batchMem(id: String, vararg frames: String) {
        db.memoryDao().insert(MemoryEntity(id, null, "text of $id", "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, 1, false, "text of $id"))
        db.frameSourceDao().insertAll(frames.map { FrameSourceEntity(id, it, null, "FACT") })
    }

    @Test fun forgetSharedFrameKeepsOtherMemory() = runBlocking {
        listOf("f1", "f2", "f3").forEach { frame(it) }
        batchMem("A", "f1", "f2"); batchMem("B", "f1", "f3")
        db.frameVectorDao().upsert(listOf(FrameVectorEntity("f1", "s", byteArrayOf(1)), FrameVectorEntity("f2", "s", byteArrayOf(2))))
        val r = service.forgetFact("A")
        assertEquals(setOf("A"), r.forgotten)
        // every frame A cited is tombstoned at once, even f1 which B still cites
        assertEquals(setOf("f1", "f2"), dao.tombstoned(listOf("f1", "f2", "f3")).toSet())
        assertTrue(db.frameSourceDao().forMemory("A").isEmpty())
        assertEquals(listOf("B"), db.frameSourceDao().memoriesForFrame("f1"))
        assertNotNull(db.memoryViewDao().byId("B"))
        // the photo stays for B; f2 is cited by nobody now
        assertEquals(listOf("/f2.jpg"), r.framesToDelete)
        assertNotNull(dao.frame("f1"))
        assertEquals(1, db.frameVectorDao().countForSpace("s")) // f2's image vector went with its photo, f1's stays
    }

    @Test fun forgottenFactNotReextracted() = runBlocking {
        frame("f1"); batchMem("A", "f1")
        db.frameStateDao().insertIgnore(listOf(FrameStateEntity("f1", "FAILED", 1, "boom", null, null, 0))) // claimable, were it not forgotten
        service.forgetFact("A")
        assertEquals(listOf("f1"), dao.tombstoned(listOf("f1")))
        // review B: the frame must not be claimable again, whatever state it was in
        val states = db.frameStateDao()
        assertTrue(states.claim(listOf("f1"), "c1", 10, 0).isEmpty())
        assertTrue(states.recoverable(10, 0, 10).none { it.frameId == "f1" })
    }

    @Test fun forgottenPendingFrameCannotBeClaimed() = runBlocking {
        frame("f2"); batchMem("B", "f2")
        db.frameStateDao().insertIgnore(listOf(FrameStateEntity("f2", "PENDING", 0, null, null, null, 0)))
        service.forgetFact("B")
        assertEquals(listOf("f2"), dao.tombstoned(listOf("f2")))
        assertNotNull("the row stays with its tombstone", dao.frame("f2"))
        assertTrue(db.frameStateDao().recoverable(10, 0, 10).none { it.frameId == "f2" })
        assertTrue(db.frameStateDao().claim(listOf("f2"), "c1", 10, 0).isEmpty())
    }

    /** Review B M-6: retention never forgets a frame the pipeline still has to extract. */
    @Test fun retentionKeepsFramesNotYetExtracted() = runBlocking {
        listOf("p", "e", "x", "fl", "ff", "none").forEach { frame(it) }
        db.frameStateDao().insertIgnore(listOf(
            FrameStateEntity("p", "PENDING", 0, null, null, null, 0), FrameStateEntity("e", "EXTRACTED", 1, null, null, null, 0),
            FrameStateEntity("x", "EXTRACTING", 1, null, 1L, "c", 0), FrameStateEntity("fl", "FAILED", 1, "e", null, null, 0),
            FrameStateEntity("ff", "FAILED_FINAL", 5, "e", null, null, 0),
        ))
        assertEquals(setOf("e", "ff", "none"), dao.orphanFramesBefore(Long.MAX_VALUE, 10).toSet())
    }

    @Test fun forgetLastCiterDeletesPhoto() = runBlocking {
        frame("f1"); batchMem("A", "f1"); batchMem("B", "f1")
        assertTrue(service.forgetFact("A").framesToDelete.isEmpty())
        assertEquals(listOf("/f1.jpg"), service.forgetFact("B").framesToDelete)
    }

    @Test fun sweepAfterRestartKeepsSharedPhoto() = runBlocking {
        frame("f1"); batchMem("A", "f1"); batchMem("B", "f1")
        service.forgetFact("A")
        // B cites f1 ONLY through frame_source (batchMem sets no memory.frameId): drop the frame row, as if it were rowless,
        // and the tombstone prune must still keep it because of that citation alone.
        dao.deleteFrame("f1")
        assertEquals(0, dao.pruneTombstones(Long.MAX_VALUE, listOf("")))
        assertEquals(listOf("f1"), dao.allTombstones())
        frame("f1")
        // "restart": a fresh pass over the same data runs the sweep queries
        assertEquals(emptyList<String>(), dao.orphanTombstonedFramePaths())
        assertEquals(0, dao.pruneTombstones(Long.MAX_VALUE, listOf("")))
        assertEquals(listOf("f1"), dao.allTombstones())
        assertNotNull(dao.frame("f1"))
        service.forgetFact("B") // last citer gone: now it is an orphan
        assertEquals(listOf("/f1.jpg"), dao.orphanTombstonedFramePaths())
    }

    @Test fun orphanQueriesCheckFrameSource() = runBlocking {
        frame("f1"); frame("f2"); batchMem("B", "f1")
        db.forgetDao().insertTombstone(com.rokid.context.memory.db.ForgetTombstoneEntity("f1", 1))
        // retention: only the uncited frame is an orphan
        assertEquals(listOf("f2"), dao.orphanFramesBefore(Long.MAX_VALUE, 10))
        assertEquals(1, dao.remainingRefs("f1")); assertEquals(0, dao.remainingRefs("f2"))
        assertEquals(listOf("B"), dao.memoryIdsOfFrame("f1"))
        // a tombstone with no frame row but a frame_source citation is not pruned
        dao.deleteFrame("f1")
        assertEquals(0, dao.pruneTombstones(Long.MAX_VALUE, listOf("")))
        // a real rowless tombstone: f1's row is gone, so it is returned; f2 (row present, no tombstone) is not
        assertEquals(listOf("f1"), dao.rowlessTombstones(listOf("f1", "f2")))
        // once its last citation goes too, the prune drops it
        db.frameSourceDao().deleteForMemory("B")
        assertEquals(1, dao.pruneTombstones(Long.MAX_VALUE, listOf("")))
    }
}
