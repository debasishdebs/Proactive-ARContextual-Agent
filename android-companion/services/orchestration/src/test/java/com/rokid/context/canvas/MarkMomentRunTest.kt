package com.rokid.context.canvas

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.canvas.Pipeline
import com.rokid.context.ai.canvas.PipelineEdge
import com.rokid.context.ai.canvas.PipelineNode
import com.rokid.context.canvas.moment.DaoMomentSource
import com.rokid.context.canvas.marks.MarkArrival
import com.rokid.context.canvas.marks.MarkRecorder
import com.rokid.context.canvas.marks.VoiceMark
import com.rokid.context.canvas.moment.MomentFixture
import com.rokid.context.canvas.moment.MomentId
import com.rokid.context.canvas.moment.MomentResolver
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.forget.ForgetService
import com.rokid.context.memory.forget.MomentForget
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/** `input.moments` over a MARK Moment through the REAL runner: no photo is invented, the code of the mark travels, and a Forget of the mark takes the run rows with it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MarkMomentRunTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var h: PyFnRunHarness
    private val fx get() = MomentFixture(h.db)
    private val label: suspend (PipelineNode) -> ModelLabel? = { n -> ModelLabel(n.id, "az", "gpt") }
    private val seen = CopyOnWriteArrayList<CanvasValue>()
    private val sec = MomentFixture.SEC
    private val markId = MomentId.format(sec)
    private val frameId = MomentId.format(sec + 5)

    @Before fun setUp() { h = PyFnRunHarness(app) }
    @After fun tearDown() { h.close() }

    /** Runs inside the prompt node, while "the model call waits": a test forgets the mark there. */
    private var onRun: suspend () -> Unit = {}
    private val recorder: NodeExecutor = object : NodeExecutor {
        override val type = NodeTypes.PROMPT
        override fun nominalCalls(node: PipelineNode, items: Int) = 0
        override fun maxCalls(node: PipelineNode, items: Int) = 0
        override suspend fun run(node: PipelineNode, items: List<CanvasItem>, ctx: RunContext) =
            items.also { onRun() }.map { seen += it.value; NodeResult(it.frameId, CanvasValue.Json(JsonObject(emptyMap()))) }
    }
    private val dao3 get() = h.db.canvasSlice3Dao()
    private val epoch = com.rokid.context.memory.db.DeletionEpoch("i", 1, 0)
    private fun fence() = com.rokid.context.memory.db.DaoFence(0, epoch, { 0 }, { epoch })
    private fun forgetService() = ForgetService(h.db, ProposalRepository(h.db.proposalDao(), h.db.actionDao(), h.db)) { h.now }
    private fun momentForget() = MomentForget(h.db, forgetService()) { h.now }
    private suspend fun markRows(id: String) = h.dao.items(id).filter { it.frameId == "moment:$markId" }
    private fun resolver() = MomentResolver(DaoMomentSource(h.dao, h.dao3))
    private fun runner() = CanvasRunner(
        h.dao, { f -> PhotoLoad(f.map(h::photo), emptySet()) }, mapOf(NodeTypes.PROMPT to recorder),
        { id -> val ai = com.rokid.context.orchestration.TestAi.graph(app) { "" }; RunContext(id, ai.runner, ai.router, ai.prompts, ai.persona, { role -> ai.bindings.get(role) }) },
        h.tokens, { false }, { h.now }, { "run${++h.ids}" }, { h.gen }, { _, _, m -> h.log += m }, moments = resolver(),
    )
    private fun pipeline() = Pipeline(2, "mp", "P", listOf(
        PipelineNode("moments", NodeTypes.INPUT_MOMENTS, "moments", JsonObject(emptyMap())),
        PipelineNode("ask", NodeTypes.PROMPT, "ask", buildJsonObject { put("schema", "{\"type\":\"object\"}") }),
    ), listOf(PipelineEdge("moments", "ask")))

    private suspend fun seed() {
        fx.mark("mk:1:1", sec * 1000 + 100, "voice")
        fx.frame("fp", (sec + 5) * 1000 + 10); fx.mark("mk:1:2", (sec + 5) * 1000 + 200, "tap")
    }
    private suspend fun runAll(r: CanvasRunner, ids: List<String>): String {
        val e = r.estimateMoments(pipeline(), ids, label) as Estimate.Ok
        val id = r.startMoments(pipeline(), "mp", null, ids, e.confirmToken, h.scope)
        h.finish(r); return id
    }

    @Test fun aMarkMomentHasNoPhotosSaysSoAndCarriesTheCodeOfItsMark() = runBlocking<Unit> {
        seed()
        val r = runner()
        val e = r.estimateMoments(pipeline(), listOf(markId, frameId), label) as Estimate.Ok
        assertEquals(2, e.photos); assertEquals("only the photo Moment has a photo", 1, e.photoCount)
        val id = runAll(r, listOf(markId, frameId))
        assertEquals("DONE", h.dao.run(id)!!.status)
        val items = seen.map { (it as CanvasValue.Json).value.jsonObject }.associateBy { it.getValue("momentId").jsonPrimitive.content }
        val mark = items.getValue(markId)
        assertEquals("MARK", mark.getValue("type").jsonPrimitive.content); assertEquals(0, mark.getValue("photos").jsonArray.size)
        assertEquals(listOf("voice"), mark.getValue("marks").jsonArray.map { it.jsonObject.getValue("code").jsonPrimitive.content })
        assertEquals("FRAME", items.getValue(frameId).getValue("type").jsonPrimitive.content); assertEquals(1, items.getValue(frameId).getValue("photos").jsonArray.size)
        for (secret in listOf("mk:1", "labelId", "name")) assertTrue("no $secret in the item", !mark.toString().contains(secret))
    }

    @Test fun aMomentForgetOfTheMarkSecondDeletesTheRunRowsOfThatMarkMomentOnly() = runBlocking<Unit> {
        seed()
        val id = runAll(runner(), listOf(markId, frameId))
        assertEquals(setOf("moment:$markId", "moment:$frameId"), h.dao.items(id).map { it.frameId }.toSet())
        MomentForget(h.db, ForgetService(h.db, ProposalRepository(h.db.proposalDao(), h.db.actionDao(), h.db)) { h.now }) { h.now }.forgetMoments(listOf(markId))
        assertEquals(setOf("moment:$frameId"), h.dao.items(id).map { it.frameId }.toSet())
    }

    // ---- the fence and every path that deletes a mark ----

    @Test fun aMarkForgottenWhileTheModelCallWaitsWritesNothingAndRecreatesNoRow() = runBlocking<Unit> {
        seed()
        onRun = { momentForget().forgetMoments(listOf(markId)) }
        val id = runAll(runner(), listOf(markId))
        assertEquals("nothing of the forgotten mark's Moment is on disk", emptyList<String>(), markRows(id).map { it.nodeId })
        assertEquals("CANCELLED", h.dao.run(id)!!.status)
    }

    private suspend fun runOverTheMark(): String { seed(); return runAll(runner(), listOf(markId)).also { assertEquals(listOf("ask", "moments"), markRows(it).map { r -> r.nodeId }.sorted()) } }

    @Test fun anUndoOfTheMarkDeletesItsRunRows() = runBlocking<Unit> {
        fx.mark("mk:e:1", sec * 1000 + 100, "tap")
        val id = runAll(runner(), listOf(markId))
        assertEquals(2, markRows(id).size)
        MarkRecorder(dao3, ::fence, { 5 }).onMark(com.rokid.context.core.model.MarkMessage(epoch = "e", seq = 2, atMs = 5, atElapsedMs = 5, listen = false, undo = 1, deletionCounter = 1), MarkArrival(sec * 1000 + 900, true, 0))
        assertEquals(emptyList<String>(), markRows(id).map { it.nodeId })
    }

    @Test fun aVoiceUndoOfTheMarkDeletesItsRunRows() = runBlocking<Unit> {
        fx.mark("mk:e:1", sec * 1000 + 100, "voice")
        val id = runAll(runner(), listOf(markId))
        assertEquals(2, markRows(id).size)
        MarkRecorder(dao3, ::fence, { 5 }).applyVoice(listOf(VoiceMark.Undo), glasses = true, nowMs = sec * 1000 + 5_000)
        assertEquals(emptyList<String>(), markRows(id).map { it.nodeId })
    }

    @Test fun aRangeForgetOfTheMarksSecondDeletesItsRunRows() = runBlocking<Unit> {
        val id = runOverTheMark()
        dao3.forgetRangeTx(sec * 1000, sec * 1000 + 999, 5)
        assertEquals(emptyList<String>(), markRows(id).map { it.nodeId })
    }

    @Test fun theForgetOfAPhotoTheMarkIsLinkedToDeletesItsRunRows() = runBlocking<Unit> {
        dao3.insertLabelFenced(com.rokid.context.memory.db.CanvasLabelEntity("mk:1:1", "MOMENT", "tap", "MOMENT", sec * 1000 + 100, frameId = "near", createdAt = 1), fence())
        val id = runAll(runner(), listOf(markId))
        assertEquals(2, markRows(id).size)
        dao3.deleteSliceOutputsForFrames(listOf("near"), 5)
        assertEquals(emptyList<String>(), markRows(id).map { it.nodeId })
    }

    @Test fun aMarkTheProjectionNamesButTheTimeRangeMissesStillLosesItsRunRowsInMomentForget() = runBlocking<Unit> {
        fx.mark("odd", sec * 1000 + 50_000, "tap") // its time is outside the forgotten second; only the stored Moment's MARK part names it
        h.db.momentDao().replace(com.rokid.context.memory.db.MomentEntity(markId, 1, 1, "h", 1, com.rokid.context.memory.moment.MomentType.MARK, com.rokid.context.memory.moment.NoPhotoReason.UNKNOWN),
            listOf(com.rokid.context.memory.db.MomentPartEntity(markId, com.rokid.context.memory.db.MomentPartKind.MARK, "odd", "r", 1, "tap")))
        h.dao.insertRunItemsFenced(listOf(com.rokid.context.memory.db.CanvasRunItemEntity("r1", "moment:$markId", "ask", "OK", null, "{}", null, null, null, null, null, null, "mf:odd", null)), emptyList(), h.gen, emptySet()) { h.gen }
        assertEquals(1, h.dao.items("r1").size)
        momentForget().forgetMoments(listOf(markId))
        assertEquals(emptyList<String>(), h.dao.items("r1").map { it.nodeId })
    }

    // ---- Scene runs carry the lineage of the marks of their Moments ----

    @Test fun aSceneRunOverAMarkMomentLosesItsRowsWhenTheMarkIsForgotten() = runBlocking<Unit> {
        seed()
        com.rokid.context.canvas.moment.MomentBuilder(h.db, enabled = { true }, clock = { 5 }).build(listOf(markId, frameId))
        com.rokid.context.canvas.moment.SceneBuilder(h.db, enabled = { true }, clock = { 5 }).rebuildWindow(sec * 1000, sec * 1000 + 3_599_000)
        val sid = "S$markId"
        val sp = Pipeline(2, "sp", "P", listOf(PipelineNode("scenes", NodeTypes.INPUT_SCENES, "scenes", JsonObject(emptyMap())), PipelineNode("ask", NodeTypes.PROMPT, "ask", buildJsonObject { put("schema", "{\"type\":\"object\"}") })), listOf(PipelineEdge("scenes", "ask")))
        val r = CanvasRunner(
            h.dao, { f -> PhotoLoad(f.map(h::photo), emptySet()) }, mapOf(NodeTypes.PROMPT to recorder),
            { id -> val ai = com.rokid.context.orchestration.TestAi.graph(app) { "" }; RunContext(id, ai.runner, ai.router, ai.prompts, ai.persona, { role -> ai.bindings.get(role) }) },
            h.tokens, { false }, { h.now }, { "run${++h.ids}" }, { h.gen }, { _, _, m -> h.log += m }, moments = resolver(), scenes = com.rokid.context.canvas.moment.DaoSceneRunSource(h.db, resolver()),
        )
        val e = r.estimateScenes(sp, listOf(sid), label) as Estimate.Ok
        val id = r.startScenes(sp, "sp", null, listOf(sid), e.confirmToken, h.scope); h.finish(r)
        assertEquals(2, h.dao.items(id).size)
        momentForget().forgetMoments(listOf(markId))
        assertEquals("the Scene's rows go with the mark", emptyList<String>(), h.dao.items(id).map { it.nodeId })
    }
}
