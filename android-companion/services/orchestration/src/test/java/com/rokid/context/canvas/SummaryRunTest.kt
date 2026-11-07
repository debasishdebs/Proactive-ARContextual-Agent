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

/** `input.scenes -> proc.summary -> Confidence check -> Save memory` through the REAL runner: the cost shown first, one summary per Scene, and the Forget cascade over the summary rows. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SummaryRunTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var h: PyFnRunHarness
    private val fx get() = MomentFixture(h.db)
    private val hour = Math.floorDiv(MomentFixture.MS, 3_600_000L) * 3_600_000L
    private val label: suspend (PipelineNode) -> ModelLabel? = { n -> ModelLabel(n.id, "az", "gpt") }
    private val prompts = CopyOnWriteArrayList<String>()
    private fun t(sec: Long) = hour + sec * 1000
    private fun mid(sec: Long) = MomentId.ofMs(t(sec))
    private fun sid(sec: Long) = "S" + mid(sec)

    @Before fun setUp() { h = PyFnRunHarness(app) }
    @After fun tearDown() { h.close() }

    private suspend fun seed() {
        for (s in listOf(0L, 30L, 400L)) fx.frame("f$s", t(s) + 5)
        MomentBuilder(h.db, enabled = { true }, clock = { 5 }).build(listOf(mid(0), mid(30), mid(400)))
        SceneBuilder(h.db, enabled = { true }, clock = { 5 }).rebuildWindow(t(0), t(3599))
        // a Scene with no visible text gets no Summary call, so the two Scenes under test carry the words of their first Moment
        for (s in listOf(0L, 400L)) ObjectAudit(h.db.objectDao()) { 7 }.writeDerived(ObjectRef(ObjectKind.MOMENT, mid(s), 1), DerivedField.CAPTION, "Desk at $s", null, 1)
    }

    private fun resolver() = MomentResolver(DaoMomentSource(h.dao, h.dao3))
    private fun runner() = CanvasRunner(
        h.dao, { f -> PhotoLoad(f.map(h::photo), emptySet()) },
        mapOf(NodeTypes.SUMMARY to SummaryExecutor, NodeTypes.LOCAL_CRITIC to LocalCriticExecutor, NodeTypes.MEMORY_WRITE to MemoryWritePreviewExecutor),
        { id ->
            val ai = com.rokid.context.orchestration.TestAi.graph(app) { p -> prompts += p; """{"summary":"Walk then desk.","confidence":0.9}""" }.also { com.rokid.context.orchestration.TestAi.bindAll(it) }
            RunContext(id, ai.runner, ai.router, ai.prompts, ai.persona, { role -> ai.bindings.get(role) }, liveness = com.rokid.context.orchestration.AllLive)
        },
        h.tokens, { false }, { h.now }, { "run${++h.ids}" }, { h.gen }, { _, _, m -> h.log += m }, moments = resolver(), scenes = DaoSceneRunSource(h.db, resolver()),
    )
    private fun pipeline() = Pipeline(2, "sp", "P", listOf(
        PipelineNode("scenes", NodeTypes.INPUT_SCENES, "scenes", JsonObject(emptyMap())),
        PipelineNode("sum", NodeTypes.SUMMARY, "Summary", JsonObject(emptyMap())),
        PipelineNode("trust", NodeTypes.LOCAL_CRITIC, "Confidence check", JsonObject(emptyMap())),
        PipelineNode("write", NodeTypes.MEMORY_WRITE, "Save memory", JsonObject(emptyMap())),
    ), listOf(PipelineEdge("scenes", "sum"), PipelineEdge("sum", "trust"), PipelineEdge("trust", "write", "trusted"), PipelineEdge("trust", "write", "unsure")))

    private suspend fun runAll(r: CanvasRunner, ids: List<String>): String {
        val e = r.estimateScenes(pipeline(), ids, label) as Estimate.Ok
        val id = r.startScenes(pipeline(), "sp", null, ids, e.confirmToken, h.scope)
        h.finish(r); return id
    }

    @Test fun theEstimateCountsOneCallPerSceneAndTheRunMakesExactlyThose() = runBlocking<Unit> {
        seed()
        val r = runner()
        val e = r.estimateScenes(pipeline(), listOf(sid(0), sid(400)), label) as Estimate.Ok
        assertEquals(2, e.nominal); assertEquals(2, e.max); assertEquals(listOf("sum"), e.models.map { it.nodeId })
        val id = runAll(r, listOf(sid(0), sid(400)))
        assertEquals("DONE", h.dao.run(id)!!.status)
        assertEquals(2, prompts.size)
        assertEquals(2, h.dao.run(id)!!.calls)
    }

    @Test fun eachSceneGetsASummaryThatTheConfidenceCheckAndTheSavePreviewTake() = runBlocking<Unit> {
        seed()
        val id = runAll(runner(), listOf(sid(0), sid(400)))
        val items = h.dao.items(id)
        for (node in listOf("sum", "trust", "write")) assertEquals(node, listOf("scene:${sid(0)}", "scene:${sid(400)}"), items.filter { it.nodeId == node }.map { it.frameId })
        assertTrue(items.all { it.status == "OK" })
        assertEquals(listOf("trusted", "trusted"), items.filter { it.nodeId == "trust" }.map { it.branch })
        assertTrue(items.first { it.nodeId == "write" }.outputJson!!.contains("Walk then desk."))
    }

    @Test fun forgettingAMemberPhotoDeletesTheSummaryRowsOfThatSceneOnly() = runBlocking<Unit> {
        seed()
        val id = runAll(runner(), listOf(sid(0), sid(400)))
        assertEquals(8, h.dao.items(id).size)
        val forget = ForgetService(h.db, ProposalRepository(h.db.proposalDao(), h.db.actionDao(), h.db)) { h.now }
        assertTrue(forget.forgetMoment("f30").framesToDelete.isNotEmpty())
        assertEquals(setOf("scene:${sid(400)}"), h.dao.items(id).map { it.frameId }.toSet())
        assertEquals("the other Scene keeps its input, summary, check and save rows", 4, h.dao.items(id).size)
    }
}
