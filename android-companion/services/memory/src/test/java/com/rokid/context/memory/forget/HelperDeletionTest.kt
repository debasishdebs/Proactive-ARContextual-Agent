package com.rokid.context.memory.forget

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.canvas.HelperConfirmOutcome
import com.rokid.context.memory.canvas.HelperFence
import com.rokid.context.memory.db.CanvasHelperJobEntity
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.FrameSourceEntity
import com.rokid.context.memory.db.HelperJobFixture
import com.rokid.context.memory.db.HelperJobFixture.Companion.JOB_MS
import com.rokid.context.memory.db.HelperJobFixture.Companion.LEASE_MS
import com.rokid.context.memory.db.HelperJobFixture.Companion.QUEUE_WAIT
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * WP4 (core plan 4.1): the REAL deletion entry points against the WP3 job lifecycle, in-memory Room, no revision advanced by a test helper (those are WP3's tests).
 * Entry points, exactly: `forgetRangeTx` (a time-range Forget; it tombstones TRACES only, never a frame, and moves the frame-Forget fence), `ForgetService.forgetMoment(frameId)`
 * (it inserts the `forget_tombstone` row, deletes the frame, and itself calls `forgetRangeTx` for the time link), `ForgetService.forgetFacts(lockFrames)`, `expirePhotos`
 * and `WipeDao.wipeMemory()` (Delete all).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HelperDeletionTest {
    private val f = HelperJobFixture()
    private val db = f.db
    private val dao = f.dao
    private val service = ForgetService(db, ProposalRepository(db.proposalDao(), db.actionDao(), db)) { f.now }
    @After fun close() = f.close()

    private val types = listOf("py_exec")
    private suspend fun state(id: String) = dao.helperJob(id)!!.state
    private suspend fun cleanupIds() = dao.pendingCleanup(100).map { it.jobId }
    private suspend fun frame(id: String, ts: Long = 1) = db.frameDao().insertIgnore(FrameEntity(id, ts, "/$id.jpg", "rokid_glasses"))
    private suspend fun mem(id: String, frame: String?) =
        db.memoryDao().insert(MemoryEntity(id, frame, "text of $id", "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, 1, false, "text of $id"))
    /** The real range Forget, as `CanvasSlice3Forget.forgetRange` runs it. */
    private suspend fun forgetRange(from: Long = 0, to: Long = 10) = dao.forgetRangeTx(from, to, f.now)
    private suspend fun heartbeat(id: String, leaseGen: Long) = dao.heartbeatHelperJob(id, "h1", leaseGen, f.now, LEASE_MS, f.live)

    // ---- the fence moves, and the job dies, in the real Forget ----

    @Test fun forgetRacingResultCommit() = runBlocking {
        // (a) a leased job, a Forget, then the helper's result: refused and nothing stored.
        val a = f.leased("a")
        forgetRange()
        val ra = dao.commitHelperResultFenced("a", "h1", a.leaseGen, "rh-a", "{\"r\":\"a\"}", f.now, f.live)
        assertFalse(ra.ok); assertEquals("voided", ra.denial)
        assertNull(dao.helperJob("a")!!.resultJson); assertEquals("VOIDED", state("a"))
        // (b) the result was committed, the ack was lost, a Forget landed, the helper retries the SAME result: it must not be acked (DONE is terminal, only the live fence tells).
        val b = f.leased("b")
        assertTrue(dao.commitHelperResultFenced("b", "h1", b.leaseGen, "rh-b", "{\"r\":\"b\"}", f.now, f.live).ok)
        forgetRange()
        val rb = dao.commitHelperResultFenced("b", "h1", b.leaseGen, "rh-b", "{\"r\":\"b\"}", f.now, f.live)
        assertFalse("a retried commit after a Forget is never acked", rb.ok); assertFalse(rb.duplicate); assertEquals("voided", rb.denial)
        // (c) a runner that captured its fence before the Forget cannot insert afterwards (the revision is the only thing that tells a frame with no labels).
        val fence = f.fence()
        forgetRange()
        assertFalse(dao.insertHelperJobFenced(f.job("c"), fence, f.live)); assertNull(dao.helperJob("c"))
        assertTrue("the same runner with a fresh fence can insert", dao.insertHelperJobFenced(f.job("c"), f.fence(), f.live))
    }

    @Test fun forgetAfterInsertBeforePayloadReleaseVoidsJobAndEnqueuesCleanup() = runBlocking {
        val l = f.leased("j1")                       // inserted, confirmed, leased: the payload has not been released yet
        forgetRange()                                // the real forgetRangeTx
        val row = dao.helperJob("j1")!!
        assertEquals("VOIDED", row.state); assertNull("the payload is gone", row.payloadJson); assertEquals("[]", row.frameIdsJson); assertEquals("frame_forget", row.voidReason)
        assertEquals("one cleanup row for the job, written by the Forget itself", listOf("j1"), cleanupIds())
        val r = dao.releasePayload("j1", "h1", l.leaseGen, f.now, f.live)
        assertFalse(r.ok); assertNull(r.payload); assertEquals("voided", r.denial)
    }

    @Test fun heartbeatNeverAcksAfterDeletionCause() = runBlocking {
        val a = f.leased("a")
        assertTrue("sanity: a heartbeat is acked while nothing was deleted", heartbeat("a", a.leaseGen).ok)
        forgetRange()
        assertFalse("range Forget", heartbeat("a", a.leaseGen).ok)

        frame("f-b"); val b = f.leased("b")
        service.forgetMoment("f-b")
        assertFalse("ForgetService.forgetMoment", heartbeat("b", b.leaseGen).ok)

        val c = f.leased("c")
        db.wipeDao().wipeMemory()
        assertFalse("Delete all (the job row is gone: not_found)", heartbeat("c", c.leaseGen).ok)
        assertEquals("not_found", heartbeat("c", c.leaseGen).denial)
    }

    // ---- a tap after a deletion cause is refused (stale confirm), and the job is already dead before the tap ----

    @Test fun staleConfirmAfterForgetRefused() = runBlocking {
        val j = f.insert("j1")                       // AWAITING_CONFIRM, with a valid binding (the tag is verified by :phone)
        forgetRange()                                // real forgetRangeTx
        val row = dao.helperJob("j1")!!
        assertEquals("the Forget itself voids the job awaiting its tap, before any tap", "VOIDED", row.state); assertNull(row.payloadJson); assertEquals(listOf("j1"), cleanupIds())
        assertEquals(HelperConfirmOutcome.NOT_AWAITING, dao.confirmJobFenced("j1", f.binding(j), f.live, f.now))
        assertEquals("VOIDED", state("j1")); assertNull(dao.leaseHelperJob(types, "h1", 7, f.now, LEASE_MS, JOB_MS, QUEUE_WAIT, f.live)); assertNull(dao.helperJob("j1")!!.payloadJson)
    }

    @Test fun staleConfirmAfterForgetMomentRefused() = runBlocking {
        frame("f-j1"); val j = f.insert("j1")
        val r = service.forgetMoment("f-j1")
        assertTrue("precondition: the frame existed and forgetMoment found something", r.framesToDelete.isNotEmpty())
        assertEquals("VOIDED", state("j1")); assertNull(dao.helperJob("j1")!!.payloadJson)
        assertNotEquals(HelperConfirmOutcome.CONFIRMED, dao.confirmJobFenced("j1", f.binding(j), f.live, f.now))
        assertEquals("VOIDED", state("j1"))
    }

    @Test fun staleConfirmAfterDeleteAllRefused() = runBlocking {
        val j = f.insert("j1")
        db.wipeDao().wipeMemory()
        assertEquals(HelperConfirmOutcome.NOT_FOUND, dao.confirmJobFenced("j1", f.binding(j), f.live, f.now))
        assertNull(dao.helperJob("j1"))
    }

    // ---- the frame entry points (ForgetService) ----

    @Test fun forgetMomentRealEntryPointVoidsEveryOpenJobEnqueuesCleanupAndNullsDoneResults() = runBlocking {
        frame("f-open"); frame("f-done"); frame("f-other", ts = 1_000_000)
        f.insert("open")                                                                     // AWAITING_CONFIRM, carries f-open
        val done = f.leased("done")                                                          // carries f-done
        assertTrue(dao.commitHelperResultFenced("done", "h1", done.leaseGen, "rh", "{\"secret\":1}", f.now, f.live).ok)
        val rev0 = dao.frameForgetRev()
        val r = service.forgetMoment("f-done")
        assertTrue("precondition: a real frame was forgotten", r.framesToDelete.isNotEmpty())
        assertEquals("the real entry point inserted the tombstone", 1, db.canvasDao().tombstonedAmong(listOf("f-done")))
        assertTrue("frame_forget_rev moved", dao.frameForgetRev() > rev0)
        assertEquals("a Forget of any frame voids the in-flight jobs (the fence is global)", "VOIDED", state("open"))
        val d = dao.helperJob("done")!!
        assertEquals("a finished job stays DONE but its result and frame ids are gone", "DONE", d.state); assertNull(d.resultJson); assertEquals("[]", d.frameIdsJson)
        assertTrue("cleanup rows for the voided job and the finished one", cleanupIds().containsAll(listOf("open", "done")))
        assertEquals("the unrelated frame row is untouched", 1, db.frameDao().byIds(listOf("f-other")).size)
    }

    @Test fun doneJobOfAnotherFrameKeepsItsResult() = runBlocking {
        val a = f.leased("a"); val b = f.leased("b", helper = "h2")
        assertTrue(dao.commitHelperResultFenced("a", "h1", a.leaseGen, "rha", "{\"a\":1}", f.now, f.live).ok)
        assertTrue(dao.commitHelperResultFenced("b", "h2", b.leaseGen, "rhb", "{\"b\":1}", f.now, f.live).ok)
        service.forgetMoment("f-a")
        assertEquals("only the result that carried the forgotten frame is nulled", "{\"b\":1}", dao.helperJob("b")!!.resultJson)
    }

    @Test fun forgetMomentOfAnIdWithNoRowsStillMovesTheFenceAndVoids() = runBlocking {
        val j = f.insert("j1", createdAt = f.now)
        val rev0 = dao.frameForgetRev()
        service.forgetMoment("f-j1")                  // no frame, no memory: only the orphan cleanup runs, there is no time link
        assertTrue(dao.frameForgetRev() > rev0); assertEquals("VOIDED", state("j1"))
        assertNotEquals(HelperConfirmOutcome.CONFIRMED, dao.confirmJobFenced("j1", f.binding(j), f.live, f.now))
    }

    @Test fun forgetFactsLockingAFrameMovesTheFenceAndVoids() = runBlocking {
        frame("f-j1"); mem("m1", null); db.frameSourceDao().insertAll(listOf(FrameSourceEntity("m1", "f-j1", null, "FACT")))
        f.insert("j1"); val rev0 = dao.frameForgetRev()
        service.forgetFacts(listOf("m1"), lockFrames = true)
        assertTrue(dao.frameForgetRev() > rev0); assertEquals("VOIDED", state("j1")); assertEquals(listOf("j1"), cleanupIds())
    }

    @Test fun retentionPhotoExpiryMovesTheFenceAndVoids() = runBlocking {
        frame("f-j1"); mem("m1", "f-j1"); f.insert("j1"); val rev0 = dao.frameForgetRev()
        service.expirePhotos(listOf("f-j1"))
        assertTrue(dao.frameForgetRev() > rev0); assertEquals("VOIDED", state("j1")); assertEquals(listOf("j1"), cleanupIds())
    }

    // ---- Delete all ----

    @Test fun deleteAllKeepsTheCleanupTableAndAddsTheWildcardRow() = runBlocking {
        f.insert("j1"); dao.cancelHelperJob("j1", f.now)           // one cleanup row exists before the wipe
        f.insert("j2")
        db.wipeDao().wipeMemory()
        assertEquals("the job rows are gone", 0, dao.openHelperJobs().size); assertNull(dao.helperJob("j2"))
        val ids = cleanupIds()
        assertTrue("the row written before the wipe survives", "j1" in ids)
        assertEquals("exactly one wildcard row tells every device-side copy to discard", 1, ids.count { it == "*" })
        assertEquals("delete_all", dao.pendingCleanup(100).first { it.jobId == "*" }.reason)
    }

    // ---- restart ----

    @Test fun cleanupSurvivesRestart() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.deleteDatabase(DatabaseFactory.NAME)
        val first = DatabaseFactory.build(ctx, encrypted = false) { error("plain") }
        val fence = HelperFence(5, f.epoch, first.canvasSlice3Dao().frameForgetRev())
        assertTrue(first.canvasSlice3Dao().insertHelperJobFenced(f.job("open"), fence, f.live))
        assertTrue(first.canvasSlice3Dao().insertHelperJobFenced(f.job("gone"), fence, f.live))
        first.canvasSlice3Dao().forgetRangeTx(0, 10, f.now)
        first.close()
        val second = DatabaseFactory.build(ctx, encrypted = false) { error("plain") }
        try {
            val s = second.canvasSlice3Dao()
            assertEquals("the persisted cleanup commands are still there after the process restart", listOf("gone", "open"), s.pendingCleanup(10).map { it.jobId }.sorted())
            assertEquals(0, s.markRunningAborted(f.now)) // nothing is left open, and recovery must not touch the cleanup rows
            assertEquals(2, s.pendingCleanup(10).size)
        } finally { second.close(); ctx.deleteDatabase(DatabaseFactory.NAME) }
    }

    @Test fun restartAbortsAnAwaitingJobAndEnqueuesCleanup() = runBlocking {
        val j = f.insert("j1")
        assertEquals(1, dao.markRunningAborted(f.now))
        assertEquals("ABORTED", state("j1")); assertNull(dao.helperJob("j1")!!.payloadJson); assertEquals(listOf("j1"), cleanupIds())
        assertNotEquals(HelperConfirmOutcome.CONFIRMED, dao.confirmJobFenced("j1", f.binding(j), f.live, f.now))
    }
}
