package com.rokid.context.canvas

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.canvas.Pipeline
import com.rokid.context.ai.canvas.PipelineEdge
import com.rokid.context.ai.canvas.PipelineNode
import com.rokid.context.canvas.moment.DaoMomentSource
import com.rokid.context.canvas.moment.DaoSceneRunSource
import com.rokid.context.canvas.moment.MomentBuilder
import com.rokid.context.canvas.moment.MomentFixture
import com.rokid.context.canvas.moment.MomentId
import com.rokid.context.canvas.moment.MomentResolver
import com.rokid.context.canvas.moment.SceneBuilder
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.forget.ForgetService
import com.rokid.context.memory.moment.DerivedField
import com.rokid.context.memory.moment.ObjectAudit
import com.rokid.context.memory.moment.ObjectKind
import com.rokid.context.memory.moment.ObjectRef
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/** `input.scenes -> prompt` through the REAL runner: what the prompt receives (visible text only, no photo ids), the freshness binding, and the Forget cascade. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SceneRunTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var h: PyFnRunHarness
    private val fx get() = MomentFixture(h.db)
    private val audit get() = ObjectAudit(h.db.objectDao()) { 7 }
    private val hour = Math.floorDiv(MomentFixture.MS, 3_600_000L) * 3_600_000L
    private val label: suspend (PipelineNode) -> ModelLabel? = { n -> ModelLabel(n.id, "az", "gpt") }
    private val seen = CopyOnWriteArrayList<CanvasValue>()
    private fun t(sec: Long) = hour + sec * 1000
    private fun mid(sec: Long) = MomentId.ofMs(t(sec))
    private fun sid(sec: Long) = "S" + mid(sec)
    private fun sref(sec: Long) = ObjectRef(ObjectKind.SCENE, sid(sec), 1)

    @Before fun setUp() { h = PyFnRunHarness(app) }
    @After fun tearDown() { h.close() }

    /** Two Scenes: S0 = photos at 0 and 30 s (two Moments), S400 = one photo. */
    private suspend fun seed() {
        for (s in listOf(0L, 30L, 400L)) fx.frame("f$s", t(s) + 5)
        MomentBuilder(h.db, enabled = { true }, clock = { 5 }).build(listOf(mid(0), mid(30), mid(400)))
        SceneBuilder(h.db, enabled = { true }, clock = { 5 }).rebuildWindow(t(0), t(3599))
    }
    private suspend fun put(o: ObjectRef, field: String, value: String) = audit.writeDerived(o, field, value, null, if (o.kind == ObjectKind.SCENE) audit.inputSeq(o) else 1)

    private val recorder: NodeExecutor = object : NodeExecutor {
        override val type = NodeTypes.PROMPT
        override fun nominalCalls(node: PipelineNode, items: Int) = 0
        override fun maxCalls(node: PipelineNode, items: Int) = 0
        override suspend fun run(node: PipelineNode, items: List<CanvasItem>, ctx: RunContext) =
            items.map { seen += it.value; NodeResult(it.frameId, CanvasValue.Json(JsonObject(mapOf("ok" to JsonPrimitive(true))))) }
    }
    private fun resolver() = MomentResolver(DaoMomentSource(h.dao, h.dao3))
    private fun runner() = CanvasRunner(
        h.dao, { f -> PhotoLoad(f.map(h::photo), emptySet()) }, mapOf(NodeTypes.PROMPT to recorder),
        { id -> val ai = com.rokid.context.orchestration.TestAi.graph(app) { "" }; RunContext(id, ai.runner, ai.router, ai.prompts, ai.persona, { role -> ai.bindings.get(role) }, liveness = com.rokid.context.orchestration.AllLive) },
        h.tokens, { false }, { h.now }, { "run${++h.ids}" }, { h.gen }, { _, _, m -> h.log += m }, moments = resolver(), scenes = DaoSceneRunSource(h.db, resolver()),
    )
    private fun pipeline() = Pipeline(2, "sp", "P", listOf(
        PipelineNode("scenes", NodeTypes.INPUT_SCENES, "scenes", JsonObject(emptyMap())),
        PipelineNode("ask", NodeTypes.PROMPT, "ask", buildJsonObject { put("schema", "{\"type\":\"object\"}") }),
    ), listOf(PipelineEdge("scenes", "ask")))

    private suspend fun estimate(r: CanvasRunner, ids: List<String>) = r.estimateScenes(pipeline(), ids, label) as Estimate.Ok
    private suspend fun runAll(r: CanvasRunner, ids: List<String>): String {
        val id = r.startScenes(pipeline(), "sp", null, ids, estimate(r, ids).confirmToken, h.scope)
        h.finish(r); return id
    }
    private fun texts() = seen.map { (it as CanvasValue.Json).value.jsonObject }
    private fun forgetService() = ForgetService(h.db, ProposalRepository(h.db.proposalDao(), h.db.actionDao(), h.db)) { h.now }

    @Test fun thePromptGetsOneItemPerSceneWithTheVisibleTextOnly() = runBlocking<Unit> {
        seed()
        put(sref(0), DerivedField.SUMMARY, "SCENE-SUM"); put(ObjectRef(ObjectKind.MOMENT, mid(30), 1), DerivedField.CAPTION, "MOMENT-CAP")
        val r = runner()
        val e = estimate(r, listOf(sid(0), sid(400)))
        assertEquals("scenes", e.kind); assertEquals(2, e.photos); assertEquals(3, e.photoCount)
        val id = runAll(r, listOf(sid(0), sid(400)))
        assertEquals("DONE", h.dao.run(id)!!.status)
        assertEquals(listOf("scene:${sid(0)}", "scene:${sid(400)}"), h.dao.items(id).filter { it.nodeId == "ask" }.map { it.frameId })
        val first = texts().first { it.getValue("sceneId").jsonPrimitive.content == sid(0) }
        assertEquals("scene", first.getValue("itemType").jsonPrimitive.content)
        assertEquals("SCENE-SUM\nMOMENT-CAP", first.getValue("text").jsonPrimitive.content)
        assertEquals("", texts().first { it.getValue("sceneId").jsonPrimitive.content == sid(400) }.getValue("text").jsonPrimitive.content)
        for (secret in listOf("f0", "f30", ".jpg", "frameId")) assertFalse("the item must not carry $secret", first.toString().contains(secret))
    }

    @Test fun aHiddenFieldNeverReachesThePrompt() = runBlocking<Unit> {
        seed()
        put(sref(0), DerivedField.SUMMARY, "SCENE-SUM"); put(ObjectRef(ObjectKind.MOMENT, mid(30), 1), DerivedField.CAPTION, "MOMENT-CAP")
        audit.markStale(ObjectKind.SCENE, sid(0)); audit.markStale(ObjectKind.MOMENT, mid(30))
        runAll(runner(), listOf(sid(0)))
        assertEquals("", texts().single().getValue("text").jsonPrimitive.content)
    }

    @Test fun aFieldHiddenAfterTheEstimateMovesTheRunAndNothingRuns() = runBlocking<Unit> {
        seed(); put(sref(0), DerivedField.SUMMARY, "SCENE-SUM")
        val r = runner()
        val tok = estimate(r, listOf(sid(0))).confirmToken
        audit.markStale(ObjectKind.SCENE, sid(0))
        val e = runCatching { r.startScenes(pipeline(), "sp", null, listOf(sid(0)), tok, h.scope) }.exceptionOrNull()
        assertTrue("expected ScenesMoved, got $e", e is CanvasRunner.ScenesMoved)
        assertTrue(seen.isEmpty())
    }

    @Test fun forgettingAMemberPhotoDeletesTheScenesRowsAndOnlyThose() = runBlocking<Unit> {
        seed()
        val r = runner()
        val id = runAll(r, listOf(sid(0), sid(400)))
        assertEquals(4, h.dao.items(id).size)
        assertTrue(forgetService().forgetMoment("f30").framesToDelete.isNotEmpty())
        assertEquals(setOf("scene:${sid(400)}"), h.dao.items(id).map { it.frameId }.toSet())
    }

    @Test fun aPhotoRunRefusesAScenesPipelineAndBadIdsAreRefused() = runBlocking<Unit> {
        seed()
        val r = runner()
        assertTrue(r.estimate(pipeline(), listOf("f0"), label) is Estimate.Refused)
        assertTrue(r.estimateScenes(pipeline(), emptyList(), label) is Estimate.Refused)
        assertTrue("an unknown Scene", r.estimateScenes(pipeline(), listOf("S" + mid(900)), label) is Estimate.Refused)
        assertTrue(runCatching { r.estimateScenes(pipeline(), listOf("nope"), label) }.exceptionOrNull() is IllegalArgumentException)
    }
}
