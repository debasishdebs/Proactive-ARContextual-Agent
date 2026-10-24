package com.rokid.context.canvas

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.canvas.Pipeline
import com.rokid.context.ai.canvas.PipelineEdge
import com.rokid.context.ai.canvas.PipelineNode
import com.rokid.context.canvas.moment.DaoMomentSource
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

    private val recorder: NodeExecutor = object : NodeExecutor {
        override val type = NodeTypes.PROMPT
        override fun nominalCalls(node: PipelineNode, items: Int) = 0
        override fun maxCalls(node: PipelineNode, items: Int) = 0
        override suspend fun run(node: PipelineNode, items: List<CanvasItem>, ctx: RunContext) =
            items.map { seen += it.value; NodeResult(it.frameId, CanvasValue.Json(JsonObject(emptyMap()))) }
    }
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
}
