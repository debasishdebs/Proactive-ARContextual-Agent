package com.rokid.context.canvas

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.Endpoint
import com.rokid.context.ai.Role
import com.rokid.context.ai.RoutedReply
import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.canvas.PipelineNode
import com.rokid.context.ai.contract.Fact
import com.rokid.context.ai.contract.FrameCaption
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.model.CallClass
import com.rokid.context.model.LanguageModel
import com.rokid.context.model.ModelImageInput
import com.rokid.context.model.ModelRequest
import com.rokid.context.orchestration.ExtractPhoto
import com.rokid.context.orchestration.TestAi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ExecutorsTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val prompts = mutableListOf<String>()
    private val requests = mutableListOf<ModelRequest>()
    private var unbound = emptySet<Role>()

    @Before fun setUp() { MemoryGraph.resetForTests(DatabaseFactory.inMemory(app)) }
    @After fun tearDown() { AiGraph.resetForTests(null); MemoryGraph.get(app).db.close(); MemoryGraph.resetForTests(null) }

    private fun sentIds(p: String) = Regex("frame_id: (ex\\d)").findAll(p).map { it.groupValues[1] }.toList().distinct()
    private fun extractReply(vararg ids: String) =
        """{"facts":[${ids.joinToString(",") { """{"text":"Mug in $it","type":"object","confidence":0.9,"tags":[],"source_frame_ids":["$it"]}""" }}],"captions":[${ids.joinToString(",") { """{"frame_id":"$it","caption":"Counter $it"}""" }}]}"""

    private fun photo(id: String) = ExtractPhoto(id, "ts=1", ModelImageInput("image/jpeg", "AAAA"))
    private fun item(id: String, v: CanvasValue = CanvasValue.Photo) = CanvasItem(id, photo(id), v)
    private fun extraction(id: String, conf: Double = 0.9) =
        CanvasValue.Extraction(listOf(Fact("Mug in $id", "object", conf, emptyList(), listOf(id))), listOf(FrameCaption(id, "Counter $id")))

    private fun node(type: String, params: JsonObject = JsonObject(emptyMap()), id: String = "n") = PipelineNode(id, type, "t", params)
    private fun draftPrompt(template: String) = buildJsonObject { putJsonObject("prompt") { put("source", "draft"); put("template", template); put("schema", JsonPrimitive(null as String?)) } }
    private fun extractNode(batch: Int, prompt: JsonObject? = null) = node(NodeTypes.VISION_EXTRACT, buildJsonObject {
        put("batch", batch)
        (prompt ?: buildJsonObject { putJsonObject("prompt") { put("source", "live") } }).forEach { (k, v) -> put(k, v) }
    }, id = "extract")

    private fun ctx(reply: (String) -> String): RunContext {
        val ai = TestAi.graph(app) { p -> prompts += p; reply(p) }.also { TestAi.bindAll(it) }
        val recording = object : LanguageModel {
            override suspend fun complete(role: Role, callClass: CallClass, build: (Endpoint) -> ModelRequest): RoutedReply =
                ai.router.complete(role, callClass) { e -> build(e).also { requests += it } }
            override suspend fun completeFromFrames(callClass: CallClass, build: (Endpoint) -> ModelRequest): RoutedReply =
                ai.router.completeFromFrames(callClass) { e -> build(e).also { requests += it } }
            override suspend fun completeOn(endpointId: String, model: String, callClass: CallClass, build: (Endpoint) -> ModelRequest): RoutedReply =
                ai.router.completeOn(endpointId, model, callClass, build)
        }
        return RunContext("run1", ai.runner, recording, ai.prompts, ai.persona, { r -> if (r in unbound) null else ai.bindings.get(r) })
    }

    private fun exec(type: String) = checkNotNull(Executors.all()[type])

    // ---- vision extract ----

    @Test fun extractBatchesByParamAndAttributesPerPhoto() = runBlocking<Unit> {
        val c = ctx { extractReply(*sentIds(it).toTypedArray()) }
        val r = exec(NodeTypes.VISION_EXTRACT).run(extractNode(2), listOf(item("ex1"), item("ex2"), item("ex3")), c)
        assertEquals(2, prompts.size)
        assertEquals(listOf("ex1", "ex2", "ex3"), r.map { it.frameId })
        r.forEach { res ->
            val v = res.value as CanvasValue.Extraction
            assertEquals(listOf("Mug in ${res.frameId}"), v.facts.map { it.text })
            assertEquals(listOf(res.frameId), v.captions.map { it.frameId })
            assertNull(res.error)
        }
        assertEquals(listOf("run1:extract:0", "run1:extract:0", "run1:extract:1"), r.map { it.meta?.batchId })
        assertTrue((r[0].meta?.promptVersion ?: 0) >= 1) // live prompt: the stored version
        assertEquals(2, r.sumOf { it.calls })
    }

    @Test fun extractDraftOverrideReachesModel() = runBlocking<Unit> {
        val c = ctx { extractReply(*sentIds(it).toTypedArray()) }
        val r = exec(NodeTypes.VISION_EXTRACT).run(extractNode(1, draftPrompt("OVERRIDE {{recent_context}}")), listOf(item("ex1")), c)
        assertTrue(prompts.single().contains("OVERRIDE"))
        assertEquals(0, r.single().meta?.promptVersion)
        assertNull(r.single().error)
    }

    @Test fun extractUpstreamJsonInContextSources() = runBlocking<Unit> {
        val c = ctx { extractReply(*sentIds(it).toTypedArray()) }
        val up = CanvasValue.Json(buildJsonObject { put("activity", "workout") })
        exec(NodeTypes.VISION_EXTRACT).run(extractNode(1, draftPrompt("SRC[{{context_sources}}] {{recent_context}}")), listOf(item("ex1", up)), c)
        val p = prompts.single()
        assertTrue(p.contains("BEGIN_DATA upstream"))
        assertTrue(p.contains("\"activity\":\"workout\""))
        prompts.clear()
        exec(NodeTypes.VISION_EXTRACT).run(extractNode(1, draftPrompt("SRC[{{context_sources}}] {{recent_context}}")), listOf(item("ex1")), c)
        assertTrue(prompts.single().contains("SRC[]"))
    }

    @Test fun extractErrorMarksWholeChunk() = runBlocking<Unit> {
        val c = ctx { p -> if ("ex1" in p) "not json at all" else extractReply(*sentIds(p).toTypedArray()) }
        val r = exec(NodeTypes.VISION_EXTRACT).run(extractNode(2), listOf(item("ex1"), item("ex2"), item("ex3")), c)
        assertEquals(listOf("INVALID_OUTPUT", "INVALID_OUTPUT", null), r.map { it.error })
        assertNull(r[0].value); assertNull(r[1].value)
        assertNotNull(r[2].value)
    }

    // ---- critics ----

    @Test fun localCriticBranches() = runBlocking<Unit> {
        val c = ctx { "" }
        val r = exec(NodeTypes.LOCAL_CRITIC).run(
            node(NodeTypes.LOCAL_CRITIC),
            listOf(item("a", extraction("a", 0.9)), item("b", extraction("b", 0.3)), item("c", CanvasValue.Extraction(emptyList(), emptyList()))), c,
        )
        assertEquals(listOf("trusted", "unsure", "trusted"), r.map { it.branch })
        assertEquals(listOf(true, false, true), r.map { (it.value as CanvasValue.Extraction).trusted })
        assertEquals(0, r.sumOf { it.calls })
        assertTrue(prompts.isEmpty())
    }

    @Test fun criticUnboundFailsWithNote() = runBlocking<Unit> {
        unbound = setOf(Role.CRITIC)
        val c = ctx { "" }
        val r = exec(NodeTypes.CRITIC).run(node(NodeTypes.CRITIC), listOf(item("a", extraction("a"))), c).single()
        assertEquals("fail", r.branch)
        assertEquals("unbound", (r.value as CanvasValue.Extraction).note)
        assertEquals(0, r.calls)
        assertTrue(prompts.isEmpty())
    }

    @Test fun criticSkipsCallOnEmptyFacts() = runBlocking<Unit> {
        val c = ctx { """{"verdict":"fail","reasons":["x"]}""" }
        val empty = CanvasValue.Extraction(emptyList(), emptyList())
        val r = exec(NodeTypes.CRITIC).run(node(NodeTypes.CRITIC), listOf(item("a", empty)), c).single()
        assertEquals("pass", r.branch)
        assertEquals(true, (r.value as CanvasValue.Extraction).trusted)
        assertEquals(0, r.calls)
        assertNull(r.error)
        assertTrue(prompts.isEmpty())
    }

    @Test fun criticEmptyFactsWithUnboundCriticIsTrusted() = runBlocking<Unit> {
        unbound = setOf(Role.CRITIC)
        val empty = CanvasValue.Extraction(emptyList(), emptyList())
        val r = exec(NodeTypes.CRITIC).run(node(NodeTypes.CRITIC), listOf(item("a", empty)), ctx { "" }).single()
        assertEquals("pass", r.branch); assertEquals(true, (r.value as CanvasValue.Extraction).trusted); assertEquals(0, r.calls)
    }

    @Test fun criticOverrideUsed() = runBlocking<Unit> {
        val c = ctx { """{"verdict":"pass","reasons":[]}""" }
        val n = node(NodeTypes.CRITIC, draftPrompt("CRITICX {{recent_context}}"))
        val r = exec(NodeTypes.CRITIC).run(n, listOf(item("a", extraction("a"))), c).single()
        assertTrue(prompts.single().contains("CRITICX"))
        assertEquals("pass", r.branch)
        assertEquals(true, (r.value as CanvasValue.Extraction).trusted)
        assertEquals(1, r.calls)
        val c2 = ctx { """{"verdict":"fail","reasons":["x"]}""" }
        val f = exec(NodeTypes.CRITIC).run(n, listOf(item("a", extraction("a"))), c2).single()
        assertEquals("fail", f.branch)
        assertEquals(false, (f.value as CanvasValue.Extraction).trusted)
        assertNull(f.error)
        val c3 = ctx { "garbage" }
        val e = exec(NodeTypes.CRITIC).run(n, listOf(item("a", extraction("a"))), c3).single()
        assertEquals("fail", e.branch)
        assertEquals("INVALID_OUTPUT", e.error)
    }

    // ---- prompt ----

    private val schema = """{"type":"object","properties":{"activity":{"type":"string"}},"required":["activity"],"additionalProperties":false}"""
    private fun promptNode(images: Boolean = false) = node(NodeTypes.PROMPT, buildJsonObject {
        put("role", "ANSWER"); put("name", "intent"); put("images", images)
        put("template", "Decide. {{recent_context}} {{context_sources}}"); put("schema", schema)
    })

    @Test fun promptValidJson() = runBlocking<Unit> {
        val c = ctx { """{"activity":"workout"}""" }
        val r = exec(NodeTypes.PROMPT).run(promptNode(), listOf(item("ex1")), c).single()
        assertNull(r.error)
        assertEquals("""{"activity":"workout"}""", (r.value as CanvasValue.Json).value.toString())
        assertEquals(1, r.calls)
        assertTrue(prompts.single().contains("frame_id: ex1 - ts=1"))
    }

    @Test fun promptInvalidOutputKeepsValue() = runBlocking<Unit> {
        val c = ctx { """{"other":1}""" }
        val r = exec(NodeTypes.PROMPT).run(promptNode(), listOf(item("ex1")), c).single()
        assertEquals("INVALID_OUTPUT", r.error)
        assertEquals("""{"other":1}""", (r.value as CanvasValue.Json).value.toString())
    }

    @Test fun promptNotJson() = runBlocking<Unit> {
        val c = ctx { "hello there" }
        val r = exec(NodeTypes.PROMPT).run(promptNode(), listOf(item("ex1")), c).single()
        assertEquals("NOT_JSON", r.error)
        assertNull(r.value)
        unbound = setOf(Role.ANSWER)
        val u = exec(NodeTypes.PROMPT).run(promptNode(), listOf(item("ex1")), ctx { "{}" }).single()
        assertEquals("NOT_SET", u.error)
        assertEquals(0, u.calls)
    }

    @Test fun promptImagesFlag() = runBlocking<Unit> {
        val c = ctx { """{"activity":"x"}""" }
        exec(NodeTypes.PROMPT).run(promptNode(images = true), listOf(item("ex1")), c)
        exec(NodeTypes.PROMPT).run(promptNode(images = false), listOf(item("ex1")), c)
        assertEquals(listOf(1, 0), requests.map { it.images.size })
    }

    // ---- switch / preview ----

    @Test fun switchKnownAndOther() = runBlocking<Unit> {
        val n = node(NodeTypes.SWITCH, buildJsonObject { put("field", "activity"); put("cases", JsonArray(listOf(JsonPrimitive("workout"), JsonPrimitive("driving")))) })
        fun j(a: String) = CanvasValue.Json(buildJsonObject { put("activity", a) })
        val r = exec(NodeTypes.SWITCH).run(n, listOf(item("a", j("workout")), item("b", j("sleeping")), item("c", CanvasValue.Json(buildJsonObject { }))), ctx { "" })
        assertEquals(listOf("workout", "other", "other"), r.map { it.branch })
        assertEquals(j("workout"), r[0].value)
        assertEquals(0, r.sumOf { it.calls })
    }

    @Test fun previewTiersAndReview() = runBlocking<Unit> {
        val trusted = (extraction("a") as CanvasValue.Extraction).copy(trusted = true)
        val unsure = (extraction("b") as CanvasValue.Extraction).copy(trusted = null)
        val r = exec(NodeTypes.MEMORY_WRITE).run(node(NodeTypes.MEMORY_WRITE), listOf(item("a", trusted), item("b", unsure)), ctx { "" })
        val a = (r[0].value as CanvasValue.Preview).rows
        assertEquals(listOf(PreviewRow("fact", "Mug in a", "object", "SHORT", "OK"), PreviewRow("caption", "Counter a", "caption", "LONG", "OK")), a)
        val b = (r[1].value as CanvasValue.Preview).rows
        assertEquals(listOf("NEEDS_REVIEW", "OK"), b.map { it.review })
        assertEquals(listOf("SHORT", "LONG"), b.map { it.tier })
    }

    // ---- hard rule ----

    private fun count(t: String) = MemoryGraph.get(app).db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$t`").use { it.moveToFirst(); it.getInt(0) }
    private val liveTables = listOf("memory", "frame_state", "frame_source", "memory_vector")

    @Test fun executorsWriteNothing() = runBlocking<Unit> {
        val before = liveTables.map(::count)
        val c = ctx { p -> if ("frame_id: ex" in p && "Decide." !in p && "BEGIN_DATA facts" !in p) extractReply(*sentIds(p).toTypedArray()) else if ("BEGIN_DATA facts" in p) """{"verdict":"pass","reasons":[]}""" else """{"activity":"workout"}""" }
        val ex = exec(NodeTypes.VISION_EXTRACT).run(extractNode(3), listOf(item("ex1"), item("ex2")), c)
        val items = ex.map { CanvasItem(it.frameId, photo(it.frameId), it.value!!) }
        val lc = exec(NodeTypes.LOCAL_CRITIC).run(node(NodeTypes.LOCAL_CRITIC), items, c)
        val cr = exec(NodeTypes.CRITIC).run(node(NodeTypes.CRITIC), lc.map { CanvasItem(it.frameId, photo(it.frameId), it.value!!) }, c)
        exec(NodeTypes.MEMORY_WRITE).run(node(NodeTypes.MEMORY_WRITE), cr.map { CanvasItem(it.frameId, photo(it.frameId), it.value!!) }, c)
        exec(NodeTypes.PROMPT).run(promptNode(), listOf(item("ex1")), c)
        assertTrue(prompts.isNotEmpty())
        assertEquals(before, liveTables.map(::count))
    }

    @Test fun callEstimates() {
        fun est(type: String, n: PipelineNode, items: Int) = exec(type).let { it.nominalCalls(n, items) to it.maxCalls(n, items) }
        assertEquals(2 to 4 + 5, est(NodeTypes.VISION_EXTRACT, extractNode(3), 5)) // ceil(5/3)=2; 2*2 + 5
        assertEquals(5 to 10, est(NodeTypes.VISION_EXTRACT, extractNode(1), 5))
        assertEquals(1 to 2 + 1, est(NodeTypes.VISION_EXTRACT, extractNode(2), 1))
        assertEquals(0 to 0, est(NodeTypes.LOCAL_CRITIC, node(NodeTypes.LOCAL_CRITIC), 4))
        assertEquals(4 to 8, est(NodeTypes.CRITIC, node(NodeTypes.CRITIC), 4))
        assertEquals(4 to 4, est(NodeTypes.PROMPT, promptNode(), 4))
        assertEquals(0 to 0, est(NodeTypes.SWITCH, node(NodeTypes.SWITCH), 4))
        assertEquals(0 to 0, est(NodeTypes.MEMORY_WRITE, node(NodeTypes.MEMORY_WRITE), 4))
        assertEquals(3, exec(NodeTypes.VISION_EXTRACT).chunkSize(extractNode(3)))
        assertNull(Executors.all()[NodeTypes.INPUT_PHOTOS])
        assertEquals(6, Executors.all().size)
    }
}
