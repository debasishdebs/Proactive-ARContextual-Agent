package com.rokid.context.canvas

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.canvas.PipelineNode
import com.rokid.context.canvas.helper.HelperProtocol
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.canvas.HelperFence
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.forget.ForgetService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** WP8: a `py.fn` run end to end through the REAL runner, executor, job service and DAO (in-memory Room). The test plays the tap and the laptop helper. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PyFnRunTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var h: PyFnRunHarness

    @Before fun setUp() { h = PyFnRunHarness(app) }
    @After fun tearDown() { h.close() }

    private suspend fun fnItems(runId: String) = h.dao.items(runId).filter { it.nodeId == "fn" }.sortedBy { it.frameId }
    private fun forgetService() = ForgetService(h.db, ProposalRepository(h.db.proposalDao(), h.db.actionDao(), h.db)) { h.now }
    private suspend fun frame(id: String) = h.db.frameDao().insertIgnore(FrameEntity(id, 1, "/$id.jpg", "rokid_glasses"))

    // ---- the happy path and the batch size ----

    @Test fun fiftyPhotoRunWithPythonNodeIsOneJob() = runBlocking<Unit> {
        val r = h.runner()
        val id = h.begin(r, h.pipeline(), h.frames(50))
        h.tap(); h.answer(h.lease())
        h.finish(r)
        val run = checkNotNull(h.dao.run(id))
        assertEquals("DONE", run.status); assertEquals(0, run.failures)
        assertEquals("one py.fn node over 50 frames is exactly one job", 1, h.submitted.get())
        val out = fnItems(id)
        assertEquals(50, out.size); assertTrue(out.all { it.status == "OK" && it.error == null && it.outputJson!!.contains("\"n\"") })
    }

    @Test fun aHelperFailureBecomesTheFixedItemCodeOnEveryItem() = runBlocking<Unit> {
        for ((code, want) in listOf("resources_busy" to "resources_busy", "timeout" to "timeout", "oom" to "oom")) {
            val r = h.runner()
            val id = h.begin(r, h.pipeline("p$code"), h.frames(2))
            h.tap(); val l = h.lease()
            check(h.service.failJob(l.jobId, "h1", l.leaseGen, code))
            h.finish(r)
            assertEquals(code, listOf(want, want), fnItems(id).map { it.error })
            assertEquals("DONE", h.dao.run(id)!!.status)
        }
    }

    @Test fun aSilentHelperEndsTheItemsKilled() = runBlocking<Unit> {
        val r = h.runner()
        val id = h.begin(r, h.pipeline(), h.frames(2))
        h.tap(); h.lease() // leased, then the helper goes silent: the lease (10 s) runs out
        h.now += HelperProtocol.LEASE_MS + 1
        h.finish(r)
        assertEquals(listOf("killed", "killed"), fnItems(id).map { it.error })
    }

    // ---- declined or expired confirmation ----

    @Test fun declinedOrExpiredConfirmFailsItemsNotConfirmedWithNoPayloadReleased() = runBlocking<Unit> {
        for (how in listOf("declined", "expired")) {
            val r = h.runner()
            val id = h.begin(r, h.pipeline("p$how"), h.frames(2))
            val jobId = if (how == "declined") h.decline() else h.pending().jobId.also { h.now += HelperProtocol.CONFIRM_TTL_MS + 1 }
            h.finish(r)
            assertEquals(how, listOf("not_confirmed", "not_confirmed"), fnItems(id).map { it.error })
            assertEquals(how, "DONE", h.dao.run(id)!!.status)
            val row = h.dao3.helperJob(jobId)!!
            assertNull("$how: the payload was never released to anyone (nulled at the end)", row.payloadJson)
            assertTrue("$how: it never became leasable", h.service.available(listOf("py_exec")).isEmpty())
            assertNull("$how: and cannot be leased", h.service.lease("h1", listOf("py_exec")))
            assertEquals("$how: it was never confirmed", null, row.confirmedAtMs)
        }
    }

    // ---- Forget ----

    @Test fun frameRunWriteAfterForgetStoresNothing() = runBlocking<Unit> {
        frame("f1"); frame("f2")
        var forgotten: ForgetService.Result? = null
        // the helper's result is committed (the job is DONE), then the source frame is forgotten through the REAL frame entry point, then the runner writes
        val forgetAfter = object : HelperExecutor by h.py {
            override suspend fun run(node: PipelineNode, items: List<CanvasItem>, ctx: RunContext): List<NodeResult> =
                h.py.run(node, items, ctx).also { forgotten = forgetService().forgetMoment("f1") }
        }
        val r = h.runner(py = forgetAfter)
        val id = h.begin(r, h.pipeline(), listOf("f1", "f2"))
        h.tap(); h.answer(h.lease())
        h.finish(r)
        assertTrue("precondition: the frame row existed and forgetMoment found something", forgotten!!.framesToDelete.isNotEmpty())
        assertEquals("CANCELLED", h.dao.run(id)!!.status)
        assertEquals("the fenced write was refused: not one fn row, for any frame", emptyList<String>(), fnItems(id).map { it.frameId })
        assertTrue("no row carries the forgotten frame at all", h.dao.items(id).none { it.frameId == "f1" })
        val cleanup = h.dao3.pendingCleanup(100)
        val forgetRow = cleanup.firstOrNull { it.reason == "frame_forget" }
        assertNotNull("a cleanup command exists for the job whose result held the frame (beside the one its result commit enqueued)", forgetRow)
        assertNull("and its stored result was scrubbed", h.dao3.helperJob(forgetRow!!.jobId)!!.resultJson)
    }

    @Test fun aForgetOfAnyFrameVoidsTheJobAndEndsTheRunWithItsOwnStatus() = runBlocking<Unit> {
        frame("zz") // some other frame: ANY Forget voids every open job (fail closed)
        val r = h.runner()
        val id = h.begin(r, h.pipeline(), h.frames(2))
        h.pending() // the job awaits its tap
        assertTrue(forgetService().forgetMoment("zz").framesToDelete.isNotEmpty())
        h.finish(r)
        val run = h.dao.run(id)!!
        assertEquals("STOPPED_FORGET", run.status)
        assertEquals(listOf("frame_forget", "frame_forget"), fnItems(id).map { it.error })
        assertNull("nothing was ever released", h.service.lease("h1", listOf("py_exec")))
    }

    @Test fun aDeleteAllDuringTheRunStillEndsItCancelled() = runBlocking<Unit> {
        val r = h.runner()
        val id = h.begin(r, h.pipeline(), h.frames(2))
        h.pending()
        h.gen += 1; h.service.voidAll("test") // Delete all: the wipe generation moves and every open job is voided
        h.finish(r)
        assertEquals("CANCELLED", h.dao.run(id)!!.status)
    }

    // ---- the fence ----

    @Test fun helperFenceCapturedBeforeFramesLoad() = runBlocking<Unit> {
        var seen: HelperFence? = null
        val spy = object : HelperExecutor {
            override val type = NodeTypes.PY_FN
            override fun nominalCalls(node: PipelineNode, items: Int) = 0
            override fun maxCalls(node: PipelineNode, items: Int) = 0
            override suspend fun captureFence(): HelperFence { h.events += "fence"; return h.fenceSource() }
            override suspend fun run(node: PipelineNode, items: List<CanvasItem>, ctx: RunContext): List<NodeResult> {
                seen = kotlin.coroutines.coroutineContext[RunHelperFence]?.fence
                return items.map { NodeResult(it.frameId, CanvasValue.Json(kotlinx.serialization.json.JsonObject(emptyMap()))) }
            }
        }
        h.photoLoad = { h.events += "load"; runBlocking { h.dao3.bumpFrameForgetRev() } } // a Forget lands DURING the load
        val before = h.dao3.frameForgetRev()
        h.run(h.runner(py = spy), h.pipeline(), h.frames(2))
        assertEquals(listOf("fence", "load"), h.events.toList())
        assertEquals("the executor sees the fence of BEFORE the load", before, seen!!.frameForgetRev)
        assertNotEquals("(and the revision did move meanwhile)", before, h.dao3.frameForgetRev())
    }

    @Test fun aRunWithoutAnyHelperNodeCapturesNoFence() = runBlocking<Unit> {
        var captured = 0
        val spy = object : HelperExecutor {
            override val type = NodeTypes.PY_FN
            override fun nominalCalls(node: PipelineNode, items: Int) = 0
            override fun maxCalls(node: PipelineNode, items: Int) = 0
            override suspend fun captureFence(): HelperFence { captured++; return h.fenceSource() }
            override suspend fun run(node: PipelineNode, items: List<CanvasItem>, ctx: RunContext) = items.map { NodeResult(it.frameId, null, error = "x") }
        }
        val plain = h.pipeline().let { it.copy(nodes = it.nodes.filter { n -> n.id != "fn" }, edges = it.edges.filter { e -> e.to != "fn" }) }
        h.run(h.runner(py = spy), plain, h.frames(1))
        assertEquals(0, captured)
    }
}
