package com.rokid.context.canvas

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.InMemoryKeyVault
import com.rokid.context.ai.InMemorySeedState
import com.rokid.context.ai.ProviderBuilder
import com.rokid.context.memory.db.CanvasRunItemEntity
import com.rokid.context.memory.db.ForgetTombstoneEntity
import com.rokid.context.model.ModelProvider
import com.rokid.context.model.ModelReply
import com.rokid.context.model.ModelRequest
import kotlinx.serialization.json.JsonPrimitive
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import org.junit.Assert.assertNotEquals
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
    @get:org.junit.Rule val freshGate = com.rokid.context.FreshDataOpGateRule()
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var dao: CanvasDao
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var now = 10_000L
    private var ids = 0
    private var hotNow = { false }
    private var missing = emptySet<String>()
    private val tokens = ConfirmTokens({ now })
    private val seen = HashMap<String, MutableList<String>>() // nodeId -> frame ids it ran for
    private var gen = 0L

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
        executors, context, tokens, { hotNow() }, { now }, { "run${++ids}" }, { gen },
    )

    /** For fake executors, which never touch the context. */
    private fun idleContext(id: String): RunContext {
        val ai = TestAi.graph(app) { "" }
        return RunContext(id, ai.runner, ai.router, ai.prompts, ai.persona, { role -> ai.bindings.get(role) }, liveness = com.rokid.context.orchestration.AllLive)
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

    // ---- crash containment ----

    private fun sql(q: String) = MemoryGraph.get(app).db.openHelper.writableDatabase.execSQL(q)
    private val failFinalTrigger = "CREATE TRIGGER fail_final BEFORE UPDATE ON canvas_run WHEN NEW.finishedAt IS NOT NULL BEGIN SELECT RAISE(ABORT, 'full'); END"

    /** Arms a DB trigger (once the run is going) that makes every final updateRun throw; [dropAtFinalCall] drops it again at that call of the clock. */
    private fun flakyRunner(dropAtFinalCall: Int?): CanvasRunner {
        var armed = false
        var finals = 0
        val ex = liveFakes() + (NodeTypes.MEMORY_WRITE to fake(NodeTypes.MEMORY_WRITE, onChunk = { if (!armed) { armed = true; sql(failFinalTrigger) } }) { _, it -> NodeResult(it.frameId, CanvasValue.Preview(emptyList())) })
        return CanvasRunner(
            dao, { f -> PhotoLoad(f.map(::photo), emptySet()) }, ex, ::idleContext, tokens, { hotNow() },
            { if (armed && ++finals == dropAtFinalCall) sql("DROP TRIGGER fail_final"); now }, { "run${++ids}" },
        )
    }

    @Test fun throwingFinalUpdateDoesNotCrashAndRowReachesTerminalState() = runBlocking<Unit> {
        val r = flakyRunner(dropAtFinalCall = 2) // the first final update throws, the retry (clock call 2) succeeds
        val run = runAll(r, DefaultPipelines.liveExtract(), frames(1))
        assertEquals("FAILED", run.status) // the retry wrote a terminal status
        assertNotNull(run.finishedAt)
        assertNull(r.active.value)
    }

    @Test fun updateRunThatKeepsThrowingStillReleasesTheRunner() = runBlocking<Unit> {
        val r = flakyRunner(dropAtFinalCall = null)
        val p = DefaultPipelines.liveExtract()
        val id = begin(r, p, frames(1))
        finish(r) // no exception escapes and the lock is released
        sql("DROP TRIGGER fail_final")
        assertEquals("RUNNING", dao.run(id)?.status) // left for recoverAtStart to sweep
        assertEquals(1, r.recoverAtStart())
    }

    @Test fun errorInsideAnExecutorFailsTheRunWithoutEscaping() = runBlocking<Unit> {
        val boom = liveFakes() + (NodeTypes.VISION_EXTRACT to fake(NodeTypes.VISION_EXTRACT) { _, _ -> throw StackOverflowError() })
        val run = runAll(runner(boom), DefaultPipelines.liveExtract(), frames(1))
        assertEquals("FAILED", run.status)
    }

    @Test fun aMissingExecutorFailsItemsWithNoExecutorAndIsNotACancel() = runBlocking<Unit> {
        val run = runAll(runner(liveFakes() - NodeTypes.VISION_EXTRACT), DefaultPipelines.liveExtract(), frames(2))
        assertNotEquals("CANCELLED", run.status) // WP8 review minor: only an interrupted helper chunk returns null
        assertTrue(dao.items(run.runId).any { it.nodeId == "extract" && it.error == "no_executor" })
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
        awaitPruned()
        val kept = dao.runs(100).map { it.runId }
        assertEquals(20, kept.size)
        assertTrue(run.runId in kept)
        assertFalse("old1" in kept)
        assertTrue("old25" in kept)
    }

    /** The prune runs after the start, in its own coroutine: wait (bounded) until it has trimmed the table to the kept 20. */
    private suspend fun awaitPruned() { withTimeout(10_000) { while (dao.runs(100).size > 20) delay(10) } }

    /** Holds the shared gate's shared side (an export) until [release] is counted down; returns once it is held. */
    private suspend fun holdGate(release: java.util.concurrent.CountDownLatch) {
        val held = kotlinx.coroutines.CompletableDeferred<Unit>()
        scope.launch { try { com.rokid.context.orchestration.DataOpGate.shared.export { held.complete(Unit); withContext(Dispatchers.IO) { release.await() } } } catch (_: Throwable) { held.complete(Unit) } }
        held.await()
    }

    @Test fun runStartDoesNotWaitForABlockedGateAndPrunesAfterwards() = runBlocking<Unit> {
        for (i in 1..25) dao.insertRun(CanvasRunEntity("old$i", "p", null, "h", "{}", "DONE", 1, 1, 0, 0, i.toLong(), i + 1L, "[]"))
        val release = java.util.concurrent.CountDownLatch(1)
        holdGate(release)
        try {
            val r = runner(liveFakes())
            val t0 = System.nanoTime()
            val first = runAll(r, DefaultPipelines.liveExtract(), frames(1))   // the start returns and the run ends while the gate is held
            assertTrue("a start must not wait for the gate", (System.nanoTime() - t0) / 1_000_000 < 5_000)
            assertEquals("DONE", first.status)
            // a second start is not told a run is active, and the prune has not run yet (gate held)
            val second = runAll(r, DefaultPipelines.liveExtract(), frames(1))
            assertEquals("DONE", second.status)
            assertTrue("the prune waits for the gate", dao.runs(100).size > 20)
        } finally { release.countDown() }
        awaitPruned()                                                       // the gate is free: the pending prune trims the table
        assertEquals(20, dao.runs(100).size)
    }

    @Test fun runStartsWhenThePruneIsSkipped() = runBlocking<Unit> {
        for (i in 1..25) dao.insertRun(CanvasRunEntity("old$i", "p", null, "h", "{}", "DONE", 1, 1, 0, 0, i.toLong(), i + 1L, "[]"))
        com.rokid.context.orchestration.DataOpGate.shared = com.rokid.context.orchestration.DataOpGate(waitMs = 50)
        val release = java.util.concurrent.CountDownLatch(1)
        holdGate(release)
        try {
            val run = runAll(runner(liveFakes()), DefaultPipelines.liveExtract(), frames(1))
            assertEquals("DONE", run.status)
            delay(300)                                                      // the bounded wait ended: the prune was skipped, the run was not affected
            assertEquals(26, dao.runs(100).size)
        } finally { release.countDown() }
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
        val ctx = { id: String -> RunContext(id, ai.runner, ai.router, ai.prompts, ai.persona, { role -> ai.bindings.get(role) }, liveness = com.rokid.context.orchestration.AllLive) }
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

    // ---- Task 7: runner minors ----

    @Test fun startRefusesOver50Photos() = runBlocking<Unit> {
        val r = runner(liveFakes()); val p = DefaultPipelines.liveExtract()
        try { r.start(p, p.id, null, frames(51), "whatever", scope); fail() } catch (e: IllegalArgumentException) { assertEquals("more than 50 photos", e.message) }
        assertTrue(dao.runs(10).isEmpty())
        assertNull(r.active.value)
    }

    /** The CancellationException branch (the scope dies mid-run): its CANCELLED write gets the same one retry as FAILED as the final update. */
    @Test fun cancelledUpdateRetried() = runBlocking<Unit> {
        val own = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var armed = false
        val r = runner(liveFakes(onExtractChunk = {
            if (!armed) { armed = true; sql("CREATE TRIGGER fail_cancel BEFORE UPDATE ON canvas_run WHEN NEW.status = 'CANCELLED' BEGIN SELECT RAISE(ABORT, 'full'); END"); own.cancel() }
        }))
        val p = DefaultPipelines.liveExtract()
        val id = r.start(p, p.id, null, frames(4), ok(r, p, frames(4)).confirmToken, own)
        finish(r)
        sql("DROP TRIGGER fail_cancel")
        val run = dao.run(id)!!
        assertEquals("FAILED", run.status) // the CANCELLED write threw once; the retry reached a terminal state
        assertNotNull(run.finishedAt)
        assertNull(r.active.value)
    }

    // ---- Task 7: fences (S24) ----

    private fun pausedExtract(started: CompletableDeferred<Unit>, gate: CompletableDeferred<Unit>) = object : NodeExecutor {
        override val type = NodeTypes.VISION_EXTRACT
        override fun chunkSize(node: PipelineNode) = 3
        override fun nominalCalls(node: PipelineNode, items: Int) = (items + 2) / 3
        override fun maxCalls(node: PipelineNode, items: Int) = nominalCalls(node, items) * 2
        override suspend fun run(node: PipelineNode, items: List<CanvasItem>, ctx: RunContext): List<NodeResult> {
            started.complete(Unit); gate.await()
            return items.map { NodeResult(it.frameId, extraction(it.frameId), calls = 1) }
        }
    }

    @Test fun wipeDuringExperimentRunWritesNoItems() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
        val r = runner(liveFakes() + (NodeTypes.VISION_EXTRACT to pausedExtract(started, gate)))
        val id = begin(r, DefaultPipelines.liveExtract(), frames(3))
        withTimeout(10_000) { started.await() }
        gen++ // Delete all ran while the model call was in flight
        gate.complete(Unit); finish(r)
        val items = dao.items(id)
        assertTrue(items.none { it.nodeId != "photos" })  // nothing derived from the photos after the wipe
        assertEquals("CANCELLED", dao.run(id)?.status)
    }

    @Test fun forgetDuringExperimentRunWritesNoItemsOfThatChunk() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
        val r = runner(liveFakes() + (NodeTypes.VISION_EXTRACT to pausedExtract(started, gate)))
        val id = begin(r, DefaultPipelines.liveExtract(), frames(3))
        withTimeout(10_000) { started.await() }
        MemoryGraph.get(app).db.forgetDao().insertTombstone(ForgetTombstoneEntity("f2", 1))
        gate.complete(Unit); finish(r)
        assertTrue(dao.items(id).none { it.nodeId != "photos" })
        assertEquals("CANCELLED", dao.run(id)?.status)
    }

    @Test fun unwipedRunStillWritesEverything() = runBlocking<Unit> {
        val run = runAll(runner(liveFakes()), DefaultPipelines.liveExtract(), frames(3))
        assertEquals("DONE", run.status)
        assertEquals(3, dao.items(run.runId).count { it.nodeId == "extract" })
    }

    // ---- Task 7: A/B (S31) ----

    private fun variantB(p: Pipeline) = p.copy(nodes = p.nodes.map { if (it.type == NodeTypes.VISION_EXTRACT) it.copy(params = JsonObject(it.params + ("batch" to JsonPrimitive(1)))) else it })
    private suspend fun abOk(r: CanvasRunner, a: Pipeline, b: Pipeline, f: List<String>) = r.estimateAb(a, b, f, noLabel) as Estimate.Ok
    private fun spec(a: Pipeline, b: Pipeline) = AbSpec(a to null, "pa", b to 2, "pb")
    private suspend fun beginAb(r: CanvasRunner, a: Pipeline, b: Pipeline, f: List<String>): AbStarted = r.startAb(spec(a, b), f, abOk(r, a, b, f).confirmToken, scope)

    @Test fun abRunsSequentiallyWithGroup() = runBlocking<Unit> {
        val order = mutableListOf<String>()
        val statusesDuringA = mutableListOf<String>()
        var first = true
        val ex = liveFakes(onExtractChunk = { if (first) { first = false; statusesDuringA += runBlocking { dao.runs(10).filter { it.abGroup != null }.map { it.status } } } })
        val r = runner(ex, context = { id -> order += id; idleContext(id) })
        val a = DefaultPipelines.liveExtract()
        val st = beginAb(r, a, variantB(a), frames(3)); finish(r)
        assertEquals(listOf(st.runA, st.runB), order)
        assertEquals(listOf("QUEUED", "RUNNING"), statusesDuringA.sorted())
        val rows = dao.runsInGroup(st.group)
        assertEquals(setOf(st.runA, st.runB), rows.map { it.runId }.toSet())
        assertTrue(rows.all { it.status == "DONE" && it.abGroup == st.group })
        assertEquals(PipelineHash.of(a), dao.run(st.runA)!!.pipelineHash)
        assertEquals(PipelineHash.of(variantB(a)), dao.run(st.runB)!!.pipelineHash)
        assertEquals("pa", dao.run(st.runA)!!.pipelineId); assertEquals("pb", dao.run(st.runB)!!.pipelineId)
        assertEquals(2, dao.run(st.runB)!!.version)
        assertTrue(dao.items(st.runB).any { it.nodeId == "extract" })
    }

    @Test fun abReturnsBothIds() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
        val r = runner(liveFakes() + (NodeTypes.VISION_EXTRACT to pausedExtract(started, gate)))
        val a = DefaultPipelines.liveExtract()
        val st = beginAb(r, a, variantB(a), frames(2))
        withTimeout(10_000) { started.await() }
        assertTrue(st.runA != st.runB && st.group.isNotEmpty())
        assertEquals("RUNNING", dao.run(st.runA)?.status)
        assertEquals("QUEUED", dao.run(st.runB)?.status)
        assertEquals(st.runA, r.active.value)
        gate.complete(Unit); finish(r)
    }

    @Test fun otherRunRefusedDuringGroup() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
        val r = runner(liveFakes() + (NodeTypes.VISION_EXTRACT to pausedExtract(started, gate)))
        val a = DefaultPipelines.liveExtract()
        beginAb(r, a, variantB(a), frames(2))
        withTimeout(10_000) { started.await() }
        try { begin(r, a, frames(1)); fail() } catch (e: CanvasRunner.Busy) { assertEquals("another run is in progress", e.message) }
        try { beginAb(r, a, variantB(a), frames(1)); fail() } catch (e: CanvasRunner.Busy) { assertEquals("another run is in progress", e.message) }
        gate.complete(Unit); finish(r)
        assertNotNull(begin(r, a, frames(1))); finish(r)
    }

    @Test fun abBNotStartedWhenACancelled() = runBlocking<Unit> {
        val order = mutableListOf<String>()
        lateinit var r: CanvasRunner
        r = runner(liveFakes(onExtractChunk = { r.cancel(r.active.value!!) }), context = { id -> order += id; idleContext(id) })
        val a = DefaultPipelines.liveExtract()
        val st = beginAb(r, a, variantB(a), frames(4)); finish(r)
        assertEquals(listOf(st.runA), order)           // B never started
        assertEquals("CANCELLED", dao.run(st.runA)?.status)
        assertEquals("CANCELLED", dao.run(st.runB)?.status)
        assertNotNull(dao.run(st.runB)?.finishedAt)
        assertTrue(dao.items(st.runB).isEmpty())
    }

    @Test fun cancelBetweenAAndBCancelsB() = runBlocking<Unit> {
        val order = mutableListOf<String>()
        lateinit var r: CanvasRunner
        var st: AbStarted? = null
        val lastNode = fake(NodeTypes.MEMORY_WRITE, onChunk = { if (st != null) r.cancel(st!!.runB) }) { _, it -> NodeResult(it.frameId, CanvasValue.Preview(emptyList())) }
        val started = CompletableDeferred<Unit>()
        r = runner(liveFakes() + (NodeTypes.MEMORY_WRITE to lastNode), context = { id -> order += id; started.complete(Unit); idleContext(id) })
        val a = DefaultPipelines.liveExtract()
        val e = abOk(r, a, variantB(a), frames(1))
        // The cancel arrives in A's very last chunk, so A ends DONE and the group is already cancelled when B would start.
        st = r.startAb(spec(a, variantB(a)), frames(1), e.confirmToken, scope)
        finish(r)
        val s = st!!
        assertEquals(listOf(s.runA), order)
        assertEquals("CANCELLED", dao.run(s.runB)?.status)
        assertTrue(dao.items(s.runB).isEmpty())
    }

    @Test fun cancelGroupCancelsBothQueuedAndRunning() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
        val r = runner(liveFakes() + (NodeTypes.VISION_EXTRACT to pausedExtract(started, gate)))
        val a = DefaultPipelines.liveExtract()
        val st = beginAb(r, a, variantB(a), frames(2))
        withTimeout(10_000) { started.await() }
        assertTrue(r.cancelGroup(st.group))
        gate.complete(Unit); finish(r)
        assertEquals("CANCELLED", dao.run(st.runB)?.status)
        assertEquals("CANCELLED", dao.run(st.runA)?.status)
        assertFalse(r.cancelGroup(st.group)) // nothing left to cancel
    }

    @Test fun recoveryCancelsQueuedB() = runBlocking<Unit> {
        fun row(id: String, status: String, group: String?) = CanvasRunEntity(id, "p", null, "h", "{}", status, 1, 1, 0, 0, 1, null, "[]", group)
        dao.insertRun(row("a", "RUNNING", "g")); dao.insertRun(row("b", "QUEUED", "g")); dao.insertRun(row("c", "RUNNING", null)); dao.insertRun(row("d", "DONE", "g"))
        assertEquals(3, runner(liveFakes()).recoverAtStart())
        assertEquals(listOf("CANCELLED", "CANCELLED", "INTERRUPTED", "DONE"), listOf("a", "b", "c", "d").map { dao.run(it)!!.status })
    }

    @Test fun abEstimateSumsAndRefusesOver200() = runBlocking<Unit> {
        val r = runner(liveFakes()); val a = DefaultPipelines.liveExtract()
        val e = abOk(r, a, variantB(a), frames(7))
        assertEquals(7, e.photos)
        assertEquals(2 * (3 + 7), e.nominal)
        assertEquals(2 * (3 * 2 + 7 * 2), e.max)
        assertEquals(listOf("extract", "critic", "extract", "critic"), e.models.map { it.nodeId })
        val heavy = runner(liveFakes() + fake(NodeTypes.CRITIC, nominal = { it * 3 }) { _, it -> NodeResult(it.frameId, it.value, branch = "pass") }.let { it.type to it })
        assertTrue(heavy.estimate(a, frames(40), noLabel) is Estimate.Ok) // one side alone fits (14 + 120)
        assertEquals(listOf("more than 200 model calls"), (heavy.estimateAb(a, variantB(a), frames(40), noLabel) as Estimate.Refused).reasons)
        assertEquals(listOf("arm A: more than 50 photos", "arm B: more than 50 photos"), (r.estimateAb(a, variantB(a), frames(51), noLabel) as Estimate.Refused).reasons)
        assertEquals(listOf("arm B: no photos"), (r.estimateAb(a, variantB(a), frames(2), emptyList(), noLabel) as Estimate.Refused).reasons)
        val bad = a.copy(edges = a.edges.drop(1))
        assertTrue("pipeline has problems" in (r.estimateAb(a, bad, frames(1), noLabel) as Estimate.Refused).reasons)
    }

    @Test fun abTokenBoundToBothHashes() = runBlocking<Unit> {
        val r = runner(liveFakes()); val a = DefaultPipelines.liveExtract(); val b = variantB(a)
        val f = frames(2)
        fun refused(s: AbSpec, t: String, frames: List<String> = f) = runBlocking { try { r.startAb(s, frames, t, scope); false } catch (e: IllegalArgumentException) { e.message == "bad_token" } }
        assertTrue(refused(spec(a, a), abOk(r, a, b, f).confirmToken))      // B swapped for A
        assertTrue(refused(spec(b, a), abOk(r, a, b, f).confirmToken))      // sides swapped
        try { r.startAb(spec(a, b), frames(3), abOk(r, a, b, f).confirmToken, scope); fail() } catch (e: CanvasRunner.AbArmMoved) { assertEquals("A", e.arm) } // the same pipelines over other frames: typed, naming the arm
        assertTrue(refused(spec(a, b), ok(r, a, f).confirmToken))           // a plain run's token
        try { r.start(a, a.id, null, f, abOk(r, a, b, f).confirmToken, scope); fail() } catch (e: IllegalArgumentException) { assertEquals("bad_token", e.message) }
        assertTrue(dao.runs(10).isEmpty())
        val st = beginAb(r, a, b, f); finish(r)
        assertEquals("DONE", dao.run(st.runB)?.status)
    }

    @Test fun abArmsRunOverTheirOwnFramesAndTheEstimatePricesEachSeparately() = runBlocking<Unit> {
        val r = runner(liveFakes()); val a = DefaultPipelines.liveExtract(); val b = variantB(a)
        val fa = frames(2); val fb = frames(5)
        val e = r.estimateAb(a, b, fa, fb, noLabel) as Estimate.Ok
        assertEquals(listOf(2, 5), e.arms.map { it.photos })
        assertEquals(5, e.photos) // distinct frames of both arms
        assertEquals(e.nominal, e.arms.sumOf { it.nominal })
        assertTrue("A priced over fewer frames than B", e.arms[0].nominal < e.arms[1].nominal)
        val st = r.startAb(spec(a, b), fa, fb, e.confirmToken, scope); finish(r)
        assertEquals(fa.toSet(), dao.items(st.runA).map { it.frameId }.toSet())
        assertEquals(fb.toSet(), dao.items(st.runB).map { it.frameId }.toSet())
        // an empty arm at the start is typed and spends the token
        val e2 = r.estimateAb(a, b, fa, fb, noLabel) as Estimate.Ok
        try { r.startAb(spec(a, b), fa, emptyList(), e2.confirmToken, scope); fail() } catch (x: CanvasRunner.AbArmEmpty) { assertEquals("B", x.arm) }
        try { r.startAb(spec(a, b), fa, fb, e2.confirmToken, scope); fail() } catch (x: IllegalArgumentException) { assertEquals("bad_token", x.message) }
    }

    @Test fun pruneKeepsActiveGroup() = runBlocking<Unit> {
        for (i in 1..25) dao.insertRun(CanvasRunEntity("old$i", "p", null, "h", "{}", "DONE", 1, 1, 0, 0, i.toLong(), i + 1L, "[]"))
        val r = runner(liveFakes()); val a = DefaultPipelines.liveExtract()
        val st = beginAb(r, a, variantB(a), frames(1)); finish(r)
        awaitPruned()
        val kept = dao.runs(100).map { it.runId }
        assertEquals(20, kept.size)
        assertTrue(st.runA in kept && st.runB in kept)
        assertFalse("old1" in kept); assertTrue("old19" in kept)
    }

    // ---- Task 7: token estimate (S32) ----

    private fun usageRow(run: String, frame: String, node: String, p: Int, c: Int, calls: Int) =
        CanvasRunItemEntity(run, frame, node, "OK", null, "{}", null, 5, p, c, "gpt", null, "$run:$node:$frame", calls)

    @Test fun tokenEstimateFromHistory() = runBlocking<Unit> {
        val past = Pipeline(1, "old", "old", listOf(PipelineNode("ex_old", NodeTypes.VISION_EXTRACT, "x", JsonObject(emptyMap()))), emptyList())
        dao.insertRun(CanvasRunEntity("hist", "old", null, "h", PipelineJson.canonical(past), "DONE", 2, 2, 2, 0, 1, 2, "[]"))
        dao.upsertItem(usageRow("hist", "a", "ex_old", 300, 30, 1)); dao.upsertItem(usageRow("hist", "b", "ex_old", 300, 30, 1))
        dao.upsertItem(usageRow("hist", "a", "critic", 50, 5, 1)) // the current pipeline's own node id
        val e = ok(runner(liveFakes()), DefaultPipelines.liveExtract(), frames(7))
        // extract: 3 nominal calls x (300, 30); critic: 7 x (50, 5)
        assertEquals(TokenEstimate(3 * 300 + 7 * 50, 3 * 30 + 7 * 5, 3), e.tokens)
    }

    @Test fun tokenEstimateNullWithoutHistory() = runBlocking<Unit> {
        assertNull(ok(runner(liveFakes()), DefaultPipelines.liveExtract(), frames(7)).tokens)
        assertNull(abOk(runner(liveFakes()), DefaultPipelines.liveExtract(), variantB(DefaultPipelines.liveExtract()), frames(2)).tokens)
    }

    // ---- Task 7: usage per call (S32), through the real executors ----

    private var providerCalls = 0
    private fun tokenContext(script: (String, Int) -> String): (String) -> RunContext {
        providerCalls = 0
        val ai = AiGraph(MemoryGraph.get(app), InMemoryKeyVault(), InMemorySeedState(), ProviderBuilder { _, _, _ ->
            object : ModelProvider {
                override suspend fun generate(prompt: String) = error("unused")
                override suspend fun complete(request: ModelRequest): ModelReply { val n = ++providerCalls; return ModelReply(script(request.prompt, n), 100 + n, 10 + n) }
            }
        }).also { AiGraph.resetForTests(it); TestAi.bindAll(it) }
        return { id -> RunContext(id, ai.runner, ai.router, ai.prompts, ai.persona, { role -> ai.bindings.get(role) }, liveness = com.rokid.context.orchestration.AllLive) }
    }
    private fun sent(p: String) = Regex("frame_id: (f\\d)").findAll(p).map { it.groupValues[1] }.toList().distinct()
    private fun good(p: String) = sent(p).let { ids ->
        """{"facts":[${ids.joinToString(",") { """{"text":"Mug in $it","type":"object","confidence":0.99,"tags":[],"source_frame_ids":["$it"]}""" }}],"captions":[${ids.joinToString(",") { """{"frame_id":"$it","caption":"Counter $it"}""" }}]}"""
    }
    private fun extractRows(runId: String) = runBlocking { dao.items(runId).filter { it.nodeId == "extract" } }

    @Test fun threePhotoBatchCountsTokensOnce() = runBlocking<Unit> {
        val run = runAll(runner(Executors.all(), tokenContext { p, _ -> good(p) }), DefaultPipelines.liveExtract(), frames(3))
        val rows = extractRows(run.runId)
        assertEquals(3, rows.size)
        assertEquals(101, rows.sumOf { it.promptTokens ?: 0 }); assertEquals(11, rows.sumOf { it.completionTokens ?: 0 })
        assertEquals(1, rows.sumOf { it.calls ?: 0 })
        assertEquals(1, rows.count { it.calls != null })
        assertEquals(1, rows.mapNotNull { it.batchId }.distinct().size)
        assertEquals("gpt", rows.single { it.calls != null }.model)
        val m = dao.tokenMeans(listOf("extract"), 10)
        assertEquals(1, m.n); assertEquals(101.0, m.p!!, 0.0)
    }

    @Test fun forgettingOnePhotoOfAMultiSourceBatchDeletesEveryDownstreamRowOfTheOther() = runBlocking<Unit> {
        // One provider call (batch "B1") produced a fact from f1 AND f2; the trust and preview rows of f2 are derived from it, so Forget of f1 must reach them (S25 lineage).
        val ex = linkedMapOf<String, NodeExecutor>().apply {
            putAll(liveFakes())
            put(NodeTypes.VISION_EXTRACT, fake(NodeTypes.VISION_EXTRACT, chunk = 3, nominal = { 1 }) { _, it ->
                NodeResult(it.frameId, extraction(it.frameId), calls = 1, meta = CallMeta(5, null, null, null, null, "B1"))
            })
        }
        val run = runAll(runner(ex), DefaultPipelines.liveExtract(), listOf("f1", "f2"))
        val before = dao.items(run.runId)
        val downstream = before.filter { it.frameId == "f2" && it.nodeId != "photos" && it.outputJson != null }
        assertTrue(downstream.isNotEmpty()); assertTrue("downstream rows keep the batch lineage", downstream.all { it.batchId!!.split('|').first() == "B1" || "B1" in it.batchId!!.split('|') })
        dao.deleteCanvasOutputsForFrames(listOf("f1"))
        assertTrue("nothing derived from the batch survives", dao.items(run.runId).none { it.frameId == "f2" && it.batchId == "B1" })
    }

    @Test fun forgettingAPhotoAlsoRemovesTheUnsureNeighbourRowsAfterThePerFrameCritic() = runBlocking<Unit> {
        // f2 is unsure, so it goes through the (real-shaped) critic whose batch is PER FRAME and used to overwrite the shared extract batch (S25 lineage)
        val ex = linkedMapOf<String, NodeExecutor>().apply {
            putAll(liveFakes(unsure = setOf("f2")))
            put(NodeTypes.VISION_EXTRACT, fake(NodeTypes.VISION_EXTRACT, chunk = 3, nominal = { 1 }) { _, it ->
                NodeResult(it.frameId, extraction(it.frameId), calls = 1, meta = CallMeta(5, null, null, null, null, "B1"))
            })
            put(NodeTypes.CRITIC, fake(NodeTypes.CRITIC, nominal = { it }) { _, it ->
                NodeResult(it.frameId, it.value, branch = "pass", calls = 1, meta = CallMeta(5, null, null, null, null, "critic:" + it.frameId, calls = 1))
            })
        }
        val run = runAll(runner(ex), DefaultPipelines.liveExtract(), listOf("f1", "f2"))
        val f2Nodes = dao.items(run.runId).filter { it.frameId == "f2" }.map { it.nodeId }.toSet()
        assertTrue("the critic ran for the unsure frame", "critic" in f2Nodes && "write" in f2Nodes)
        dao.deleteCanvasOutputsForFrames(listOf("f1"))
        val left = dao.items(run.runId).filter { it.frameId == "f2" && it.outputJson != null && it.nodeId != "photos" }
        assertTrue("rows of f2 derived from the shared call survive: " + left.map { it.nodeId }, left.isEmpty())
    }

    @Test fun rejectedRetryCountedAsAttempt() = runBlocking<Unit> {
        val run = runAll(runner(Executors.all(), tokenContext { p, n -> if (n == 1) "not json" else good(p) }), DefaultPipelines.liveExtract(), frames(1))
        val owner = extractRows(run.runId).single()
        assertEquals(2, owner.calls)
        assertEquals(101 + 102, owner.promptTokens)
    }

    @Test fun singlePhotoFallbackAttemptsCounted() = runBlocking<Unit> {
        val run = runAll(runner(Executors.all(), tokenContext { p, _ -> if (sent(p).size > 1) """{"facts":[],"captions":[]}""" else good(p) }), DefaultPipelines.liveExtract(), frames(3))
        val rows = extractRows(run.runId)
        assertEquals(5, providerCalls)
        assertEquals(5, rows.sumOf { it.calls ?: 0 })
        assertEquals(101 + 102 + 103 + 104 + 105, rows.sumOf { it.promptTokens ?: 0 })
        val u = RunUsage.of(dao.items(run.runId))
        assertEquals(5, u.calls)
    }

    // ---- fix round 1 ----

    @Test fun queuedBIsBusyAndDeletedBNeverRuns() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val r = runner(liveFakes() + (NodeTypes.VISION_EXTRACT to pausedExtract(started, gate)), context = { id -> order += id; idleContext(id) })
        val a = DefaultPipelines.liveExtract()
        val st = beginAb(r, a, variantB(a), frames(2))
        withTimeout(10_000) { started.await() }
        assertTrue(r.isBusy(st.runA)); assertTrue(r.isBusy(st.runB)); assertFalse(r.isBusy("other"))
        dao.deleteRun(st.runB) // the row vanishes anyway (a delete that raced)
        gate.complete(Unit); finish(r)
        assertEquals(listOf(st.runA), order)            // B made no model call and built no context
        assertTrue(dao.items(st.runB).isEmpty())
        assertNull(dao.run(st.runB))
        assertFalse(r.isBusy(st.runB))
    }

    @Test fun forgetBetweenChunksDoesNotSendTheForgottenPhoto() = runBlocking<Unit> {
        var chunks = 0
        val r = runner(liveFakes(onExtractChunk = {
            if (++chunks == 1) runBlocking { MemoryGraph.get(app).db.forgetDao().insertTombstone(ForgetTombstoneEntity("f4", 1)) }
        }))
        val run = runAll(r, DefaultPipelines.liveExtract(), frames(4))
        assertEquals(1, chunks)                         // the second chunk (f4) was never sent
        assertEquals(listOf("f1", "f2", "f3"), seen["extract"]?.sorted())
        assertEquals("CANCELLED", run.status)
    }

    @Test fun wipeBetweenChunksStopsBeforeTheNextCall() = runBlocking<Unit> {
        var chunks = 0
        val r = runner(liveFakes(onExtractChunk = { if (++chunks == 1) gen++ }))
        val run = runAll(r, DefaultPipelines.liveExtract(), frames(4))
        assertEquals(1, chunks)
        assertEquals("CANCELLED", run.status)
    }

    @Test fun bStartsAtItsOwnTime() = runBlocking<Unit> {
        val lastNode = fake(NodeTypes.MEMORY_WRITE, onChunk = { now += 5_000 }) { _, it -> NodeResult(it.frameId, CanvasValue.Preview(emptyList())) }
        val r = runner(liveFakes() + (NodeTypes.MEMORY_WRITE to lastNode))
        val a = DefaultPipelines.liveExtract()
        val st = beginAb(r, a, variantB(a), frames(1)); finish(r)
        assertEquals(10_000L, dao.run(st.runA)!!.startedAt)
        assertTrue(dao.run(st.runB)!!.startedAt >= 15_000L)
    }

    // ---- Forget lifecycle M3 (T6): one read lease for the whole run ----

    private fun singletonExtract(onChunk: (List<CanvasItem>) -> Unit) = fake(NodeTypes.VISION_EXTRACT, chunk = 1, nominal = { it }, onChunk = onChunk) { _, it -> NodeResult(it.frameId, extraction(it.frameId), calls = 1) }

    /** Leak 4 (C1): a singleton flush used to check only its own photo's key, so text a batch call took from a forgotten PEER photo was written. The lease covers every photo of the run. */
    @Test fun aSingletonFlushAfterAPeerPhotoWasForgottenWritesNothing() = runBlocking<Unit> {
        var first = true
        val r = runner(liveFakes() + (NodeTypes.VISION_EXTRACT to singletonExtract { if (first) { first = false; runBlocking { MemoryGraph.get(app).db.forgetDao().insertTombstone(ForgetTombstoneEntity("f3", 1)) } } }))
        val id = begin(r, DefaultPipelines.liveExtract(), frames(3)); finish(r)
        assertTrue("f1's own row was not forgotten, yet nothing derived is written", dao.items(id).none { it.nodeId != "photos" })
        assertEquals("CANCELLED", dao.run(id)?.status)
        assertEquals(0, com.rokid.context.orchestration.DataOpGate.shared.leases.openCount)
    }

    /** Leak 5 on a run: a Forget that reaches a photo of the run (a fact Forget revokes without tombstoning it) cancels the run before its next write. */
    @Test fun aLeaseRevokedMidRunCancelsTheRunAndWritesNothingFurther() = runBlocking<Unit> {
        var first = true
        val r = runner(liveFakes() + (NodeTypes.VISION_EXTRACT to singletonExtract { if (first) { first = false; com.rokid.context.orchestration.DataOpGate.shared.leases.revoke(com.rokid.context.memory.forget.lifecycle.Leases.photos(listOf("f2"))) } }))
        val id = begin(r, DefaultPipelines.liveExtract(), frames(3)); finish(r)
        assertTrue(dao.items(id).none { it.nodeId != "photos" })
        assertEquals("CANCELLED", dao.run(id)?.status)
    }

    @Test fun aForgetThatReachesNothingOfTheRunLeavesItAlone() = runBlocking<Unit> {
        var first = true
        val r = runner(liveFakes(onExtractChunk = { if (first) { first = false; com.rokid.context.orchestration.DataOpGate.shared.leases.revoke(com.rokid.context.memory.forget.lifecycle.Leases.photos(listOf("other"))) } }))
        val run = runAll(r, DefaultPipelines.liveExtract(), frames(3))
        assertEquals("DONE", run.status); assertEquals(3, dao.items(run.runId).count { it.nodeId == "extract" })
    }
}
