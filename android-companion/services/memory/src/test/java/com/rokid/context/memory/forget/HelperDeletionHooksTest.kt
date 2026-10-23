package com.rokid.context.memory.forget

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.canvas.HelperConfirmOutcome
import com.rokid.context.memory.canvas.HelperDeletion
import com.rokid.context.memory.canvas.HelperDeletionCause
import com.rokid.context.memory.canvas.HelperSessionHooks
import com.rokid.context.memory.db.HelperJobFixture
import com.rokid.context.memory.db.HelperJobFixture.Companion.LEASE_MS
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * WP4: the non-Forget deletion causes of core plan section 6 (grant off, Disconnect all, session end, restart) through [HelperDeletion], with a recording fake of the small
 * [HelperSessionHooks] interface that WP6 binds to WP2's `HelperGrants`/`HelperAuth`; plus the in-transaction fault test of the Forget step.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HelperDeletionHooksTest {
    private val f = HelperJobFixture()
    private val dao = f.dao
    @After fun close() = f.close()

    private class Recorder(val calls: MutableList<String> = mutableListOf(), var failGrant: Boolean = false, var failRevoke: Boolean = false) : HelperSessionHooks {
        override fun grantsOff() { calls += "grantsOff"; if (failGrant) error("grant: prefs unavailable") }
        override fun revokeHelper() { calls += "revokeHelper"; if (failRevoke) error("revoke: unavailable") }
    }

    private val hooks = Recorder()
    private val deletion get() = HelperDeletion(dao, hooks) { f.now }
    private suspend fun state(id: String) = dao.helperJob(id)!!.state
    private suspend fun cleanup() = dao.pendingCleanup(100).map { it.jobId to it.reason }

    /** One job in each open state: awaiting the tap, confirmed and queued, leased. */
    private suspend fun openJobs(): Triple<com.rokid.context.memory.db.CanvasHelperJobEntity, String, com.rokid.context.memory.canvas.LeasedHelperJob> {
        val leased = f.leased("leased")
        val queued = f.insertConfirmed("queued")
        val awaiting = f.insert("awaiting")
        return Triple(awaiting, queued.jobId, leased)
    }

    @Test fun grantOffVoidsEveryOpenJobAndEnqueuesCleanup() = runBlocking {
        val (awaiting, _, leased) = openJobs()
        assertEquals(3, deletion.grantOff())
        listOf("awaiting", "queued", "leased").forEach { assertEquals(it, "VOIDED", state(it)); assertNull(dao.helperJob(it)!!.payloadJson) }
        assertEquals(listOf("grantsOff"), hooks.calls)
        assertEquals(setOf("awaiting" to "grant_off", "queued" to "grant_off", "leased" to "grant_off"), cleanup().toSet())
        assertFalse("heartbeat is never acked after the cause", dao.heartbeatHelperJob("leased", "h1", leased.leaseGen, f.now, LEASE_MS, f.live).ok)
        assertEquals("a tap after the cause is refused", HelperConfirmOutcome.NOT_AWAITING, dao.confirmJobFenced("awaiting", f.binding(awaiting), f.live, f.now))
    }

    @Test fun disconnectAllRevokesAndVoidsEveryOpenJobAndEnqueuesCleanup() = runBlocking {
        val (awaiting, _, leased) = openJobs()
        assertEquals(3, deletion.disconnectAll())
        assertEquals(listOf("revokeHelper"), hooks.calls)
        listOf("awaiting", "queued", "leased").forEach { assertEquals(it, "VOIDED", state(it)) }
        assertEquals(3, cleanup().count { it.second == "disconnect" })
        assertFalse(dao.heartbeatHelperJob("leased", "h1", leased.leaseGen, f.now, LEASE_MS, f.live).ok)
        assertNotEquals(HelperConfirmOutcome.CONFIRMED, dao.confirmJobFenced("awaiting", f.binding(awaiting), f.live, f.now))
    }

    @Test fun endSessionTurnsGrantOffRevokesThenVoids() = runBlocking {
        openJobs()
        assertEquals(3, deletion.endSession(HelperDeletionCause.SESSION_END))
        assertEquals("the grant goes off and the token is revoked BEFORE the jobs are voided, so nothing can lease in between", listOf("grantsOff", "revokeHelper"), hooks.calls)
        assertEquals(3, cleanup().count { it.second == "session_end" })
        assertEquals("a second call is a no-op for the jobs", 0, deletion.endSession(HelperDeletionCause.SESSION_END)); assertEquals(3, cleanup().size)
    }

    @Test fun jobsAreVoidedEvenWhenAHookFails() = runBlocking {
        openJobs()
        val failing = HelperDeletion(dao, Recorder(failGrant = true)) { f.now }
        assertThrows(IllegalStateException::class.java) { runBlocking { failing.grantOff() } }
        listOf("awaiting", "queued", "leased").forEach { assertEquals("fail closed: $it", "VOIDED", state(it)) }
    }

    @Test fun endSessionRevokesEvenWhenGrantHookFails() = runBlocking {
        openJobs()
        val rec = Recorder(failGrant = true)
        val e = assertThrows(IllegalStateException::class.java) { runBlocking { HelperDeletion(dao, rec) { f.now }.endSession(HelperDeletionCause.SESSION_END) } }
        assertEquals("grant: prefs unavailable", e.message)
        assertEquals("the token is revoked although the grant hook threw", listOf("grantsOff", "revokeHelper"), rec.calls)
        listOf("awaiting", "queued", "leased").forEach { assertEquals(it, "VOIDED", state(it)) }
    }

    @Test fun endSessionVoidsAndRethrowsFirstWhenRevokeAloneOrBothFail() = runBlocking {
        openJobs()
        val rec = Recorder(failRevoke = true)
        val e = assertThrows(IllegalStateException::class.java) { runBlocking { HelperDeletion(dao, rec) { f.now }.endSession(HelperDeletionCause.SESSION_END) } }
        assertEquals("revoke: unavailable", e.message); assertEquals(listOf("grantsOff", "revokeHelper"), rec.calls)
        listOf("awaiting", "queued", "leased").forEach { assertEquals(it, "VOIDED", state(it)) }
        val both = Recorder(failGrant = true, failRevoke = true)
        val e2 = assertThrows(IllegalStateException::class.java) { runBlocking { HelperDeletion(dao, both) { f.now }.endSession(HelperDeletionCause.SESSION_END) } }
        assertEquals("the FIRST failure is rethrown", "grant: prefs unavailable", e2.message)
        assertEquals("the second is attached", listOf("revoke: unavailable"), e2.suppressed.map { it.message }); assertEquals(listOf("grantsOff", "revokeHelper"), both.calls)
    }

    @Test fun disconnectAndGrantOffVoidWhenTheirHookFails() = runBlocking {
        f.insert("a"); f.insert("b")
        assertThrows(IllegalStateException::class.java) { runBlocking { HelperDeletion(dao, Recorder(failRevoke = true)) { f.now }.disconnectAll() } }
        listOf("a", "b").forEach { assertEquals(it, "VOIDED", state(it)) }
        f.insert("c")
        assertThrows(IllegalStateException::class.java) { runBlocking { HelperDeletion(dao, Recorder(failGrant = true)) { f.now }.grantOff() } }
        assertEquals("VOIDED", state("c"))
    }

    @Test fun faultInForgetMomentRollsBackFrameTombstoneAndFence() = runBlocking {
        val db = f.db
        db.frameDao().insertIgnore(com.rokid.context.memory.db.FrameEntity("f-j1", 1, "/f-j1.jpg", "rokid_glasses")); f.insert("j1")
        val service = ForgetService(db, com.rokid.context.memory.ProposalRepository(db.proposalDao(), db.actionDao(), db)) { f.now }
        val rev0 = dao.frameForgetRev()
        dao.testFaultAfterFenceMove = { error("injected") }
        try { assertThrows(IllegalStateException::class.java) { runBlocking { service.forgetMoment("f-j1") } } } finally { dao.testFaultAfterFenceMove = null }
        assertEquals("the frame row is still there", 1, db.frameDao().byIds(listOf("f-j1")).size)
        assertEquals("no tombstone", 0, db.canvasDao().tombstonedAmong(listOf("f-j1")))
        assertEquals(rev0, dao.frameForgetRev()); assertEquals("AWAITING_CONFIRM", state("j1")); assertEquals(emptyList<Pair<String, String>>(), cleanup())
    }

    @Test fun restartAbortsOpenJobsAndEnqueuesCleanup() = runBlocking {
        val (awaiting, _, _) = openJobs()
        assertEquals(3, deletion.restart())
        listOf("awaiting", "queued", "leased").forEach { assertEquals(it, "ABORTED", state(it)) }
        assertEquals(3, cleanup().count { it.second == "restart" })
        assertNotEquals(HelperConfirmOutcome.CONFIRMED, dao.confirmJobFenced("awaiting", f.binding(awaiting), f.live, f.now))
    }

    @Test fun staleConfirmAfterGrantOffAndDisconnectRefused() = runBlocking {
        val a = f.insert("a"); deletion.grantOff()
        assertEquals("VOIDED", state("a")); assertNull(dao.helperJob("a")!!.payloadJson)
        assertEquals(HelperConfirmOutcome.NOT_AWAITING, dao.confirmJobFenced("a", f.binding(a), f.live, f.now))
        val b = f.insert("b"); deletion.disconnectAll()
        assertEquals("VOIDED", state("b")); assertEquals(HelperConfirmOutcome.NOT_AWAITING, dao.confirmJobFenced("b", f.binding(b), f.live, f.now))
    }

    // ---- the Forget step is one transaction ----

    @Test fun forgetBumpsRevisionInvalidatesJobAndInsertsCleanupInOneTransaction() = runBlocking {
        val j = f.insert("j1")
        val rev0 = dao.frameForgetRev(); val ranges0 = dao.forgottenRanges()
        dao.testFaultAfterFenceMove = { error("injected: the process dies after the revision bump, the void and the cleanup row") }
        try { assertThrows(IllegalStateException::class.java) { runBlocking { dao.forgetRangeTx(0, 10, f.now) } } } finally { dao.testFaultAfterFenceMove = null }
        assertEquals("the revision bump rolled back", rev0, dao.frameForgetRev())
        assertEquals("the job is still open (the void rolled back)", "AWAITING_CONFIRM", state("j1")); assertTrue(dao.helperJob("j1")!!.payloadJson != null)
        assertEquals("the cleanup row rolled back", emptyList<Pair<String, String>>(), cleanup())
        assertEquals("the range fence rolled back too", ranges0, dao.forgottenRanges())
        // Without the fault the same Forget does all three, once.
        val o = dao.forgetRangeTx(0, 10, f.now)
        assertEquals(1, o.helperJobsVoided); assertTrue(dao.frameForgetRev() > rev0)
        assertEquals("VOIDED", state("j1")); assertEquals(listOf("j1" to "frame_forget"), cleanup())
        assertEquals(HelperConfirmOutcome.NOT_AWAITING, dao.confirmJobFenced("j1", f.binding(j), f.live, f.now))
    }

    @Test fun faultInTheFrameDeletionStepAlsoRollsBack() = runBlocking {
        f.insert("j1"); val rev0 = dao.frameForgetRev()
        dao.testFaultAfterFenceMove = { error("injected") }
        try { assertThrows(IllegalStateException::class.java) { runBlocking { dao.deleteSliceOutputsForFrames(listOf("f-j1"), f.now) } } } finally { dao.testFaultAfterFenceMove = null }
        assertEquals(rev0, dao.frameForgetRev()); assertEquals("AWAITING_CONFIRM", state("j1")); assertEquals(emptyList<Pair<String, String>>(), cleanup())
    }
}
