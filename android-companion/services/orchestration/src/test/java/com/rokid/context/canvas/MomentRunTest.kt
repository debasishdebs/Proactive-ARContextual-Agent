package com.rokid.context.canvas

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.canvas.Pipeline
import com.rokid.context.ai.canvas.PipelineEdge
import com.rokid.context.ai.canvas.PipelineNode
import com.rokid.context.canvas.moment.DaoMomentSource
import com.rokid.context.canvas.moment.MomentId
import com.rokid.context.canvas.moment.MomentResolver
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.forget.ForgetService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Moment-lite: a Moment run through the REAL runner, executor, job service and DAO over in-memory Room: what the helper receives, the freshness binding, and the Forget races. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MomentRunTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var h: PyFnRunHarness
    private val sec = 1_761_000_000L
    private val ms = sec * 1000
    private val m0 = MomentId.format(sec)
    private val m1 = MomentId.format(sec + 5)
    private val label: suspend (PipelineNode) -> ModelLabel? = { n -> ModelLabel(n.id, "az", "gpt") }

    @Before fun setUp() { h = PyFnRunHarness(app) }
    @After fun tearDown() { h.close() }

    private fun forgetService() = ForgetService(h.db, ProposalRepository(h.db.proposalDao(), h.db.actionDao(), h.db)) { h.now }
    private suspend fun frame(id: String, ts: Long) = h.db.frameDao().insertIgnore(FrameEntity(id, ts, "/$id.jpg", "rokid_glasses"))
    private suspend fun seed() { frame("fA", ms + 10); frame("fB", ms + 900); frame("fC", ms + 5000) } // m0 has two photos, m1 has one

    /** Counts `frames` reads (one per resolved Moment) and lets a test move the data at a chosen read. */
    private var framesReads = 0
    private var atRead: Pair<Int, suspend () -> Unit>? = null
    private fun resolver() = MomentResolver(object : com.rokid.context.canvas.moment.MomentSource by DaoMomentSource(h.dao, h.dao3) {
        override suspend fun frames(fromMs: Long, toMs: Long, limit: Int): List<com.rokid.context.canvas.moment.MomentFrame> {
            framesReads++
            atRead?.takeIf { it.first == framesReads }?.second?.invoke()
            return h.dao.momentFrames(fromMs, toMs, limit).map { com.rokid.context.canvas.moment.MomentFrame(it.id, it.ts, it.rev) }
        }
    })
    private fun runner(py: NodeExecutor = h.py) = CanvasRunner(
        h.dao, { f -> PhotoLoad(f.map(h::photo), emptySet()) }, mapOf(NodeTypes.PROMPT to h.prompt, py.type to py), { id ->
            val ai = com.rokid.context.orchestration.TestAi.graph(app) { "" }
            RunContext(id, ai.runner, ai.router, ai.prompts, ai.persona, { role -> ai.bindings.get(role) })
        },
        h.tokens, { false }, { h.now }, { "run${++h.ids}" }, { h.gen }, { _, _, m -> h.log += m }, moments = resolver(),
    )

    private fun pipeline(id: String = "mp") = Pipeline(
        2, id, "P",
        listOf(
            PipelineNode("moments", NodeTypes.INPUT_MOMENTS, "moments", JsonObject(emptyMap())),
            PipelineNode("fn", NodeTypes.PY_FN, "fn", buildJsonObject { put("source", "def run(x):\n    return {'n': 1}\n"); put("outSchema", h.outSchema) }),
        ),
        listOf(PipelineEdge("moments", "fn")),
    )

    private suspend fun fnItems(runId: String) = h.dao.items(runId).filter { it.nodeId == "fn" }.sortedBy { it.frameId }
    private suspend fun estimate(r: CanvasRunner, p: Pipeline = pipeline(), ids: List<String> = listOf(m0, m1)) = r.estimateMoments(p, ids, label) as Estimate.Ok
    private suspend fun start(r: CanvasRunner, tok: String, p: Pipeline = pipeline(), ids: List<String> = listOf(m0, m1)) = r.startMoments(p, p.id, null, ids, tok, h.scope)

    // ---- the happy path and what the helper receives ----

    @Test fun momentRunIsOneJobWithOneItemPerMomentAndRowsKeyedByMoment() = runBlocking<Unit> {
        seed()
        val r = runner()
        val e = estimate(r)
        assertEquals("moments", e.kind); assertEquals(2, e.photos); assertEquals(3, e.photoCount); assertEquals(0, e.nominal)
        val id = start(r, e.confirmToken)
        h.tap(); h.answer(h.lease())
        h.finish(r)
        assertEquals("DONE", h.dao.run(id)!!.status)
        assertEquals(1, h.submitted.get())
        val out = fnItems(id)
        assertEquals(listOf("moment:$m0", "moment:$m1"), out.map { it.frameId })
        assertTrue(out.all { it.status == "OK" })
    }

    @Test fun theHelperPayloadHasTheMomentShapeAndNeitherFrameIdsNorBytes() = runBlocking<Unit> {
        seed()
        val r = runner()
        start(r, estimate(r).confirmToken)
        h.tap()
        val l = checkNotNull(h.service.lease("h1", listOf("py_exec")))
        val payload = checkNotNull(h.service.releasePayload(l.jobId, "h1", l.leaseGen).payload)
        for (secret in listOf("fA", "fB", "fC", ".jpg", "jpegRef", "frameId", "moment:")) assertFalse("payload must not carry $secret", payload.contains(secret))
        val items = kotlinx.serialization.json.Json.parseToJsonElement(payload).jsonObject.getValue("items").jsonArray
        assertEquals(listOf("i0", "i1"), items.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        val first = items[0].jsonObject.getValue("input").jsonObject
        assertEquals(setOf("itemType", "momentId", "contentRev", "contentHash", "startMs", "photos", "sensor"), first.keys)
        assertEquals("moment", first.getValue("itemType").jsonPrimitive.content)
        assertEquals(m0, first.getValue("momentId").jsonPrimitive.content)
        assertEquals(ms.toString(), first.getValue("startMs").jsonPrimitive.content)
        assertEquals(listOf(ms + 10, ms + 900), first.getValue("photos").jsonArray.map { it.jsonObject.getValue("tsMs").jsonPrimitive.content.toLong() })
        assertTrue(first.getValue("photos").jsonArray.all { it.jsonObject.keys == setOf("tsMs") })
        assertEquals("null", first.getValue("sensor").toString())
        assertEquals(64, first.getValue("contentHash").jsonPrimitive.content.length)
        assertEquals(1, items[1].jsonObject.getValue("input").jsonObject.getValue("photos").jsonArray.size)
        h.answer(PyFnRunHarness.Lease(l.jobId, l.leaseGen, listOf("i0", "i1"))); h.finish(r)
    }

    @Test fun nothingIsPersistedByTheMomentLayerAndTheSchemaStaysV8() = runBlocking<Unit> {
        seed()
        val before = h.db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }
        resolver().window(0, ms * 2, 50); resolver().resolve(listOf(m0))
        val after = h.db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }
        assertEquals(before, after); assertEquals(8, h.db.openHelper.readableDatabase.version)
        assertTrue("no moment table", after.none { it.contains("moment", ignoreCase = true) })
    }

    // ---- freshness binding ----

    @Test fun aLatePhotoBetweenEstimateAndStartRefusesAndRunsNothing() = runBlocking<Unit> {
        seed()
        val r = runner()
        val e = estimate(r)
        frame("fLate", ms + 400)
        assertMoved { start(r, e.confirmToken) }
        assertNull("no run row", h.dao.runs(5).firstOrNull()); assertEquals(0, h.submitted.get())
        assertFalse("the runner is free again", r.active.value != null)
    }

    @Test fun aNewMemoryOnAMemberBetweenEstimateAndStartRefuses() = runBlocking<Unit> {
        seed()
        val r = runner()
        val e = estimate(r)
        h.db.memoryDao().insert(com.rokid.context.memory.db.MemoryEntity("m1", "fA", "a cat", "fact", "", com.rokid.context.memory.db.MemoryTier.SHORT, 0.9, com.rokid.context.memory.db.ReviewState.OK, true, ms, false, "a cat"))
        assertMoved { start(r, e.confirmToken) }
    }

    @Test fun aCorrectedMarkBetweenEstimateAndStartRefuses() = runBlocking<Unit> {
        seed()
        val r = runner()
        val e = estimate(r)
        h.db.openHelper.writableDatabase.execSQL("INSERT INTO canvas_label (labelId, kind, source, name, atMs, provenance, deletionCounter, createdAt) VALUES ('L1', 'MOMENT', 'tap', 'x', ${ms + 100}, '', 0, $ms)")
        assertMoved { start(r, e.confirmToken) }
    }

    @Test fun aForgetOfAMemberBetweenEstimateAndStartRefuses() = runBlocking<Unit> {
        seed()
        val r = runner()
        val e = estimate(r)
        assertTrue(forgetService().forgetMoment("fB").framesToDelete.isNotEmpty())
        assertMoved { start(r, e.confirmToken) }
        assertNull(h.dao.runs(5).firstOrNull()); assertEquals(0, h.submitted.get())
    }

    @Test fun aChangeBetweenTheStartCheckAndTheFenceCaptureCancelsTheRunBeforeAnythingIsSent() = runBlocking<Unit> {
        seed()
        val r = runner()
        val e = estimate(r) // reads 1..2
        atRead = 5 to { frame("fSneak", ms + 300) } // reads 3..4 are the start check; read 5 is the run's own re-resolve right after the fence capture
        val id = start(r, e.confirmToken)
        h.finish(r)
        assertEquals("CANCELLED", h.dao.run(id)!!.status)
        assertEquals(0, h.submitted.get()); assertTrue(h.dao.items(id).isEmpty())
    }

    @Test fun anUnchangedMomentStartsAndATokenIsSingleUse() = runBlocking<Unit> {
        seed()
        val r = runner()
        val e = estimate(r)
        val id = start(r, e.confirmToken)
        h.tap(); h.answer(h.lease()); h.finish(r)
        assertEquals("DONE", h.dao.run(id)!!.status)
        assertTrue(runCatching { start(r, e.confirmToken) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun aTokenForOtherMomentsOrAnotherPipelineIsABadToken() = runBlocking<Unit> {
        seed()
        val r = runner()
        val e = estimate(r, ids = listOf(m0))
        assertEquals("bad_token", runCatching { start(r, e.confirmToken, ids = listOf(m0, m1)) }.exceptionOrNull()?.message)
        val e2 = estimate(r)
        assertEquals("bad_token", runCatching { start(r, e2.confirmToken, p = pipeline("other")) }.exceptionOrNull()?.message)
    }

    @Test fun badSelectionsAreRefusedAtEstimate() = runBlocking<Unit> {
        seed()
        val r = runner()
        assertTrue(r.estimateMoments(pipeline(), emptyList(), label) is Estimate.Refused)
        assertTrue("a second with no photo", r.estimateMoments(pipeline(), listOf(MomentId.format(sec + 3)), label) is Estimate.Refused)
        assertTrue("a photo pipeline", r.estimateMoments(h.pipeline(), listOf(m0), label) is Estimate.Refused)
        assertTrue("a moments pipeline over frame ids", r.estimate(pipeline(), listOf("fA"), label) is Estimate.Refused)
        assertTrue(runCatching { r.estimateMoments(pipeline(), listOf("zz"), label) }.exceptionOrNull() is IllegalArgumentException)
    }

    // ---- Forget races ----

    @Test fun aForgetOfAMemberMidRunVoidsTheJobAndTheFencedWriteStoresNothing() = runBlocking<Unit> {
        seed()
        val r = runner()
        val id = start(r, estimate(r).confirmToken)
        h.pending() // the job awaits its tap
        assertTrue(forgetService().forgetMoment("fA").framesToDelete.isNotEmpty())
        h.finish(r)
        assertEquals("the member is tombstoned, so the fenced write is refused and the run ends CANCELLED (as a photo run does)", "CANCELLED", h.dao.run(id)!!.status)
        assertTrue("not one fn row", fnItems(id).isEmpty())
        assertNull("the job was voided: nothing was ever released", h.service.lease("h1", listOf("py_exec")))
    }

    @Test fun aForgetOfAnyOtherFrameMidRunVoidsTheJobAndEndsTheRunStoppedForget() = runBlocking<Unit> {
        seed(); frame("zz", ms + 90_000) // not a member: ANY Forget voids every open job (fail closed)
        val r = runner()
        val id = start(r, estimate(r).confirmToken)
        h.pending()
        assertTrue(forgetService().forgetMoment("zz").framesToDelete.isNotEmpty())
        h.finish(r)
        assertEquals("STOPPED_FORGET", h.dao.run(id)!!.status)
        assertEquals(listOf("frame_forget", "frame_forget"), fnItems(id).map { it.error })
        assertEquals(listOf("moment:$m0", "moment:$m1"), fnItems(id).map { it.frameId })
        assertNull("nothing was ever released", h.service.lease("h1", listOf("py_exec")))
    }

    @Test fun aForgetAfterTheResultCommitStoresNothingForTheMoment() = runBlocking<Unit> {
        seed()
        val forgetAfter = object : HelperExecutor by h.py {
            override suspend fun run(node: PipelineNode, items: List<CanvasItem>, ctx: RunContext): List<NodeResult> =
                h.py.run(node, items, ctx).also { forgetService().forgetMoment("fC") }
        }
        val r = runner(forgetAfter)
        val id = start(r, estimate(r).confirmToken)
        h.tap(); h.answer(h.lease()); h.finish(r)
        assertEquals("CANCELLED", h.dao.run(id)!!.status)
        assertTrue("the fenced write was refused: not one fn row", fnItems(id).isEmpty())
    }

    @Test fun aForgetOfAMemberAfterTheRunDeletesTheMomentsRows() = runBlocking<Unit> {
        seed()
        val r = runner()
        val id = start(r, estimate(r).confirmToken)
        h.tap(); h.answer(h.lease()); h.finish(r)
        assertEquals(4, h.dao.items(id).size) // moments + fn rows of two Moments
        assertTrue(forgetService().forgetMoment("fB").framesToDelete.isNotEmpty())
        val left = h.dao.items(id)
        assertEquals("only the untouched Moment keeps its rows", setOf("moment:$m1"), left.map { it.frameId }.toSet())
        assertTrue(left.all { it.batchId!!.contains("mf:fC") && !it.batchId!!.contains("fA") })
    }

    @Test fun deleteAllDuringAMomentRunStillEndsItCancelled() = runBlocking<Unit> {
        seed()
        val r = runner()
        val id = start(r, estimate(r).confirmToken)
        h.pending()
        h.gen += 1; h.service.voidAll("test")
        h.finish(r)
        assertEquals("CANCELLED", h.dao.run(id)!!.status)
    }

    private suspend fun assertMoved(block: suspend () -> Unit) {
        val e = runCatching { block() }.exceptionOrNull()
        assertTrue("expected MomentsMoved, got $e", e is CanvasRunner.MomentsMoved)
    }
}
