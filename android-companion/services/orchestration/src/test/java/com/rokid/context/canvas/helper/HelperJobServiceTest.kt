package com.rokid.context.canvas.helper

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.canvas.ConfirmBinding
import com.rokid.context.memory.canvas.HelperJobState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** WP5: the real [HelperJobService] over the WP3 DAO (in-memory Room), fake clock. The shared port contract is [HelperJobServiceContractTest]. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HelperJobServiceTest {
    private val h = RealPortHarness()
    private val svc = h.service
    private val scope = CoroutineScope(Dispatchers.Default)
    private val known = HashSet<String>()
    private val ok2 = okResult(listOf("i0", "i1"))

    @After fun close() { scope.cancel(); h.close() }

    private class Run(val jobId: String, val outcome: Deferred<PyOutcome>)

    private suspend fun start(req: PyRequest? = null): Run {
        val r = req ?: h.request()
        val d = scope.async { svc.runPy(r) }
        val id = withTimeout(10_000) {
            var f: String? = null
            while (f == null) { f = svc.pendingConfirmations().map { it.jobId }.firstOrNull { it !in known }; if (f == null) delay(5) }
            f
        }
        known += id
        return Run(id, d)
    }

    private suspend fun Run.await(): PyOutcome = withTimeout(10_000) { outcome.await() }
    private suspend fun binding(id: String) = svc.pendingConfirmations().first { it.jobId == id }.let { ConfirmBinding(it.jobId, it.bundleHash, it.sessionGen, it.expiresAtMs) }
    private suspend fun confirmed(): Run { val r = start(); assertEquals(ConfirmResult.CONFIRMED, svc.confirm(r.jobId, binding(r.jobId))); return r }
    private suspend fun leased(): Run { val r = confirmed(); assertEquals(r.jobId, h.leaseNext()); return r }
    private suspend fun cleanupJobs() = h.store.pendingCleanup(100).map { it.jobId }
    private suspend fun awaitState(id: String, s: HelperJobState) = withTimeout(10_000) { while (h.state(id) != s) delay(5) }

    // ---- confirmation ----

    @Test fun submitWaitsInAwaitingConfirm() = runBlocking {
        val r = start()
        assertEquals(HelperJobState.AWAITING_CONFIRM, h.state(r.jobId))
        assertFalse(r.outcome.isCompleted)
        assertTrue(svc.available(listOf("py_exec")).isEmpty())
        val p = svc.pendingConfirmations().single()
        assertEquals(h.request().source.length, p.sourceChars)
        assertEquals(h.now + HelperProtocol.CONFIRM_TTL_MS, p.expiresAtMs)
    }

    @Test fun confirmReleasesJobToQueueOnce() = runBlocking {
        val r = start()
        val b = binding(r.jobId)
        assertEquals(ConfirmResult.CONFIRMED, svc.confirm(r.jobId, b))
        assertEquals(ConfirmResult.DUPLICATE, svc.confirm(r.jobId, b))
        assertEquals(HelperJobState.QUEUED, h.state(r.jobId))
        assertEquals(listOf(r.jobId), svc.available(listOf("py_exec")).map { it.jobId })
        assertEquals(r.jobId, h.leaseNext())
        assertNull(h.leaseNext())
    }

    @Test fun unconfirmedJobNotInAvailableAndNeverLeased() = runBlocking {
        val r = start()
        assertTrue(svc.available(listOf("py_exec")).isEmpty())
        assertNull(svc.lease("h1", listOf("py_exec")))
        assertEquals("not_confirmed", svc.releasePayload(r.jobId, "h1", 1).denial)
        assertEquals("not_confirmed", svc.heartbeat(r.jobId, "h1", 1).denial)
        assertEquals(ResultCommit.Denied("not_confirmed"), svc.commitResult(r.jobId, "h1", 1, ok2))
        assertEquals(HelperJobState.AWAITING_CONFIRM, h.state(r.jobId))
    }

    @Test fun declineAndExpiryFailItemsNotConfirmed() = runBlocking {
        val declined = start()
        val expiring = start()
        assertTrue(svc.declineConfirm(declined.jobId))
        assertEquals(PyOutcome.NotConfirmed, declined.await())
        h.advance(HelperProtocol.CONFIRM_TTL_MS)
        svc.sweep()
        assertEquals(PyOutcome.NotConfirmed, expiring.await())
        assertEquals(setOf(declined.jobId, expiring.jobId), cleanupJobs().toSet())
        assertNull(h.store.job(expiring.jobId)!!.payloadJson)
        assertNull(svc.lease("h1", listOf("py_exec")))
    }

    @Test fun confirmFromServiceHasNoAccessToKey() {
        val cls = HelperJobService::class.java
        val types = (cls.declaredFields.map { it.type } + cls.declaredConstructors.flatMap { it.parameterTypes.toList() }).map { it.name }
        assertTrue(types.none { it.startsWith("javax.crypto") || it == "[B" || it.contains("Key") || it.contains("Mac") })
        val confirm = HelperJobPort::class.java.methods.single { it.name == "confirm" }
        assertTrue(confirm.parameterTypes.any { it == ConfirmBinding::class.java })
        val dir = listOf("src/main/java", "android-companion/services/orchestration/src/main/java", System.getProperty("user.dir") + "/src/main/java").map { File(it, "com/rokid/context/canvas/helper") }.firstOrNull { it.isDirectory } ?: File("missing")
        assertTrue("main sources not found from ${File(".").absoluteFile}", dir.isDirectory)
        for (f in dir.listFiles { x -> x.extension == "kt" }!!) {
            val t = f.readText()
            assertFalse("${f.name} imports javax.crypto", t.contains("import javax.crypto"))
            assertFalse("${f.name} references :phone", t.contains("com.rokid.context.phone"))
            assertFalse("${f.name} imports android.util", t.contains("import android.util"))
            assertFalse("${f.name} calls Log.x", Regex("""\bLog\.[diwev]\(""").containsMatchIn(t))
        }
    }

    @Test fun aForgetBetweenCaptureAndSubmitRefusesTheJobWithNoRow() = runBlocking {
        val req = h.request()
        h.forget()
        assertEquals(PyOutcome.Voided, svc.runPy(req))
        assertTrue(svc.pendingConfirmations().isEmpty())
        assertTrue(h.dao.openHelperJobs().isEmpty())
        assertEquals(0, svc.waiterCount())
    }

    // ---- the three bounded waits (c8 #2) ----

    @Test fun confirmJustBeforeExpiryThenExecutionGetsFullRuntime() = runBlocking {
        val r = start()
        val b = binding(r.jobId)
        val submittedAt = h.now
        h.advance(HelperProtocol.CONFIRM_TTL_MS - 1)
        assertEquals(ConfirmResult.CONFIRMED, svc.confirm(r.jobId, b))
        assertEquals(r.jobId, h.leaseNext())
        h.advance(HelperProtocol.EXEC_WAIT_MS - HelperProtocol.CONFIRM_TTL_MS + 2_000) // beyond submit + execWaitMs: the old single timer would have fired here
        svc.sweep()
        assertTrue(h.now > submittedAt + HelperProtocol.EXEC_WAIT_MS)
        assertEquals(HelperJobState.LEASED, h.state(r.jobId))
        assertFalse(r.outcome.isCompleted)
        h.advance(HelperProtocol.PY_EXEC_DEADLINE_MS - 1 - (HelperProtocol.EXEC_WAIT_MS - HelperProtocol.CONFIRM_TTL_MS + 2_000)) // heartbeats every second up to lease + deadline - 1
        assertEquals(HelperJobState.LEASED, h.state(r.jobId))
        assertEquals(emptyList<String>(), h.complete(r.jobId, ok2))
        assertTrue(r.await() is PyOutcome.Done)
    }

    @Test fun aResultAtTheDeadlineAfterALateConfirmIsExpired() = runBlocking {
        val r = start()
        val b = binding(r.jobId)
        h.advance(HelperProtocol.CONFIRM_TTL_MS - 1)
        svc.confirm(r.jobId, b)
        h.leaseNext()
        h.advance(HelperProtocol.PY_EXEC_DEADLINE_MS)
        assertTrue(h.complete(r.jobId, ok2).isNotEmpty())
        assertEquals(PyOutcome.Failed("timeout"), r.await())
    }

    @Test fun queueWaitExpiresWithoutLease() = runBlocking {
        val r = start()
        val b = binding(r.jobId)
        h.advance(HelperProtocol.CONFIRM_TTL_MS - 1) // the 30 s queue wait counts from the confirm, not from the submit
        svc.confirm(r.jobId, b)
        h.advance(HelperProtocol.QUEUE_WAIT_MS - 1); svc.sweep()
        assertEquals(HelperJobState.QUEUED, h.state(r.jobId))
        h.advance(1); svc.sweep()
        assertEquals(PyOutcome.Failed("resources_busy"), r.await())
        assertEquals(listOf(r.jobId), cleanupJobs())
        assertNull(svc.lease("h1", listOf("py_exec")))
    }

    @Test fun cancelInEachWaitEnqueuesCleanup() = runBlocking {
        // the executor's coroutine is cancelled during confirmation, then queue, then execution (one job at a time so each phase is exactly that job's)
        val inConfirm = start()
        inConfirm.outcome.cancel()
        awaitState(inConfirm.jobId, HelperJobState.CANCELLED)
        val inQueue = confirmed()
        inQueue.outcome.cancel()
        awaitState(inQueue.jobId, HelperJobState.CANCELLED)
        val inExec = leased()
        inExec.outcome.cancel()
        awaitState(inExec.jobId, HelperJobState.CANCELLED)
        assertEquals(listOf(inConfirm.jobId, inQueue.jobId, inExec.jobId), cleanupJobs())
        assertEquals(0, svc.waiterCount())
        for (r in listOf(inConfirm, inQueue, inExec)) assertNull(h.store.job(r.jobId)!!.payloadJson)
        assertNull(svc.lease("h1", listOf("py_exec")))
        // an explicit cancel in each phase completes the waiter with Cancelled and enqueues the cleanup
        svc.ackCleanup(h.store.pendingCleanup(100).map { it.cleanupId })
        val c1 = start()
        assertTrue(svc.cancel(c1.jobId)); assertEquals(PyOutcome.Cancelled, c1.await())
        val c2 = confirmed()
        assertTrue(svc.cancel(c2.jobId)); assertEquals(PyOutcome.Cancelled, c2.await())
        val c3 = leased()
        assertTrue(svc.cancel(c3.jobId)); assertEquals(PyOutcome.Cancelled, c3.await())
        assertEquals(listOf(c1.jobId, c2.jobId, c3.jobId), cleanupJobs())
    }

    // ---- lease, heartbeat, result ----

    @Test fun heartbeatAfterCancelDenied() = runBlocking {
        val r = leased()
        assertTrue(svc.heartbeat(r.jobId, "h1", 1).ok)
        assertTrue(svc.cancel(r.jobId))
        val hb = svc.heartbeat(r.jobId, "h1", 1)
        assertFalse(hb.ok); assertEquals("cancelled", hb.denial)
        assertEquals("cancelled", svc.releasePayload(r.jobId, "h1", 1).denial)
        assertEquals(ResultCommit.Denied("cancelled"), svc.commitResult(r.jobId, "h1", 1, ok2))
    }

    @Test fun heartbeatAfterVoidDenied() = runBlocking {
        val r = leased()
        assertEquals(1, svc.voidAll("grant_off"))
        assertEquals("voided", svc.heartbeat(r.jobId, "h1", 1).denial)
        assertEquals(PyOutcome.Voided, r.await())
        assertEquals(listOf(r.jobId), cleanupJobs())
    }

    @Test fun aHeartbeatForAForgetMovedFenceIsNeverAcked() = runBlocking {
        val r = leased()
        h.forget()
        val hb = svc.heartbeat(r.jobId, "h1", 1)
        assertFalse(hb.ok); assertEquals("voided", hb.denial)
        assertEquals(PyOutcome.Voided, r.await())
    }

    @Test fun aLeaseThatIsNotRenewedExpiresAndTheRunFails() = runBlocking {
        val r = leased()
        h.now += HelperProtocol.LEASE_MS
        svc.sweep()
        assertEquals(PyOutcome.Failed("killed"), r.await())
        assertEquals(listOf(r.jobId), cleanupJobs())
        assertEquals("lease_expired", svc.heartbeat(r.jobId, "h1", 1).denial)
    }

    @Test fun payloadIsTheImmutableHashedBundleAndCommitIsIdempotent() = runBlocking {
        val r = leased()
        val pay = svc.releasePayload(r.jobId, "h1", 1)
        assertTrue(pay.ok)
        assertEquals(BundleHash.canonical(h.request().payload()), pay.payload)
        assertEquals(h.store.job(r.jobId)!!.bundleHash, BundleHash.ofCanonical(pay.payload!!))
        assertEquals(ResultCommit.Committed(false), svc.commitResult(r.jobId, "h1", 1, ok2))
        assertEquals(ResultCommit.Committed(true), svc.commitResult(r.jobId, "h1", 1, ok2))
        assertTrue(r.await() is PyOutcome.Done)
        assertEquals(listOf(r.jobId), cleanupJobs())
    }

    @Test fun anInvalidResultIsAnInvalidCommitAndKeepsTheLease() = runBlocking {
        val r = leased()
        assertTrue(svc.commitResult(r.jobId, "h1", 1, okResult(listOf("i0"))) is ResultCommit.Invalid)
        assertEquals(HelperJobState.LEASED, h.state(r.jobId))
    }

    // ---- fan-out, waiters ----

    @Test fun waitersCompleteExactlyOnceOnTheFirstTerminalState() = runBlocking {
        val r = leased()
        assertEquals(ResultCommit.Committed(false), svc.commitResult(r.jobId, "h1", 1, ok2))
        val first = r.await()
        assertTrue(first is PyOutcome.Done)
        assertFalse(svc.cancel(r.jobId))
        assertEquals(0, svc.voidAll("late"))
        svc.sweep()
        assertEquals(first, r.await())
        assertEquals(0, svc.waiterCount())
        assertEquals(1, cleanupJobs().count { it == r.jobId })
    }

    @Test fun anOutsideDaoVoidIsSeenOnTheNextSweep() = runBlocking {
        val r = start()
        assertTrue(h.store.void(r.jobId, "frame_forget", h.now))
        assertFalse(r.outcome.isCompleted)
        svc.sweep()
        assertEquals(PyOutcome.Voided, r.await())
        assertEquals(0, svc.waiterCount())
    }

    @Test fun runPyIsBoundedWithoutAnExternalTicker() = runBlocking {
        val own = RealPortHarness(waiterPollMs = 20L)
        try {
            val d = scope.async { own.service.runPy(own.request()) }
            withTimeout(10_000) { while (own.service.pendingConfirmations().isEmpty()) delay(5) }
            own.now += HelperProtocol.CONFIRM_TTL_MS
            assertEquals(PyOutcome.NotConfirmed, withTimeout(10_000) { d.await() })
        } finally { own.close() }
    }

    // ---- readiness ----

    @Test fun readinessIsStaleAfter15sAndOnlyAFreshTrueIsReady() {
        assertEquals(Readiness(false, true, null), svc.readiness())
        svc.advertiseReady(true)
        assertEquals(Readiness(true, false, 0), svc.readiness())
        h.now += HelperProtocol.READY_STALE_MS - 1
        assertTrue(svc.readiness().ready)
        h.now += 1
        assertEquals(Readiness(false, true, HelperProtocol.READY_STALE_MS), svc.readiness())
        svc.advertiseReady(false)
        assertEquals(Readiness(false, false, 0), svc.readiness())
        svc.advertiseReady(true)
        assertTrue(svc.readiness().ready)
    }

    // ---- privacy ----

    @Test fun logLinesAreCountOnly() = runBlocking {
        val r = leased()
        svc.heartbeat(r.jobId, "h1", 1)
        svc.commitResult(r.jobId, "h1", 1, ok2)
        r.await()
        val row = h.store.job(r.jobId)!!
        assertTrue(h.lines.isNotEmpty())
        for (l in h.lines) {
            assertFalse(l, l.contains(r.jobId)); assertFalse(l, l.contains(row.bundleHash!!)); assertFalse(l, l.contains("frame-")); assertFalse(l, l.contains("h1"))
        }
    }

    // ---- review 1 ----

    private class Hooked(val inner: HelperJobStore, val beforeInsert: suspend () -> Unit = {}, val afterCommit: suspend () -> Unit = {}) : HelperJobStore by inner {
        override suspend fun insert(job: com.rokid.context.memory.db.CanvasHelperJobEntity, fence: com.rokid.context.memory.canvas.HelperFence): Boolean { beforeInsert(); return inner.insert(job, fence) }
        override suspend fun commit(jobId: String, helperId: String, leaseGen: Long, resultHash: String, resultJson: String, now: Long): com.rokid.context.memory.canvas.HelperLeaseResult =
            inner.commit(jobId, helperId, leaseGen, resultHash, resultJson, now).also { afterCommit() }
    }

    private fun service(store: HelperJobStore) = HelperJobService(store, { h.now }, { h.sessionGen }, h.readiness, { _, _, _ -> }, Counter()::next, 600_000L)
    private class Counter { private var n = 0; fun next() = "hx_${++n}" }

    @Test fun aSweepDuringInsertDoesNotCompleteTheRun() = runBlocking {
        lateinit var s2: HelperJobService
        s2 = service(Hooked(h.store, beforeInsert = { s2.sweep() }))
        val d = scope.async { s2.runPy(h.request()) }
        val id = withTimeout(10_000) { var f: String? = null; while (f == null) { f = s2.pendingConfirmations().firstOrNull()?.jobId; if (f == null) delay(5) }; f }
        assertFalse("the run ended before its job existed", d.isCompleted)
        val p = s2.pendingConfirmations().single()
        assertEquals(ConfirmResult.CONFIRMED, s2.confirm(id, ConfirmBinding(p.jobId, p.bundleHash, p.sessionGen, p.expiresAtMs)))
        val l = s2.lease("h1", listOf("py_exec"))!!
        assertEquals(ResultCommit.Committed(false), s2.commitResult(l.jobId, "h1", l.leaseGen, ok2))
        assertTrue(withTimeout(10_000) { d.await() } is PyOutcome.Done)
        assertEquals(0, s2.waiterCount())
    }

    @Test fun anErrorInsideRunPyCancelsTheJobAndDropsTheWaiter() = runBlocking {
        val boom = object : HelperJobStore by h.store {
            override suspend fun insert(job: com.rokid.context.memory.db.CanvasHelperJobEntity, fence: com.rokid.context.memory.canvas.HelperFence): Boolean {
                val ok = h.store.insert(job, fence); throw IllegalStateException("after commit")
            }
        }
        val s2 = service(boom)
        val r = runCatching { s2.runPy(h.request()) }
        assertTrue(r.exceptionOrNull() is IllegalStateException)
        assertEquals(0, s2.waiterCount())
        assertTrue(svc.pendingConfirmations().isEmpty())
        assertEquals(1, cleanupJobs().size)
    }

    @Test fun aDoneRowScrubbedByAFrameForgetEndsVoided() = runBlocking {
        val s2 = service(Hooked(h.store, afterCommit = { h.dao.scrubDoneJobsForFrames(listOf("frame-0"), h.now) }))
        val d = scope.async { s2.runPy(h.request()) }
        val id = withTimeout(10_000) { var f: String? = null; while (f == null) { f = s2.pendingConfirmations().firstOrNull()?.jobId; if (f == null) delay(5) }; f }
        val p = s2.pendingConfirmations().single()
        s2.confirm(id, ConfirmBinding(p.jobId, p.bundleHash, p.sessionGen, p.expiresAtMs))
        val l = s2.lease("h1", listOf("py_exec"))!!
        s2.commitResult(l.jobId, "h1", l.leaseGen, ok2)
        assertEquals(PyOutcome.Voided, withTimeout(10_000) { d.await() })
    }
}
