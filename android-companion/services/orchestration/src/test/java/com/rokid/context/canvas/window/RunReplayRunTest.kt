package com.rokid.context.canvas.window

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.canvas.DefaultPipelines
import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.canvas.Pipeline
import com.rokid.context.ai.canvas.PipelineNode
import com.rokid.context.ai.contract.Fact
import com.rokid.context.ai.contract.FrameCaption
import com.rokid.context.canvas.CanvasItem
import com.rokid.context.canvas.CanvasRunner
import com.rokid.context.canvas.CanvasValue
import com.rokid.context.canvas.ConfirmTokens
import com.rokid.context.canvas.Estimate
import com.rokid.context.canvas.ModelLabel
import com.rokid.context.canvas.NodeExecutor
import com.rokid.context.canvas.NodeResult
import com.rokid.context.canvas.PhotoLoad
import com.rokid.context.canvas.RunContext
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.CanvasDao
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.model.ModelImageInput
import com.rokid.context.orchestration.ExtractPhoto
import com.rokid.context.orchestration.TestAi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Canvas v2.1 P3 wiring: a run that replays an earlier run's rows (REPLAYED), the seed reaching the executors' context (SEEDED), and the determinism report over the two runs' rows. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RunReplayRunTest {
    @get:org.junit.Rule val freshGate = com.rokid.context.FreshDataOpGateRule()
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var dao: CanvasDao
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var ids = 0
    private val live = HashMap<String, Int>()       // nodeId -> live item executions
    private val seeds = mutableListOf<Int?>()
    private var drift = false

    @Before fun setUp() { MemoryGraph.resetForTests(DatabaseFactory.inMemory(app)); dao = MemoryGraph.get(app).db.canvasDao() }
    @After fun tearDown() { scope.cancel(); AiGraph.resetForTests(null); MemoryGraph.get(app).db.close(); MemoryGraph.resetForTests(null) }

    private fun extraction(id: String, tag: String) = CanvasValue.Extraction(listOf(Fact("Mug $tag in $id", "object", 0.9, emptyList(), listOf(id))), listOf(FrameCaption(id, "Counter $id")))

    private fun fake(type: String, fn: (CanvasItem) -> NodeResult) = object : NodeExecutor {
        override val type = type
        override fun chunkSize(node: PipelineNode) = 1
        override fun nominalCalls(node: PipelineNode, items: Int) = items
        override fun maxCalls(node: PipelineNode, items: Int) = items
        override suspend fun run(node: PipelineNode, items: List<CanvasItem>, ctx: RunContext): List<NodeResult> {
            seeds += ctx.seed
            live.merge(node.id, items.size, Int::plus)
            return items.map(fn)
        }
    }

    private fun executors(): Map<String, NodeExecutor> = listOf(
        fake(NodeTypes.VISION_EXTRACT) { NodeResult(it.frameId, extraction(it.frameId, if (drift) "B" else "A"), calls = 1) },
        fake(NodeTypes.LOCAL_CRITIC) { NodeResult(it.frameId, (it.value as CanvasValue.Extraction).copy(trusted = true), branch = "trusted") },
        fake(NodeTypes.CRITIC) { NodeResult(it.frameId, it.value, branch = "pass", calls = 1) },
        fake(NodeTypes.MEMORY_WRITE) { NodeResult(it.frameId, CanvasValue.Preview(emptyList())) },
    ).associateBy { it.type }

    private fun runner(): CanvasRunner = CanvasRunner(
        dao, { f -> PhotoLoad(f.map { ExtractPhoto(it, "ts=1", ModelImageInput("image/jpeg", "AAAA")) }, emptySet()) },
        executors(), { id -> val ai = TestAi.graph(app) { "" }; RunContext(id, ai.runner, ai.router, ai.prompts, ai.persona, { r -> ai.bindings.get(r) }, liveness = com.rokid.context.orchestration.AllLive) },
        ConfirmTokens({ 10_000L }), { false }, { 10_000L }, { "run${++ids}" }, { 0L },
    )

    private val noLabel: suspend (PipelineNode) -> ModelLabel? = { n -> ModelLabel(n.id, "az", "gpt") }
    private suspend fun go(r: CanvasRunner, p: Pipeline, f: List<String>, replay: RunReplay? = null): String {
        val t = (r.estimate(p, f, noLabel) as Estimate.Ok).confirmToken
        val id = r.start(p, p.id, null, f, t, scope, replay)
        withTimeout(20_000) { r.active.first { it == null } }
        return id
    }

    @Test fun replayServesTheSourceRowsWithZeroLiveCallsAndReportsIdentical() = runBlocking<Unit> {
        val r = runner(); val p = DefaultPipelines.liveExtract()
        val src = go(r, p, listOf("f1", "f2", "f3"))
        val liveBefore = HashMap(live)
        val rep = go(r, p, listOf("f1", "f2", "f3"), RunReplay(DaoRecordedSource(dao, src)))
        assertEquals("the model node made no live call in the replay", liveBefore["extract"], live["extract"])
        assertEquals("deterministic nodes re-execute for real", liveBefore["trust"]!! * 2, live["trust"])
        val a = dao.items(src); val b = dao.items(rep)
        assertTrue(b.filter { it.nodeId == "extract" }.all { it.status == "OK" && it.calls == 0 })
        val order = p.nodes.map { it.id }
        val report = DeterminismReport.of(a, b, order)
        assertEquals("3/3 items identical", report.line)
    }

    @Test fun aMissIsNotRecordedByDefaultAndLiveOnlyByChoice() = runBlocking<Unit> {
        val r = runner(); val p = DefaultPipelines.liveExtract()
        val src = go(r, p, listOf("f1", "f2"))
        val before = live["extract"]!!
        val rep = go(r, p, listOf("f1", "f2", "f3"), RunReplay(DaoRecordedSource(dao, src)))
        assertEquals("not_recorded", dao.items(rep).single { it.frameId == "f3" && it.nodeId == "extract" }.error)
        assertEquals(before, live["extract"])
        val rep2 = go(r, p, listOf("f1", "f2", "f3"), RunReplay(DaoRecordedSource(dao, src), onMissLive = true))
        assertEquals("only the miss ran live", before + 1, live["extract"])
        assertEquals("OK", dao.items(rep2).single { it.frameId == "f3" && it.nodeId == "extract" }.status)
    }

    @Test fun aPrunedOrForgottenSourceIsRefusedNeverLive() = runBlocking<Unit> {
        val r = runner(); val p = DefaultPipelines.liveExtract()
        val src = go(r, p, listOf("f1"))
        dao.deleteRun(src)
        val before = HashMap(live)
        val rep = go(r, p, listOf("f1"), RunReplay(DaoRecordedSource(dao, src), onMissLive = true))
        assertEquals("replay_source_gone", dao.items(rep).single { it.nodeId == "extract" }.error)
        assertEquals(before, live)
    }

    @Test fun driftIsNamedAtTheFirstNodeThatDiffers() = runBlocking<Unit> {
        val r = runner(); val p = DefaultPipelines.liveExtract()
        val src = go(r, p, listOf("f1", "f2"))
        drift = true
        val rep = go(r, p, listOf("f1", "f2"), RunReplay(null, seed = 5))
        val report = DeterminismReport.of(dao.items(src), dao.items(rep), p.nodes.map { it.id })
        assertEquals(0, report.identical); assertEquals(mapOf("extract" to 2), report.driftedNodes)
        assertTrue(report.line.contains("drifted at node extract"))
    }

    @Test fun theSeedReachesTheExecutorsContextOnlyWhenTheRunAsksForIt() = runBlocking<Unit> {
        val r = runner(); val p = DefaultPipelines.liveExtract()
        go(r, p, listOf("f1"))
        assertTrue(seeds.isNotEmpty() && seeds.all { it == null })
        seeds.clear()
        go(r, p, listOf("f1"), RunReplay(null, seed = 7))
        assertTrue(seeds.isNotEmpty() && seeds.all { it == 7 })
        assertEquals(null, RunContext("x", TestAi.graph(app) { "" }.runner, TestAi.graph(app) { "" }.router, TestAi.graph(app) { "" }.prompts, TestAi.graph(app) { "" }.persona, { null }).requestTemperature)
        assertEquals(0.0, RunContext("x", TestAi.graph(app) { "" }.runner, TestAi.graph(app) { "" }.router, TestAi.graph(app) { "" }.prompts, TestAi.graph(app) { "" }.persona, { null }, seed = 3).requestTemperature)
    }

    @Test fun aNonPositiveSeedIsRefused() {
        try { RunReplay(null, seed = 0); org.junit.Assert.fail() } catch (e: IllegalArgumentException) { assertEquals("bad_seed", e.message) }
    }
}
