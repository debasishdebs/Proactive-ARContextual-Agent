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
import com.rokid.context.orchestration.AskOutcome
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
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(app))
        dao = MemoryGraph.get(app).db.canvasDao()
        repo = CanvasRepository(dao, { "seed" }, { now++ }, { "p-${++ids}" })
        ai = TestAi.graph(app) { reply }.also { TestAi.bindAll(it) }
        runBlocking { repo.ensureBuiltIns() }
    }
    @After fun tearDown() { AiGraph.resetForTests(null); MemoryGraph.get(app).db.close(); MemoryGraph.resetForTests(null) }

    private val db get() = MemoryGraph.get(app).db
    private fun publisher() = CanvasPublisher(dao, ai.prompts, { _, _ -> emptyList() }, { now++ }, CanvasPublisher.inTransaction(db))

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

    @Test fun whatIsNotLiveYetIsRefusedWithFixedReasons() = runBlocking {
        val (id, v) = version(DefaultPipelines.DEFAULT_NUDGE) { p -> p.copy(nodes = p.nodes.map { if (it.id == "trigger") it.copy(type = "trigger.schedule") else if (it.id == "deliver") it.copy(params = buildJsonObject { put("target", "phone") }) else it }) }
        val r = publisher().publishFlow(id, v) as CanvasPublisher.Outcome.Blocked
        assertEquals(listOf("node trigger: this trigger is not live yet", "node deliver: delivery to phone is not live yet (only both)"), r.reasons)
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
