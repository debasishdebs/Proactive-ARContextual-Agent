package com.rokid.context.canvas.helper

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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `HelperJobPort` contract (WP5 exit criterion: "port fake and real service pass the same contract test"). Subclasses supply the harness; every case runs unchanged
 * against [InMemoryHelperJobPort] (WP1) and the real [HelperJobService]. The fake's behaviour is the contract.
 */
abstract class HelperJobPortContract {
    internal abstract fun newHarness(): PortHarness
    internal val h: PortHarness by lazy { newHarness() }
    private val scope = CoroutineScope(Dispatchers.Default)
    private val known = HashSet<String>()

    @After fun closeAll() { scope.cancel(); h.close() }

    private class Run(val jobId: String, val outcome: Deferred<PyOutcome>)

    private suspend fun start(req: PyRequest): Run {
        val d = scope.async { h.port.runPy(req) }
        val id = withTimeout(10_000) {
            var found: String? = null
            while (found == null) { found = h.port.pendingConfirmations().map { it.jobId }.firstOrNull { it !in known }; if (found == null) delay(5) }
            found
        }
        known += id
        return Run(id, d)
    }

    private suspend fun Run.await(): PyOutcome = withTimeout(10_000) { outcome.await() }

    private suspend fun binding(jobId: String): ConfirmBinding {
        val p = h.port.pendingConfirmations().first { it.jobId == jobId }
        return ConfirmBinding(p.jobId, p.bundleHash, p.sessionGen, p.expiresAtMs)
    }

    private suspend fun confirmed(): Run {
        val r = start(h.request())
        assertEquals(ConfirmResult.CONFIRMED, h.port.confirm(r.jobId, binding(r.jobId)))
        return r
    }

    private suspend fun leasedRun(): Run { val r = confirmed(); assertEquals(r.jobId, h.leaseNext()); return r }

    private val ok2 = okResult(listOf("i0", "i1"))

    @Test fun confirmedJobCompletesThroughTheHelperAndRunPyReturnsDone() = runBlocking {
        val r = start(h.request())
        assertEquals(2, h.port.pendingConfirmations().single().itemCount)
        assertEquals(HelperJobState.AWAITING_CONFIRM, h.state(r.jobId))
        assertEquals(ConfirmResult.CONFIRMED, h.port.confirm(r.jobId, binding(r.jobId)))
        assertEquals(r.jobId, h.leaseNext())
        assertEquals(emptyList<String>(), h.complete(r.jobId, okResult(listOf("i1", "i0"))))
        val out = r.await() as PyOutcome.Done
        assertEquals(listOf("i0", "i1"), out.outputs.map { it.id }.sorted())
        assertEquals(5L, out.ms)
        assertEquals(HelperJobState.DONE, h.state(r.jobId))
    }

    @Test fun anUnconfirmedJobIsNeverLeasedAndCannotBeCompleted() = runBlocking {
        val r = start(h.request())
        assertNull(h.leaseNext())
        assertTrue(h.complete(r.jobId, ok2).isNotEmpty())
        assertEquals(HelperJobState.AWAITING_CONFIRM, h.state(r.jobId))
    }

    @Test fun anInvalidResultIsRefusedAndTheJobStaysLeased() = runBlocking {
        val r = leasedRun()
        assertTrue(h.complete(r.jobId, okResult(listOf("i0"))).isNotEmpty())
        assertEquals(HelperJobState.LEASED, h.state(r.jobId))
    }

    @Test fun aBadRequestIsRefusedBeforeAJobExists() = runBlocking {
        assertTrue(h.port.runPy(h.request(n = 0)) is PyOutcome.Refused)
        assertTrue(h.port.pendingConfirmations().isEmpty())
    }

    @Test fun fractionalInputOrSchemaBoundIsRefusedNotThrown() = runBlocking {
        val base = h.request()
        val frac = PyRequest(base.source, listOf(PyItem("i0", kotlinx.serialization.json.JsonObject(mapOf("x" to kotlinx.serialization.json.JsonPrimitive(1.5))))), base.outSchema, base.frameIds, base.fence)
        assertTrue(h.port.runPy(frac) is PyOutcome.Refused)
        assertTrue(h.port.pendingConfirmations().isEmpty())
    }

    @Test fun cancellingTheCallerCancelsTheJob() = runBlocking {
        val r = start(h.request())
        r.outcome.cancel()
        withTimeout(10_000) { while (h.state(r.jobId) != HelperJobState.CANCELLED) delay(5) }
        assertTrue(h.port.pendingConfirmations().isEmpty())
    }

    @Test fun voidAllEndsOpenJobsVoided() = runBlocking {
        val r = start(h.request())
        h.voidAll()
        assertEquals(PyOutcome.Voided, r.await())
        assertEquals(HelperJobState.VOIDED, h.state(r.jobId))
    }

    @Test fun cancelEndsAnOpenJobCancelled() = runBlocking {
        val r = leasedRun()
        assertTrue(h.cancel(r.jobId))
        assertEquals(PyOutcome.Cancelled, r.await())
        assertEquals(HelperJobState.CANCELLED, h.state(r.jobId))
        assertTrue(!h.cancel(r.jobId))
    }

    @Test fun resultJustBeforeTheExecutionDeadlineIsAccepted() = runBlocking {
        val r = leasedRun()
        h.advance(HelperProtocol.PY_EXEC_DEADLINE_MS - 1)
        assertEquals(emptyList<String>(), h.complete(r.jobId, ok2))
        assertEquals(HelperJobState.DONE, h.state(r.jobId))
        assertTrue(r.await() is PyOutcome.Done)
    }

