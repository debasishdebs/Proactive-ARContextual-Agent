package com.rokid.context.canvas.helper

import com.rokid.context.memory.canvas.ConfirmBinding
import com.rokid.context.memory.canvas.HelperJobState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The port contract as the in-memory fake implements it (WP5's real service must pass the same contract, WP5 exit criterion). */
class InMemoryHelperJobPortTest {
    @Test fun confirmedJobCompletesThroughTheFakeHelperAndRunPyReturnsDone() = runBlocking {
        val port = InMemoryHelperJobPort(now = { 1_000L }, sessionGen = { 7 }, newId = { "job-1" })
        val run = async(start = CoroutineStart.UNDISPATCHED) { port.runPy(fakeRequest()) }
        val p = port.pendingConfirmations().single()
        assertEquals(2, p.itemCount)
        assertEquals(HelperJobState.AWAITING_CONFIRM, port.state("job-1"))
        assertEquals(ConfirmResult.CONFIRMED, port.confirm("job-1", ConfirmBinding(p.jobId, p.bundleHash, p.sessionGen, p.expiresAtMs)))
        assertEquals("job-1", port.leaseNext())
        assertEquals(emptyList<String>(), port.complete("job-1", okResult(listOf("i1", "i0"))))
        val out = run.await() as PyOutcome.Done
        assertEquals(listOf("i0", "i1"), out.outputs.map { it.id }.sorted())
        assertEquals(HelperJobState.DONE, port.state("job-1"))
    }

    @Test fun fakeHelperCannotCompleteAJobThatIsNotLeased() = runBlocking {
        val port = InMemoryHelperJobPort(now = { 1_000L }, sessionGen = { 7 }, newId = { "job-1" })
        val job = port.submitForTest(fakeRequest())
        assertTrue(port.complete(job, okResult(listOf("i0", "i1"))).isNotEmpty())
        assertEquals(HelperJobState.AWAITING_CONFIRM, port.state(job))
    }

    @Test fun invalidResultIsRefusedAndTheJobStaysLeased() = runBlocking {
        val port = InMemoryHelperJobPort(now = { 1_000L }, sessionGen = { 7 }, newId = { "job-1" })
        val job = port.submitForTest(fakeRequest())
        val p = port.pendingConfirmations().single()
        port.confirm(job, ConfirmBinding(p.jobId, p.bundleHash, p.sessionGen, p.expiresAtMs)); port.leaseNext()
        assertTrue(port.complete(job, okResult(listOf("i0"))).isNotEmpty()) // missing i1
        assertEquals(HelperJobState.LEASED, port.state(job))
    }

    @Test fun badRequestIsRefusedBeforeAJobExists() = runBlocking {
        val port = InMemoryHelperJobPort(now = { 1_000L }, sessionGen = { 7 }, newId = { "job-1" })
        val r = port.runPy(fakeRequest(n = 0))
        assertTrue(r is PyOutcome.Refused)
        assertTrue(port.pendingConfirmations().isEmpty())
    }

    @Test fun cancellingTheCallerCancelsTheJob() = runBlocking {
        val port = InMemoryHelperJobPort(now = { 1_000L }, sessionGen = { 7 }, newId = { "job-1" })
        val run = async(start = CoroutineStart.UNDISPATCHED) { port.runPy(fakeRequest()) }
        yield()
        run.cancel()
        runCatching { run.await() }
        assertEquals(HelperJobState.CANCELLED, port.state("job-1"))
        assertTrue(port.pendingConfirmations().isEmpty())
    }

    @Test fun voidAllEndsOpenJobsVoided() = runBlocking {
        val port = InMemoryHelperJobPort(now = { 1_000L }, sessionGen = { 7 }, newId = { "job-1" })
        val run = async(start = CoroutineStart.UNDISPATCHED) { port.runPy(fakeRequest()) }
        port.voidAll()
        assertEquals(PyOutcome.Voided, run.await())
        assertEquals(HelperJobState.VOIDED, port.state("job-1"))
    }
}
