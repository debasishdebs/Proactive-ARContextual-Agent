package com.rokid.context.memory.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.HelperJobFixture.Companion.JOB_MS
import com.rokid.context.memory.db.HelperJobFixture.Companion.LEASE_MS
import com.rokid.context.memory.db.HelperJobFixture.Companion.QUEUE_WAIT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * WP3 (Room v8): the fenced job lifecycle of `CanvasSlice3Dao`: insert, lease (one per helper), payload release, heartbeat, result commit, fail, cancel, void, expire.
 * Every fence predicate is tested by advancing the revision DIRECTLY (frame_forget_rev, the deletion epoch, the wipe generation); no deletion hook is called, so a
 * green test here cannot come from a Forget having voided the job first.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HelperJobDaoTest {
    private val f = HelperJobFixture()
    private val dao = f.dao
    @After fun close() = f.close()

    private val types = listOf("py_exec")
    private suspend fun lease(helper: String = "h1", sessionGen: Long = 7) = dao.leaseHelperJob(types, helper, sessionGen, f.now, LEASE_MS, JOB_MS, f.live)
    private suspend fun state(id: String) = dao.helperJob(id)!!.state

    // ---- insert ----

    @Test fun insertFencedStoresAwaitingConfirmWithTheCapturedFence() = runBlocking {
        val fence = f.fence()
        assertTrue(dao.insertHelperJobFenced(f.job("j1").copy(state = "QUEUED"), fence, f.live))
        val row = dao.helperJob("j1")!!
        assertEquals("a py_exec job is inserted awaiting the tap, whatever state the caller set", "AWAITING_CONFIRM", row.state)
        assertEquals("{\"p\":\"j1\"}", row.payloadJson); assertEquals("hash-j1", row.bundleHash)
        assertEquals(5L, row.wipeGen); assertEquals("install-a", row.installId); assertEquals(3L, row.deletionCounter); assertEquals(0L, row.frameForgetRev)
        assertEquals(0L, row.leaseGen); assertNull(row.confirmedAtMs); assertNull(row.resultJson)
    }

    @Test fun insertFencedRefusedAfterWipeGenerationMoved() = runBlocking {
        val fence = f.fence(); f.wipe()
        assertFalse(dao.insertHelperJobFenced(f.job("j1"), fence, f.live)); assertNull(dao.helperJob("j1"))
    }

    @Test fun insertFencedRefusedAfterDeletionEpochMoved() = runBlocking {
        val fence = f.fence(); f.newEpoch()
        assertFalse(dao.insertHelperJobFenced(f.job("j1"), fence, f.live)); assertNull(dao.helperJob("j1"))
    }

    @Test fun forgetBetweenRunnerPrecheckAndHelperInsertRefusesPayload() = runBlocking {
        // A frame with no labels and no trace sources: nothing but the revision counter can tell the insert that a Forget landed after the runner captured its fence.
        val fence = f.fence()           // captured before the inputs load
        f.bumpRev()                     // the frame Forget, reduced to the counter move (no hook called)
        assertFalse("the insert refuses", dao.insertHelperJobFenced(f.job("j1"), fence, f.live))
        assertNull("no row holds the payload", dao.helperJob("j1"))
        assertTrue(dao.awaitingConfirmJobs().isEmpty())
    }

    @Test fun insertOfAnExistingJobIdIsRefusedAndKeepsTheFirstPayload() = runBlocking {
        f.insert("j1")
        assertFalse(dao.insertHelperJobFenced(f.job("j1", payload = "{\"other\":1}"), f.fence(), f.live))
        assertEquals("{\"p\":\"j1\"}", dao.helperJob("j1")!!.payloadJson)
    }

    @Test fun insertWithoutConfirmWindowOrPayloadOrHashIsRefused() = runBlocking {
        val fence = f.fence()
        assertFalse(dao.insertHelperJobFenced(f.job("a").copy(confirmExpiresAtMs = null), fence, f.live))
        assertFalse(dao.insertHelperJobFenced(f.job("b").copy(payloadJson = null), fence, f.live))
        assertFalse(dao.insertHelperJobFenced(f.job("c").copy(bundleHash = null), fence, f.live))
        assertTrue(dao.awaitingConfirmJobs().isEmpty())
    }

    @Test fun payloadIsWriteOnceAndOnlyEverNulled() = runBlocking {
        val original = "{\"p\":\"j1\"}"
        val leased = f.leased("j1")
        assertEquals(original, dao.releasePayload("j1", "h1", leased.leaseGen, f.now, f.live).payload)
        assertTrue(dao.heartbeatHelperJob("j1", "h1", leased.leaseGen, f.now + 1_000, LEASE_MS, f.live).ok)
        assertEquals("heartbeat does not touch the payload", original, dao.helperJob("j1")!!.payloadJson)
        assertTrue(dao.failHelperJob("j1", "h1", leased.leaseGen, "killed", f.now + 2_000))
        assertNull("a terminal job keeps no payload", dao.helperJob("j1")!!.payloadJson)
    }

    // ---- lease ----

    @Test fun leaseTakesTheOldestConfirmedJobAndReturnsMetadataOnly() = runBlocking {
        f.insertConfirmed("late", createdAt = f.now + 10); f.insertConfirmed("early", createdAt = f.now)
        val l = lease()!!
        assertEquals("early", l.jobId); assertEquals("py_exec", l.jobType); assertEquals(1L, l.leaseGen)
        assertEquals(f.now + LEASE_MS, l.leaseUntilMs); assertEquals(f.now + JOB_MS, l.deadlineMs); assertEquals("hash-early", l.bundleHash)
        assertEquals("{\"p\":\"early\"}".toByteArray().size, l.payloadBytes)
        val row = dao.helperJob("early")!!
        assertEquals("LEASED", row.state); assertEquals("h1", row.leaseOwner); assertEquals(f.now, row.lastHeartbeatMs)
    }

    @Test fun concurrentLeaseGivesEachJobToExactlyOneHelper() = runBlocking(Dispatchers.IO) {
        (1..5).forEach { f.insertConfirmed("j$it", createdAt = f.now + it) }
        val got = (1..20).map { n -> async { lease("helper-$n") } }.awaitAll().filterNotNull()
        assertEquals("five jobs, five leases", 5, got.size)
        assertEquals("every job leased once", 5, got.map { it.jobId }.toSet().size)
    }

    @Test fun concurrentLeasesBySameHelperGrantExactlyOne() = runBlocking(Dispatchers.IO) {
        (1..5).forEach { f.insertConfirmed("j$it", createdAt = f.now + it) }
        val got = (1..20).map { async { lease("same-helper") } }.awaitAll().filterNotNull()
        assertEquals("maxActiveJobs = 1 for one helper", 1, got.size)
        assertEquals("four jobs stay queued", 4, dao.availableHelperJobs(types, 7).size)
    }

    @Test fun sameHelperLeasesTheNextJobAfterTheFirstEnds() = runBlocking {
        f.insertConfirmed("a", createdAt = f.now); f.insertConfirmed("b", createdAt = f.now + 1)
        val a = lease()!!
        assertNull("second lease while the first is live", lease())
        assertTrue(dao.failHelperJob("a", "h1", a.leaseGen, "timeout", f.now))
        assertEquals("b", lease()!!.jobId)
    }

    @Test fun leaseSkipsAJobOfAnotherSessionGeneration() = runBlocking {
        f.insertConfirmed("old", sessionGen = 6)
        assertNull(lease(sessionGen = 7)); assertEquals("QUEUED", state("old"))
        assertEquals("old", lease(sessionGen = 6)!!.jobId)
    }

    @Test fun leaseSkipsOtherJobTypes() = runBlocking {
        f.insertConfirmed("j1")
        assertNull(dao.leaseHelperJob(listOf("tool_build"), "h1", 7, f.now, LEASE_MS, JOB_MS, f.live))
    }

    @Test fun leaseSkipsAndVoidsAJobWhoseFrameForgetRevisionMoved() = runBlocking {
        f.insertConfirmed("stale"); f.bumpRev()
        assertNull(lease())
        assertEquals("fail closed: the job is voided, not left queued", "VOIDED", state("stale"))
        assertNull(dao.helperJob("stale")!!.payloadJson)
        assertEquals(1, dao.pendingCleanup(10).count { it.jobId == "stale" })
    }

    @Test fun leaseSkipsAJobWhoseWipeGenerationOrEpochMoved() = runBlocking {
        f.insertConfirmed("w"); f.wipe()
        assertNull(lease()); assertEquals("VOIDED", state("w"))
        f.insertConfirmed("e"); f.newEpoch()
        assertNull(lease()); assertEquals("VOIDED", state("e"))
    }

    // ---- payload release ----

    @Test fun releaseOnlyForTheLeaseHolderAtItsGeneration() = runBlocking {
        val l = f.leased("j1")
        assertEquals("stale_generation", dao.releasePayload("j1", "other", l.leaseGen, f.now, f.live).denial)
        assertEquals("stale_generation", dao.releasePayload("j1", "h1", l.leaseGen + 1, f.now, f.live).denial)
        assertEquals("not_found", dao.releasePayload("nope", "h1", 1, f.now, f.live).denial)
        val ok = dao.releasePayload("j1", "h1", l.leaseGen, f.now, f.live)
        assertTrue(ok.ok); assertEquals("{\"p\":\"j1\"}", ok.payload)
    }

    @Test fun releaseRefusedAfterTheLeaseExpired() = runBlocking {
        val l = f.leased("j1")
        assertEquals("lease_expired", dao.releasePayload("j1", "h1", l.leaseGen, f.now + LEASE_MS, f.live).denial)
    }

    @Test fun revisionAdvancedAfterInsertBeforePayloadReleaseRefusesRelease() = runBlocking {
        val l = f.leased("j1")
        f.bumpRev() // set directly: no Forget hook voided the job, so only the release predicate can refuse
        val r = dao.releasePayload("j1", "h1", l.leaseGen, f.now, f.live)
        assertFalse(r.ok); assertNull("the payload never leaves the phone", r.payload); assertEquals("voided", r.denial)
        assertEquals("fail closed: the job is voided", "VOIDED", state("j1")); assertNull(dao.helperJob("j1")!!.payloadJson)
    }

    @Test fun releaseRefusedAfterWipeGenerationOrEpochMoved() = runBlocking {
        val a = f.leased("a"); f.wipe()
        assertEquals("voided", dao.releasePayload("a", "h1", a.leaseGen, f.now, f.live).denial)
        val b = f.leased("b"); f.newEpoch()
        assertEquals("voided", dao.releasePayload("b", "h1", b.leaseGen, f.now, f.live).denial)
    }

    @Test fun releaseOfAnUnconfirmedJobIsDeniedNotConfirmed() = runBlocking {
        f.insert("j1")
        val r = dao.releasePayload("j1", "h1", 0, f.now, f.live)
        assertEquals("not_confirmed", r.denial); assertNull(r.payload)
    }

    // ---- heartbeat ----

    @Test fun heartbeatExtendsOnlyForTheLeaseHolder() = runBlocking {
        val l = f.leased("j1")
        assertFalse("another helper id", dao.heartbeatHelperJob("j1", "other", l.leaseGen, f.now + 1_000, LEASE_MS, f.live).ok)
        val ok = dao.heartbeatHelperJob("j1", "h1", l.leaseGen, f.now + 1_000, LEASE_MS, f.live)
        assertTrue(ok.ok); assertEquals(f.now + 1_000 + LEASE_MS, ok.leaseUntilMs)
        assertEquals(f.now + 1_000 + LEASE_MS, dao.helperJob("j1")!!.leaseUntilMs)
    }

    @Test fun heartbeatAfterLeaseExpiryRefused() = runBlocking {
        val l = f.leased("j1")
        assertEquals("lease_expired", dao.heartbeatHelperJob("j1", "h1", l.leaseGen, f.now + LEASE_MS, LEASE_MS, f.live).denial)
    }

    @Test fun heartbeatWithStaleLeaseGenerationRefused() = runBlocking {
        val l = f.leased("j1")
        assertEquals("stale_generation", dao.heartbeatHelperJob("j1", "h1", l.leaseGen + 1, f.now + 1, LEASE_MS, f.live).denial)
        assertEquals("stale_generation", dao.heartbeatHelperJob("j1", "h1", l.leaseGen - 1, f.now + 1, LEASE_MS, f.live).denial)
    }

    @Test fun heartbeatNeverAcksWhenRevisionAdvanced() = runBlocking {
        val l = f.leased("j1")
        f.bumpRev()
        val r = dao.heartbeatHelperJob("j1", "h1", l.leaseGen, f.now + 1_000, LEASE_MS, f.live)
        assertFalse("no 200 for a job whose frames may have been forgotten", r.ok); assertEquals("voided", r.denial)
        assertEquals("VOIDED", state("j1"))
        assertEquals("and the next heartbeat is still refused", "voided", dao.heartbeatHelperJob("j1", "h1", l.leaseGen, f.now + 2_000, LEASE_MS, f.live).denial)
    }

    @Test fun heartbeatNeverAcksAfterWipeGenerationOrEpochMoved() = runBlocking {
        val a = f.leased("a"); f.wipe()
        assertFalse(dao.heartbeatHelperJob("a", "h1", a.leaseGen, f.now + 1, LEASE_MS, f.live).ok)
        val b = f.leased("b"); f.newEpoch()
        assertFalse(dao.heartbeatHelperJob("b", "h1", b.leaseGen, f.now + 1, LEASE_MS, f.live).ok)
    }

    @Test fun heartbeatAfterCancelAndVoidDenied() = runBlocking {
        val a = f.leased("a")
        assertTrue(dao.cancelHelperJob("a", f.now))
        assertEquals("cancelled", dao.heartbeatHelperJob("a", "h1", a.leaseGen, f.now + 1, LEASE_MS, f.live).denial)
        val b = f.leased("b")
        assertTrue(dao.voidHelperJob("b", "grant_off", f.now))
        assertEquals("voided", dao.heartbeatHelperJob("b", "h1", b.leaseGen, f.now + 1, LEASE_MS, f.live).denial)
    }

    @Test fun heartbeatAfterTheJobDeadlineRefused() = runBlocking {
        val l = f.leased("j1")
        var t = f.now
        while (t + 1_000 < f.now + JOB_MS) { t += 1_000; assertTrue(dao.heartbeatHelperJob("j1", "h1", l.leaseGen, t, LEASE_MS, f.live).ok) }
        assertEquals("deadline_exceeded", dao.heartbeatHelperJob("j1", "h1", l.leaseGen, f.now + JOB_MS, LEASE_MS, f.live).denial)
    }

    // ---- result commit ----

    @Test fun resultCommitsOnlyForTheLeaseHolderAndGeneration() = runBlocking {
        val l = f.leased("j1")
        assertFalse(dao.commitHelperResultFenced("j1", "other", l.leaseGen, "rh", "{\"r\":1}", f.now, f.live).ok)
        assertFalse(dao.commitHelperResultFenced("j1", "h1", l.leaseGen + 1, "rh", "{\"r\":1}", f.now, f.live).ok)
        assertEquals("LEASED", state("j1"))
        val ok = dao.commitHelperResultFenced("j1", "h1", l.leaseGen, "rh", "{\"r\":1}", f.now + 5, f.live)
        assertTrue(ok.ok); assertFalse(ok.duplicate)
        val row = dao.helperJob("j1")!!
        assertEquals("DONE", row.state); assertEquals("{\"r\":1}", row.resultJson); assertEquals("rh", row.resultHash); assertNull("payload released", row.payloadJson)
    }

    @Test fun revisionAdvancedBeforeResultCommitRejectsResult() = runBlocking {
        val l = f.leased("j1")
        f.bumpRev()
        val r = dao.commitHelperResultFenced("j1", "h1", l.leaseGen, "rh", "{\"r\":1}", f.now + 5, f.live)
        assertFalse(r.ok); assertEquals("voided", r.denial)
        val row = dao.helperJob("j1")!!
        assertEquals("VOIDED", row.state); assertNull("no result is stored for forgotten data", row.resultJson); assertNull(row.resultHash)
    }

    @Test fun resultRejectedAfterWipeGenerationOrEpochMoved() = runBlocking {
        val a = f.leased("a"); f.wipe()
        assertFalse(dao.commitHelperResultFenced("a", "h1", a.leaseGen, "rh", "{}", f.now, f.live).ok); assertNull(dao.helperJob("a")!!.resultJson)
        val b = f.leased("b"); f.newEpoch()
        assertFalse(dao.commitHelperResultFenced("b", "h1", b.leaseGen, "rh", "{}", f.now, f.live).ok); assertNull(dao.helperJob("b")!!.resultJson)
    }

    @Test fun duplicateResultIsIdempotent() = runBlocking {
        val l = f.leased("j1")
        assertTrue(dao.commitHelperResultFenced("j1", "h1", l.leaseGen, "rh", "{\"r\":1}", f.now, f.live).ok)
        val again = dao.commitHelperResultFenced("j1", "h1", l.leaseGen, "rh", "{\"r\":1}", f.now + 10, f.live)
        assertTrue(again.ok); assertTrue(again.duplicate)
        assertFalse("a different result for the same job is refused", dao.commitHelperResultFenced("j1", "h1", l.leaseGen, "other", "{\"r\":2}", f.now + 10, f.live).ok)
        assertEquals("{\"r\":1}", dao.helperJob("j1")!!.resultJson)
    }

    @Test fun staleResultAfterExpiryRefused() = runBlocking {
        val l = f.leased("j1")
        assertEquals("lease_expired", dao.commitHelperResultFenced("j1", "h1", l.leaseGen, "rh", "{}", f.now + LEASE_MS, f.live).denial)
        val m = f.leased("j2", helper = "h2")
        // lease kept alive by heartbeats; a result AT lease + deadline is expired (the job deadline is deadlineMs <= now)
        assertEquals("deadline_exceeded", dao.commitHelperResultFenced("j2", "h2", m.leaseGen, "rh", "{}", f.now + JOB_MS, f.live).denial)
    }

    @Test fun resultAfterVoidAndAfterCancelRefused() = runBlocking {
        val a = f.leased("a"); assertTrue(dao.voidHelperJob("a", "grant_off", f.now))
        assertEquals("voided", dao.commitHelperResultFenced("a", "h1", a.leaseGen, "rh", "{}", f.now, f.live).denial)
        val b = f.leased("b"); assertTrue(dao.cancelHelperJob("b", f.now))
        assertEquals("cancelled", dao.commitHelperResultFenced("b", "h1", b.leaseGen, "rh", "{}", f.now, f.live).denial)
        assertNull(dao.helperJob("a")!!.resultJson); assertNull(dao.helperJob("b")!!.resultJson)
    }

    @Test fun resultForAnUnconfirmedJobIsDeniedNotConfirmed() = runBlocking {
        f.insert("j1")
        assertEquals("not_confirmed", dao.commitHelperResultFenced("j1", "h1", 0, "rh", "{}", f.now, f.live).denial)
        assertNull(dao.helperJob("j1")!!.resultJson); assertEquals("AWAITING_CONFIRM", state("j1"))
    }

    // ---- fail, cancel, void, expire, abort, cleanup ----

    @Test fun failStoresTheCodeNullsThePayloadAndEnqueuesCleanupInTheSameTransaction() = runBlocking {
        val l = f.leased("j1")
        assertFalse("only the holder at its generation", dao.failHelperJob("j1", "other", l.leaseGen, "timeout", f.now))
        assertTrue(dao.failHelperJob("j1", "h1", l.leaseGen, "timeout", f.now))
        val row = dao.helperJob("j1")!!
        assertEquals("FAILED", row.state); assertEquals("timeout", row.failCode); assertNull(row.payloadJson)
        assertEquals(listOf("j1"), dao.pendingCleanup(10).map { it.jobId })
    }

    @Test fun cancelEndsEveryOpenStateNullsThePayloadAndEnqueuesCleanup() = runBlocking {
        f.insert("await"); f.insertConfirmed("queued"); f.leased("leased")
        listOf("await", "queued", "leased").forEach { id ->
            assertTrue(id, dao.cancelHelperJob(id, f.now))
            val row = dao.helperJob(id)!!
            assertEquals(id, "CANCELLED", row.state); assertNull(id, row.payloadJson); assertEquals("[]", row.frameIdsJson)
        }
        assertEquals(setOf("await", "queued", "leased"), dao.pendingCleanup(10).map { it.jobId }.toSet())
        assertFalse("a terminal job is not cancelled again", dao.cancelHelperJob("await", f.now))
        assertEquals("and gets no second cleanup row", 3, dao.pendingCleanup(10).size)
    }

    @Test fun voidWritesCleanupInTheSameTransactionAndVoidAllLeavesTerminalJobsAlone() = runBlocking {
        f.insert("await"); f.insertConfirmed("queued"); val l = f.leased("leased")
        val done = f.leased("done", helper = "h2")
        assertTrue(dao.commitHelperResultFenced("done", "h2", done.leaseGen, "rh", "{\"r\":1}", f.now, f.live).ok)
        assertEquals(3, dao.voidAllHelperJobs("disconnect", f.now))
        listOf("await", "queued", "leased").forEach { assertEquals(it, "VOIDED", state(it)); assertNull(dao.helperJob(it)!!.payloadJson); assertEquals("disconnect", dao.helperJob(it)!!.voidReason) }
        assertEquals("DONE", state("done")); assertEquals("{\"r\":1}", dao.helperJob("done")!!.resultJson)
        assertEquals(setOf("await", "queued", "leased"), dao.pendingCleanup(10).map { it.jobId }.toSet())
        assertEquals("heartbeat after the void", "voided", dao.heartbeatHelperJob("leased", "h1", l.leaseGen, f.now + 1, LEASE_MS, f.live).denial)
    }

    @Test fun voidJobsWithStaleFrameRevVoidsOnlyThoseCapturedBeforeTheMove() = runBlocking {
        f.insert("before"); f.bumpRev(); f.insert("after")
        assertEquals(1, dao.voidJobsWithStaleFrameRev(f.now))
        assertEquals("VOIDED", state("before")); assertEquals("AWAITING_CONFIRM", state("after"))
    }

    @Test fun expireLeasesFailsTheJobAndEnqueuesCleanup() = runBlocking {
        val a = f.leased("a")                   // lease runs out at now + LEASE_MS
        val b = f.leased("b", helper = "h2")
        assertTrue(dao.heartbeatHelperJob("b", "h2", b.leaseGen, f.now + 9_000, LEASE_MS, f.live).ok)
        assertEquals(listOf("a"), dao.expireHelperLeases(f.now + LEASE_MS))
        assertEquals("FAILED", state("a")); assertEquals("lease_expired", dao.helperJob("a")!!.failCode); assertNull(dao.helperJob("a")!!.payloadJson)
        assertEquals("LEASED", state("b"))
        // the job deadline, however alive the lease is: deadlineMs <= now
        assertEquals(listOf("b"), dao.expireHelperLeases(f.now + JOB_MS))
        assertEquals("deadline_exceeded", dao.helperJob("b")!!.failCode)
        assertEquals(setOf("a", "b"), dao.pendingCleanup(10).map { it.jobId }.toSet())
        assertTrue("idempotent", dao.expireHelperLeases(f.now + JOB_MS + 1).isEmpty()); assertEquals(1L, a.leaseGen)
    }

    @Test fun expireQueuedAndAwaitingByTheirOwnWindows() = runBlocking {
        f.insert("await"); f.insertConfirmed("queued")
        assertTrue(dao.expireAwaitingConfirm(f.now + 119_999).isEmpty())
        assertEquals(listOf("await"), dao.expireAwaitingConfirm(f.now + 120_000))
        assertEquals("FAILED", state("await")); assertEquals("not_confirmed", dao.helperJob("await")!!.failCode)
        assertTrue(dao.expireQueuedHelperJobs(f.now + QUEUE_WAIT - 1, QUEUE_WAIT).isEmpty())
        assertEquals(listOf("queued"), dao.expireQueuedHelperJobs(f.now + QUEUE_WAIT, QUEUE_WAIT))
        assertEquals("resources_busy", dao.helperJob("queued")!!.failCode)
        assertEquals(setOf("await", "queued"), dao.pendingCleanup(10).map { it.jobId }.toSet())
    }

    @Test fun markRunningAbortedAbortsOpenHelperJobsAndKeepsTheRest() = runBlocking {
        f.insert("await"); f.insertConfirmed("queued"); f.leased("leased")
        val d = f.leased("done", helper = "h2"); dao.commitHelperResultFenced("done", "h2", d.leaseGen, "rh", "{}", f.now, f.live)
        dao.insertReplay(CanvasReplayEntity("r1", null, "{}", "EXACT", null, false, "[]", 0, "RUNNING", null, null, 1))
        assertEquals("one replay and three helper jobs", 4, dao.markRunningAborted(99))
        listOf("await", "queued", "leased").forEach { assertEquals(it, "ABORTED", state(it)); assertNull(dao.helperJob(it)!!.payloadJson) }
        assertEquals("DONE", state("done"))
        assertEquals(3, dao.pendingCleanup(10).size)
        assertEquals("idempotent", 0, dao.markRunningAborted(100))
    }

    @Test fun cleanupAckPurgeAndSurvivalOfTheJobRows() = runBlocking {
        f.insertConfirmed("a"); f.insertConfirmed("b")
        dao.cancelHelperJob("a", 1_000); dao.cancelHelperJob("b", 2_000)
        val rows = dao.pendingCleanup(10)
        assertEquals(listOf("a", "b"), rows.map { it.jobId }); assertEquals(listOf("cancelled", "cancelled"), rows.map { it.reason })
        assertEquals("limit", 1, dao.pendingCleanup(1).size)
        // Delete all removes the job rows but keeps the cleanup rows: the laptop copies must still be deleted.
        f.db.wipeDao().wipeMemory()
        assertNull(dao.helperJob("a")); assertEquals(2, dao.pendingCleanup(10).size)
        assertEquals(1, dao.ackCleanup(listOf(rows[0].cleanupId)))
        assertEquals(listOf("b"), dao.pendingCleanup(10).map { it.jobId })
        assertEquals("30 days retention: only rows older than the cut-off go", 0, dao.purgeOldCleanup(2_000))
        assertEquals(1, dao.purgeOldCleanup(2_001)); assertTrue(dao.pendingCleanup(10).isEmpty())
    }

    // ---- reads and the revision counter ----

    @Test fun frameForgetRevDefaultsToZeroAndOnlyGoesUp() = runBlocking {
        assertEquals(0L, dao.frameForgetRev())
        assertEquals(1L, dao.bumpFrameForgetRev()); assertEquals(2L, dao.bumpFrameForgetRev()); assertEquals(2L, dao.frameForgetRev())
    }

    @Test fun availableListsOnlyConfirmedQueuedJobsWithoutPayload() = runBlocking {
        f.insert("await"); f.insertConfirmed("queued"); f.leased("leased", helper = "h2")
        val av = dao.availableHelperJobs(types, 7)
        assertEquals(listOf("queued"), av.map { it.jobId })
        assertEquals("QUEUED", av[0].state); assertEquals("hash-queued", av[0].bundleHash); assertEquals(2, av[0].itemCount); assertEquals(30, av[0].sourceChars)
        assertEquals("{\"p\":\"queued\"}".toByteArray().size, av[0].payloadBytes)
        assertEquals(listOf("await"), dao.awaitingConfirmJobs().map { it.jobId })
        assertTrue(dao.availableHelperJobs(types, 8).isEmpty())
    }

    // ---- the existing open-state queries know the new states ----

    @Test fun openHelperJobsExcludesEveryTerminalStateAndIncludesAwaitingConfirm() = runBlocking {
        f.insert("await"); f.insertConfirmed("cancelled"); dao.cancelHelperJob("cancelled", f.now)
        f.insertConfirmed("aborted"); dao.abortOpenHelperJobs(f.now - 1) // aborts "await" too; a job inserted afterwards stays open
        f.insert("fresh")
        assertEquals(listOf("fresh"), dao.openHelperJobs().map { it.jobId })
    }

    @Test fun frameForgetVoidsAJobStillAwaitingTheTapAndNullsItsPayload() = runBlocking {
        f.insert("await"); f.insertConfirmed("cancelled"); dao.cancelHelperJob("cancelled", f.now)
        assertEquals("only the open job: a cancelled one is closed", 1, dao.voidHelperJobsForFrames(listOf("f-await", "f-cancelled"), f.now))
        assertEquals("VOIDED", state("await")); assertNull(dao.helperJob("await")!!.payloadJson)
        assertEquals("CANCELLED", state("cancelled"))
    }

    @Test fun aLeasedJobCannotBeConfirmedBackIntoTheQueue() = runBlocking {
        f.leased("j1")
        assertEquals(com.rokid.context.memory.canvas.HelperConfirmOutcome.DUPLICATE, dao.confirmJobFenced("j1", f.binding(f.job("j1")), f.live, f.now))
        assertEquals("LEASED", state("j1"))
    }
}
