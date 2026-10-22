package com.rokid.context.phone.canvas.api

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.Capability
import com.rokid.context.ai.Endpoint
import com.rokid.context.ai.EndpointKind
import com.rokid.context.ai.InMemoryKeyVault
import com.rokid.context.ai.InMemorySeedState
import com.rokid.context.ai.ProviderBuilder
import com.rokid.context.ai.Role
import com.rokid.context.ai.RoleBinding
import com.rokid.context.ai.canvas.DefaultPipelines
import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.canvas.Pipeline
import com.rokid.context.ai.canvas.PipelineEdge
import com.rokid.context.ai.canvas.PipelineHash
import com.rokid.context.ai.canvas.PipelineJson
import com.rokid.context.ai.canvas.PipelineNode
import com.rokid.context.ai.canvas.plan.PlanCompiler
import com.rokid.context.ai.canvas.plan.asyncProjection
import com.rokid.context.canvas.CanvasPublisher
import com.rokid.context.canvas.plan.CapturePlanPublisher
import com.rokid.context.canvas.plan.DaoGroupStore
import com.rokid.context.canvas.plan.DaoM1EvidenceStore
import com.rokid.context.canvas.plan.GlassesState
import com.rokid.context.canvas.plan.MetaPlanSource
import com.rokid.context.canvas.plan.PlanActivation
import com.rokid.context.canvas.plan.PlanEvidenceGates
import com.rokid.context.canvas.plan.PlanPublishGroups
import com.rokid.context.canvas.plan.PlanTransport
import com.rokid.context.canvas.plan.ReplayRequest
import com.rokid.context.canvas.plan.ReplayService
import com.rokid.context.canvas.plan.ReplayTrace
import com.rokid.context.canvas.plan.SendOutcome
import com.rokid.context.capture.replay.ReplayCandidate
import com.rokid.context.capture.replay.ReplayFigures
import com.rokid.context.capture.replay.ReplayReport
import com.rokid.context.capture.replay.StateFigures
import com.rokid.context.core.model.CapturePlanAck
import com.rokid.context.core.model.PlanAckState
import com.rokid.context.core.model.PlanMode
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.CanvasRunEntity
import com.rokid.context.memory.db.CanvasRunItemEntity
import com.rokid.context.memory.db.CanvasTraceEntity
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.DeletionEpoch
import com.rokid.context.model.ModelProvider
import com.rokid.context.orchestration.AnswerService
import com.rokid.context.memory.retrieval.RetrievalMode
import com.rokid.context.memory.retrieval.RetrievalSettings
import com.rokid.context.phone.canvas.CanvasExtras
import com.rokid.context.phone.canvas.server.CanvasAuth
import com.rokid.context.phone.canvas.server.HttpRequest
import com.rokid.context.phone.canvas.server.HttpResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Slice 3a Task 28 (S72, S26): a full three-domain graph (glasses RT, phone RT and ASYNC in one pipeline) goes through the REAL [CanvasBackendImpl], its REAL [CanvasPublisher] and the
 * real tables, over the canvas API: the capture half publishes as a `capture_plan` group, the ASYNC projection as an interpreter of the same group once the glasses say EFFECTIVE, and
 * Undo latest undoes the group as one unit. Only the glasses link (a transport that records) and the model are fakes.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class CanvasPlanPublishApiTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dev = MutableStateFlow(true)
    private lateinit var db: com.rokid.context.memory.db.ContextDatabase
    private lateinit var backend: CanvasBackendImpl
    private lateinit var api: CanvasApi
    private lateinit var plans: CapturePlanPublisher
    private lateinit var groups: PlanPublishGroups
    private lateinit var replay: ReplayService
    private val sent = ArrayList<Sent>()
    private val phoneRt = ArrayList<String?>()
    private var glasses = GlassesState.LINKED_CAPABLE
    private var now = 1_000_000L
    private val install = "inst"
    private val epoch = DeletionEpoch("install", 1, 0)
    private fun fence() = DaoFence(1, epoch, { 1 }, { epoch })
    private val traces = HashMap<String, ReplayTrace>()
    private var traceSeq = 0

    class Sent(val gen: Long, val mode: PlanMode, val planJson: String?, val hash: String?, val publishId: String, val trialMaxMs: Long?)

    @Before fun setUp() {
        db = DatabaseFactory.inMemory(app).also { MemoryGraph.resetForTests(it) }
        val ai = AiGraph(MemoryGraph.get(app), InMemoryKeyVault(), InMemorySeedState(), ProviderBuilder { _, _, _ ->
            object : ModelProvider { override suspend fun generate(prompt: String) = """{"facts":[],"captions":[]}""" }
        }).also { AiGraph.resetForTests(it) }
        runBlocking {
            ai.endpoints.save(Endpoint("az", "Azure", EndpointKind.AZURE_OPENAI, "https://az.example", "2024-10-21", listOf("gpt"), setOf(Capability.CHAT, Capability.VISION), 1), apiKey = "k").getOrThrow()
            Role.entries.filter { it != Role.EMBED }.forEach { ai.bindings.set(RoleBinding(it, "az", "gpt")).getOrThrow() }
        }
        var gen = 0L
        val transport = object : PlanTransport {
            override suspend fun send(mode: PlanMode, planJson: String?, configHash: String?, publishId: String, trialMaxMs: Long?): SendOutcome {
                gen++; sent += Sent(gen, mode, planJson, configHash, publishId, trialMaxMs); return SendOutcome(gen, true)
            }
        }
        backend = CanvasBackendImpl.create(
            app, ai, CanvasAuth({ System.currentTimeMillis() }), { false }, scope,
            answers = { AnswerService(MemoryGraph.get(app), ai.runner, ai.retriever, { RetrievalSettings(mode = RetrievalMode.LEXICAL, topK = 8) }) },
            plans = { plans },
        )
        val dao = db.canvasSlice3Dao(); val canvas = db.canvasDao()
        val store = DaoGroupStore(dao)
        val activation = PlanActivation(canvas, backend.publisher, { phoneRt += it }, { now++ })
        groups = PlanPublishGroups(store, transport, { install }, { now++ }, activation::activate, MetaPlanSource(dao), activation::deactivate)
        plans = CapturePlanPublisher(canvas, groups, store, PlanEvidenceGates(dao, DaoM1EvidenceStore(dao), { glasses }, canvas), backend.publisher, { now++ }, newGroupId = { "g${++groupSeq}" })
        replay = ReplayService(dao, { id -> traces[id] }, ::fence, scope, dev, null, { now++ }, { "rp_${now++}" })
        val extras = CanvasExtras.build(dao, replay, dev, startup = {}, sender = { null }, collect = { null }, plans = { plans }, m1 = { DaoM1EvidenceStore(dao) })
        api = CanvasApi(backend, extras = extras.extras)
        runBlocking { backend.start() }
    }
    private var groupSeq = 0

    @After fun tearDown() { scope.cancel(); AiGraph.resetForTests(null); db.close(); MemoryGraph.resetForTests(null) }

    // ---- fixtures ----

    private val none = JsonObject(emptyMap())
    private fun node(id: String, type: String, params: JsonObject = none) = PipelineNode(id, type, id, params)
    private fun sig(id: String, type: String, domain: String) = node(id, type, buildJsonObject { put("name", "ctx"); put("domain", domain) })

    /** Glasses RT chain, phone RT (the hub and its signal out) and slice 2's live extraction, joined by the photos binding. */
    private fun fullGraph(name: String, settleMs: Int? = null): Pipeline {
        val async = DefaultPipelines.liveExtract()
        return Pipeline(
            2, "p", name,
            listOf(
                node("head", NodeTypes.IMU_HEAD_MOTION), node("thresh", NodeTypes.GATE_THRESHOLD, buildJsonObject { put("feature", "gyroPeak"); put("op", ">"); put("value", 0.6) }),
                node("settle", NodeTypes.IMU_SETTLE, if (settleMs == null) none else buildJsonObject { put("settleMs", settleMs) }), node("novel", NodeTypes.GATE_NOVELTY),
                node("rate", NodeTypes.POLICY_CAPTURE_RATE), node("cap", NodeTypes.GATE_RATE_CAP), node("cam", NodeTypes.CAPTURE_IMAGE),
                sig("cin", NodeTypes.SIGNAL_IN, "RT_GLASSES"), node("hub", NodeTypes.HUB_CONTEXT), sig("cout", NodeTypes.SIGNAL_OUT, "RT_PHONE"),
            ) + async.nodes,
            listOf(
                PipelineEdge("head", "thresh"), PipelineEdge("thresh", "settle"), PipelineEdge("settle", "novel"), PipelineEdge("novel", "rate"), PipelineEdge("cin", "rate"),
                PipelineEdge("rate", "cap"), PipelineEdge("cap", "cam"), PipelineEdge("hub", "cout"),
            ) + async.edges + PipelineEdge("cam", "photos"),
        )
    }

    private fun call(method: String, path: String, body: JsonObject? = null): HttpResponse = api.handle(HttpRequest(method, path, emptyMap(), emptyMap(), (body ?: buildJsonObject { }).toString().toByteArray()))
    private fun ok(r: HttpResponse): JsonObject { assertEquals(String(r.body), 200, r.status); return Json.parseToJsonElement(String(r.body)).jsonObject }
    private fun json(r: HttpResponse) = Json.parseToJsonElement(String(r.body)).jsonObject
    private fun problems(r: HttpResponse) = json(r)["problems"]!!.jsonArray.map { it.jsonPrimitive.content }

    /** Saves the graph through the API, freezes it, and records a successful run of its ASYNC projection (the interpreter publish's "tested" gate). Returns id and version. */
    private fun frozen(p: Pipeline): Pair<String, Int> {
        val id = ok(call("POST", "/api/pipelines", buildJsonObject { put("from", "live-extract"); put("name", p.name) }))["id"]!!.jsonPrimitive.content
        val rev = ok(call("GET", "/api/pipelines/$id"))["draftRev"]!!.jsonPrimitive.int
        val saved = ok(call("PUT", "/api/pipelines/$id", buildJsonObject { put("json", PipelineJson.encode(p.copy(id = id))); put("draftRev", rev) }))
        assertEquals(emptyList<Any>(), saved["problems"]!!.jsonArray.toList())
        val version = ok(call("POST", "/api/pipelines/$id/freeze", buildJsonObject { put("note", "n") }))["version"]!!.jsonPrimitive.int
        runBlocking {
            val canvas = db.canvasDao()
            val frozenGraph = PipelineJson.decode(canvas.version(id, version)!!.json).getOrThrow()
            val runId = "run${++traceSeq}"
            canvas.insertRun(CanvasRunEntity(runId, "x", 1, PipelineHash.of(frozenGraph.asyncProjection()), "{}", "DONE", 1, 1, 1, 0, 1, 2, "[]"))
            canvas.upsertItem(CanvasRunItemEntity(runId, "f1", "extract", "OK", null, null, null, 1, null, null, null, null, null))
        }
        return id to version
    }

    /** A COMPLETE trace and a finished candidate replay of [hash]: 40 minutes over REST and WALKING. */
    private fun addReplay(hash: String) = runBlocking {
        val dao = db.canvasSlice3Dao()
        val traceId = "trace-${++traceSeq}"
        dao.acceptTraceFenced(CanvasTraceEntity(traceId, "IMU", "COMPLETE", "{}", 1, false, 0, null, epoch.installId, epoch.counter, true, 10_000, 20_000, 1), fence())
        fun fig(h: String, pct: Double) = ReplayFigures(h, 30, 1.0, 20.0, 0.5, 30.0, 1.0, 0, pct, 3.0)
        traces[traceId] = object : ReplayTrace("0", 0, 10_000, 20_000) {
            override fun replay(candidate: ReplayCandidate?, candidateFusion: Map<String, Double>?, tick: () -> Unit) =
                ReplayReport("COUNTERFACTUAL", 40.0, fig("0", 0.49), candidate?.let { fig(it.configHash, 0.45) }, 0.0, 1, 0, 0, emptyMap(), false, emptyList(),
                    mapOf("REST" to StateFigures(25.0, 3.0), "WALKING" to StateFigures(15.0, 6.0)))
        }
        val id = replay.start(ReplayRequest(listOf(traceId), ReplayCandidate(hash)))
        withTimeout(10_000) { while (dao.replay(id)!!.jobState == "RUNNING") delay(5) }
        id
    }

    private fun effective(gen: Long = sent.last().gen) {
        val s = sent.last { it.gen == gen }
        runBlocking { groups.onAck(CapturePlanAck(gen = gen, publishId = s.publishId, mode = s.mode, effectiveHash = s.hash, state = PlanAckState.EFFECTIVE, phoneInstall = install)) }
    }

    private fun publishBody(id: String, v: Int, mode: String) = buildJsonObject { put("pipelineId", id); put("version", v); put("mode", mode) }

    // ---- the tests ----

    /** S72 and S26: one full graph, the real backend and publisher, one group, one undo. */
    @Test fun fullThreeDomainGraphPublishesThroughRealApi() {
        val graph = fullGraph("Quiet walk")
        val (id, v) = frozen(graph)
        val hash = PlanCompiler.compile(graph).plan!!.configHash
        addReplay(hash)

        // the gates, read only
        val check = ok(call("POST", "/api/plans/check", publishBody(id, v, "SHADOW")))
        assertTrue(check.toString(), check["ok"]!!.jsonPrimitive.content.toBoolean()); assertEquals(hash, check["configHash"]!!.jsonPrimitive.content)
        assertTrue(sent.isEmpty())
        // Task 37: the evidence behind gate 3 and the M1 flag ride along for the publish UI
        val ev = check["evidence"]!!.jsonObject
        assertEquals(40.0, ev["minutes"]!!.jsonPrimitive.content.toDouble(), 0.001); assertEquals(2, ev["statesCovered"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, ev["windowsCompared"]!!.jsonPrimitive.content.toInt()); assertEquals(0, ev["inexactWindows"]!!.jsonPrimitive.content.toInt())
        assertEquals("false", ev["fusionWarning"]!!.jsonPrimitive.content); assertEquals(0.45, ev["candidatePctPerMin"]!!.jsonPrimitive.content.toDouble(), 0.001)
        assertEquals("false", check["m1Passed"]!!.jsonPrimitive.content)

        // 1. the capture half publishes as a capture_plan and is SENT; the ASYNC half waits for the glasses (S25)
        val pub = ok(call("POST", "/api/plans/publish", publishBody(id, v, "SHADOW")))
        assertEquals("SHADOW", pub["mode"]!!.jsonPrimitive.content); assertEquals(hash, pub["configHash"]!!.jsonPrimitive.content)
        val groupId = pub["groupId"]!!.jsonPrimitive.content
        val stack1 = ok(call("GET", "/api/publish"))
        assertEquals(listOf("capture_plan"), stack1["stack"]!!.jsonArray.map { it.jsonObject["kind"]!!.jsonPrimitive.content })
        assertEquals("null", stack1["interpreter"].toString())
        assertEquals(groupId, stack1["plan"]!!.jsonObject["groupId"]!!.jsonPrimitive.content)
        assertTrue(phoneRt.isEmpty())

        // 2. EFFECTIVE: the phone RT applies the plan and the ASYNC projection publishes as an interpreter of the same group
        effective()
        assertEquals(listOf(sent.single().planJson), phoneRt)
        val stack2 = ok(call("GET", "/api/publish"))
        assertEquals(listOf("interpreter", "capture_plan"), stack2["stack"]!!.jsonArray.map { it.jsonObject["kind"]!!.jsonPrimitive.content })
        val interp = stack2["interpreter"]!!.jsonObject
        assertEquals("SHADOW", interp["mode"]!!.jsonPrimitive.content); assertEquals(groupId, interp["groupId"]!!.jsonPrimitive.content)
        val canvas = db.canvasDao()
        val rows = runBlocking { canvas.publishesOfGroup(groupId) }
        val planRow = rows.single { it.kind == CanvasPublisher.KIND_CAPTURE_PLAN }; val asyncRow = rows.single { it.kind == CanvasPublisher.KIND_INTERPRETER }
        assertEquals("capture_plan", planRow.component); assertEquals(hash, planRow.configHash); assertEquals(sent.single().planJson, planRow.planJson)
        val frozenGraph = PipelineJson.decode(runBlocking { canvas.version(id, v)!!.json }).getOrThrow()
        assertEquals("the interpreter's hash is slice 2's hash of the ASYNC projection", PipelineHash.of(frozenGraph.asyncProjection()), asyncRow.hash)
        assertNull(planRow.rolledBackAt); assertNull(asyncRow.rolledBackAt)
        assertTrue("the send time feeds the trial countdown", status()["active"]!!.jsonObject["sentAtMs"]!!.jsonPrimitive.content.toLong() > 0)
        assertEquals(true, status()["active"]!!.jsonObject["settingsRow"]!!.jsonPrimitive.content.startsWith("Capture plan: Quiet walk v$v (shadow)"))

        // 3. a stale click undoes nothing; Undo latest by the newest publish's id undoes the whole group
        assertEquals(409, call("POST", "/api/publish/rollback", buildJsonObject { put("expectedId", planRow.id) }).status)
        assertEquals(2, ok(call("GET", "/api/publish"))["stack"]!!.jsonArray.size)
        ok(call("POST", "/api/publish/rollback", buildJsonObject { put("expectedId", asyncRow.id) }))
        assertTrue(ok(call("GET", "/api/publish"))["stack"]!!.jsonArray.isEmpty())
        val history = ok(call("GET", "/api/publish/history").let { HttpResponse.json(200, buildJsonObject { put("rows", Json.parseToJsonElement(String(it.body))) }) })["rows"]!!.jsonArray
        assertEquals(setOf("undo"), history.filter { it.jsonObject["kind"]!!.jsonPrimitive.content == "capture_plan" }.map { it.jsonObject["rollbackReason"]!!.jsonPrimitive.content }.toSet())
        assertEquals(PlanMode.SETTINGS, sent.last().mode); assertNull(sent.last().planJson)
        assertNull("the capture half is back on settings", status()["active"].takeIf { it.toString() != "null" })
    }

    private fun status() = ok(call("GET", "/api/plans/status"))

    @Test fun trialAndLiveAreRefusedUntilM1IsRecordedThroughTheRoute() {
        val (id, v) = frozen(fullGraph("T")); addReplay(PlanCompiler.compile(fullGraph("T")).plan!!.configHash)
        val blocked = call("POST", "/api/plans/publish", publishBody(id, v, "TRIAL"))
        assertEquals(409, blocked.status); assertEquals("publish_blocked", json(blocked)["error"]!!.jsonPrimitive.content)
        assertTrue(problems(blocked).single().startsWith("Gate 4: a TRIAL or LIVE plan is blocked until milestone M1"))
        assertTrue(sent.isEmpty())
        dev.value = false
        assertEquals("the record needs Developer mode", 403, call("POST", "/api/plans/m1-evidence", buildJsonObject { put("note", "run") }).status)
        dev.value = true
        assertEquals(400, call("POST", "/api/plans/m1-evidence", buildJsonObject { put("note", "x".repeat(81)) }).status)
        ok(call("POST", "/api/plans/m1-evidence", buildJsonObject { put("note", "worn run 1") }))
        assertEquals("true", ok(call("POST", "/api/plans/check", publishBody(id, v, "TRIAL")))["m1Passed"]!!.jsonPrimitive.content)
        val trial = ok(call("POST", "/api/plans/publish", publishBody(id, v, "TRIAL")))
        assertEquals("TRIAL", trial["mode"]!!.jsonPrimitive.content)
        assertEquals(3_600_000L, sent.single().trialMaxMs)
        assertEquals(409, call("POST", "/api/plans/publish", publishBody(id, v, "LIVE")).status)
    }

    @Test fun badRequestsAndUnavailableAnswerFixedStatuses() {
        assertEquals(400, call("POST", "/api/plans/publish", buildJsonObject { put("pipelineId", "x"); put("version", 1); put("mode", "SETTINGS") }).status)
        assertEquals(400, call("POST", "/api/plans/publish", buildJsonObject { put("pipelineId", "x y"); put("version", 1); put("mode", "SHADOW") }).status)
        assertEquals(404, call("POST", "/api/plans/check", publishBody("missing", 1, "SHADOW")).status)
        assertEquals(409, call("POST", "/api/plans/publish", publishBody("missing", 1, "SHADOW")).status)
        val bare = CanvasApi(backend, extras = CanvasExtras.build(db.canvasSlice3Dao(), replay, dev, startup = {}, sender = { null }, collect = { null }).extras)
        fun bareCall(m: String, p: String) = bare.handle(HttpRequest(m, p, emptyMap(), emptyMap(), "{}".toByteArray())).status
        assertEquals(503, bareCall("POST", "/api/plans/publish")); assertEquals(503, bareCall("GET", "/api/plans/status")); assertEquals(503, bareCall("POST", "/api/plans/use-settings"))
    }

    @Test fun useSettingsAndRestorePreviousThroughTheRoutes() {
        val g = fullGraph("R"); val (id, v) = frozen(g); addReplay(PlanCompiler.compile(g).plan!!.configHash)
        ok(call("POST", "/api/plans/publish", publishBody(id, v, "SHADOW"))); effective()
        val us = ok(call("POST", "/api/plans/use-settings"))
        assertEquals("SETTINGS", us["mode"]!!.jsonPrimitive.content); assertEquals(PlanMode.SETTINGS, sent.last().mode)
        assertEquals("null", status()["active"].toString())
        val asyncStillActive = runBlocking { db.canvasDao().activePublishes() }.any { it.kind == CanvasPublisher.KIND_INTERPRETER }
        assertTrue("ASYNC is untouched by Use settings", asyncStillActive)
        val back = ok(call("POST", "/api/plans/restore-previous"))
        assertEquals("SHADOW", back["mode"]!!.jsonPrimitive.content)
        assertEquals(PlanMode.SHADOW, sent.last().mode)
        glasses = GlassesState.NOT_LINKED
        ok(call("POST", "/api/plans/use-settings"))
        val refused = call("POST", "/api/plans/restore-previous")
        assertEquals(409, refused.status); assertTrue(problems(refused).single().startsWith("Gate 5"))
    }

    @Test fun anInterpreterPublishOfAFullGraphIsStillSliceTwosPublishRoute() {
        val g = fullGraph("Plain"); val (id, v) = frozen(g)
        val shadow = ok(call("POST", "/api/pipelines/$id/versions/$v/publish", buildJsonObject { put("mode", "shadow") }))
        assertEquals("SHADOW", shadow["mode"]!!.jsonPrimitive.content)
        val row = runBlocking { db.canvasDao().activePublishes() }.single()
        assertNull("not part of a group", row.groupId)
        ok(call("POST", "/api/publish/rollback", buildJsonObject { put("expectedId", row.id) }))
        assertTrue(sent.isEmpty())
    }
}
