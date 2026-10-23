package com.rokid.context.canvas

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.Endpoint
import com.rokid.context.ai.FailureKind
import com.rokid.context.ai.InMemoryKeyVault
import com.rokid.context.ai.InMemorySeedState
import com.rokid.context.ai.ModelCallException
import com.rokid.context.ai.ProviderBuilder
import com.rokid.context.ai.Role
import com.rokid.context.ai.RoutedReply
import com.rokid.context.ai.canvas.DefaultPipelines
import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.canvas.Pipeline
import com.rokid.context.ai.canvas.PipelineEdge
import com.rokid.context.ai.canvas.PipelineJson
import com.rokid.context.ai.canvas.PipelineNode
import com.rokid.context.ai.contract.DefaultSchemas
import com.rokid.context.ai.contract.Fact
import com.rokid.context.ai.contract.FrameCaption
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.model.CallClass
import com.rokid.context.model.CallGate
import com.rokid.context.model.LanguageModel
import com.rokid.context.model.ModelImageInput
import com.rokid.context.model.ModelProvider
import com.rokid.context.model.ModelRequest
import com.rokid.context.orchestration.ExtractPhoto
import com.rokid.context.orchestration.ExtractSave
import com.rokid.context.orchestration.TestAi
import com.rokid.context.prompt.PromptDraft
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.io.InterruptedIOException
import kotlin.coroutines.cancellation.CancellationException

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PipelineInterpreterTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val prompts = mutableListOf<String>()
    private val classes = mutableListOf<CallClass>()
    private var unbound = emptySet<Role>()
    private var now = 1_000L
    private lateinit var ai: AiGraph

    @Before fun setUp() { MemoryGraph.resetForTests(DatabaseFactory.inMemory(app)) }
    @After fun tearDown() { AiGraph.resetForTests(null); MemoryGraph.get(app).db.close(); MemoryGraph.resetForTests(null) }

    private fun sentIds(p: String) = Regex("frame_id: (ex\\d)").findAll(p).map { it.groupValues[1] }.toList().distinct()
    private fun photo(id: String) = ExtractPhoto(id, "ts=1", ModelImageInput("image/jpeg", "AAAA"))
    private fun photos(vararg ids: String) = ids.map(::photo)
    private fun factJson(text: String, vararg ids: String) =
        """{"text":"$text","type":"object","confidence":0.9,"tags":[],"source_frame_ids":[${ids.joinToString(",") { "\"$it\"" }}]}"""
    private fun extractReply(vararg ids: String) =
        """{"facts":[${ids.joinToString(",") { factJson("Mug in $it", it) }}],"captions":[${ids.joinToString(",") { """{"frame_id":"$it","caption":"Counter $it"}""" }}]}"""

    /** The AiGraph whose every provider attempt goes through a recording gate (it sees the call class of the runner and of the model calls). */
    private fun graph(reply: suspend (String) -> String) {
        val gate = object : CallGate {
            override suspend fun <T> withPermit(callClass: CallClass, block: suspend () -> T): T { classes += callClass; return block() }
        }
        ai = AiGraph(MemoryGraph.get(app), InMemoryKeyVault(), InMemorySeedState(), ProviderBuilder { _, _, _ ->
            object : ModelProvider { override suspend fun generate(prompt: String): String { prompts += prompt; return reply(prompt) } }
        }, gate = gate).also { AiGraph.resetForTests(it) }
        TestAi.bindAll(ai)
    }

    private fun ctx(callClass: CallClass = CallClass.EXPERIMENT, model: LanguageModel? = null) =
        RunContext("live:7", ai.runner, model ?: ai.router, ai.prompts, ai.persona, { r -> if (r in unbound) null else ai.bindings.get(r) }, callClass = callClass)

    private fun interpreter(executors: Map<String, NodeExecutor> = Executors.all()) = PipelineInterpreter(executors, { now })

    private fun node(type: String, id: String, params: JsonObject = JsonObject(emptyMap())) = PipelineNode(id, type, id, params)
    private fun draft(template: String) = buildJsonObject { putJsonObject("prompt") { put("source", "draft"); put("template", template); put("schema", JsonPrimitive(null as String?)) } }
    private fun extractNode(id: String, template: String? = null, batch: Int = 3) = node(NodeTypes.VISION_EXTRACT, id, buildJsonObject {
        put("batch", batch)
        (template?.let(::draft) ?: buildJsonObject { putJsonObject("prompt") { put("source", "live") } }).forEach { (k, v) -> put(k, v) }
    })

    private val intentSchema = """{"type":"object","properties":{"activity":{"type":"string"},"confidence":{"type":"number"}},"required":["activity"],"additionalProperties":false}"""
    private fun intentNode() = node(NodeTypes.PROMPT, "intent", buildJsonObject {
        put("role", "VISION_EXTRACT"); put("name", "intent"); put("images", true); put("template", "INTENT {{recent_context}}"); put("schema", intentSchema)
    })
    private fun switchNode() = node(NodeTypes.SWITCH, "route", buildJsonObject {
        put("field", "activity"); put("cases", JsonArray(listOf(JsonPrimitive("driving"), JsonPrimitive("computer_work"))))
    })

    /** photos -> intent -> route -> {driving: DRIVING_X, computer_work: DESK_X, other: live-prompt extract}. */
    private fun intentPipeline() = Pipeline(
        PipelineJson.SCHEMA, "p", "p",
        listOf(
            node(NodeTypes.INPUT_PHOTOS, "photos"), intentNode(), switchNode(),
            extractNode("ex_driving", "DRIVING_X {{recent_context}} {{context_sources}}"),
            extractNode("ex_desk", "DESK_X {{recent_context}} {{context_sources}}"),
            extractNode("ex_other"),
        ),
        listOf(
            PipelineEdge("photos", "intent"), PipelineEdge("intent", "route"),
            PipelineEdge("route", "ex_driving", "driving"), PipelineEdge("route", "ex_desk", "computer_work"), PipelineEdge("route", "ex_other", "other"),
        ),
    )

    private fun intentReply(p: String): String? = when {
        "INTENT" !in p -> null
        "ex1" in p -> """{"activity":"driving","confidence":0.8}"""
        "ex2" in p -> """{"activity":"computer_work"}"""
        else -> """{"activity":"sleeping","confidence":0.4}"""
    }
    private fun intentGraph() = graph { p -> intentReply(p) ?: extractReply(*sentIds(p).toTypedArray()) }

    private suspend fun run(p: Pipeline, ph: List<ExtractPhoto>, c: RunContext = ctx(), i: PipelineInterpreter = interpreter()) = i.run(p, 7L, "LIVE", ph, c)
    private fun extracted(o: PipelineInterpreter.Outcome) = o as? PipelineInterpreter.Outcome.Extracted ?: fail("expected Extracted but was $o").let { error("unreachable") }
    private fun failed(o: PipelineInterpreter.Outcome) = o as? PipelineInterpreter.Outcome.Failed ?: fail("expected Failed but was $o").let { error("unreachable") }

    /** An executor that runs [fn] for the whole chunk (for failure-path tests that must not depend on the router). */
    private fun fake(type: String, fn: suspend (PipelineNode, List<CanvasItem>) -> List<NodeResult>): NodeExecutor = object : NodeExecutor {
        override val type = type
        override fun chunkSize(node: PipelineNode) = 3
        override fun nominalCalls(node: PipelineNode, items: Int) = 0
        override fun maxCalls(node: PipelineNode, items: Int) = 0
        override suspend fun run(node: PipelineNode, items: List<CanvasItem>, ctx: RunContext) = fn(node, items)
    }

    // ---- routing and merge ----

    @Test fun intentRoutesToPerLabelPrompt() = runBlocking<Unit> {
        intentGraph()
        val o = extracted(run(intentPipeline(), photos("ex1", "ex2")))
        val intent = prompts.filter { "INTENT" in it }
        assertEquals(listOf(listOf("ex1"), listOf("ex2")), intent.map(::sentIds))
        val driving = prompts.single { "DRIVING_X" in it }
        val desk = prompts.single { "DESK_X" in it }
        assertEquals(listOf("ex1"), sentIds(driving))
        assertEquals(listOf("ex2"), sentIds(desk))
        assertTrue(driving.contains("BEGIN_DATA upstream") && driving.contains("\"activity\":\"driving\""))
        assertTrue(desk.contains("\"activity\":\"computer_work\""))
        assertEquals(listOf("Mug in ex1", "Mug in ex2"), o.batch.output.facts.map { it.text })
    }

    @Test fun mergedBatchHasFactsAndCaptionsPerFrame() = runBlocking<Unit> {
        graph { extractReply(*sentIds(it).toTypedArray()) }
        val o = extracted(run(DefaultPipelines.liveExtract(), photos("ex1", "ex2", "ex3")))
        assertEquals(listOf("Mug in ex1", "Mug in ex2", "Mug in ex3"), o.batch.output.facts.map { it.text })
        assertEquals(listOf("ex1", "ex2", "ex3"), o.batch.output.captions.map { it.frameId })
        assertTrue(o.batch.output.provenance)
        assertEquals(0, o.batch.promptVersion)
        assertEquals(1, prompts.size)
    }

    @Test fun duplicateFactAcrossItemsMergedOnce() = runBlocking<Unit> {
        // One fact cites both photos (so both items carry it) and the model repeats another one verbatim.
        graph { p ->
            val ids = sentIds(p)
            val caps = ids.joinToString(",") { """{"frame_id":"$it","caption":"Counter $it"}""" }
            """{"facts":[${factJson("Shared mug", "ex1", "ex2")},${factJson("Cup", "ex1")},${factJson("Cup", "ex1")}],"captions":[$caps]}"""
        }
        val o = extracted(run(DefaultPipelines.liveExtract(), photos("ex1", "ex2")))
        assertEquals(listOf("Shared mug", "Cup"), o.batch.output.facts.map { it.text })
        assertEquals(listOf("ex1", "ex2"), o.batch.output.captions.map { it.frameId })
    }

    @Test fun sameTextWithDifferentSourceSetsIsKept() = runBlocking<Unit> {
        graph { p ->
            val id = sentIds(p).single()
            """{"facts":[${factJson("Mug", id)}],"captions":[{"frame_id":"$id","caption":"Counter $id"}]}"""
        }
        val p = Pipeline(PipelineJson.SCHEMA, "p", "p", listOf(node(NodeTypes.INPUT_PHOTOS, "photos"), extractNode("e", batch = 1)), listOf(PipelineEdge("photos", "e")))
        val o = extracted(run(p, photos("ex1", "ex2")))
        assertEquals(listOf(listOf("ex1"), listOf("ex2")), o.batch.output.facts.map { it.sourceFrameIds })
    }

    @Test fun liveExtractShapeEqualsDirectExtract() = runBlocking<Unit> {
        graph { p -> extractReply(*sentIds(p).toTypedArray()) }
        val three = photos("ex1", "ex2", "ex3")
        val direct = ExtractSave.extract(ai.runner, three, CallClass.EXTRACT)
        val viaInterpreter = extracted(run(DefaultPipelines.liveExtract(), three, ctx(CallClass.EXTRACT)))
        assertEquals(direct.output.facts, viaInterpreter.batch.output.facts)
        assertEquals(direct.output.captions, viaInterpreter.batch.output.captions)
        assertEquals(direct.output.provenance, viaInterpreter.batch.output.provenance)
    }

    @Test fun legacySchemaProvenanceMatchesDirect() = runBlocking<Unit> {
        graph { p -> """{"facts":[{"text":"Mug in ${sentIds(p).first()}","type":"object","confidence":0.9,"tags":[]}]}""" }
        val v = ai.prompts.active(PromptKey.VISION_EXTRACT)
        ai.prompts.save(PromptKey.VISION_EXTRACT, PromptDraft(v.template, DefaultSchemas.text(PromptKey.EXPLICIT_REMEMBER)), "old", false)
        val two = photos("ex1", "ex2")
        val direct = ExtractSave.extract(ai.runner, two, CallClass.EXTRACT)
        assertFalse(direct.output.provenance)
        prompts.clear()
        val o = extracted(run(DefaultPipelines.liveExtract(), two, ctx(CallClass.EXTRACT)))
        assertFalse(o.batch.output.provenance)
        assertEquals(direct.output.facts, o.batch.output.facts)
        assertTrue(o.batch.output.captions.isEmpty())
        assertEquals(listOf(listOf("ex1"), listOf("ex2")), o.batch.output.facts.map { it.sourceFrameIds })
    }

    // ---- failures ----

    @Test fun invalidIntentOutputFails() = runBlocking<Unit> {
        graph { p -> if ("INTENT" in p) """{"other":1}""" else extractReply(*sentIds(p).toTypedArray()) }
        val f = failed(run(intentPipeline(), photos("ex1", "ex2")))
        assertEquals("INVALID_OUTPUT", f.reason)
        assertTrue(prompts.none { "DRIVING_X" in it || "DESK_X" in it }) // no extraction was paid for
        val batchRows = f.traces.filter { it.nodeId == "_batch" }
        assertEquals(listOf("ex1", "ex2"), batchRows.map { it.frameId })
        assertTrue(batchRows.all { it.status == "ERROR" && it.error == "INVALID_OUTPUT" })
        assertTrue(f.traces.any { it.nodeId == "intent" && it.status == "ERROR" })
    }

    @Test fun unboundRoleFails() = runBlocking<Unit> {
        intentGraph()
        unbound = setOf(Role.VISION_EXTRACT)
        val f = failed(run(intentPipeline(), photos("ex1")))
        assertEquals("NOT_SET", f.reason)
        assertTrue(prompts.isEmpty())
    }

    @Test fun itemReachingTwoExtractsFails() = runBlocking<Unit> {
        graph { p -> extractReply(*sentIds(p).toTypedArray()) }
        val p = Pipeline(
            PipelineJson.SCHEMA, "p", "p", listOf(node(NodeTypes.INPUT_PHOTOS, "photos"), extractNode("a"), extractNode("b")),
            listOf(PipelineEdge("photos", "a"), PipelineEdge("photos", "b")),
        )
        val f = failed(run(p, photos("ex1", "ex2")))
        assertEquals("routing", f.reason)
        assertEquals(1, prompts.size) // the second extractor never called the model
        assertEquals(2, f.traces.count { it.nodeId == "_batch" && it.status == "ERROR" && it.error == "routing" })
    }

    @Test fun itemReachingNoExtractFails() = runBlocking<Unit> {
        intentGraph()
        val full = intentPipeline()
        val unwired = full.copy(nodes = full.nodes.filter { it.id != "ex_other" }, edges = full.edges.filter { it.to != "ex_other" })
        val f = failed(run(unwired, photos("ex3"))) // "sleeping" routes to other, which goes nowhere
        assertEquals("routing", f.reason)
    }

    @Test fun retryableModelErrorRethrown() = runBlocking<Unit> {
        graph { "" }
        val boom = object : LanguageModel {
            override suspend fun complete(role: Role, callClass: CallClass, build: (Endpoint) -> ModelRequest): RoutedReply =
                throw ModelCallException("slow", retryable = true, kind = FailureKind.TRANSIENT)
            override suspend fun completeFromFrames(callClass: CallClass, build: (Endpoint) -> ModelRequest): RoutedReply = throw IllegalStateException()
            override suspend fun completeOn(endpointId: String, model: String, callClass: CallClass, build: (Endpoint) -> ModelRequest): RoutedReply = throw IllegalStateException()
        }
        try {
            run(intentPipeline(), photos("ex1"), ctx(model = boom))
            fail("expected the transient failure to propagate")
        } catch (e: ModelCallException) {
            assertEquals(FailureKind.TRANSIENT, e.kind)
        }
    }

    @Test fun cancellationRethrown() = runBlocking<Unit> {
        graph { "" }
        val ex = mapOf(NodeTypes.VISION_EXTRACT to fake(NodeTypes.VISION_EXTRACT) { _, _ -> throw CancellationException("stop") })
        try {
            run(DefaultPipelines.liveExtract(), photos("ex1"), i = interpreter(ex))
            fail("expected the cancellation to propagate")
        } catch (c: CancellationException) {
            assertEquals("stop", c.message)
        }
    }

    private fun failingWith(t: Throwable) = mapOf(NodeTypes.VISION_EXTRACT to fake(NodeTypes.VISION_EXTRACT) { _, items ->
        items.map { NodeResult(it.frameId, null, error = "X", cause = t, calls = 1) }
    })

    @Test fun invalidOutputRetryableFails() = runBlocking<Unit> {
        graph { "" }
        val t = ModelCallException("bad json", retryable = true, kind = FailureKind.INVALID_OUTPUT)
        val f = failed(run(DefaultPipelines.liveExtract(), photos("ex1"), i = interpreter(failingWith(t))))
        assertEquals("INVALID_OUTPUT", f.reason)
    }

    @Test fun notSetRetryableFails() = runBlocking<Unit> {
        graph { "" }
        val t = ModelCallException("no model", retryable = true, kind = FailureKind.NOT_SET)
        assertEquals("NOT_SET", failed(run(DefaultPipelines.liveExtract(), photos("ex1"), i = interpreter(failingWith(t)))).reason)
    }

    @Test fun authRejectedAndOtherThrowablesFail() = runBlocking<Unit> {
        graph { "" }
        for ((t, reason) in listOf(
            ModelCallException("a", retryable = false, kind = FailureKind.AUTH) to "AUTH",
            ModelCallException("r", retryable = false, kind = FailureKind.REJECTED) to "REJECTED",
            ModelCallException("t", retryable = false, kind = FailureKind.TRANSIENT) to "TRANSIENT", // not retryable: the fallback decides
            IllegalStateException("boom") to "IllegalStateException",
        )) assertEquals(reason, failed(run(DefaultPipelines.liveExtract(), photos("ex1"), i = interpreter(failingWith(t)))).reason)
    }

    @Test fun ioInCauseChainRethrown() = runBlocking<Unit> {
        graph { "" }
        for (io in listOf(IOException("net"), InterruptedIOException("timeout"))) {
            // A SUMMARIZE-style wrapper: a non-transient looking ModelCallException around an IOException.
            val t = ModelCallException("wrapped", retryable = false, cause = RuntimeException("mid", io), kind = FailureKind.REJECTED)
            try {
                run(DefaultPipelines.liveExtract(), photos("ex1"), i = interpreter(failingWith(t)))
                fail("expected the IOException chain to propagate")
            } catch (e: ModelCallException) {
                assertTrue(generateSequence<Throwable>(e) { it.cause }.any { it === io })
            }
        }
    }

    @Test fun ioFromTheProviderPropagatesThroughTheRealPath() = runBlocking<Unit> {
        graph { throw IOException("offline") }
        try {
            run(DefaultPipelines.liveExtract(), photos("ex1"))
            fail("expected the transient failure to propagate")
        } catch (e: Exception) {
            assertTrue(generateSequence<Throwable>(e) { it.cause }.any { it is IOException })
        }
    }

    @Test fun transientWinsOverEarlierNonTransientFailure() = runBlocking<Unit> {
        graph { "" }
        val ex = mapOf(NodeTypes.VISION_EXTRACT to fake(NodeTypes.VISION_EXTRACT) { _, items ->
            items.mapIndexed { i, it -> NodeResult(it.frameId, null, error = "X", cause = if (i == 0) IllegalStateException("a") else IOException("b")) }
        })
        try {
            run(DefaultPipelines.liveExtract(), photos("ex1", "ex2"), i = interpreter(ex))
            fail("expected the IOException to propagate")
        } catch (e: IOException) {
            assertEquals("b", e.message)
        }
    }

    // ---- labels, traces, class ----

    @Test fun labelsCollected() = runBlocking<Unit> {
        intentGraph()
        val o = extracted(run(intentPipeline(), photos("ex1", "ex2", "ex3")))
        assertEquals(mapOf("ex1" to ("driving" to 0.8f), "ex2" to ("computer_work" to null), "ex3" to ("other" to 0.4f)), o.labels)
    }

    @Test fun noSwitchNoLabels() = runBlocking<Unit> {
        graph { extractReply(*sentIds(it).toTypedArray()) }
        assertTrue(extracted(run(DefaultPipelines.liveExtract(), photos("ex1"))).labels.isEmpty())
    }

    @Test fun tracesPerNodeAndBatchRow() = runBlocking<Unit> {
        intentGraph()
        now = 4_242L
        val o = extracted(run(intentPipeline(), photos("ex1", "ex2")))
        val byFrame = o.traces.groupBy { it.frameId }
        assertEquals(listOf("intent", "route", "ex_driving", "_batch"), byFrame.getValue("ex1").map { it.nodeId })
        assertEquals(listOf("intent", "route", "ex_desk", "_batch"), byFrame.getValue("ex2").map { it.nodeId })
        assertTrue(o.traces.all { it.publishId == 7L && it.mode == "LIVE" && it.status == "OK" && it.error == null && it.at == 4_242L })
        assertEquals("driving", o.traces.single { it.frameId == "ex1" && it.nodeId == "route" }.branch)
        assertTrue(o.traces.single { it.frameId == "ex1" && it.nodeId == "ex_driving" }.outputJson!!.contains("Mug in ex1"))
        assertNull(o.traces.single { it.nodeId == "_batch" && it.frameId == "ex1" }.outputJson)
        assertTrue(o.traces.filter { it.nodeId == "intent" || it.nodeId.startsWith("ex_") }.all { it.batchId != null }) // the model calls carry one
        assertTrue(o.traces.filter { it.nodeId == "_batch" }.all { it.batchId == null })
        assertTrue("downstream rows keep their frame's batch", o.traces.filter { it.nodeId == "route" }.all { it.batchId != null })
    }

    @Test fun forgettingAPhotoRemovesTheTracesSharingItsExtractCallAndKeepsTheLineageTokens() = runBlocking<Unit> {
        // The interpreter only runs the pre-save nodes (prompts, switch, extract): the critic and write never run here, so the chain is intent -> extract.
        graph { p -> extractReply(*sentIds(p).toTypedArray()) }
        val fakeExtract = fake(NodeTypes.VISION_EXTRACT) { _, items ->
            items.map { NodeResult(it.frameId, CanvasValue.Extraction(listOf(Fact("Shared mug", "object", 0.9, emptyList(), items.map { x -> x.frameId })),
                items.map { x -> FrameCaption(x.frameId, "c") }), calls = 1, meta = CallMeta(5, null, null, null, null, "ex:shared")) }
        }
        val promptFake = fake(NodeTypes.PROMPT) { _, items -> items.map { NodeResult(it.frameId, CanvasValue.Json(JsonObject(emptyMap())), calls = 1, meta = CallMeta(5, null, null, null, null, "intent:" + it.frameId)) } }
        val p = Pipeline(PipelineJson.SCHEMA, "x", "x", listOf(node(NodeTypes.INPUT_PHOTOS, "photos"), node(NodeTypes.PROMPT, "intent"), extractNode("extract")),
            listOf(PipelineEdge("photos", "intent"), PipelineEdge("intent", "extract")))
        val o = extracted(run(p, photos("ex1", "ex2"), i = interpreter(Executors.all() + mapOf(NodeTypes.PROMPT to promptFake, NodeTypes.VISION_EXTRACT to fakeExtract))))
        val ex2Extract = o.traces.single { it.frameId == "ex2" && it.nodeId == "extract" }
        assertTrue("the extract row names the shared call AND the frame's own earlier call: " + ex2Extract.batchId, ex2Extract.batchId!!.contains("intent:ex2") && ex2Extract.batchId!!.contains("ex:shared"))
        val dao = MemoryGraph.get(app).db.canvasDao()
        assertTrue(dao.commitTracesFenced(o.traces, 0, setOf("ex1", "ex2")) { 0 })
        dao.deleteCanvasOutputsForFrames(listOf("ex1"))
        val left = dao.traces(7, 100).filter { it.frameId == "ex2" && it.outputJson?.contains("Shared mug") == true }
        assertTrue("traces of ex2 still hold the forgotten fact: " + left.map { it.nodeId }, left.isEmpty())
    }

    @Test fun traceBatchIdsAreUniquePerRun() = runBlocking<Unit> {
        graph { extractReply(*sentIds(it).toTypedArray()) }
        val a = extracted(run(DefaultPipelines.liveExtract(), photos("ex1")))
        val b = extracted(run(DefaultPipelines.liveExtract(), photos("ex1")))
        val ida = a.traces.mapNotNull { it.batchId }.toSet()
        val idb = b.traces.mapNotNull { it.batchId }.toSet()
        assertTrue(ida.isNotEmpty() && idb.isNotEmpty())
        assertTrue(ida.intersect(idb).isEmpty())
    }

    @Test fun traceOutputIsCapped() = runBlocking<Unit> {
        graph { "" }
        val big = CanvasValue.Extraction(listOf(Fact("x".repeat(20_000), "object", 0.9, emptyList(), listOf("ex1"))), listOf(FrameCaption("ex1", "c")))
        val ex = mapOf(NodeTypes.VISION_EXTRACT to fake(NodeTypes.VISION_EXTRACT) { _, items -> items.map { NodeResult(it.frameId, big) } })
        val o = extracted(run(DefaultPipelines.liveExtract(), photos("ex1"), i = interpreter(ex)))
        val out = o.traces.single { it.nodeId == "extract" }.outputJson!!
        assertEquals(PipelineInterpreter.OUTPUT_CAP, out.length)
        assertTrue(out.endsWith(PipelineInterpreter.TRUNCATED))
    }

    @Test fun callClassFromContext() = runBlocking<Unit> {
        intentGraph()
        extracted(run(intentPipeline(), photos("ex1", "ex2"), ctx(CallClass.EXTRACT)))
        assertTrue(classes.isNotEmpty() && classes.all { it == CallClass.EXTRACT })
        val live = classes.size
        classes.clear()
        extracted(run(intentPipeline(), photos("ex1", "ex2"), ctx()))
        assertEquals(live, classes.size)
        assertTrue(classes.all { it == CallClass.EXPERIMENT })
    }

    @Test fun interpreterWritesNothing() = runBlocking<Unit> {
        intentGraph()
        val db = MemoryGraph.get(app).db.openHelper.readableDatabase
        fun count(t: String) = db.query("SELECT COUNT(*) FROM `$t`").use { it.moveToFirst(); it.getInt(0) }
        val tables = listOf("memory", "frame_state", "frame_source", "memory_vector", "canvas_live_trace", "canvas_run_item", "frame_meta")
        val before = tables.map(::count)
        extracted(run(intentPipeline(), photos("ex1", "ex2")))
        assertEquals(before, tables.map(::count))
    }

    @Test fun sinkNoneDoesNothing() = runBlocking<Unit> {
        val seen = mutableListOf<String>()
        val sink = FrameIntentSink { f, l, _, _ -> seen += "$f:$l" }
        sink.record("a", "driving", 0.5f, 1L)
        assertEquals(listOf("a:driving"), seen)
        FrameIntentSink.None.record("a", "driving", 0.5f, 1L) // returns without effect
        assertTrue(seen.size == 1)
    }

    // ---- fix round 1 ----

    @Test fun intentBatchIdsDistinctPerPhotoAndExtractCallSharedOnlyByItsFrames() = runBlocking<Unit> {
        graph { p -> intentReply(p) ?: extractReply(*sentIds(p).toTypedArray()) }
        val o = extracted(run(intentPipeline(), photos("ex1", "ex2", "ex3")))
        val intent = o.traces.filter { it.nodeId == "intent" }.map { it.batchId }
        assertEquals(3, intent.toSet().size)
        assertTrue(intent.none { it == null })
        val extractRows = o.traces.filter { it.nodeId.startsWith("ex_") }
        assertEquals(3, extractRows.map { it.batchId }.toSet().size) // three labels, three single-photo calls
        assertTrue(extractRows.map { it.batchId }.toSet().intersect(intent.toSet()).isEmpty())
    }

    @Test fun oneExtractCallOfThreePhotosSharesOneBatchId() = runBlocking<Unit> {
        graph { extractReply(*sentIds(it).toTypedArray()) }
        val o = extracted(run(DefaultPipelines.liveExtract(), photos("ex1", "ex2", "ex3")))
        val ids = o.traces.filter { it.nodeId == "extract" }
        assertEquals(setOf("ex1", "ex2", "ex3"), ids.map { it.frameId }.toSet())
        assertEquals(1, ids.map { it.batchId }.toSet().size)
    }

    @Test fun labelIsNormalisedToSwitchCasesOrOther() = runBlocking<Unit> {
        graph { p ->
            when {
                "INTENT" !in p -> extractReply(*sentIds(p).toTypedArray())
                "ex1" in p -> """{"activity":"  DRIVING ","confidence":2}"""
                "ex2" in p -> """{"activity":"${"x".repeat(200)}"}"""
                else -> """{"activity":"Computer_Work"}"""
            }
        }
        val o = extracted(run(intentPipeline(), photos("ex1", "ex2", "ex3")))
        assertEquals(mapOf("ex1" to ("driving" to 1f), "ex2" to ("other" to null), "ex3" to ("computer_work" to null)), o.labels)
    }

    @Test fun nodeResultToStringNeverPrintsTheCause() {
        val r = NodeResult("f", null, error = "X", cause = ModelCallException("provider said hunter2", true))
        assertFalse(r.toString().contains("hunter2"))
    }

    @Test fun traceCapDoesNotSplitASurrogatePair() = runBlocking<Unit> {
        graph { "" }
        for (prefix in listOf("", "x")) {
            val big = CanvasValue.Extraction(listOf(Fact(prefix + "\uD83D\uDE00".repeat(10_000), "object", 0.9, emptyList(), listOf("ex1"))), emptyList())
            val ex = mapOf(NodeTypes.VISION_EXTRACT to fake(NodeTypes.VISION_EXTRACT) { _, items -> items.map { NodeResult(it.frameId, big) } })
            val out = extracted(run(DefaultPipelines.liveExtract(), photos("ex1"), i = interpreter(ex))).traces.single { it.nodeId == "extract" }.outputJson!!
            assertTrue(out.length <= PipelineInterpreter.OUTPUT_CAP && out.endsWith(PipelineInterpreter.TRUNCATED))
            val body = out.dropLast(PipelineInterpreter.TRUNCATED.length)
            assertFalse(Character.isHighSurrogate(body.last()))
        }
    }

    @Test fun criticExecutorUsesTheContextCallClass() = runBlocking<Unit> {
        graph { """{"verdict":"pass","reasons":[]}""" }
        val v = CanvasValue.Extraction(listOf(Fact("Mug", "object", 0.9, emptyList(), listOf("ex1"))), emptyList())
        CriticExecutor.run(node(NodeTypes.CRITIC, "critic"), listOf(CanvasItem("ex1", photo("ex1"), v)), ctx(CallClass.EXTRACT))
        assertEquals(listOf(CallClass.EXTRACT), classes)
    }
    // ---- WP7 pins: unknown and tier-3 nodes ----

    /**
     * Pin: an in-path node (one of `InterpretableShape.preSaveNodes`: input, prompt, switch, extract) whose executor is missing fails the batch with `no_executor`.
     * A node type OUTSIDE that set is skipped silently by `preSaveNodes`, so tier 3 is closed by the start guard, not by this code
     * (see `interpreterRefusesToStartWithTier3Node`; do not remove that guard as redundant).
     */
    @Test fun aNodeWithNoExecutorFailsTheBatchWithNoExecutor() = runBlocking<Unit> {
        graph { "" }
        val f = failed(run(DefaultPipelines.liveExtract(), photos("ex1"), i = interpreter(emptyMap())))
        assertEquals("no_executor", f.reason)
        assertTrue(f.traces.any { it.nodeId == "extract" && it.error == "no_executor" })
    }

    /** Defence in depth (S6): a graph with a tier-3 node never starts; no executor runs, every photo gets a `_batch` ERROR row with the fixed reason. */
    @Test fun interpreterRefusesToStartWithTier3Node() = runBlocking<Unit> {
        graph { "" }
        var ran = 0
        val spy = mapOf(NodeTypes.VISION_EXTRACT to fake(NodeTypes.VISION_EXTRACT) { _, items -> ran++; items.map { NodeResult(it.frameId, null) } })
        for (type in listOf("py.fn", "tool.draft", "tool.generated")) {
            val base = DefaultPipelines.liveExtract()
            val p = base.copy(nodes = base.nodes + node(type, "t3"))
            val f = failed(run(p, photos("ex1", "ex2"), i = interpreter(spy)))
            assertEquals(type, "tier3_node", f.reason)
            assertEquals(type, 2, f.traces.count { it.nodeId == "_batch" && it.error == "tier3_node" })
        }
        assertEquals("no executor was called", 0, ran)
    }
}