    @Test fun resultAtTheExecutionDeadlineIsRefusedAndTheJobFailsTimeout() = runBlocking {
        val r = leasedRun()
        h.advance(HelperProtocol.PY_EXEC_DEADLINE_MS)
        assertTrue(h.complete(r.jobId, ok2).isNotEmpty())
        assertEquals(HelperJobState.FAILED, h.state(r.jobId))
        assertEquals(PyOutcome.Failed("timeout"), r.await())
    }

    @Test fun aLeasedJobThatNeverReportsExpiresOnTick() = runBlocking {
        val r = leasedRun()
        h.advance(HelperProtocol.PY_EXEC_DEADLINE_MS)
        h.tick()
        assertEquals(HelperJobState.FAILED, h.state(r.jobId))
        assertEquals(PyOutcome.Failed("timeout"), r.await())
        assertNull(h.leaseNext())
    }

    @Test fun theHelperCanFailALeasedJobWithAKnownCodeOnly() = runBlocking {
        val r = leasedRun()
        assertTrue(!h.fail(r.jobId, "not_a_code"))
        assertTrue(h.fail(r.jobId, "oom"))
        assertEquals(PyOutcome.Failed("oom"), r.await())
    }

    @Test fun confirmIsIdempotentAndRefusesAnotherBinding() = runBlocking {
        val r = start(h.request())
        val b = binding(r.jobId)
        assertEquals(ConfirmResult.MISMATCH, h.port.confirm(r.jobId, b.copy(bundleHash = "x".repeat(64))))
        assertEquals(ConfirmResult.MISMATCH, h.port.confirm(r.jobId, b.copy(sessionGen = b.sessionGen + 1)))
        assertEquals(ConfirmResult.MISMATCH, h.port.confirm(r.jobId, b.copy(expiresAtMs = b.expiresAtMs + 1)))
        assertEquals(ConfirmResult.CONFIRMED, h.port.confirm(r.jobId, b))
        assertEquals(ConfirmResult.DUPLICATE, h.port.confirm(r.jobId, b))
        assertEquals(ConfirmResult.NOT_FOUND, h.port.confirm("nope", b.copy(jobId = "nope")))
        assertTrue(h.port.pendingConfirmations().isEmpty())
    }

    @Test fun aTapAfterTheWindowClosedIsExpiredAndTheRunEndsNotConfirmed() = runBlocking {
        val r = start(h.request())
        val b = binding(r.jobId)
        h.advance(HelperProtocol.CONFIRM_TTL_MS)
        assertEquals(ConfirmResult.EXPIRED, h.port.confirm(r.jobId, b))
        assertEquals(PyOutcome.NotConfirmed, r.await())
        assertNull(h.leaseNext())
    }

    @Test fun anUnconfirmedJobExpiresOnTickAndLeavesThePendingList() = runBlocking {
        val r = start(h.request())
        h.advance(HelperProtocol.CONFIRM_TTL_MS - 1)
        h.tick()
        assertEquals(1, h.port.pendingConfirmations().size)
        h.advance(1)
        h.tick()
        assertEquals(PyOutcome.NotConfirmed, r.await())
        assertTrue(h.port.pendingConfirmations().isEmpty())
        assertTrue(!h.port.awaitingConfirm(r.jobId))
    }

    @Test fun declineEndsTheRunNotConfirmedOnce() = runBlocking {
        val r = start(h.request())
        assertTrue(h.port.awaitingConfirm(r.jobId))
        assertTrue(h.port.declineConfirm(r.jobId))
        assertTrue(!h.port.declineConfirm(r.jobId))
        assertEquals(PyOutcome.NotConfirmed, r.await())
        assertEquals(ConfirmResult.NOT_AWAITING, h.port.confirm(r.jobId, ConfirmBinding(r.jobId, "h", 7, 0)))
    }

    @Test fun aTapAfterAForgetIsRefusedAndTheJobIsVoidedWithNothingReleased() = runBlocking {
        val r = start(h.request())
        val b = binding(r.jobId)
        h.forget()
        assertEquals(ConfirmResult.STALE_FENCE, h.port.confirm(r.jobId, b))
        assertEquals(PyOutcome.Voided, r.await())
        assertEquals(HelperJobState.VOIDED, h.state(r.jobId))
        assertNull(h.leaseNext())
    }

    @Test fun aConfirmedJobThatIsNotLeasedWithinTheQueueWaitFailsResourcesBusy() = runBlocking {
        val r = confirmed()
        h.advance(HelperProtocol.QUEUE_WAIT_MS - 1); h.tick()
        assertEquals(HelperJobState.QUEUED, h.state(r.jobId))
        h.advance(1); h.tick()
        assertEquals(PyOutcome.Failed("resources_busy"), r.await())
        assertNull(h.leaseNext())
    }

    @Test fun onlyOneJobIsLeasedAtATime() = runBlocking {
        val a = confirmed()
        val b = confirmed()
        assertEquals(a.jobId, h.leaseNext())
        assertNull(h.leaseNext())
        assertEquals(emptyList<String>(), h.complete(a.jobId, ok2))
        assertEquals(b.jobId, h.leaseNext())
        assertNotEquals(a.jobId, b.jobId)
    }
}
