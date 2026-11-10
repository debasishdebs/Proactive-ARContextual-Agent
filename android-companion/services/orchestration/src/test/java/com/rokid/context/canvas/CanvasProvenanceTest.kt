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

    private fun byNode(first: NodeExecutor, last: NodeExecutor) = object : NodeExecutor {
        override val type = NodeTypes.VISION_EXTRACT
        private fun of(n: PipelineNode) = if (n.id == "last") last else first
        override fun chunkSize(node: PipelineNode) = of(node).chunkSize(node)
        override fun nominalCalls(node: PipelineNode, items: Int) = of(node).nominalCalls(node, items)
        override fun maxCalls(node: PipelineNode, items: Int) = of(node).maxCalls(node, items)
        override suspend fun run(node: PipelineNode, items: List<CanvasItem>, ctx: RunContext) = of(node).run(node, items, ctx)
    }

    /** One Vision(batch=2) -> Prompt -> [last] run over A and B; Forget B at the router admission of the first real model call. */
    private suspend fun runForgettingB(vision: NodeExecutor, last: Pair<String, NodeExecutor>, lastParams: JsonObject = JsonObject(emptyMap())): String {
        val db = MemoryGraph.get(app).db
        db.frameDao().insertIgnore(FrameEntity("A", 1, "/A.jpg", "rokid_glasses")); db.frameDao().insertIgnore(FrameEntity("B", 2, "/B.jpg", "rokid_glasses"))
        var forgot = false
        val gate = object : CallGate {
            override suspend fun <T> withPermit(callClass: CallClass, block: suspend () -> T): T {
                if (!forgot) { forgot = true; db.forgetDao().insertTombstone(ForgetTombstoneEntity("B", now)); db.frameDao().deleteByIds(listOf("B")) }
                return block()
            }
        }
        val ai = AiGraph(MemoryGraph.get(app), InMemoryKeyVault(), InMemorySeedState(), ProviderBuilder { _, _, _ ->
            object : ModelProvider { override suspend fun generate(prompt: String): String { sent += prompt; return """{"summary":"x","confidence":0.9,"facts":[],"captions":[]}""" } }
        }, gate = gate).also { AiGraph.resetForTests(it) }
        TestAi.bindAll(ai)
        val prompt = exec(NodeTypes.PROMPT, 1) { items -> items.map { NodeResult(it.frameId, CanvasValue.Json(buildJsonObject { put("text", "UPSTREAM-${it.frameId}") })) } }
        val runner = CanvasRunner(
            db.canvasDao(), { f -> PhotoLoad(f.map(::photo), emptySet()) },
            // both Vision nodes share the node type: one executor that sends "ext" to the fake and "last" to the real one
            mapOf(NodeTypes.PROMPT to prompt) + (if (last.first == NodeTypes.VISION_EXTRACT) mapOf(NodeTypes.VISION_EXTRACT to byNode(vision, last.second))
                else mapOf(NodeTypes.VISION_EXTRACT to vision, last)),
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
            PipelineNode("last", last.first, "last", lastParams),
        ), listOf(PipelineEdge("photos", "ext"), PipelineEdge("ext", "prompt"), PipelineEdge("prompt", "last")))
        val est = runner.estimate(p, listOf("A", "B"), { n -> ModelLabel(n.id, "az", "gpt") })
        assertTrue("estimate: $est", est is Estimate.Ok)
        val runId = runner.start(p, p.id, null, listOf("A", "B"), (est as Estimate.Ok).confirmToken, scope)
        withTimeout(20_000) { runner.active.first { it == null } }
        return runId
    }

    // codex r14 P1-1: A's caption carries a B-specific detail but cites only A; the batch mate B must still travel with A's output
    @Test fun aBatchMatesProvenanceTravelsWithEveryOutputOfTheBatch() = runBlocking<Unit> {
        val vision = exec(NodeTypes.VISION_EXTRACT, 2) { items ->
            items.map { NodeResult(it.frameId, CanvasValue.Extraction(listOf(Fact("Seen in ${it.frameId}", "object", 0.9, emptyList(), listOf(it.frameId))), listOf(FrameCaption(it.frameId, "Same mug as the other photo")))) }
        }
        val runId = runForgettingB(vision, NodeTypes.SUMMARY to SummaryExecutor)
        assertTrue("no Summary text was sent after B was forgotten: $sent", sent.isEmpty())
        assertEquals(listOf("frame_forget"), MemoryGraph.get(app).db.canvasDao().items(runId).filter { it.nodeId == "last" && it.frameId == "A" }.map { it.error })
    }

    // codex r14 P1-2: Vision(batch=2) -> Prompt -> Vision(batch=1): the final Vision request declares its upstream JSON's sources (A+B), not just photo A
    @Test fun aVisionRequestDeclaresTheSourcesOfItsUpstreamJson() = runBlocking<Unit> {
        val vision = exec(NodeTypes.VISION_EXTRACT, 2) { items ->
            items.map { NodeResult(it.frameId, CanvasValue.Extraction(listOf(Fact("Mug in ${it.frameId}", "object", 0.9, emptyList(), listOf("A", "B"))), emptyList())) }
        }
        val runId = runForgettingB(vision, NodeTypes.VISION_EXTRACT to VisionExtractExecutor,
            buildJsonObject { put("batch", 1); put("prompt", buildJsonObject { put("source", "draft"); put("template", "X {{recent_context}} {{context_sources}}"); put("schema", kotlinx.serialization.json.JsonNull) }) })
        assertTrue("no request carrying B-derived upstream JSON was sent after B was forgotten: $sent", sent.isEmpty())
        val dao = MemoryGraph.get(app).db.canvasDao()
        assertEquals("run ${dao.run(runId)?.status} items ${dao.items(runId).map { "${it.nodeId}/${it.frameId}/${it.status}/${it.error}" }}",
            listOf("frame_forget"), dao.items(runId).filter { it.nodeId == "last" && it.frameId == "A" }.map { it.error })
    }

    // codex r14 RULE A, property: every built-in executor run over a batch of 2+ inputs; every output carries every input's sources
    @Test fun everyBuiltInExecutorsOutputsCarryEveryInputsSources() = runBlocking<Unit> {
        val ai = TestAi.graph(app) { """{"verdict":"pass","reasons":[],"summary":"s","confidence":0.9,"text":"t","facts":[],"captions":[]}""" }.also { TestAi.bindAll(it) }
        val ctx = RunContext("r", ai.runner, ai.router, ai.prompts, ai.persona, { role -> ai.bindings.get(role) }, liveness = com.rokid.context.orchestration.AllLive)
        val inputs = listOf("A", "B", "C").map { id ->
            CanvasItem(id, photo(id), CanvasValue.Extraction(listOf(Fact("f $id", "object", 0.9, emptyList(), listOf(id))), listOf(FrameCaption(id, "c $id"))),
                com.rokid.context.model.PayloadSources(setOf("src-$id"), setOf("mem-$id")))
        }
        val everyInput = inputs.fold(com.rokid.context.model.PayloadSources.NONE) { acc, it -> acc + sourcesOf(it) }
        var checked = 0
        for ((type, ex) in Executors.all()) {
            val node = PipelineNode("n", type, "n", JsonObject(emptyMap()))
            val provenance = NodeProvenance(ex)
            var soFar = com.rokid.context.model.PayloadSources.NONE
            for (chunk in inputs.chunked(maxOf(2, ex.chunkSize(node)))) { // at least a batch of 2
                provenance.consume(chunk)
                soFar = chunk.fold(soFar) { acc, it -> acc + sourcesOf(it) }
                val results = runCatching { ex.run(node, chunk, ctx) }.getOrDefault(emptyList())
                for (r in results) {
                    val out = provenance.of(r.value)
                    val consumed = chunk.fold(com.rokid.context.model.PayloadSources.NONE) { acc, it -> acc + sourcesOf(it) }
                    assertTrue("$type output ${r.frameId} carries every input of its execution", out.frameIds.containsAll(consumed.frameIds) && out.memoryIds.containsAll(consumed.memoryIds))
                    if (!ex.chunkLocal) assertTrue("$type (stateful) carries everything consumed so far", out.frameIds.containsAll(soFar.frameIds) && out.memoryIds.containsAll(soFar.memoryIds))
                    checked++
                }
            }
        }
        assertTrue("outputs were checked: $checked", checked > 0)
        assertTrue(everyInput.frameIds.containsAll(setOf("A", "B", "C", "src-A", "src-B", "src-C")))
    }
}
