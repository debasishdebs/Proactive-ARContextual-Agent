package com.rokid.context.memory.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.canvas.ConfirmBinding
import com.rokid.context.memory.canvas.HelperConfirmOutcome.CONFIRMED
import com.rokid.context.memory.canvas.HelperConfirmOutcome.DUPLICATE
import com.rokid.context.memory.canvas.HelperConfirmOutcome.EXPIRED
import com.rokid.context.memory.canvas.HelperConfirmOutcome.MISMATCH
import com.rokid.context.memory.canvas.HelperConfirmOutcome.NOT_AWAITING
import com.rokid.context.memory.canvas.HelperConfirmOutcome.NOT_FOUND
import com.rokid.context.memory.canvas.HelperConfirmOutcome.STALE_FENCE
import com.rokid.context.memory.db.HelperJobFixture.Companion.CONFIRM_TTL
import com.rokid.context.memory.db.HelperJobFixture.Companion.JOB_MS
import com.rokid.context.memory.db.HelperJobFixture.Companion.LEASE_MS
import com.rokid.context.memory.db.HelperJobFixture.Companion.QUEUE_WAIT
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * WP3 per-run confirmation in the DAO (user decision 2026-10-23 evening): `confirmJobFenced` is the ONE transaction that moves AWAITING_CONFIRM to QUEUED, after it
 * re-checked the state, the window, the bundle hash and session generation against the binding, and the fence. Revisions are advanced directly.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ConfirmDaoTest {
    private val f = HelperJobFixture()
    private val dao = f.dao
    @After fun close() = f.close()

    private val types = listOf("py_exec")
    private suspend fun state(id: String) = dao.helperJob(id)!!.state
    private suspend fun confirm(j: CanvasHelperJobEntity, b: ConfirmBinding = f.binding(j), now: Long = f.now) = dao.confirmJobFenced(j.jobId, b, f.live, now)

    @Test fun confirmMovesAwaitingToQueuedOnce() = runBlocking {
        val j = f.insert("j1")
        assertEquals("AWAITING_CONFIRM", state("j1")); assertNull(dao.helperJob("j1")!!.confirmedAtMs)
        assertEquals(CONFIRMED, confirm(j, now = f.now + 5))
        val row = dao.helperJob("j1")!!
        assertEquals("QUEUED", row.state); assertEquals(f.now + 5, row.confirmedAtMs)
        assertEquals("a second tap does not confirm again", DUPLICATE, confirm(j, now = f.now + 6))
        assertEquals("and the first confirmation time stays", f.now + 5, dao.helperJob("j1")!!.confirmedAtMs)
    }

    @Test fun confirmAfterExpiryRefused() = runBlocking {
        val j = f.insert("j1")
        assertEquals(EXPIRED, confirm(j, now = f.now + CONFIRM_TTL))
        val row = dao.helperJob("j1")!!
        assertEquals("the window closed: the job ends, it does not stay confirmable", "FAILED", row.state); assertEquals("not_confirmed", row.failCode); assertNull(row.payloadJson)
        assertEquals(NOT_AWAITING, confirm(j, now = f.now + 1))
    }

    @Test fun confirmJustBeforeExpiryIsAccepted() = runBlocking {
        val j = f.insert("j1")
        assertEquals(CONFIRMED, confirm(j, now = f.now + CONFIRM_TTL - 1))
    }

    @Test fun confirmWithOtherBundleHashRefused() = runBlocking {
        val j = f.insert("j1")
        assertEquals(MISMATCH, confirm(j, f.binding(j).copy(bundleHash = "hash-other")))
        assertEquals("a refused tap changes nothing", "AWAITING_CONFIRM", state("j1")); assertNotNull(dao.helperJob("j1")!!.payloadJson)
    }

    @Test fun confirmWithOtherSessionGenRefused() = runBlocking {
        val j = f.insert("j1")
        assertEquals(MISMATCH, confirm(j, f.binding(j).copy(sessionGen = j.sessionGen + 1)))
        assertEquals("AWAITING_CONFIRM", state("j1"))
    }

    @Test fun confirmWithOtherExpiryOrJobIdRefused() = runBlocking {
        val j = f.insert("j1")
        assertEquals(MISMATCH, confirm(j, f.binding(j).copy(expiresAtMs = j.confirmExpiresAtMs!! + 1)))
        assertEquals(NOT_FOUND, dao.confirmJobFenced("nope", f.binding(j).copy(jobId = "nope"), f.live, f.now))
        assertEquals("AWAITING_CONFIRM", state("j1"))
    }

    @Test fun confirmWhenRevisionAdvancedRefusesAndPayloadNeverReleased() = runBlocking {
        val j = f.insert("j1")
        f.bumpRev() // set directly: no Forget hook voided the job
        assertEquals(STALE_FENCE, confirm(j))
        val row = dao.helperJob("j1")!!
        assertEquals("fail closed: the job is voided, not left confirmable", "VOIDED", row.state); assertNull(row.payloadJson)
        assertNull("never leasable", dao.leaseHelperJob(types, "h1", 7, f.now, LEASE_MS, JOB_MS, QUEUE_WAIT, f.live))
        assertEquals("no payload release for it either", "voided", dao.releasePayload("j1", "h1", 1, f.now, f.live).denial)
        assertEquals("and the helper is told to clean up", listOf("j1"), dao.pendingCleanup(10).map { it.jobId })
    }

    @Test fun confirmWhenWipeGenerationOrEpochMovedRefuses() = runBlocking {
        val a = f.insert("a"); f.wipe()
        assertEquals(STALE_FENCE, confirm(a)); assertEquals("VOIDED", state("a"))
        val b = f.insert("b"); f.newEpoch()
        assertEquals(STALE_FENCE, confirm(b)); assertEquals("VOIDED", state("b"))
    }

    @Test fun awaitingJobNeverLeasable() = runBlocking {
        f.insert("j1")
        assertNull(dao.leaseHelperJob(types, "h1", 7, f.now, LEASE_MS, JOB_MS, QUEUE_WAIT, f.live))
        assertTrue("and never in the available list", dao.availableHelperJobs(types, 7).isEmpty())
        assertEquals("AWAITING_CONFIRM", state("j1"))
    }

    @Test fun declineEndsJob() = runBlocking {
        val j = f.insert("j1")
        assertTrue(dao.declineConfirm("j1", f.now + 1))
        val row = dao.helperJob("j1")!!
        assertEquals("CANCELLED", row.state); assertEquals("declined", row.voidReason); assertNull(row.payloadJson)
        assertEquals(NOT_AWAITING, confirm(j))
        assertTrue("declining again does nothing", !dao.declineConfirm("j1", f.now + 2))
        assertNull(dao.leaseHelperJob(types, "h1", 7, f.now, LEASE_MS, JOB_MS, QUEUE_WAIT, f.live))
    }

    @Test fun declineOfAConfirmedJobIsRefused() = runBlocking {
        f.insertConfirmed("j1")
        assertTrue(!dao.declineConfirm("j1", f.now)); assertEquals("QUEUED", state("j1"))
    }

    @Test fun duplicateConfirmIdempotent() = runBlocking {
        val j = f.insert("j1")
        assertEquals(CONFIRMED, confirm(j))
        repeat(3) { assertEquals(DUPLICATE, confirm(j)) }
        assertEquals("one job in the queue", 1, dao.availableHelperJobs(types, 7).size)
        val l = checkNotNull(dao.leaseHelperJob(types, "h1", 7, f.now, LEASE_MS, JOB_MS, QUEUE_WAIT, f.live))
        assertEquals("still a duplicate once leased, and the lease is untouched", DUPLICATE, confirm(j))
        assertEquals("LEASED", state("j1")); assertEquals(l.leaseGen, dao.helperJob("j1")!!.leaseGen)
    }

    @Test fun confirmOfATerminalJobIsRefused() = runBlocking {
        val j = f.insert("j1"); dao.cancelHelperJob("j1", f.now)
        assertEquals(NOT_AWAITING, confirm(j))
        val k = f.insert("k1"); dao.voidHelperJob("k1", "grant_off", f.now)
        assertEquals(NOT_AWAITING, confirm(k))
    }
}
