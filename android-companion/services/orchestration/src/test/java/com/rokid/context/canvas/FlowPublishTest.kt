package com.rokid.context.canvas

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.canvas.DefaultPipelines
import com.rokid.context.ai.canvas.FlowPlan
import com.rokid.context.ai.canvas.Pipeline
import com.rokid.context.ai.canvas.PipelineJson
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.CanvasDao
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.legacy.LegacyImporter
import com.rokid.context.memory.retrieval.RetrievalSettings
import com.rokid.context.orchestration.AnswerService
import com.rokid.context.core.model.DeliveryRoute
import com.rokid.context.orchestration.AskOutcome
import com.rokid.context.orchestration.DeliveryEnvironment
import com.rokid.context.orchestration.FlowPlans
import com.rokid.context.orchestration.TestAi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** v0.9.8 slice B: a Query or Nudge pipeline is frozen, published as a `flow` row, read back as a plan, rolled back, and Ask runs under it or falls back. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FlowPublishTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var dao: CanvasDao
    private lateinit var repo: CanvasRepository
    private lateinit var ai: AiGraph
    private var now = 1_000L
    private var ids = 0
    private var reply = """{"answer":"Milk is low.","sources":["rokid_mem_1"]}"""

    @Before fun setUp() {
        com.rokid.context.memory.forget.lifecycle.ForgetV2.testOverride = false;
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(app))
        dao = MemoryGraph.get(app).db.canvasDao()
        repo = CanvasRepository(dao, { "seed" }, { now++ }, { "p-${++ids}" })
        ai = TestAi.graph(app) { reply }.also { TestAi.bindAll(it) }
        runBlocking { repo.ensureBuiltIns() }
    }
    @After fun tearDown() { com.rokid.context.memory.forget.lifecycle.ForgetV2.testOverride = null; AiGraph.resetForTests(null); MemoryGraph.get(app).db.close(); MemoryGraph.resetForTests(null) }

    private val db get() = MemoryGraph.get(app).db
    private var caps = com.rokid.context.core.model.DeviceCapabilities.ALL
    private fun publisher() = CanvasPublisher(dao, ai.prompts, { _, _ -> emptyList() }, { now++ }, CanvasPublisher.inTransaction(db), { caps })

    /** A frozen copy of a default flow with the retrieve node's params replaced. */
    private suspend fun version(from: String, retrieve: JsonObject? = null, edit: (Pipeline) -> Pipeline = { it }): Pair<String, Int> {
        val id = repo.duplicate(from, null, "Mine")
        val p = edit(PipelineJson.decode(repo.get(id)!!.draftJson).getOrThrow().let { d -> if (retrieve == null) d else d.copy(nodes = d.nodes.map { if (it.id == "retrieve") it.copy(params = retrieve) else it }) })
        repo.saveDraft(id, PipelineJson.encode(p), 0)
        return id to repo.freeze(id, "v").version
    }

    @Test fun theDefaultFlowsAreSeededReadOnlyAndPublishable() = runBlocking {
        assertTrue(repo.get(DefaultPipelines.DEFAULT_QUERY)!!.builtIn && repo.get(DefaultPipelines.DEFAULT_NUDGE)!!.builtIn)
        val (qid, qv) = version(DefaultPipelines.DEFAULT_QUERY)
        val out = publisher().publishFlow(qid, qv) as CanvasPublisher.Outcome.Published
        assertEquals(FlowPlan.BUILT_IN_QUERY, DaoFlowPlans(dao).plan(FlowPlan.Kind.QUERY))
        assertNull(DaoFlowPlans(dao).plan(FlowPlan.Kind.NUDGE))
        val row = dao.activeFlow("query")!!
        assertEquals(out.publishId, row.id); assertEquals("flow", row.kind)
        // the prompts and interpreter stacks do not see it
        assertNull(dao.activeInterpreter())
    }

    @Test fun aNewPublishSupersedesAndARollbackRestoresTheBuiltIn() = runBlocking {
        val (id, v1) = version(DefaultPipelines.DEFAULT_QUERY)
        val first = publisher().publishFlow(id, v1) as CanvasPublisher.Outcome.Published
        val (id2, v2) = version(DefaultPipelines.DEFAULT_QUERY, buildJsonObject { put("memories", true); put("photos", false); put("topK", 2) })
        val second = publisher().publishFlow(id2, v2) as CanvasPublisher.Outcome.Published
        assertEquals(second.publishId, dao.activeFlow("query")!!.id)
        assertEquals(1, dao.activePublishes().count { it.kind == "flow" })
        assertEquals(false, DaoFlowPlans(dao).plan(FlowPlan.Kind.QUERY)!!.photos)
        assertEquals(CanvasPublisher.Outcome.Published(second.publishId, emptyList()), publisher().rollback(second.publishId))
        assertNull(DaoFlowPlans(dao).plan(FlowPlan.Kind.QUERY)) // the superseded row stays rolled back: the built-in runs
        assertTrue(first.publishId != second.publishId)
    }

    private fun nudgeEdit(trigger: String, params: JsonObject = JsonObject(emptyMap()), target: String = "phone"): (Pipeline) -> Pipeline = { p ->
        p.copy(nodes = p.nodes.map { if (it.id == "trigger") it.copy(type = trigger, params = params) else if (it.id == "deliver") it.copy(params = buildJsonObject { put("target", target) }) else it })
    }

    @Test fun everySlice3CTriggerAndTargetPublishesAndIsReadBack() = runBlocking {
        for ((t, target) in listOf("trigger.moment" to "phone", "trigger.scene" to "glasses", "trigger.signal" to "both", "trigger.schedule" to "phone")) {
            val (id, v) = version(DefaultPipelines.DEFAULT_NUDGE, edit = nudgeEdit(t, target = target))
            assertTrue(t, publisher().publishFlow(id, v) is CanvasPublisher.Outcome.Published)
            val plan = DaoFlowPlans(dao).plan(FlowPlan.Kind.NUDGE)!!
            assertEquals(t, plan.trigger); assertEquals(target, plan.target)
        }
        assertEquals(1, dao.activePublishes().count { it.kind == "flow" })
        // a Query may deliver to the glasses now
        val (qid, qv) = version(DefaultPipelines.DEFAULT_QUERY) { p -> p.copy(nodes = p.nodes.map { if (it.id == "deliver") it.copy(params = buildJsonObject { put("target", "glasses") }) else it }) }
        assertTrue(publisher().publishFlow(qid, qv) is CanvasPublisher.Outcome.Published)
        assertEquals("glasses", DaoFlowPlans(dao).plan(FlowPlan.Kind.QUERY)!!.target)
    }

    @Test fun aFlowWhoseOnlyDeliveryTargetIsSwitchedOffPublishesWithAWarning() = runBlocking {
        fun queryTo(target: String) = runBlocking { version(DefaultPipelines.DEFAULT_QUERY) { p -> p.copy(nodes = p.nodes.map { if (it.id == "deliver") it.copy(params = buildJsonObject { put("target", target) }) else it }) } }
        fun warnings(target: String) = (runBlocking { queryTo(target).let { (id, v) -> publisher().publishFlow(id, v) } } as CanvasPublisher.Outcome.Published).warnings
        assertTrue(warnings("glasses").isEmpty())
        caps = caps.with("glasses", "display", false)
        assertEquals(1, warnings("glasses").size)
        assertTrue("both still has the phone", warnings("both").isEmpty()); assertTrue(warnings("phone").isEmpty()); assertTrue(warnings("caller").isEmpty())
        caps = caps.with("phone", "display", false)
        assertEquals(1, warnings("both").size); assertEquals(1, warnings("phone").size)
        assertEquals("a warning never blocks: the flow is live", "phone", DaoFlowPlans(dao).plan(FlowPlan.Kind.QUERY)!!.target)
    }

    @Test fun badSchedulesAndRangesNeverFreezeAndAMeaninglessTargetIsRefusedAtPublish() = runBlocking {
        // a version with a validation problem cannot be frozen, so it can never reach a publish
        for (edit in listOf(nudgeEdit("trigger.schedule", buildJsonObject { put("cron", "*/5 * * * *") }), nudgeEdit("trigger.moment", buildJsonObject { put("maxPerHour", 50) }))) {
            val id = repo.duplicate(DefaultPipelines.DEFAULT_NUDGE, null, "Mine")
            repo.saveDraft(id, PipelineJson.encode(edit(PipelineJson.decode(repo.get(id)!!.draftJson).getOrThrow())), 0)
            assertTrue(runCatching { repo.freeze(id, "v") }.exceptionOrNull() is IllegalArgumentException)
        }
        val (id, v) = version(DefaultPipelines.DEFAULT_NUDGE, edit = nudgeEdit("trigger.scene", target = "caller"))
        assertEquals(listOf("node deliver: a Nudge has no caller; deliver to phone, glasses or both"), (publisher().publishFlow(id, v) as CanvasPublisher.Outcome.Blocked).reasons)
        assertNull(dao.activeFlow("nudge"))
        assertEquals(CanvasPublisher.Outcome.Blocked(listOf("Version not found")), publisher().publishFlow("nope", 1))
        // a photo pipeline is not a flow
        val live = repo.freeze(repo.duplicate(DefaultPipelines.LIVE_EXTRACT, null, "x"), "v")
        assertTrue(publisher().publishFlow(live.pipelineId, live.version) is CanvasPublisher.Outcome.Blocked)
    }

    // ---- Ask under a plan ----

    private suspend fun seed() { MemoryGraph.get(app).memories.add(LegacyImporter.toMemory("rokid_mem_1", "short_term", 0.9, now, "Milk carton nearly empty")) }
    private fun service(flows: FlowPlans) = AnswerService(MemoryGraph.get(app), ai.runner, ai.retriever, { RetrievalSettings() }, flows = flows)

    @Test fun askRunsUnderThePublishedPlanAndFallsBackWhenItBreaks() = runBlocking {
        seed()
        assertTrue(service(FlowPlans.None).answer("milk?", "t") is AskOutcome.Answered)
        assertTrue(service(FlowPlans { FlowPlan.BUILT_IN_QUERY }).answer("milk?", "t") is AskOutcome.Answered)
        // a plan that reads only photos finds nothing here: the plan is in effect
        val photosOnly = FlowPlan.BUILT_IN_QUERY.copy(memories = false)
        assertEquals(AskOutcome.Failed(com.rokid.context.orchestration.AskActions.NO_MEMORIES), service(FlowPlans { photosOnly }).answer("milk?", "t"))
        // a lookup that throws never silences the user: the built-in flow answers
        assertTrue(service(FlowPlans { throw IllegalStateException("db") }).answer("milk?", "t") is AskOutcome.Answered)
    }

    @Test fun queryDeliveryFollowsTheTargetAndFallsBackToThePhoneWithARecordedReason() = runBlocking {
        seed()
        fun route(target: String, source: String) = (runBlocking { service(FlowPlans { FlowPlan.BUILT_IN_QUERY.copy(target = target) }).answer("milk?", source) } as AskOutcome.Answered).route
        try {
            DeliveryEnvironment.glassesConnected = { true }
            assertNull("the built-in flow answers the surface that asked, untouched", route("caller", "glasses_voice"))
            assertEquals(DeliveryRoute(phone = false, glasses = true), route("glasses", "phone_voice"))
            assertEquals(DeliveryRoute(phone = true, glasses = false), route("phone", "glasses_voice"))
            assertEquals(DeliveryRoute(phone = true, glasses = true), route("both", "glasses_voice"))
            assertTrue(MemoryGraph.get(app).traces.recent(10).none { it.state == "ASK_DELIVERY_FALLBACK" })
            // not connected: the phone, and the reason is on record
            DeliveryEnvironment.glassesConnected = { false }
            assertEquals(DeliveryRoute(phone = true, glasses = false, fallback = DeliveryRoute.GLASSES_NOT_CONNECTED), route("glasses", "phone_voice"))
            assertEquals(listOf(DeliveryRoute.GLASSES_NOT_CONNECTED), MemoryGraph.get(app).traces.recent(10).filter { it.state == "ASK_DELIVERY_FALLBACK" }.map { it.note })
            // the hub is not built yet: unknown counts as not connected
            DeliveryEnvironment.glassesConnected = null
            assertEquals(DeliveryRoute.GLASSES_NOT_CONNECTED, route("both", "glasses_voice")!!.fallback)
            // slice D: a switched-off glasses display is not a surface (recorded), and a glasses-voice caller then answers on the phone
            DeliveryEnvironment.glassesConnected = { true }
            DeliveryEnvironment.capabilities = { com.rokid.context.core.model.DeviceCapabilities.ALL.with("glasses", "display", false) }
            assertEquals(DeliveryRoute(phone = true, glasses = false, fallback = com.rokid.context.core.model.DeviceCapabilities.GLASSES_DISPLAY_OFF), route("glasses", "phone_voice"))
            assertEquals(DeliveryRoute(phone = true, glasses = false, fallback = com.rokid.context.core.model.DeviceCapabilities.GLASSES_DISPLAY_OFF), route("caller", "glasses_voice"))
            assertEquals("a phone-voice caller gets an explicit phone-only route, never the HUD", DeliveryRoute(phone = true, glasses = false, fallback = com.rokid.context.core.model.DeviceCapabilities.GLASSES_DISPLAY_OFF), route("caller", "phone_voice"))
            assertTrue(MemoryGraph.get(app).traces.recent(10).any { it.note == com.rokid.context.core.model.DeviceCapabilities.GLASSES_DISPLAY_OFF })
            DeliveryEnvironment.capabilities = null
            // a queue or other ask has no path to the glasses even when they are connected
            DeliveryEnvironment.glassesConnected = { true }
            assertEquals(DeliveryRoute(phone = true, glasses = false, fallback = DeliveryRoute.NO_GLASSES_PATH), route("glasses", "phone"))
            // v0.9.8 E: the Home chat (typed ask) reaches the HUD through the phone's mirror, by the same route the voice asks follow
            val chat = com.rokid.context.orchestration.AskActions.TYPED_SOURCE
            assertEquals(DeliveryRoute(phone = false, glasses = true), route("glasses", chat))
            assertEquals(DeliveryRoute(phone = true, glasses = true), route("both", chat))
            assertEquals(DeliveryRoute(phone = true, glasses = false), route("phone", chat))
            assertNull("the built-in flow from chat is untouched", route("caller", chat))
            DeliveryEnvironment.glassesConnected = { false }
            assertEquals(DeliveryRoute(phone = true, glasses = false, fallback = DeliveryRoute.GLASSES_NOT_CONNECTED), route("glasses", chat))
            DeliveryEnvironment.glassesConnected = { true }
            DeliveryEnvironment.capabilities = { com.rokid.context.core.model.DeviceCapabilities.ALL.with("glasses", "display", false) }
            assertEquals(DeliveryRoute(phone = true, glasses = false, fallback = com.rokid.context.core.model.DeviceCapabilities.GLASSES_DISPLAY_OFF), route("both", chat))
            assertEquals("chat caller, glasses display off: explicit, no HUD mirror", DeliveryRoute(phone = true, glasses = false, fallback = com.rokid.context.core.model.DeviceCapabilities.GLASSES_DISPLAY_OFF), route("caller", chat))
        } finally { DeliveryEnvironment.glassesConnected = null; DeliveryEnvironment.capabilities = null }
    }

    @Test fun aPublishedRowThatNoLongerCompilesRunsTheBuiltIn() = runBlocking {
        seed()
        val (id, v) = version(DefaultPipelines.DEFAULT_QUERY)
        publisher().publishFlow(id, v)
        // the stored version is made unreadable behind the publisher's back
        db.openHelper.writableDatabase.execSQL("UPDATE canvas_pipeline_version SET json = 'x' WHERE pipelineId = ?", arrayOf(id))
        assertNull(DaoFlowPlans(dao).plan(FlowPlan.Kind.QUERY))
        assertTrue(service(DaoFlowPlans(dao)).answer("milk?", "t") is AskOutcome.Answered)
    }

    /** A history-only dependency (the source of an earlier nudge a plan reads) forgotten while the model answers redacts the answer; the row keeps it as a dependency. */
    @Test fun aHistoryOnlyDependencyIsFencedAndPersisted() = runBlocking {
        val g = MemoryGraph.get(app)
        seed()
        g.memories.add(LegacyImporter.toMemory("rokid_mem_9", "short_term", 0.9, now, "Parked on level 3"))
        g.nudges.addIgnore(com.rokid.context.memory.db.NudgeEntity("n1", now - 60_000L, "car is on 3", "", "[\"rokid_mem_9\"]", 1, "h1", "glance", false))
        val plan = FlowPlan.BUILT_IN_QUERY.copy(history = true, historyWindowMin = 1440)
        val ok = service(FlowPlans { plan }).answerOrThrow("milk?", "t", now)
        assertEquals(listOf("rokid_mem_9"), com.rokid.context.orchestration.AskSources.parseAll(g.asks.byId(ok.askId)!!.sourcesJson).filter { it == "rokid_mem_9" })
        val dropping = TestAi.graph(app) { com.rokid.context.memory.forget.ForgetService(g.db, g.proposals).forgetFact("rokid_mem_9"); reply }.also { TestAi.bindAll(it) }
        val svc = AnswerService(g, dropping.runner, dropping.retriever, { RetrievalSettings() }, flows = FlowPlans { plan })
        val row = g.asks.byId(svc.answerOrThrow("milk?", "t", now).askId)!!
        assertEquals(true, row.redacted); assertEquals(com.rokid.context.memory.forget.ForgetService.REDACTED, row.answer)
    }
}
