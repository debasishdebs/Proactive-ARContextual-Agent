package com.rokid.context.canvas

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.canvas.helper.HelperProtocol
import com.rokid.context.memory.canvas.HelperJobState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * WP8: `CanvasRunner.cancel` / `cancelGroup` reach a chunk that is suspended inside the executor, so the helper job ends with the run (real runner, executor, service and DAO).
 * Without the chunk-job cancel the run would wait for the bounded waits of the service (120 s confirmation, 135 s execution); every wait here is capped at 10 s.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RunnerCancelEndsHelperJobTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var h: PyFnRunHarness

    @Before fun setUp() { h = PyFnRunHarness(app) }
    @After fun tearDown() { h.close() }

    private suspend fun state(jobId: String) = HelperJobState.valueOf(h.dao3.helperJob(jobId)!!.state)
    private suspend fun finish10(r: CanvasRunner) = withTimeout(10_000) { h.finish(r) }

    @Test fun cancelWhileAwaitingConfirmEndsTheJobAndTheRun() = runBlocking<Unit> {
        val r = h.runner()
        val id = h.begin(r, h.pipeline(), h.frames(2))
        val job = h.pending().jobId
        assertTrue(r.cancel(id))
        finish10(r)
        assertEquals("CANCELLED", h.dao.run(id)!!.status)
        assertEquals(HelperJobState.CANCELLED, state(job))
        assertTrue("nothing is left to tap", h.service.pendingConfirmations().isEmpty())
        assertTrue("a cleanup command exists for the job", h.dao3.pendingCleanup(100).any { it.jobId == job })
    }

    @Test fun cancelWhileTheHelperHoldsTheLeaseEndsTheJobAndRefusesItsHeartbeat() = runBlocking<Unit> {
        val r = h.runner()
        val id = h.begin(r, h.pipeline(), h.frames(2))
        h.tap(); val lease = h.lease()
        assertTrue(r.cancel(id))
        finish10(r)
        assertEquals("CANCELLED", h.dao.run(id)!!.status)
        assertEquals(HelperJobState.CANCELLED, state(lease.jobId))
        val hb = h.service.heartbeat(lease.jobId, "h1", lease.leaseGen)
        assertFalse("the helper's next heartbeat is not acked", hb.ok)
        assertEquals("cancelled", hb.denial)
        assertTrue(h.dao3.pendingCleanup(100).any { it.jobId == lease.jobId })
    }

    @Test fun cancelAbRunWhileASideIsSuspendedCancelsTheJobAndBNeverStarts() = runBlocking<Unit> {
        val r = h.runner()
        val g = h.beginAb(r, h.pipeline("a"), h.pipeline("b"), h.frames(2))
        val job = h.pending().jobId
        assertTrue(r.cancel(g.runA))
        finish10(r)
        assertEquals("CANCELLED", h.dao.run(g.runA)!!.status)
        assertEquals("CANCELLED", h.dao.run(g.runB)!!.status)
        assertTrue("B never started: no item row", h.dao.items(g.runB).isEmpty())
        assertEquals(HelperJobState.CANCELLED, state(job))
        assertEquals("only A's job ever existed", 1, h.submitted.get())
    }

    @Test fun cancelGroupWhileASideIsSuspendedCancelsTheJob() = runBlocking<Unit> {
        val r = h.runner()
        val g = h.beginAb(r, h.pipeline("a"), h.pipeline("b"), h.frames(2))
        val job = h.pending().jobId
        assertTrue(r.cancelGroup(g.group))
        finish10(r)
        assertEquals("CANCELLED", h.dao.run(g.runA)!!.status); assertEquals("CANCELLED", h.dao.run(g.runB)!!.status)
        assertEquals(HelperJobState.CANCELLED, state(job))
        assertEquals(1, h.submitted.get())
    }

    @Test fun cancelBetweenChunksStillStopsBeforeTheNextJob() = runBlocking<Unit> {
        // the flag path is unchanged: a cancel that lands before the chunk starts creates no job at all
        val r = h.runner()
        h.hotNow = { false }
        h.photoLoad = { r.cancel("run1") } // run1 is active while the frames load
        val id = h.begin(r, h.pipeline(), h.frames(2))
        finish10(r)
        assertEquals("CANCELLED", h.dao.run(id)!!.status)
        assertEquals(0, h.submitted.get())
    }

    @Test fun aSecondRunStartsAfterACancelledOne() = runBlocking<Unit> {
        val r = h.runner()
        val first = h.begin(r, h.pipeline("one"), h.frames(2))
        h.pending(); r.cancel(first); finish10(r)
        val second = h.begin(r, h.pipeline("two"), h.frames(2))
        h.tap(); h.answer(h.lease())
        finish10(r)
        assertEquals("DONE", h.dao.run(second)!!.status)
    }
}
