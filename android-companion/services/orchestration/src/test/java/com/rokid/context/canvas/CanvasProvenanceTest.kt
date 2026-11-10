package com.rokid.context.canvas

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.InMemoryKeyVault
import com.rokid.context.ai.InMemorySeedState
import com.rokid.context.ai.ProviderBuilder
import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.canvas.Pipeline
import com.rokid.context.ai.canvas.PipelineEdge
import com.rokid.context.ai.canvas.PipelineNode
import com.rokid.context.ai.contract.Fact
import com.rokid.context.ai.contract.FrameCaption
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.ForgetTombstoneEntity
import com.rokid.context.model.CallClass
import com.rokid.context.model.CallGate
import com.rokid.context.model.ModelImageInput
import com.rokid.context.model.ModelProvider
import com.rokid.context.orchestration.ExtractPhoto
import com.rokid.context.orchestration.TestAi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Codex r13 P1-1 repro: Vision(batch=2) -> Prompt -> Summary. The A-item fact cites A+B; Prompt turns it into JSON (the citation is gone from the value);
 * B is forgotten while Summary waits at router admission. The Summary request must still carry B and be dropped unsent.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CanvasProvenanceTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sent = CopyOnWriteArrayList<String>()
    private var now = 10_000L
    private var ids = 0

    @Before fun setUp() { MemoryGraph.resetForTests(DatabaseFactory.inMemory(app)) }
    @After fun tearDown() { scope.cancel(); AiGraph.resetForTests(null); MemoryGraph.get(app).db.close(); MemoryGraph.resetForTests(null) }

    private fun photo(id: String) = ExtractPhoto(id, "ts=1", ModelImageInput("image/jpeg", "AAAA", ref = id))

    private fun exec(type: String, chunk: Int, fn: (List<CanvasItem>) -> List<NodeResult>) = object : NodeExecutor {
        override val type = type
        override fun chunkSize(node: PipelineNode) = chunk
        override fun nominalCalls(node: PipelineNode, items: Int) = 0
        override fun maxCalls(node: PipelineNode, items: Int) = 0
        override suspend fun run(node: PipelineNode, items: List<CanvasItem>, ctx: RunContext) = fn(items)
    }

    @Test fun summaryAfterPromptStillCarriesTheBatchMatePhotoAndIsDroppedWhenItIsForgotten() = runBlocking<Unit> {
        val db = MemoryGraph.get(app).db
        db.frameDao().insertIgnore(FrameEntity("A", 1, "/A.jpg", "rokid_glasses")); db.frameDao().insertIgnore(FrameEntity("B", 2, "/B.jpg", "rokid_glasses"))
        // Forget B lands while the Summary call waits for its permit (router admission)
        val gate = object : CallGate {
            override suspend fun <T> withPermit(callClass: CallClass, block: suspend () -> T): T {
                db.forgetDao().insertTombstone(ForgetTombstoneEntity("B", now)); db.frameDao().deleteByIds(listOf("B"))
                return block()
            }
        }
        val ai = AiGraph(MemoryGraph.get(app), InMemoryKeyVault(), InMemorySeedState(), ProviderBuilder { _, _, _ ->
            object : ModelProvider { override suspend fun generate(prompt: String): String { sent += prompt; return """{"summary":"x","confidence":0.9}""" } }
        }, gate = gate).also { AiGraph.resetForTests(it) }
        TestAi.bindAll(ai)
        val vision = exec(NodeTypes.VISION_EXTRACT, 2) { items ->
            items.map { NodeResult(it.frameId, CanvasValue.Extraction(listOf(Fact("Mug seen in ${it.frameId}", "object", 0.9, emptyList(), listOf("A", "B"))), listOf(FrameCaption(it.frameId, "c")))) }
        }
        val prompt = exec(NodeTypes.PROMPT, 1) { items -> items.map { NodeResult(it.frameId, CanvasValue.Json(buildJsonObject { put("text", "a mug from both photos") })) } }
        val runner = CanvasRunner(
            MemoryGraph.get(app).db.canvasDao(), { f -> PhotoLoad(f.map(::photo), emptySet()) },
            mapOf(NodeTypes.VISION_EXTRACT to vision, NodeTypes.PROMPT to prompt, NodeTypes.SUMMARY to SummaryExecutor),
            { id -> RunContext(id, ai.runner, ai.router, ai.prompts, ai.persona, { role -> ai.bindings.get(role) }, liveness = com.rokid.context.work.frames.FrameLiveness.forApp(app)) },
            ConfirmTokens({ now }), { false }, { now }, { "run${++ids}" }, { 0L },
        )
        val p = Pipeline(2, "pv", "P", listOf(
            PipelineNode("photos", NodeTypes.INPUT_PHOTOS, "photos", JsonObject(emptyMap())),
            PipelineNode("ext", NodeTypes.VISION_EXTRACT, "ext", buildJsonObject { put("batch", 2); put("prompt", buildJsonObject { put("source", "live") }) }),
            PipelineNode("prompt", NodeTypes.PROMPT, "prompt", buildJsonObject {
                put("name", "p"); put("template", "T {{context_sources}}")
                put("schema", """{"type":"object","properties":{"text":{"type":"string"}},"required":["text"],"additionalProperties":false}""")
            }),
            PipelineNode("sum", NodeTypes.SUMMARY, "Summary", JsonObject(emptyMap())),
        ), listOf(PipelineEdge("photos", "ext"), PipelineEdge("ext", "prompt"), PipelineEdge("prompt", "sum")))
        val est = runner.estimate(p, listOf("A"), { n -> ModelLabel(n.id, "az", "gpt") })
        assertTrue("estimate: $est", est is Estimate.Ok)
        val e = est as Estimate.Ok
        val runId = runner.start(p, p.id, null, listOf("A"), e.confirmToken, scope)
        withTimeout(20_000) { runner.active.first { it == null } }
        assertTrue("no Summary text derived from the forgotten photo B was sent: $sent", sent.isEmpty())
        val sum = MemoryGraph.get(app).db.canvasDao().items(runId).filter { it.nodeId == "sum" }
        assertEquals("the Summary item failed as forgotten", listOf("frame_forget"), sum.map { it.error })
    }

    @Test fun carriedSourcesKeepEveryInputsProvenance() {
        val a = CanvasItem("A", photo("A"), CanvasValue.Extraction(listOf(Fact("f", "object", 0.9, emptyList(), listOf("A", "B"))), emptyList()))
        val afterPrompt = CanvasItem("A", photo("A"), CanvasValue.Json(buildJsonObject { put("t", "x") }), carriedSources(a, CanvasValue.Json(JsonObject(emptyMap()))))
        assertEquals(setOf("A", "B"), sourcesOf(afterPrompt).frameIds)
    }
}
