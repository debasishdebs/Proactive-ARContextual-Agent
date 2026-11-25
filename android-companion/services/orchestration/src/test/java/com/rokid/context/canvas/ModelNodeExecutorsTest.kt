package com.rokid.context.canvas

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.Endpoint
import com.rokid.context.ai.Role
import com.rokid.context.ai.RoutedReply
import com.rokid.context.ai.canvas.ModelNodeTypes
import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.canvas.Pipeline
import com.rokid.context.ai.canvas.PipelineNode
import com.rokid.context.core.net.Backend
import com.rokid.context.core.net.ModelPrivacy
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.model.CallClass
import com.rokid.context.model.LanguageModel
import com.rokid.context.model.ModelImageInput
import com.rokid.context.model.ModelRequest
import com.rokid.context.orchestration.ExtractPhoto
import com.rokid.context.orchestration.TestAi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ModelNodeExecutorsTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val prompts = mutableListOf<String>()
    private val requests = mutableListOf<ModelRequest>()
    private var privacy = ModelPrivacy.AUTO
    private var bound: Backend? = Backend.ONLINE

    @Before fun setUp() { MemoryGraph.resetForTests(DatabaseFactory.inMemory(app)) }
    @After fun tearDown() { AiGraph.resetForTests(null); MemoryGraph.get(app).db.close(); MemoryGraph.resetForTests(null); NodeTypes.modelNodesEnabled = { false } }

    private fun ctx(reply: (String) -> String): RunContext {
        val ai = TestAi.graph(app) { p -> prompts += p; reply(p) }.also { TestAi.bindAll(it) }
        val recording = object : LanguageModel {
            override suspend fun complete(role: Role, callClass: CallClass, build: (Endpoint) -> ModelRequest): RoutedReply =
                ai.router.complete(role, callClass) { e -> build(e).also { requests += it } }
            override suspend fun completeFromFrames(callClass: CallClass, build: (Endpoint) -> ModelRequest): RoutedReply = error("unused")
            override suspend fun completeOn(endpointId: String, model: String, callClass: CallClass, build: (Endpoint) -> ModelRequest): RoutedReply = error("unused")
        }
        return RunContext("run1", ai.runner, recording, ai.prompts, ai.persona, { r -> ai.bindings.get(r) }, callClass = CallClass.EXPERIMENT, liveness = com.rokid.context.orchestration.AllLive,
            modelPrivacy = { privacy }, boundBackend = { bound })
    }

    private fun item(id: String, v: CanvasValue = CanvasValue.Json(buildJsonObject { put("text", "a kitchen") })) = CanvasItem(id, ExtractPhoto(id, "ts=1", ModelImageInput("image/jpeg", "AAAA")), v)
    private fun cls(extra: JsonObject.() -> Map<String, kotlinx.serialization.json.JsonElement> = { emptyMap() }, type: String = ModelNodeTypes.CLASSIFY): PipelineNode {
        val base = buildJsonObject { put("labels", "indoor, outdoor"); put("threshold", 0.6) }
        return PipelineNode("c", type, "t", JsonObject(base + base.extra()))
    }
    private fun exec(type: String) = checkNotNull(ModelNodeExecutors.all()[type])

    @Test fun theSeededLevelSendsSeedAndTemperatureZeroAndALiveRunSendsNeither() = runBlocking<Unit> {
        val c = ctx { """{"label":"indoor","confidence":0.9}""" }
        exec(ModelNodeTypes.CLASSIFY).run(cls(), listOf(item("f1")), c)
        assertEquals(null, requests.last().seed); assertEquals(null, requests.last().temperature)
        exec(ModelNodeTypes.CLASSIFY).run(cls(), listOf(item("f1")), c.withSeed(11))
        assertEquals(11, requests.last().seed); assertEquals(0.0, requests.last().temperature)
        val pn = PipelineNode("p", NodeTypes.PROMPT, "t", buildJsonObject { put("role", "ANSWER"); put("name", "intent"); put("images", false); put("template", "Decide. {{recent_context}} {{context_sources}}"); put("schema", """{"type":"object"}""") })
        checkNotNull(com.rokid.context.canvas.Executors.all()[NodeTypes.PROMPT]).run(pn, listOf(item("f1")), c.withSeed(12))
        assertEquals("the generic Prompt node is seeded too", 12, requests.last().seed)
    }

    @Test fun classifyReturnsLabelBranchAndCountsOneCall() = runBlocking<Unit> {
        val c = ctx { """{"label":"indoor","confidence":0.9}""" }
        val r = exec(ModelNodeTypes.CLASSIFY).run(cls(), listOf(item("f1")), c).single()
        assertEquals("indoor", r.branch); assertEquals(1, r.calls); assertNull(r.error)
        assertEquals(CanvasValue.Json(buildJsonObject { put("label", "indoor"); put("confidence", 0.9) }), r.value)
        assertTrue(prompts.single().contains("never name, identify"))
        assertTrue(requests.single().images.isEmpty())
    }

    @Test fun belowThresholdAndUnknownLabelGoToNone() = runBlocking<Unit> {
        assertEquals("none", exec(ModelNodeTypes.CLASSIFY).run(cls(), listOf(item("f1")), ctx { """{"label":"indoor","confidence":0.2}""" }).single().branch)
        assertEquals("none", exec(ModelNodeTypes.CLASSIFY).run(cls(), listOf(item("f1")), ctx { """{"label":"Alice","confidence":0.99}""" }).single().branch)
        assertEquals("NOT_JSON", exec(ModelNodeTypes.CLASSIFY).run(cls(), listOf(item("f1")), ctx { "garbage" }).single().error)
    }

    @Test fun routeCarriesTheItem() = runBlocking<Unit> {
        val r = exec(ModelNodeTypes.ROUTE).run(cls(type = ModelNodeTypes.ROUTE), listOf(item("f1")), ctx { """{"label":"outdoor","confidence":0.8}""" }).single()
        val o = (r.value as CanvasValue.Json).value as JsonObject
        assertEquals("outdoor", r.branch); assertTrue("item" in o)
    }

    @Test fun offlineOnlyRefusesRemoteWithNoRequestBuilt() = runBlocking<Unit> {
        privacy = ModelPrivacy.OFFLINE_ONLY
        val r = exec(ModelNodeTypes.CLASSIFY).run(cls(), listOf(item("f1"), item("f2")), ctx { error("must not be called") })
        assertEquals(listOf("offline_only", "offline_only"), r.map { it.error }); assertEquals(0, r.sumOf { it.calls })
        assertTrue(requests.isEmpty() && prompts.isEmpty())
        // a pinned offline backend with only a remote endpoint bound
        privacy = ModelPrivacy.AUTO
        val r2 = exec(ModelNodeTypes.CLASSIFY).run(cls({ mapOf("backend" to JsonPrimitive("offline")) }), listOf(item("f1")), ctx { error("no") })
        assertEquals("offline_only_no_local_model", r2.single().error); assertTrue(requests.isEmpty())
    }

    @Test fun skipSendsItemToNoneWithoutACall() = runBlocking<Unit> {
        privacy = ModelPrivacy.OFFLINE_ONLY
        val r = exec(ModelNodeTypes.CLASSIFY).run(cls({ mapOf("onUnavailable" to JsonPrimitive("skip")) }), listOf(item("f1")), ctx { error("no") }).single()
        assertEquals("none", r.branch); assertNull(r.error); assertEquals(0, r.calls); assertTrue(requests.isEmpty())
    }

    @Test fun unreadableBindingFailsClosedAndDefaultContextIsOfflineOnly() = runBlocking<Unit> {
        bound = null
        assertEquals("not_bound", exec(ModelNodeTypes.CLASSIFY).run(cls(), listOf(item("f1")), ctx { error("no") }).single().error)
    }

    @Test fun imagesOnlyWhenAskedAndOnlyThroughTheRouter() = runBlocking<Unit> {
        val c = ctx { """{"label":"indoor","confidence":0.9}""" }
        exec(ModelNodeTypes.CLASSIFY).run(cls({ mapOf("images" to JsonPrimitive(true)) }), listOf(item("f1", CanvasValue.Photo)), c)
        assertEquals(1, requests.single().images.size); assertTrue(requests.single().requireImages)
    }

    @Test fun badLabelsAreRefusedBeforeAnyCall() = runBlocking<Unit> {
        val n = PipelineNode("c", ModelNodeTypes.CLASSIFY, "t", buildJsonObject { put("labels", "only") })
        assertEquals("BAD_PARAMS", exec(ModelNodeTypes.CLASSIFY).run(n, listOf(item("f1")), ctx { error("no") }).single().error)
    }

    @Test fun ruleTableBranchesWithoutAnyModel() = runBlocking<Unit> {
        val n = PipelineNode("r", ModelNodeTypes.RULE_TABLE, "t", buildJsonObject { put("rows", """[{"field":"confidence","op":">=","value":0.8,"branch":"sure"}]""") })
        val hi = item("f1", CanvasValue.Json(buildJsonObject { put("confidence", 0.9) })); val lo = item("f2", CanvasValue.Json(buildJsonObject { put("confidence", 0.1) }))
        val r = exec(ModelNodeTypes.RULE_TABLE).run(n, listOf(hi, lo, item("f3", CanvasValue.Photo)), ctx { error("no") })
        assertEquals(listOf("sure", "other", null), r.map { it.branch }); assertEquals("BAD_INPUT", r[2].error)
        assertEquals(0, exec(ModelNodeTypes.RULE_TABLE).nominalCalls(n, 50))
    }

    @Test fun preflightRefusesAtEstimateUnderOfflineOnly() = runBlocking<Unit> {
        val p = Pipeline(2, "p", "p", listOf(PipelineNode("c", ModelNodeTypes.CLASSIFY, "t", buildJsonObject { put("labels", "a,b") })), emptyList())
        assertEquals(listOf("offline_only"), ModelNodePreflight.reasons(p, { ModelPrivacy.OFFLINE_ONLY }, { Backend.ONLINE }))
        assertTrue(ModelNodePreflight.reasons(p, { ModelPrivacy.OFFLINE_ONLY }, { Backend.OFFLINE }).isEmpty())
        assertEquals(listOf("not_bound"), ModelNodePreflight.reasons(p, { ModelPrivacy.AUTO }, { null }))
        assertFalse(ModelNodePreflight.reasons(p, { error("x") }, { Backend.ONLINE }).isEmpty())
    }

    // ---- rule sets (no ExprProgram change) ----

    private fun json(vararg kv: Pair<String, Any>) = CanvasValue.Json(buildJsonObject { kv.forEach { (k, v) -> if (v is Number) put(k, v) else put(k, v.toString()) } })
    private fun cond(type: String, rows: String) = PipelineNode("r", type, "r", buildJsonObject { put("conditions", rows) })
    private fun runRule(type: String, rows: String, v: CanvasValue) = runBlocking { exec(type).run(cond(type, rows), listOf(item("f1", v)), ctx { "" }).single() }
    private val two = """[{"field":"label","op":"==","value":"indoor"},{"field":"confidence","op":">=","value":0.7}]"""

    @Test fun allAnyNotCombineTheConditionsOverTheOneItem() {
        val both = json("label" to "indoor", "confidence" to 0.9); val one = json("label" to "indoor", "confidence" to 0.3); val none = json("label" to "outdoor", "confidence" to 0.1)
        assertEquals(listOf("pass", "fail", "fail"), listOf(both, one, none).map { runRule(ModelNodeTypes.RULE_ALL, two, it).branch })
        assertEquals(listOf("pass", "pass", "fail"), listOf(both, one, none).map { runRule(ModelNodeTypes.RULE_ANY, two, it).branch })
        assertEquals(listOf("fail", "fail", "pass"), listOf(both, one, none).map { runRule(ModelNodeTypes.RULE_NOT, two, it).branch })
        assertEquals("the item passes on unchanged", both, runRule(ModelNodeTypes.RULE_ALL, two, both).value)
        assertEquals(0, runRule(ModelNodeTypes.RULE_ALL, two, both).calls)
    }

    @Test fun ruleSetsFailClosedOnBadParamsAndNonJsonInput() {
        assertEquals("BAD_PARAMS", runRule(ModelNodeTypes.RULE_ANY, "nope", json("a" to 1)).error)
        assertEquals("BAD_INPUT", runRule(ModelNodeTypes.RULE_ANY, two, CanvasValue.Photo).error)
        assertEquals(0, exec(ModelNodeTypes.RULE_ALL).nominalCalls(cond(ModelNodeTypes.RULE_ALL, two), 50) + exec(ModelNodeTypes.RULE_DEBOUNCE).maxCalls(cond(ModelNodeTypes.RULE_DEBOUNCE, ""), 50))
    }

    private fun timed(type: String, params: JsonObject, vararg ids: String) = runBlocking {
        exec(type).run(PipelineNode("t", type, "t", params), ids.map { item(it) }, ctx { "" }).associate { it.frameId to (it.branch ?: it.error) }
    }
    private fun fid(sec: Int) = "${1_793_891_000_000L + sec * 1000L}_rokid_x.v2"

    @Test fun debounceKeepsTheFirstOfABurstWhateverTheListedOrder() {
        val q = buildJsonObject { put("quietMs", 10_000) }
        val r = timed(ModelNodeTypes.RULE_DEBOUNCE, q, fid(30), fid(0), fid(5), fid(12), fid(35))
        assertEquals(mapOf(fid(0) to "pass", fid(5) to "dropped", fid(12) to "dropped", fid(30) to "pass", fid(35) to "dropped"), r)
        assertEquals("a gap of exactly the quiet time passes", "pass", timed(ModelNodeTypes.RULE_DEBOUNCE, q, fid(0), fid(10))[fid(10)])
    }

    @Test fun rateAllowsAtMostNPerWindowAndStateIsNotSharedBetweenCalls() {
        val p = buildJsonObject { put("maxItems", 2); put("perMs", 10_000) }
        val ids = arrayOf(fid(0), fid(2), fid(4), fid(11), fid(12), fid(13))
        val first = timed(ModelNodeTypes.RULE_RATE, p, *ids)
        assertEquals(listOf("pass", "pass", "dropped", "pass", "pass", "dropped"), ids.map { first[it] })
        assertEquals("a second call starts empty", first, timed(ModelNodeTypes.RULE_RATE, p, *ids))
    }

    @Test fun timeRulesFailClosedWithoutAFrameTime() {
        val r = timed(ModelNodeTypes.RULE_DEBOUNCE, JsonObject(emptyMap()), "f1", fid(0))
        assertEquals("NO_TIME", r["f1"]); assertEquals("pass", r[fid(0)])
        assertEquals(null, RuleTimeExecutor.frameTimeMs("f1")); assertEquals(1_793_891_000_000L, RuleTimeExecutor.frameTimeMs(fid(0)))
        assertEquals(Int.MAX_VALUE, exec(ModelNodeTypes.RULE_RATE).chunkSize(PipelineNode("t", ModelNodeTypes.RULE_RATE, "t", JsonObject(emptyMap()))))
    }
}
