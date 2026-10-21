package com.rokid.context.canvas

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.canvas.DefaultPipelines
import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.canvas.Pipeline
import com.rokid.context.ai.canvas.PipelineEdge
import com.rokid.context.ai.canvas.PipelineHash
import com.rokid.context.ai.canvas.PipelineJson
import com.rokid.context.ai.canvas.PipelineNode
import com.rokid.context.ai.contract.Fact
import com.rokid.context.ai.contract.FrameCaption
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.CanvasDao
import com.rokid.context.memory.db.CanvasRunEntity
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.model.ModelImageInput
import com.rokid.context.orchestration.ExtractPhoto
import com.rokid.context.orchestration.TestAi
import com.rokid.context.work.frames.KeyframePipeline
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CanvasRunnerTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var dao: CanvasDao
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var now = 10_000L
    private var ids = 0
    private var hotNow = { false }
    private var missing = emptySet<String>()
    private val tokens = ConfirmTokens({ now })
    private val seen = HashMap<String, MutableList<String>>() // nodeId -> frame ids it ran for

    @Before fun setUp() {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(app))
        dao = MemoryGraph.get(app).db.canvasDao()
    }

    @After fun tearDown() {
        scope.cancel()
        AiGraph.resetForTests(null); MemoryGraph.get(app).db.close(); MemoryGraph.resetForTests(null)
    }

    private fun photo(id: String) = ExtractPhoto(id, "ts=1", ModelImageInput("image/jpeg", "AAAA"))
    private fun extraction(id: String) = CanvasValue.Extraction(listOf(Fact("Mug in $id", "object", 0.9, emptyList(), listOf(id))), listOf(FrameCaption(id, "Counter $id")))

    /** A configurable executor: [fn] makes each item's result; every item it sees is recorded under the node id. */
    private fun fake(type: String, chunk: Int = 1, nominal: (Int) -> Int = { 0 }, onChunk: (List<CanvasItem>) -> Unit = {}, fn: (PipelineNode, CanvasItem) -> NodeResult): NodeExecutor =
        object : NodeExecutor {
            override val type = type
            override fun chunkSize(node: PipelineNode) = chunk
            override fun nominalCalls(node: PipelineNode, items: Int) = nominal(items)
            override fun maxCalls(node: PipelineNode, items: Int) = nominal(items) * 2
            override suspend fun run(node: PipelineNode, items: List<CanvasItem>, ctx: RunContext): List<NodeResult> {
                items.forEach { seen.getOrPut(node.id) { mutableListOf() } += it.frameId }
                onChunk(items)
                return items.map { fn(node, it) }
            }
        }

    private fun liveFakes(unsure: Set<String> = emptySet(), onExtractChunk: (List<CanvasItem>) -> Unit = {}): Map<String, NodeExecutor> = listOf(
        fake(NodeTypes.VISION_EXTRACT, chunk = 3, nominal = { (it + 2) / 3 }, onChunk = onExtractChunk) { _, it -> NodeResult(it.frameId, extraction(it.frameId), calls = 1) },
        fake(NodeTypes.LOCAL_CRITIC) { _, it ->
            val trusted = it.frameId !in unsure
            NodeResult(it.frameId, (it.value as CanvasValue.Extraction).copy(trusted = trusted), branch = if (trusted) "trusted" else "unsure")
        },
        fake(NodeTypes.CRITIC, nominal = { it }) { _, it -> NodeResult(it.frameId, it.value, branch = "pass", calls = 1) },
        fake(NodeTypes.MEMORY_WRITE) { _, it -> NodeResult(it.frameId, CanvasValue.Preview(emptyList())) },
    ).associateBy { it.type }

    private fun runner(executors: Map<String, NodeExecutor>, context: (String) -> RunContext = ::idleContext) = CanvasRunner(
        dao, { f -> PhotoLoad(f.filter { it !in missing }.map(::photo), f.filter { it in missing }.toSet()) },
        executors, context, tokens, { hotNow() }, { now }, { "run${++ids}" },
    )

    /** For fake executors, which never touch the context. */
    private fun idleContext(id: String): RunContext {
        val ai = TestAi.graph(app) { "" }
        return RunContext(id, ai.runner, ai.router, ai.prompts, ai.persona, { role -> ai.bindings.get(role) })
    }

    private val noLabel: suspend (PipelineNode) -> ModelLabel? = { n -> ModelLabel(n.id, "az", "gpt") }

    private suspend fun ok(r: CanvasRunner, p: Pipeline, f: List<String>) = r.estimate(p, f, noLabel) as Estimate.Ok
    private suspend fun begin(r: CanvasRunner, p: Pipeline, f: List<String>): String = r.start(p, p.id, null, f, ok(r, p, f).confirmToken, scope)
    private suspend fun finish(r: CanvasRunner) { withTimeout(20_000) { r.active.first { it == null } } }
    private suspend fun runAll(r: CanvasRunner, p: Pipeline, f: List<String>): CanvasRunEntity { val id = begin(r, p, f); finish(r); return checkNotNull(dao.run(id)) }
    private fun frames(n: Int) = (1..n).map { "f$it" }

    // ---- estimate ----

    @Test fun estimateCountsUpperBound() = runBlocking<Unit> {
        val e = ok(runner(liveFakes()), DefaultPipelines.liveExtract(), frames(7))
        assertEquals(7, e.photos)
        assertEquals(3 + 7, e.nominal)          // extract ceil(7/3)=3, critic 7
        assertEquals(3 * 2 + 7 * 2, e.max)     // the fakes report max = 2 x nominal
        assertEquals(listOf("extract", "critic"), e.models.map { it.nodeId })
        assertEquals(32, e.confirmToken.length)
    }

    @Test fun estimateRefusesOver50Photos() = runBlocking<Unit> {
        val r = runner(liveFakes()).estimate(DefaultPipelines.liveExtract(), frames(51), noLabel)
        assertEquals(listOf("more than 50 photos"), (r as Estimate.Refused).reasons)
        assertTrue(runner(liveFakes()).estimate(DefaultPipelines.liveExtract(), frames(50), noLabel) is Estimate.Ok)
    }

    @Test fun estimateRefusesOver200Calls() = runBlocking<Unit> {
        val heavy = liveFakes() + fake(NodeTypes.CRITIC, nominal = { it * 5 }) { _, it -> NodeResult(it.frameId, it.value, branch = "pass") }.let { it.type to it }
        val r = runner(heavy).estimate(DefaultPipelines.liveExtract(), frames(50), noLabel)
        assertEquals(listOf("more than 200 model calls"), (r as Estimate.Refused).reasons)
    }

    @Test fun estimateRefusesNoPhotosAndBadPipeline() = runBlocking<Unit> {
        val r = runner(liveFakes())
        assertEquals(listOf("no photos"), (r.estimate(DefaultPipelines.liveExtract(), emptyList(), noLabel) as Estimate.Refused).reasons)
        val bad = DefaultPipelines.liveExtract().let { it.copy(edges = it.edges.drop(1)) }
        assertTrue("pipeline has problems" in (r.estimate(bad, frames(1), noLabel) as Estimate.Refused).reasons)
    }

    // ---- start / tokens / busy ----

    @Test fun startNeedsValidToken() = runBlocking<Unit> {
        val r = runner(liveFakes()); val p = DefaultPipelines.liveExtract()
        try { r.start(p, p.id, null, frames(1), "bogus", scope); fail() } catch (e: IllegalArgumentException) { }
        val t = ok(r, p, frames(1)).confirmToken
        now += 5 * 60 * 1000L
        try { r.start(p, p.id, null, frames(1), t, scope); fail() } catch (e: IllegalArgumentException) { }
        assertTrue(dao.runs(10).isEmpty())
        assertNull(r.active.value)
    }

    @Test fun invalidPipelineIsRefusedAtStart() = runBlocking<Unit> {
        val r = runner(liveFakes()); val p = DefaultPipelines.liveExtract()
        val t = ok(r, p, frames(1)).confirmToken
        val bad = p.copy(edges = p.edges.drop(1))
        try { r.start(bad, p.id, null, frames(1), t, scope); fail() } catch (e: IllegalArgumentException) { assertEquals("invalid_pipeline", e.message) }
    }

    @Test fun tokenSingleUseAndFrameBound() = runBlocking<Unit> {
        val r = runner(liveFakes()); val p = DefaultPipelines.liveExtract()
        val t = ok(r, p, frames(2)).confirmToken
        try { r.start(p, p.id, null, frames(3), t, scope); fail() } catch (e: IllegalArgumentException) { }
        val t2 = ok(r, p, frames(2)).confirmToken
        r.start(p, p.id, null, frames(2), t2, scope); finish(r)
        try { r.start(p, p.id, null, frames(2), t2, scope); fail() } catch (e: IllegalArgumentException) { }
    }

    @Test fun secondRunIsBusy() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val ex = liveFakes() + (NodeTypes.MEMORY_WRITE to object : NodeExecutor {
            override val type = NodeTypes.MEMORY_WRITE
            override fun nominalCalls(node: PipelineNode, items: Int) = 0
            override fun maxCalls(node: PipelineNode, items: Int) = 0
            override suspend fun run(node: PipelineNode, items: List<CanvasItem>, ctx: RunContext): List<NodeResult> {
                gate.await(); return items.map { NodeResult(it.frameId, CanvasValue.Preview(emptyList())) }
            }
        })
        val r = runner(ex); val p = DefaultPipelines.liveExtract()
        val id = begin(r, p, frames(1))
        assertEquals(id, r.active.value)
        try { begin(r, p, frames(1)); fail() } catch (e: CanvasRunner.Busy) { }
        gate.complete(Unit); finish(r)
        assertEquals("DONE", dao.run(id)?.status)
        assertNotNull(begin(r, p, frames(1))); finish(r)
    }

    // ---- walking the graph ----

    @Test fun topoOrderAndBranchRouting() = runBlocking<Unit> {
        val run = runAll(runner(liveFakes(unsure = setOf("f3"))), DefaultPipelines.liveExtract(), frames(3))
        assertEquals("DONE", run.status)
        assertEquals(listOf("f3"), seen["critic"])
        assertEquals(listOf("f1", "f2", "f3"), seen["write"]?.sorted())
        assertEquals(3, seen["write"]?.size)
        val items = dao.items(run.runId)
        assertEquals(setOf("f1", "f2", "f3"), items.filter { it.nodeId == "photos" }.map { it.frameId }.toSet())
        assertEquals("unsure", items.single { it.nodeId == "trust" && it.frameId == "f3" }.branch)
        assertEquals(1, items.count { it.nodeId == "critic" })
        assertEquals(3, items.count { it.nodeId == "write" })
        assertEquals(3 + 1, run.calls)     // the extract fake counts 1 per item, plus one critic call
    }

    @Test fun switchRoutesPerItem() = runBlocking<Unit> {
        val labels = mapOf("f1" to "workout", "f2" to "driving", "f3" to "mystery")
        val ex = listOf(
            fake(NodeTypes.PROMPT) { _, it -> NodeResult(it.frameId, CanvasValue.Json(buildJsonObject { put("activity", labels.getValue(it.frameId)) })) },
            SwitchExecutor,
            fake(NodeTypes.VISION_EXTRACT) { _, it -> NodeResult(it.frameId, extraction(it.frameId)) },
            fake(NodeTypes.MEMORY_WRITE) { _, it -> NodeResult(it.frameId, CanvasValue.Preview(emptyList())) },
        ).associateBy { it.type }
        val run = runAll(runner(ex), DefaultPipelines.intentDraft("seed {{recent_context}}"), frames(3))
        assertEquals("DONE", run.status)
        assertEquals(listOf("f1"), seen["extract_workout"])
        assertEquals(listOf("f2"), seen["extract_driving"])
        assertEquals(listOf("f3"), seen["extract_other"])
        assertNull(seen["extract_walking"])
        assertEquals(listOf("f1", "f2", "f3"), seen["write"]?.sorted())
        // Nodes no item reached have no rows.
        assertTrue(dao.items(run.runId).none { it.nodeId == "extract_walking" })
    }

    @Test fun mergeConflictIsItemError() = runBlocking<Unit> {
        val p = DefaultPipelines.liveExtract().let { it.copy(edges = it.edges + PipelineEdge("photos", "extract")) }
        val run = runAll(runner(liveFakes()), p, frames(2))
        assertEquals("DONE", run.status)
        assertNull(seen["extract"])
        val rows = dao.items(run.runId).filter { it.nodeId == "extract" }
        assertEquals(listOf("merge_conflict", "merge_conflict"), rows.map { it.error })
        assertTrue(rows.all { it.status == "ERROR" && it.outputJson == null })
        assertEquals(2, run.failures)
    }

    @Test fun missingPhotoIsItemError() = runBlocking<Unit> {
        missing = setOf("f2")
        val run = runAll(runner(liveFakes()), DefaultPipelines.liveExtract(), frames(3))
        val rows = dao.items(run.runId).filter { it.nodeId == "photos" }.associateBy { it.frameId }
        assertEquals("ERROR", rows.getValue("f2").status)
        assertEquals("unreadable", rows.getValue("f2").error)
        assertEquals("OK", rows.getValue("f1").status)
        assertTrue(rows.getValue("f1").outputJson!!.contains("\"kind\":\"photo\""))
        assertEquals(listOf("f1", "f3"), seen["extract"]?.sorted())
        assertEquals(1, run.failures)
        assertTrue(dao.items(run.runId).none { it.frameId == "f2" && it.nodeId != "photos" })
    }

    @Test fun itemsPersistedPerChunk() = runBlocking<Unit> {
        var rowsAtSecondChunk = -1
        var chunks = 0
        val ex = liveFakes(onExtractChunk = { _ -> if (++chunks == 2) rowsAtSecondChunk = runBlocking { dao.items("run1").count { it.nodeId == "extract" } } })
        val run = runAll(runner(ex), DefaultPipelines.liveExtract(), frames(4))
        assertEquals(3, rowsAtSecondChunk)   // chunk 1 (3 items) is stored before chunk 2 starts
        assertEquals(4, dao.items(run.runId).count { it.nodeId == "extract" })
        assertEquals(4, run.calls)
    }

    // ---- life cycle ----

    @Test fun cancelledScopeDoesNotLeaveRunnerBusy() = runBlocking<Unit> {
        val dead = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { it.cancel() }
        val r = runner(liveFakes()); val p = DefaultPipelines.liveExtract()
        val id = r.start(p, p.id, null, frames(2), ok(r, p, frames(2)).confirmToken, dead)
        finish(r)
        assertNull(r.active.value)
        val st = dao.run(id)?.status
        assertTrue(st != "RUNNING" && st != null)
        assertNotNull(begin(r, p, frames(1))); finish(r) // not Busy
    }

    @Test fun cancelStopsBeforeNextChunk() = runBlocking<Unit> {
        lateinit var r: CanvasRunner
        r = runner(liveFakes(onExtractChunk = { r.cancel("run1") }))
        val run = runAll(r, DefaultPipelines.liveExtract(), frames(4))
        assertEquals("CANCELLED", run.status)
        assertNotNull(run.finishedAt)
        assertEquals(3, dao.items(run.runId).count { it.nodeId == "extract" })
        assertTrue(dao.items(run.runId).none { it.nodeId == "write" })
        assertFalse(r.cancel("run1")) // nothing active now
    }

    @Test fun hotStopsAsStoppedHot() = runBlocking<Unit> {
        var chunks = 0
        val r = runner(liveFakes(onExtractChunk = { chunks++ }))
        hotNow = { chunks >= 1 }
        val run = runAll(r, DefaultPipelines.liveExtract(), frames(4))
        assertEquals("STOPPED_HOT", run.status)
        assertNotNull(run.finishedAt)
        assertEquals(1, chunks)
    }

    @Test fun unexpectedExceptionFailsRun() = runBlocking<Unit> {
        val boom = object : NodeExecutor {
            override val type = NodeTypes.VISION_EXTRACT
            override fun nominalCalls(node: PipelineNode, items: Int) = 1
            override fun maxCalls(node: PipelineNode, items: Int) = 1
            override suspend fun run(node: PipelineNode, items: List<CanvasItem>, ctx: RunContext): List<NodeResult> = throw IllegalStateException("secret provider text")
        }
        val run = runAll(runner(liveFakes() + (boom.type to boom)), DefaultPipelines.liveExtract(), frames(1))
        assertEquals("FAILED", run.status)
        assertNotNull(run.finishedAt)
    }

    @Test fun recoverMarksInterrupted() = runBlocking<Unit> {
        dao.insertRun(CanvasRunEntity("old", "p", null, "h", "{}", "RUNNING", 1, 1, 0, 0, 1, null, "[]"))
        dao.insertRun(CanvasRunEntity("done", "p", null, "h", "{}", "DONE", 1, 1, 0, 0, 2, 3, "[]"))
        assertEquals(1, runner(liveFakes()).recoverAtStart())
        assertEquals("INTERRUPTED", dao.run("old")?.status)
        assertEquals("DONE", dao.run("done")?.status)
    }

    @Test fun pruneKeepsTwentyIncludingNew() = runBlocking<Unit> {
        for (i in 1..25) dao.insertRun(CanvasRunEntity("old$i", "p", null, "h", "{}", "DONE", 1, 1, 0, 0, i.toLong(), i + 1L, "[]"))
        val run = runAll(runner(liveFakes()), DefaultPipelines.liveExtract(), frames(1))
        val kept = dao.runs(100).map { it.runId }
        assertEquals(20, kept.size)
        assertTrue(run.runId in kept)
        assertFalse("old1" in kept)
        assertTrue("old25" in kept)
    }

    @Test fun snapshotStoresCanonicalJsonAndHash() = runBlocking<Unit> {
        val p = DefaultPipelines.liveExtract()
        val r = runner(liveFakes())
        val est = ok(r, p, frames(2))
        val id = r.start(p, "live-extract", 3, frames(2), est.confirmToken, scope); finish(r)
        val run = checkNotNull(dao.run(id))
        assertEquals(PipelineJson.canonical(p), run.pipelineJson)
        assertEquals(PipelineHash.of(p), run.pipelineHash)
        assertEquals("live-extract", run.pipelineId)
        assertEquals(3, run.version)
        assertEquals(2, run.frameCount)
        assertEquals(est.nominal, run.estCalls)
        assertEquals(10_000L, run.startedAt)
        val models = kotlinx.serialization.json.Json.parseToJsonElement(run.modelsJson) as kotlinx.serialization.json.JsonArray
        assertEquals(listOf("extract", "critic"), models.map { it.jsonObject["node"]!!.jsonPrimitive.content })
    }

    // ---- hard rule ----

    private fun count(t: String) = MemoryGraph.get(app).db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$t`").use { it.moveToFirst(); it.getInt(0) }
    private val liveTables = listOf("memory", "frame_state", "frame_source", "memory_vector")

    @Test fun runWritesNoLiveRows() = runBlocking<Unit> {
        val before = liveTables.map(::count)
        val ai = TestAi.graph(app) { p ->
            if ("BEGIN_DATA facts" in p) """{"verdict":"pass","reasons":[]}"""
            else {
                val ids = Regex("frame_id: (f\\d)").findAll(p).map { it.groupValues[1] }.toList().distinct()
                """{"facts":[${ids.joinToString(",") { """{"text":"Mug in $it","type":"object","confidence":0.5,"tags":[],"source_frame_ids":["$it"]}""" }}],"captions":[${ids.joinToString(",") { """{"frame_id":"$it","caption":"Counter $it"}""" }}]}"""
            }
        }.also { TestAi.bindAll(it) }
        val ctx = { id: String -> RunContext(id, ai.runner, ai.router, ai.prompts, ai.persona, { role -> ai.bindings.get(role) }) }
        val run = runAll(runner(Executors.all(), ctx), DefaultPipelines.liveExtract(), frames(4))
        assertEquals("DONE", run.status)
        val items = dao.items(run.runId)
        assertEquals(4, items.count { it.nodeId == "write" && it.status == "OK" })
        assertTrue(run.calls > 0)
        assertEquals(before, liveTables.map(::count))
    }

    // ---- extra item from the Task 3 review ----

    @Test fun liveExtractDefaultBatchMatchesKeyframePipeline() {
        val extract = DefaultPipelines.liveExtract().nodes.single { it.type == NodeTypes.VISION_EXTRACT }
        assertEquals(KeyframePipeline.MAX_BATCH, extract.params["batch"]!!.jsonPrimitive.content.toInt())
    }
}
