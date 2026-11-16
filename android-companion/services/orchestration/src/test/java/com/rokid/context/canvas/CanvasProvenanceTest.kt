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
import org.junit.Assert.assertNotNull
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
        // Forget lifecycle M3 (C1): B is a photo of this run, so its Forget revokes the run's lease and the run ends CANCELLED before the "last" row of A is written (before M3 that row was written as a frame_forget error): nothing with text
        val dao = MemoryGraph.get(app).db.canvasDao()
        assertEquals("CANCELLED", dao.run(runId)?.status)
        assertTrue(dao.items(runId).filter { it.nodeId == "last" }.all { it.outputJson == null && (it.error == null || it.error == "frame_forget") })
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
        // Forget lifecycle M3 (C1): as above, the run is cancelled by the lease; the "last" row of A (a frame_forget error before M3) is not written, and nothing with text is
        assertEquals("CANCELLED", dao.run(runId)?.status)
        assertTrue(dao.items(runId).filter { it.nodeId == "last" }.all { it.outputJson == null && (it.error == null || it.error == "frame_forget") })
    }

    // codex r15 P1-3: the carried union reaches persistence. Vision(2) -> Prompt -> Summary -> LocalCritic -> memory.write; A's summary carries a B detail
    // but cites only A. Saved, then Forget B: the saved A summary goes too.
    @Test fun aSavedCanvasMemoryGoesWithAnyPhotoItWasDerivedFrom() = runBlocking<Unit> {
        val db = MemoryGraph.get(app).db
        for ((id, ts) in listOf("A" to 1L, "B" to 2L)) {
            val f = java.io.File(app.filesDir, "rokid_archive/$id.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
            com.rokid.context.orchestration.PipelineRecords.appendFrame(app, FrameEntity(id, ts, f.absolutePath, "rokid_glasses", "frame"))
        }
        val ai = TestAi.graph(app) { "{}" }.also { TestAi.bindAll(it) }
        val vision = exec(NodeTypes.VISION_EXTRACT, 2) { items -> items.map { NodeResult(it.frameId, CanvasValue.Extraction(listOf(Fact("Mug in ${it.frameId}", "object", 0.9, emptyList(), listOf(it.frameId))), emptyList())) } }
        val prompt = exec(NodeTypes.PROMPT, 1) { items -> items.map { NodeResult(it.frameId, CanvasValue.Json(buildJsonObject { put("text", "the same mug as in the other photo") })) } }
        // only A's summary reaches the save (B's fails), so nothing but the carried lineage links the saved A summary to B
        val summary = exec(NodeTypes.SUMMARY, 1) { items -> items.map {
            if (it.frameId == "B") NodeResult("B", null, error = "NO_INPUT")
            else NodeResult(it.frameId, CanvasValue.Extraction(listOf(Fact("SUMMARY-${it.frameId} shares a mug with B", com.rokid.context.ai.canvas.SummaryNode.FACT_TYPE, 0.9, emptyList(), listOf(it.frameId))), emptyList()))
        } }
        val runner = CanvasRunner(
            db.canvasDao(), { f -> PhotoLoad(f.map(::photo), emptySet()) },
            mapOf(NodeTypes.VISION_EXTRACT to vision, NodeTypes.PROMPT to prompt, NodeTypes.SUMMARY to summary, NodeTypes.LOCAL_CRITIC to LocalCriticExecutor, NodeTypes.MEMORY_WRITE to MemoryWritePreviewExecutor),
            { id -> RunContext(id, ai.runner, ai.router, ai.prompts, ai.persona, { role -> ai.bindings.get(role) }, liveness = com.rokid.context.orchestration.AllLive) },
            ConfirmTokens({ now }), { false }, { now }, { "run${++ids}" }, { 0L },
        )
        val p = Pipeline(2, "ps", "P", listOf(
            PipelineNode("photos", NodeTypes.INPUT_PHOTOS, "photos", JsonObject(emptyMap())),
            PipelineNode("ext", NodeTypes.VISION_EXTRACT, "ext", buildJsonObject { put("batch", 2); put("prompt", buildJsonObject { put("source", "live") }) }),
            PipelineNode("prompt", NodeTypes.PROMPT, "prompt", buildJsonObject {
                put("name", "p"); put("template", "T {{context_sources}}")
                put("schema", """{"type":"object","properties":{"text":{"type":"string"}},"required":["text"],"additionalProperties":false}""")
            }),
            PipelineNode("sum", NodeTypes.SUMMARY, "Summary", JsonObject(emptyMap())),
            PipelineNode("trust", NodeTypes.LOCAL_CRITIC, "Confidence check", JsonObject(emptyMap())),
            PipelineNode("write", NodeTypes.MEMORY_WRITE, "Save memory", JsonObject(emptyMap())),
        ), listOf(PipelineEdge("photos", "ext"), PipelineEdge("ext", "prompt"), PipelineEdge("prompt", "sum"), PipelineEdge("sum", "trust"),
            PipelineEdge("trust", "write", "trusted"), PipelineEdge("trust", "write", "unsure")))
        val est = runner.estimate(p, listOf("A", "B"), { n -> ModelLabel(n.id, "az", "gpt") })
        assertTrue("estimate: $est", est is Estimate.Ok)
        val runId = runner.start(p, p.id, null, listOf("A", "B"), (est as Estimate.Ok).confirmToken, scope)
        withTimeout(20_000) { runner.active.first { it == null } }
        val saved = CanvasRunSave(app, db, afterSave = { _, _ -> }).save(runId)
        assertTrue("saved: $saved ${db.canvasDao().run(runId)?.pipelineJson?.let { com.rokid.context.ai.canvas.PipelineJson.decode(it) }}", saved is CanvasRunSave.Result.Saved)
        fun texts() = runBlocking { MemoryGraph.get(app).memories.all().map { it.text } }
        assertTrue(texts().toString(), texts().any { "SUMMARY-A" in it })
        assertTrue(com.rokid.context.orchestration.forget.ForgetActions.forgetMoment(app, "B"))
        assertTrue("the A summary derived from B is gone after Forget B: ${texts()}", texts().none { "SUMMARY-A" in it })
    }

    // codex r16 P1-1: carried provenance is per saved memory. Vision(batch=1) over unrelated A and B, save, Forget B: A's memory and A's photo survive
    @Test fun anUnrelatedPhotosForgetLeavesTheOtherSavedMemoryAndItsPhoto() = runBlocking<Unit> {
        val db = MemoryGraph.get(app).db
        for ((id, ts) in listOf("A" to 1L, "B" to 2L)) {
            val f = java.io.File(app.filesDir, "rokid_archive/u$id.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
            com.rokid.context.orchestration.PipelineRecords.appendFrame(app, FrameEntity(id, ts, f.absolutePath, "rokid_glasses", "frame"))
        }
        val ai = TestAi.graph(app) { "{}" }.also { TestAi.bindAll(it) }
        val vision = exec(NodeTypes.VISION_EXTRACT, 1) { items -> items.map { NodeResult(it.frameId, CanvasValue.Extraction(listOf(Fact("ONLY-${it.frameId}", "object", 0.95, emptyList(), listOf(it.frameId))), emptyList())) } }
        val runner = CanvasRunner(
            db.canvasDao(), { f -> PhotoLoad(f.map(::photo), emptySet()) },
            mapOf(NodeTypes.VISION_EXTRACT to vision, NodeTypes.LOCAL_CRITIC to LocalCriticExecutor, NodeTypes.MEMORY_WRITE to MemoryWritePreviewExecutor),
            { id -> RunContext(id, ai.runner, ai.router, ai.prompts, ai.persona, { role -> ai.bindings.get(role) }, liveness = com.rokid.context.orchestration.AllLive) },
            ConfirmTokens({ now }), { false }, { now }, { "run${++ids}" }, { 0L },
        )
        val p = Pipeline(2, "pu", "P", listOf(
            PipelineNode("photos", NodeTypes.INPUT_PHOTOS, "photos", JsonObject(emptyMap())),
            PipelineNode("ext", NodeTypes.VISION_EXTRACT, "ext", buildJsonObject { put("batch", 1); put("prompt", buildJsonObject { put("source", "live") }) }),
            PipelineNode("trust", NodeTypes.LOCAL_CRITIC, "Confidence check", JsonObject(emptyMap())),
            PipelineNode("write", NodeTypes.MEMORY_WRITE, "Save memory", JsonObject(emptyMap())),
        ), listOf(PipelineEdge("photos", "ext"), PipelineEdge("ext", "trust"), PipelineEdge("trust", "write", "trusted"), PipelineEdge("trust", "write", "unsure")))
        val est = runner.estimate(p, listOf("A", "B"), { n -> ModelLabel(n.id, "az", "gpt") }) as Estimate.Ok
        val runId = runner.start(p, p.id, null, listOf("A", "B"), est.confirmToken, scope)
        withTimeout(20_000) { runner.active.first { it == null } }
        assertTrue(CanvasRunSave(app, db, afterSave = { _, _ -> }).save(runId) is CanvasRunSave.Result.Saved)
        val r = com.rokid.context.orchestration.forget.ForgetActions.forgetMomentOutcome(app, "B")
        assertTrue(r.committed)
        val texts = MemoryGraph.get(app).memories.all().map { it.text }
        assertTrue("A's own memory survives Forget B: $texts", texts.any { "ONLY-A" in it } && texts.none { "ONLY-B" in it })
        assertNotNull("A's photo survives", db.forgetDao().frame("A"))
        assertTrue("A is never tombstoned", db.forgetDao().tombstoned(listOf("A")).isEmpty())
    }

    // codex r17 P1-2: a save's provenance follows the edges into memory.write only. [photos, solo(batch=1), write, side(batch=2)] with photos->solo->write
    // and photos->side: the side branch read A and B together but never fed the save, so Forget B leaves the A fact
    @Test fun aSideBranchNeverAddsItsSourcesToTheSave() = runBlocking<Unit> {
        val db = MemoryGraph.get(app).db
        for ((id, ts) in listOf("A" to 1L, "B" to 2L)) {
            val f = java.io.File(app.filesDir, "rokid_archive/s$id.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
            com.rokid.context.orchestration.PipelineRecords.appendFrame(app, FrameEntity(id, ts, f.absolutePath, "rokid_glasses", "frame"))
        }
        val ai = TestAi.graph(app) { "{}" }.also { TestAi.bindAll(it) }
        val solo = exec(NodeTypes.VISION_EXTRACT, 1) { items -> items.map { NodeResult(it.frameId, CanvasValue.Extraction(listOf(Fact("SOLO-${it.frameId}", "object", 0.95, emptyList(), listOf(it.frameId))), emptyList())) } }
        val side = exec(NodeTypes.VISION_EXTRACT, 2) { items -> items.map { NodeResult(it.frameId, CanvasValue.Extraction(listOf(Fact("SIDE-${it.frameId}", "object", 0.95, emptyList(), listOf("A", "B"))), emptyList())) } }
        val vision = object : NodeExecutor {
            override val type = NodeTypes.VISION_EXTRACT
            private fun of(n: PipelineNode) = if (n.id == "side") side else solo
            override fun chunkSize(node: PipelineNode) = of(node).chunkSize(node)
            override fun nominalCalls(node: PipelineNode, items: Int) = 0
            override fun maxCalls(node: PipelineNode, items: Int) = 0
            override suspend fun run(node: PipelineNode, items: List<CanvasItem>, ctx: RunContext) = of(node).run(node, items, ctx)
        }
        val runner = CanvasRunner(
            db.canvasDao(), { f -> PhotoLoad(f.map(::photo), emptySet()) },
            mapOf(NodeTypes.VISION_EXTRACT to vision, NodeTypes.MEMORY_WRITE to MemoryWritePreviewExecutor),
            { id -> RunContext(id, ai.runner, ai.router, ai.prompts, ai.persona, { role -> ai.bindings.get(role) }, liveness = com.rokid.context.orchestration.AllLive) },
            ConfirmTokens({ now }), { false }, { now }, { "run${++ids}" }, { 0L },
        )
        val live = buildJsonObject { put("prompt", buildJsonObject { put("source", "live") }) }
        val p = Pipeline(2, "pb", "P", listOf(
            PipelineNode("photos", NodeTypes.INPUT_PHOTOS, "photos", JsonObject(emptyMap())),
            PipelineNode("solo", NodeTypes.VISION_EXTRACT, "solo", buildJsonObject { put("batch", 1); put("prompt", live["prompt"]!!) }),
            PipelineNode("write", NodeTypes.MEMORY_WRITE, "Save memory", JsonObject(emptyMap())),
            PipelineNode("side", NodeTypes.VISION_EXTRACT, "side", buildJsonObject { put("batch", 2); put("prompt", live["prompt"]!!) }),
        ), listOf(PipelineEdge("photos", "solo"), PipelineEdge("solo", "write"), PipelineEdge("photos", "side")))
        val est = runner.estimate(p, listOf("A", "B"), { n -> ModelLabel(n.id, "az", "gpt") })
        assertTrue("estimate: $est ${com.rokid.context.ai.canvas.PipelineValidator.validate(p)}", est is Estimate.Ok)
        val runId = runner.start(p, p.id, null, listOf("A", "B"), (est as Estimate.Ok).confirmToken, scope)
        withTimeout(20_000) { runner.active.first { it == null } }
        val saved = CanvasRunSave(app, db, afterSave = { _, _ -> }).save(runId)
        assertTrue("saved: $saved", saved is CanvasRunSave.Result.Saved)
        assertTrue(com.rokid.context.orchestration.forget.ForgetActions.forgetMomentOutcome(app, "B").committed)
        val texts = MemoryGraph.get(app).memories.all().map { it.text }
        assertTrue("the A fact survives Forget B: $texts", texts.any { "SOLO-A" in it })
    }

    // codex r18 P1-1: provenance reflects the RENDERED request. Vision(batch=2) -> Prompt -> Vision(batch=1, live template without {{context_sources}})
    // -> memory.write over unrelated A/B: the final A request held only A, so the saved A memory survives Forget B
    @Test fun unusedContextIsNeverRecordedAsProvenance() = runBlocking<Unit> {
        val db = MemoryGraph.get(app).db
        for ((id, ts) in listOf("A" to 1L, "B" to 2L)) {
            val f = java.io.File(app.filesDir, "rokid_archive/r$id.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
            com.rokid.context.orchestration.PipelineRecords.appendFrame(app, FrameEntity(id, ts, f.absolutePath, "rokid_glasses", "frame"))
        }
        val ai = TestAi.graph(app) { p ->
            val id = Regex("frame_id: ([AB])").find(p)?.groupValues?.get(1) ?: "A"
            """{"facts":[{"text":"FINAL-$id","type":"object","confidence":0.9,"tags":[],"source_frame_ids":["$id"]}],"captions":[{"frame_id":"$id","caption":"c $id"}]}"""
        }.also { TestAi.bindAll(it) }
        val first = exec(NodeTypes.VISION_EXTRACT, 2) { items -> items.map { NodeResult(it.frameId, CanvasValue.Extraction(listOf(Fact("Mug in ${it.frameId}", "object", 0.9, emptyList(), listOf("A", "B"))), emptyList())) } }
        val prompt = exec(NodeTypes.PROMPT, 1) { items -> items.map { NodeResult(it.frameId, CanvasValue.Json(buildJsonObject { put("text", "UPSTREAM-${it.frameId}") })) } }
        val runner = CanvasRunner(
            db.canvasDao(), { f -> PhotoLoad(f.map(::photo), emptySet()) },
            mapOf(NodeTypes.PROMPT to prompt, NodeTypes.VISION_EXTRACT to byNode(first, VisionExtractExecutor), NodeTypes.MEMORY_WRITE to MemoryWritePreviewExecutor),
            { id -> RunContext(id, ai.runner, ai.router, ai.prompts, ai.persona, { role -> ai.bindings.get(role) }, liveness = com.rokid.context.orchestration.AllLive) },
            ConfirmTokens({ now }), { false }, { now }, { "run${++ids}" }, { 0L },
        )
        val p = Pipeline(2, "pr", "P", listOf(
            PipelineNode("photos", NodeTypes.INPUT_PHOTOS, "photos", JsonObject(emptyMap())),
            PipelineNode("ext", NodeTypes.VISION_EXTRACT, "ext", buildJsonObject { put("batch", 2); put("prompt", buildJsonObject { put("source", "live") }) }),
            PipelineNode("prompt", NodeTypes.PROMPT, "prompt", promptParams),
            PipelineNode("last", NodeTypes.VISION_EXTRACT, "last", buildJsonObject { put("batch", 1); put("prompt", buildJsonObject { put("source", "live") }) }),
            PipelineNode("write", NodeTypes.MEMORY_WRITE, "Save memory", JsonObject(emptyMap())),
        ), listOf(PipelineEdge("photos", "ext"), PipelineEdge("ext", "prompt"), PipelineEdge("prompt", "last"), PipelineEdge("last", "write")))
        val est = runner.estimate(p, listOf("A", "B"), { n -> ModelLabel(n.id, "az", "gpt") })
        assertTrue("estimate: $est", est is Estimate.Ok)
        val runId = runner.start(p, p.id, null, listOf("A", "B"), (est as Estimate.Ok).confirmToken, scope)
        withTimeout(20_000) { runner.active.first { it == null } }
        assertTrue(CanvasRunSave(app, db, afterSave = { _, _ -> }).save(runId) is CanvasRunSave.Result.Saved)
        assertTrue(com.rokid.context.orchestration.forget.ForgetActions.forgetMomentOutcome(app, "B").committed)
        val texts = MemoryGraph.get(app).memories.all().map { it.text }
        assertTrue("the final A memory survives Forget B: $texts", texts.any { "FINAL-A" in it })
    }

    // codex r18 P1-3: call lineage follows producing edges too. [photos, side(batch=2), solo(batch=1), write]; a Forget of B before the save reaches the
    // side branch's shared call but never solo A's extraction and preview, so the save still writes the A fact
    @Test fun aSideBranchsSharedCallNeverReachesTheSoloBranchsRows() = runBlocking<Unit> {
        val db = MemoryGraph.get(app).db
        for ((id, ts) in listOf("A" to 1L, "B" to 2L)) {
            val f = java.io.File(app.filesDir, "rokid_archive/c$id.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
            com.rokid.context.orchestration.PipelineRecords.appendFrame(app, FrameEntity(id, ts, f.absolutePath, "rokid_glasses", "frame"))
        }
        val ai = TestAi.graph(app) { "{}" }.also { TestAi.bindAll(it) }
        val side = exec(NodeTypes.VISION_EXTRACT, 2) { items -> items.map { NodeResult(it.frameId, CanvasValue.Extraction(listOf(Fact("SIDE-${it.frameId}", "object", 0.95, emptyList(), listOf(it.frameId))), emptyList()),
            meta = CallMeta(1, null, null, null, null, "side-call"), calls = 1) } }
        val solo = exec(NodeTypes.VISION_EXTRACT, 1) { items -> items.map { NodeResult(it.frameId, CanvasValue.Extraction(listOf(Fact("SOLO-${it.frameId}", "object", 0.95, emptyList(), listOf(it.frameId))), emptyList()),
            meta = CallMeta(1, null, null, null, null, "solo-${it.frameId}"), calls = 1) } }
        val vision = object : NodeExecutor {
            override val type = NodeTypes.VISION_EXTRACT
            private fun of(n: PipelineNode) = if (n.id == "side") side else solo
            override fun chunkSize(node: PipelineNode) = of(node).chunkSize(node)
            override fun nominalCalls(node: PipelineNode, items: Int) = 0
            override fun maxCalls(node: PipelineNode, items: Int) = 0
            override suspend fun run(node: PipelineNode, items: List<CanvasItem>, ctx: RunContext) = of(node).run(node, items, ctx)
        }
        val runner = CanvasRunner(
            db.canvasDao(), { f -> PhotoLoad(f.map(::photo), emptySet()) },
            mapOf(NodeTypes.VISION_EXTRACT to vision, NodeTypes.MEMORY_WRITE to MemoryWritePreviewExecutor),
            { id -> RunContext(id, ai.runner, ai.router, ai.prompts, ai.persona, { role -> ai.bindings.get(role) }, liveness = com.rokid.context.orchestration.AllLive) },
            ConfirmTokens({ now }), { false }, { now }, { "run${++ids}" }, { 0L },
        )
        val live = buildJsonObject { put("source", "live") }
        val p = Pipeline(2, "pc", "P", listOf(
            PipelineNode("photos", NodeTypes.INPUT_PHOTOS, "photos", JsonObject(emptyMap())),
            PipelineNode("side", NodeTypes.VISION_EXTRACT, "side", buildJsonObject { put("batch", 2); put("prompt", live) }),
            PipelineNode("solo", NodeTypes.VISION_EXTRACT, "solo", buildJsonObject { put("batch", 1); put("prompt", live) }),
            PipelineNode("write", NodeTypes.MEMORY_WRITE, "Save memory", JsonObject(emptyMap())),
        ), listOf(PipelineEdge("photos", "side"), PipelineEdge("photos", "solo"), PipelineEdge("solo", "write")))
        val est = runner.estimate(p, listOf("A", "B"), { n -> ModelLabel(n.id, "az", "gpt") })
        assertTrue("estimate: $est", est is Estimate.Ok)
        val runId = runner.start(p, p.id, null, listOf("A", "B"), (est as Estimate.Ok).confirmToken, scope)
        withTimeout(20_000) { runner.active.first { it == null } }
        assertTrue(com.rokid.context.orchestration.forget.ForgetActions.forgetMomentOutcome(app, "B").committed)
        val left = db.canvasDao().items(runId).filter { it.frameId == "A" }.map { it.nodeId }.toSet()
        assertTrue("solo A's extraction and preview survive: $left", left.containsAll(setOf("solo", "write")))
        assertTrue(CanvasRunSave(app, db, afterSave = { _, _ -> }).save(runId) is CanvasRunSave.Result.Saved)
        assertTrue(MemoryGraph.get(app).memories.all().any { "SOLO-A" in it.text })
    }

    // Room v16: a real Vision call stores its delivery proof per photo, the proof travels through a pass-through check, and Run and Save saves the caption
    @Test fun aRealVisionRunStoresDeliveryProofAndSavesTheProvedCaption() = runBlocking<Unit> {
        val db = MemoryGraph.get(app).db
        for ((id, ts) in listOf("A" to 1L, "B" to 2L)) {
            val f = java.io.File(app.filesDir, "rokid_archive/v$id.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
            com.rokid.context.orchestration.PipelineRecords.appendFrame(app, FrameEntity(id, ts, f.absolutePath, "rokid_glasses", "frame"))
        }
        val ai = TestAi.graph(app) { p ->
            val ids = Regex("frame_id: ([AB])").findAll(p).map { it.groupValues[1] }.toList().distinct()
            """{"facts":[${ids.joinToString(",") { """{"text":"Mug $it","type":"object","confidence":0.95,"tags":[],"source_frame_ids":["$it"]}""" }}],"captions":[${ids.joinToString(",") { """{"frame_id":"$it","caption":"CAPTION-$it"}""" }}]}"""
        }.also { TestAi.bindAll(it) }
        val runner = CanvasRunner(
            db.canvasDao(), { f -> PhotoLoad(f.map(::photo), emptySet()) },
            mapOf(NodeTypes.VISION_EXTRACT to VisionExtractExecutor, NodeTypes.LOCAL_CRITIC to LocalCriticExecutor, NodeTypes.MEMORY_WRITE to MemoryWritePreviewExecutor),
            { id -> RunContext(id, ai.runner, ai.router, ai.prompts, ai.persona, { role -> ai.bindings.get(role) }, liveness = com.rokid.context.orchestration.AllLive) },
            ConfirmTokens({ now }), { false }, { now }, { "run${++ids}" }, { 0L },
        )
        val p = Pipeline(2, "pd", "P", listOf(
            PipelineNode("photos", NodeTypes.INPUT_PHOTOS, "photos", JsonObject(emptyMap())),
            PipelineNode("ext", NodeTypes.VISION_EXTRACT, "ext", buildJsonObject { put("batch", 2); put("prompt", buildJsonObject { put("source", "live") }) }),
            PipelineNode("trust", NodeTypes.LOCAL_CRITIC, "Confidence check", JsonObject(emptyMap())),
            PipelineNode("write", NodeTypes.MEMORY_WRITE, "Save memory", JsonObject(emptyMap())),
        ), listOf(PipelineEdge("photos", "ext"), PipelineEdge("ext", "trust"), PipelineEdge("trust", "write", "trusted"), PipelineEdge("trust", "write", "unsure")))
        val est = runner.estimate(p, listOf("A", "B"), { n -> ModelLabel(n.id, "az", "gpt") }) as Estimate.Ok
        val runId = runner.start(p, p.id, null, listOf("A", "B"), est.confirmToken, scope)
        withTimeout(20_000) { runner.active.first { it == null } }
        val rows = db.canvasDao().items(runId)
        assertTrue("the Vision rows carry the proof: ${rows.map { "${it.nodeId}/${it.frameId}/${it.imageDelivered}" }}", rows.filter { it.nodeId in setOf("ext", "trust") }.all { it.imageDelivered == true })
        assertTrue(CanvasRunSave(app, db, afterSave = { _, _ -> }).save(runId) is CanvasRunSave.Result.Saved)
        val caps = MemoryGraph.get(app).memories.all().filter { it.type == "caption" }
        assertEquals(setOf("CAPTION-A", "CAPTION-B"), caps.map { it.text }.toSet())
        assertTrue(caps.all { it.imageDelivered == true })
        // opus r19: A's caption came from the request that also held B, so it is linked to B and goes with it
        assertTrue(com.rokid.context.orchestration.forget.ForgetActions.forgetMomentOutcome(app, "B").committed)
        assertTrue("CAPTION-A was derived in B's request: ${MemoryGraph.get(app).memories.all().map { it.text }}", MemoryGraph.get(app).memories.all().none { it.text == "CAPTION-A" })
    }

    // opus r19, the context variant: a caption whose request rendered upstream JSON derived from B is linked to B
    @Test fun aCaptionOfARequestThatRenderedContextCarriesItsSources() = runBlocking<Unit> {
        val db = MemoryGraph.get(app).db
        for ((id, ts) in listOf("A" to 1L, "B" to 2L)) {
            val f = java.io.File(app.filesDir, "rokid_archive/x$id.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
            com.rokid.context.orchestration.PipelineRecords.appendFrame(app, FrameEntity(id, ts, f.absolutePath, "rokid_glasses", "frame"))
        }
        val ai = TestAi.graph(app) { p ->
            val id = Regex("frame_id: ([AB])").find(p)?.groupValues?.get(1) ?: "A"
            """{"facts":[{"text":"FINAL-$id","type":"object","confidence":0.95,"tags":[],"source_frame_ids":["$id"]}],"captions":[{"frame_id":"$id","caption":"CTX-CAPTION-$id"}]}"""
        }.also { TestAi.bindAll(it) }
        val first = exec(NodeTypes.VISION_EXTRACT, 2) { items -> items.map { NodeResult(it.frameId, CanvasValue.Extraction(listOf(Fact("Mug in ${it.frameId}", "object", 0.9, emptyList(), listOf("A", "B"))), emptyList())) } }
        val prompt = exec(NodeTypes.PROMPT, 1) { items -> items.map { NodeResult(it.frameId, CanvasValue.Json(buildJsonObject { put("text", "UPSTREAM-${it.frameId}") })) } }
        val runner = CanvasRunner(
            db.canvasDao(), { f -> PhotoLoad(f.map(::photo), emptySet()) },
            mapOf(NodeTypes.PROMPT to prompt, NodeTypes.VISION_EXTRACT to byNode(first, VisionExtractExecutor), NodeTypes.MEMORY_WRITE to MemoryWritePreviewExecutor),
            { id -> RunContext(id, ai.runner, ai.router, ai.prompts, ai.persona, { role -> ai.bindings.get(role) }, liveness = com.rokid.context.orchestration.AllLive) },
            ConfirmTokens({ now }), { false }, { now }, { "run${++ids}" }, { 0L },
        )
        val p = Pipeline(2, "px", "P", listOf(
            PipelineNode("photos", NodeTypes.INPUT_PHOTOS, "photos", JsonObject(emptyMap())),
            PipelineNode("ext", NodeTypes.VISION_EXTRACT, "ext", buildJsonObject { put("batch", 2); put("prompt", buildJsonObject { put("source", "live") }) }),
            PipelineNode("prompt", NodeTypes.PROMPT, "prompt", promptParams),
            PipelineNode("last", NodeTypes.VISION_EXTRACT, "last", buildJsonObject { put("batch", 1)
                put("prompt", buildJsonObject { put("source", "draft"); put("template", "X {{recent_context}} {{context_sources}}"); put("schema", kotlinx.serialization.json.JsonNull) }) }),
            PipelineNode("write", NodeTypes.MEMORY_WRITE, "Save memory", JsonObject(emptyMap())),
        ), listOf(PipelineEdge("photos", "ext"), PipelineEdge("ext", "prompt"), PipelineEdge("prompt", "last"), PipelineEdge("last", "write")))
        val est = runner.estimate(p, listOf("A", "B"), { n -> ModelLabel(n.id, "az", "gpt") })
        assertTrue("estimate: $est", est is Estimate.Ok)
        val runId = runner.start(p, p.id, null, listOf("A", "B"), (est as Estimate.Ok).confirmToken, scope)
        withTimeout(20_000) { runner.active.first { it == null } }
        assertTrue(CanvasRunSave(app, db, afterSave = { _, _ -> }).save(runId) is CanvasRunSave.Result.Saved)
        assertTrue(MemoryGraph.get(app).memories.all().any { it.text == "CTX-CAPTION-A" })
        assertTrue(com.rokid.context.orchestration.forget.ForgetActions.forgetMomentOutcome(app, "B").committed)
        assertTrue("CTX-CAPTION-A rendered B-derived context: ${MemoryGraph.get(app).memories.all().map { it.text }}", MemoryGraph.get(app).memories.all().none { it.text == "CTX-CAPTION-A" })
    }

    // ---- codex r14/r15 RULE A property, through the REAL CanvasRunner and PipelineInterpreter: every built-in executor over 3 photos (batches of 2+ where
    // the executor batches), every output OK with a value, and every output row's lineage lists every input photo of its execution (and, for an
    // executor that is not chunkLocal, every input the node consumed before)

    private val seedJson = buildJsonObject {
        put("value", 1.0); put("label", "moving"); put("text", "words")
        put("sensor", buildJsonObject { com.rokid.context.ai.canvas.WindowAggregate.FEATURES.forEach { f -> put(f, 1.0) } })
    }
    private fun seedExtraction(id: String) = CanvasValue.Extraction(listOf(Fact("Mug in $id", "object", 0.95, emptyList(), listOf(id))), listOf(FrameCaption(id, "c $id")))

    /** A per-item seed (chunk 1, chunkLocal): each input of the node under test carries exactly its own photo. */
    private fun seed(type: String, json: Boolean) = object : NodeExecutor {
        override val type = type
        override val chunkLocal = true
        override fun nominalCalls(node: PipelineNode, items: Int) = 0
        override fun maxCalls(node: PipelineNode, items: Int) = 0
        override suspend fun run(node: PipelineNode, items: List<CanvasItem>, ctx: RunContext) =
            items.map { NodeResult(it.frameId, if (json) CanvasValue.Json(seedJson) else seedExtraction(it.frameId)) }
    }

    /** Both nodes may share a type: "seed" runs the seed, "e" the real executor. */
    private fun dispatch(type: String, seed: NodeExecutor, real: NodeExecutor) = object : NodeExecutor {
        override val type = type
        private fun of(n: PipelineNode) = if (n.id == "e") real else seed
        override val chunkLocal get() = real.chunkLocal
        override fun chunkSize(node: PipelineNode) = of(node).chunkSize(node)
        override fun nominalCalls(node: PipelineNode, items: Int) = of(node).nominalCalls(node, items)
        override fun maxCalls(node: PipelineNode, items: Int) = of(node).maxCalls(node, items)
        override suspend fun run(node: PipelineNode, items: List<CanvasItem>, ctx: RunContext) = of(node).run(node, items, ctx)
    }

    private val promptParams = buildJsonObject {
        put("name", "p"); put("template", "T {{recent_context}} {{context_sources}}")
        put("schema", """{"type":"object","properties":{"text":{"type":"string"}},"required":["text"],"additionalProperties":false}""")
    }
    private fun paramsOf(type: String): JsonObject = when (type) {
        NodeTypes.VISION_EXTRACT -> buildJsonObject { put("batch", 2); put("prompt", buildJsonObject { put("source", "live") }) }
        NodeTypes.PROMPT -> promptParams
        NodeTypes.SWITCH -> buildJsonObject { put("field", "label"); put("cases", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("moving")))) }
        else -> JsonObject(emptyMap())
    }
    /** What the fake model answers, by the contract the prompt asks for. */
    private fun reply(prompt: String): String {
        val ids = Regex("frame_id: ([A-C])").findAll(prompt).map { it.groupValues[1] }.toList().distinct()
        return when {
            "verdict" in prompt -> """{"verdict":"pass","reasons":[]}"""
            "captions" in prompt -> """{"facts":[${ids.joinToString(",") { """{"text":"Mug in $it","type":"object","confidence":0.9,"tags":[],"source_frame_ids":["$it"]}""" }}],"captions":[${ids.joinToString(",") { """{"frame_id":"$it","caption":"c $it"}""" }}]}"""
            "summary" in prompt.lowercase() && "confidence" in prompt -> """{"summary":"s","confidence":0.9}"""
            else -> """{"text":"t"}"""
        }
    }

    private fun lineagePhotos(batchId: String?): Set<String> =
        batchId.orEmpty().split(CanvasLineage.SEP).mapNotNull { t -> t.substringAfterLast(CanvasRunner.MEMBER_TOKEN, "").takeIf { t.contains(CanvasRunner.MEMBER_TOKEN) && it.isNotEmpty() } }.toSet()

    /** The photos every input of the execution that produced item [i] (of [order]) consumed, by the executor's own chunking. */
    private fun expected(ex: NodeExecutor, node: PipelineNode, order: List<String>, i: Int): Set<String> {
        val size = ex.chunkSize(node).coerceAtLeast(1).coerceAtMost(order.size)
        val chunk = i / size
        return if (ex.chunkLocal) order.chunked(size)[chunk].toSet() else order.chunked(size).take(chunk + 1).flatten().toSet()
    }

    @Test fun everyBuiltInExecutorThroughTheRealRunnerCarriesEveryInputOfItsExecution() = runBlocking<Unit> {
        val photos = listOf("A", "B", "C")
        val extractionInput = setOf(NodeTypes.LOCAL_CRITIC, NodeTypes.CRITIC, NodeTypes.MEMORY_WRITE)
        // the number-port executors take Scenes (agg.window) or a number: they run over a Scene run whose items carry member photos f-<scene>
        val numberPort = setOf(NodeTypes.AGG_WINDOW, NodeTypes.COMPARE, NodeTypes.PROC_MOTION_MODEL, NodeTypes.OUT_SET_FREQUENCY_PREVIEW)
        val sceneIds = (0..2).map { "S" + com.rokid.context.memory.moment.MomentId.ofMs(com.rokid.context.canvas.moment.MomentFixture.MS + it * 60_000L) }.sorted()
        val tested = HashSet<String>()
        for ((type, real) in Executors.all()) {
            MemoryGraph.get(app).db.clearAllTables()
            val ai = TestAi.graph(app) { reply(it) }.also { TestAi.bindAll(it) }
            val scenesRun = type in numberPort
            val seedType = when {
                scenesRun -> if (type == NodeTypes.AGG_WINDOW) null else NodeTypes.AGG_WINDOW
                type in extractionInput -> NodeTypes.VISION_EXTRACT
                else -> NodeTypes.PROMPT
            }
            val seedEx = seedType?.let { seed(it, json = type !in extractionInput) }
            val executors = when {
                seedEx == null -> mapOf(type to real)
                seedType == type -> mapOf(type to dispatch(type, seedEx, real))
                else -> mapOf(seedType!! to seedEx, type to real)
            }
            val runner = CanvasRunner(
                MemoryGraph.get(app).db.canvasDao(), { f -> PhotoLoad(f.map(::photo), emptySet()) }, executors,
                { id -> RunContext(id, ai.runner, ai.router, ai.prompts, ai.persona, { role -> ai.bindings.get(role) }, liveness = com.rokid.context.orchestration.AllLive) },
                ConfirmTokens({ now }), { false }, { now }, { "run${++ids}" }, { 0L },
                scenes = com.rokid.context.canvas.moment.SceneRunSource { ids -> ids.map { com.rokid.context.canvas.moment.SceneRunItem(it, "b-$it", listOf("f-$it"), seedJson, emptyList(), listOf("m-$it")) } },
            )
            val node = PipelineNode("e", type, "e", paramsOf(type))
            val source = if (scenesRun) PipelineNode("src", NodeTypes.INPUT_SCENES, "scenes", JsonObject(emptyMap())) else PipelineNode("src", NodeTypes.INPUT_PHOTOS, "photos", JsonObject(emptyMap()))
            val nodes = listOfNotNull(source, seedType?.let { PipelineNode("seed", it, "seed", paramsOf(it)) }, node)
            val edges = if (seedType == null) listOf(PipelineEdge("src", "e")) else listOf(PipelineEdge("src", "seed"), PipelineEdge("seed", "e"))
            val p = Pipeline(2, "pp", "P", nodes, edges)
            val items = if (scenesRun) sceneIds else photos
            val est = if (scenesRun) runner.estimateScenes(p, items, { n -> ModelLabel(n.id, "az", "gpt") }) else runner.estimate(p, items, { n -> ModelLabel(n.id, "az", "gpt") })
            assertTrue("$type estimate: $est ${com.rokid.context.ai.canvas.PipelineValidator.validate(p)}", est is Estimate.Ok)
            val token = (est as Estimate.Ok).confirmToken
            val runId = if (scenesRun) runner.startScenes(p, p.id, null, items, token, scope) else runner.start(p, p.id, null, items, token, scope)
            withTimeout(20_000) { runner.active.first { it == null } }
            val keys = if (scenesRun) sceneIds.map { CanvasRunner.SCENE_KEY_PREFIX + it } else photos
            val sourcePhotos = if (scenesRun) sceneIds.map { "f-$it" } else photos
            val rows = MemoryGraph.get(app).db.canvasDao().items(runId).filter { it.nodeId == "e" }.associateBy { it.frameId }
            keys.forEachIndexed { i, id ->
                val r = rows[id]
                assertTrue("$type $id produced an OK output with a value: ${r?.status} ${r?.error} ${rows.keys}", r != null && r.status == "OK" && r.error == null && r.outputJson != null)
                val want = expected(real, node, sourcePhotos, i)
                assertTrue("$type $id carries $want, has ${lineagePhotos(r!!.batchId)}", lineagePhotos(r.batchId).containsAll(want))
            }
            tested += type
        }
        assertEquals("every built-in executor was exercised", Executors.all().keys, tested)
    }

    @Test fun theLiveInterpreterCarriesEveryInputOfAVisionBatch() = runBlocking<Unit> {
        val ai = TestAi.graph(app) { reply(it) }.also { TestAi.bindAll(it) }
        val ctx = RunContext("live", ai.runner, ai.router, ai.prompts, ai.persona, { role -> ai.bindings.get(role) }, callClass = CallClass.EXTRACT, liveness = com.rokid.context.orchestration.AllLive)
        val p = Pipeline(2, "lp", "L", listOf(
            PipelineNode("photos", NodeTypes.INPUT_PHOTOS, "photos", JsonObject(emptyMap())),
            PipelineNode("prompt", NodeTypes.PROMPT, "prompt", promptParams),
            PipelineNode("switch", NodeTypes.SWITCH, "switch", buildJsonObject { put("field", "text"); put("cases", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("t")))) }),
            PipelineNode("ext", NodeTypes.VISION_EXTRACT, "ext", paramsOf(NodeTypes.VISION_EXTRACT)),
        ), listOf(PipelineEdge("photos", "prompt"), PipelineEdge("prompt", "switch"), PipelineEdge("switch", "ext", "t")))
        val out = PipelineInterpreter(clock = { now }).run(p, 1, "LIVE", listOf("A", "B", "C").map(::photo), ctx)
        assertTrue("$out", out is PipelineInterpreter.Outcome.Extracted)
        val traces = (out as PipelineInterpreter.Outcome.Extracted).traces.filter { it.nodeId == "ext" }.associateBy { it.frameId }
        assertEquals(setOf("A", "B", "C"), traces.keys)
        assertTrue(traces.values.all { it.status == "OK" })
        assertTrue("batch A+B: ${traces.mapValues { lineagePhotos(it.value.batchId) }}", lineagePhotos(traces.getValue("A").batchId).containsAll(setOf("A", "B")) && lineagePhotos(traces.getValue("B").batchId).containsAll(setOf("A", "B")))
        assertTrue(lineagePhotos(traces.getValue("C").batchId).contains("C"))
    }
}
