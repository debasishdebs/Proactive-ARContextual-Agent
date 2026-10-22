package com.rokid.context.canvas

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.InMemoryKeyVault
import com.rokid.context.ai.InMemorySeedState
import com.rokid.context.ai.ProviderBuilder
import com.rokid.context.ai.canvas.DefaultPipelines
import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.canvas.Pipeline
import com.rokid.context.ai.canvas.PipelineEdge
import com.rokid.context.ai.canvas.PipelineJson
import com.rokid.context.ai.canvas.PipelineNode
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.CanvasDao
import com.rokid.context.memory.db.CanvasLiveTraceEntity
import com.rokid.context.memory.db.CanvasPipelineVersionEntity
import com.rokid.context.memory.db.CanvasPublishEntity
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.ForgetTombstoneEntity
import com.rokid.context.model.CallClass
import com.rokid.context.model.CallGate
import com.rokid.context.model.ModelImageInput
import com.rokid.context.model.ModelProvider
import com.rokid.context.orchestration.ExtractPhoto
import com.rokid.context.orchestration.ExtractSave
import com.rokid.context.orchestration.MemoryWipe
import com.rokid.context.orchestration.TestAi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LiveExtractTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val prompts = CopyOnWriteArrayList<String>()
    private val classes = CopyOnWriteArrayList<CallClass>()
    private val recorded = CopyOnWriteArrayList<Triple<String, String, Float?>>()
    private var gateHook: suspend (Int) -> Unit = {}
    private val permits = AtomicInteger(0)
    private var now = 1_000L
    private var devMode = true
    private lateinit var ai: AiGraph
    private lateinit var dao: CanvasDao

    @Before fun setUp() { MemoryGraph.resetForTests(DatabaseFactory.inMemory(app)); dao = MemoryGraph.get(app).db.canvasDao() }
    @After fun tearDown() { AiGraph.resetForTests(null); MemoryGraph.get(app).db.close(); MemoryGraph.resetForTests(null) }

    private fun photo(id: String) = ExtractPhoto(id, "ts=1", ModelImageInput("image/jpeg", "AAAA"))
    private fun photos(vararg ids: String) = ids.map(::photo)
    private fun sentIds(p: String) = Regex("frame_id: (ex\\d+)").findAll(p).map { it.groupValues[1] }.toList().distinct()
    private fun extractReply(p: String): String {
        val ids = sentIds(p)
        return """{"facts":[${ids.joinToString(",") { """{"text":"Mug in $it","type":"object","confidence":0.9,"tags":[],"source_frame_ids":["$it"]}""" }}],""" +
            """"captions":[${ids.joinToString(",") { """{"frame_id":"$it","caption":"Counter $it"}""" }}]}"""
    }
    private fun isIntent(p: String) = "INTENT" in p

    private fun graph(reply: suspend (String) -> String) {
        val gate = object : CallGate {
            override suspend fun <T> withPermit(callClass: CallClass, block: suspend () -> T): T { classes += callClass; gateHook(permits.incrementAndGet()); return block() }
        }
        ai = AiGraph(MemoryGraph.get(app), InMemoryKeyVault(), InMemorySeedState(), ProviderBuilder { _, _, _ ->
            object : ModelProvider { override suspend fun generate(prompt: String): String { prompts += prompt; return reply(prompt) } }
        }, gate = gate).also { AiGraph.resetForTests(it) }
        TestAi.bindAll(ai)
    }

    private fun deps(sink: FrameIntentSink = FrameIntentSink { f, l, c, _ -> recorded += Triple(f, l, c) }, deadlineMs: Long = 20_000L) = LiveDeps(
        dao = dao, runner = ai.runner,
        context = { runId, cc -> RunContext(runId, ai.runner, ai.router, ai.prompts, ai.persona, { r -> ai.bindings.get(r) }, callClass = cc) },
        interpreter = PipelineInterpreter(clock = { now }), sink = sink,
        publisher = { id, reason -> CanvasPublisher.autoRollbackOn(dao, { now }, id, reason) },
        shadowAllowed = { devMode }, clock = { now }, deadlineMs = deadlineMs,
    )

    private fun node(type: String, id: String, params: JsonObject = JsonObject(emptyMap())) = PipelineNode(id, type, id, params)
    private fun extractNode(id: String, template: String? = null) = node(NodeTypes.VISION_EXTRACT, id, buildJsonObject {
        put("batch", 3)
        putJsonObject("prompt") { if (template == null) put("source", "live") else { put("source", "draft"); put("template", template); put("schema", JsonPrimitive(null as String?)) } }
    })
    private val intentSchema = """{"type":"object","properties":{"activity":{"type":"string"},"confidence":{"type":"number"}},"required":["activity"],"additionalProperties":false}"""

    /** photos -> intent -> route -> {driving: X1, computer_work: X2, other: live-prompt extract}. */
    private fun intentPipeline() = Pipeline(
        PipelineJson.SCHEMA, "intent-p", "Intent",
        listOf(
            node(NodeTypes.INPUT_PHOTOS, "photos"),
            node(NodeTypes.PROMPT, "intent", buildJsonObject { put("role", "VISION_EXTRACT"); put("name", "intent"); put("images", true); put("template", "INTENT {{recent_context}}"); put("schema", intentSchema) }),
            node(NodeTypes.SWITCH, "route", buildJsonObject { put("field", "activity"); put("cases", JsonArray(listOf(JsonPrimitive("driving"), JsonPrimitive("computer_work")))) }),
            extractNode("ex_driving", "DRIVING_X {{recent_context}} {{context_sources}}"), extractNode("ex_desk", "DESK_X {{recent_context}} {{context_sources}}"), extractNode("ex_other"),
        ),
        listOf(PipelineEdge("photos", "intent"), PipelineEdge("intent", "route"),
            PipelineEdge("route", "ex_driving", "driving"), PipelineEdge("route", "ex_desk", "computer_work"), PipelineEdge("route", "ex_other", "other")),
    )

    private fun intentReply(p: String): String? = when {
        !isIntent(p) -> null
        "ex1" in p -> """{"activity":"driving","confidence":0.8}"""
        "ex2" in p -> """{"activity":"computer_work"}"""
        else -> """{"activity":"sleeping","confidence":0.4}"""
    }
    private fun intentGraph() = graph { p -> intentReply(p) ?: extractReply(p) }

    private suspend fun publish(p: Pipeline, mode: LiveMode, json: String? = null): Long {
        dao.insertVersion(CanvasPipelineVersionEntity(p.id, 1, json ?: PipelineJson.encode(p), "h-${p.id}", "n", 1))
        return dao.insertPublish(CanvasPublishEntity(pipelineId = p.id, version = 1, hash = "h-${p.id}", publishedAt = 1, rolledBackAt = null,
            snapshotJson = "{}", kind = CanvasPublisher.KIND_INTERPRETER, mode = mode.name))
    }
    private val extractCalls get() = prompts.count { !isIntent(it) }
    private val intentCalls get() = prompts.count(::isIntent)
    private suspend fun allTraces(pid: Long) = dao.traces(pid, 1_000)

    // ---- (a) no publish: the seam is the old call ----

    @Test fun noPublishIsExactlyExtractSave() = runBlocking<Unit> {
        graph { extractReply(it) }
        val three = photos("ex1", "ex2", "ex3")
        val direct = ExtractSave.extract(ai.runner, three, CallClass.EXTRACT)
        val directPrompts = prompts.toList(); prompts.clear(); classes.clear()
        val viaSeam = LiveExtract.batch(deps(), three, CallClass.EXTRACT)
        assertEquals(directPrompts, prompts.toList())
        assertEquals(listOf(CallClass.EXTRACT), classes.toList())
        assertEquals(direct, viaSeam.batch)
        assertSame(LiveEffects.None, viaSeam.effects)
        LiveExtract.commit(deps(), viaSeam.effects, MemoryWipe.generation.get(), setOf("ex1", "ex2", "ex3"))
        assertTrue(dao.tracesForFrame("ex1").isEmpty())
        assertTrue(recorded.isEmpty())
    }

    @Test fun rolledBackPublishNoLongerAffectsTheSeam() = runBlocking<Unit> {
        intentGraph()
        val pid = publish(intentPipeline(), LiveMode.LIVE)
        assertEquals(1, dao.markRolledBack(pid, 5))
        val out = LiveExtract.batch(deps(), photos("ex1", "ex2"), CallClass.EXTRACT)
        assertEquals(0, intentCalls); assertEquals(1, extractCalls)
        assertSame(LiveEffects.None, out.effects)
    }

    // ---- modes ----

    @Test fun shadowModeBatchIsLive() = runBlocking<Unit> {
        intentGraph()
        publish(intentPipeline(), LiveMode.SHADOW)
        val three = photos("ex1", "ex2", "ex3")
        val out = LiveExtract.batch(deps(), three, CallClass.EXTRACT)
        assertEquals(0, intentCalls); assertEquals(1, extractCalls)
        assertTrue(out.effects.shadow); assertTrue(out.effects.traces.isEmpty()); assertTrue(out.effects.labels.isEmpty()); assertFalse(out.effects.fellBack)
        assertEquals(listOf("Mug in ex1", "Mug in ex2", "Mug in ex3"), out.batch.output.facts.map { it.text })
    }

    @Test fun liveModeUsesInterpreter() = runBlocking<Unit> {
        intentGraph()
        val pid = publish(intentPipeline(), LiveMode.LIVE)
        val out = LiveExtract.batch(deps(), photos("ex1", "ex2", "ex3"), CallClass.EXTRACT)
        assertEquals(3, intentCalls)
        assertTrue(prompts.any { "DRIVING_X" in it } && prompts.any { "DESK_X" in it })
        assertEquals(listOf("Mug in ex1", "Mug in ex2", "Mug in ex3"), out.batch.output.facts.map { it.text })
        assertFalse(out.effects.fellBack)
        assertEquals(pid, out.effects.publishId)
        assertEquals(mapOf("ex1" to ("driving" to 0.8f), "ex2" to ("computer_work" to null), "ex3" to ("other" to 0.4f)), out.effects.labels)
        assertTrue(classes.all { it == CallClass.EXTRACT })
        assertTrue("nothing is written before the save", allTraces(pid).isEmpty())
    }

    // ---- (b) merge equivalence ----

    @Test fun liveModeWithTheIdentityPipelineEqualsDirectExtraction() = runBlocking<Unit> {
        graph { extractReply(it) }
        val three = photos("ex1", "ex2", "ex3")
        val direct = ExtractSave.extract(ai.runner, three, CallClass.EXTRACT)
        prompts.clear()
        publish(DefaultPipelines.liveExtract(), LiveMode.LIVE)
        val out = LiveExtract.batch(deps(), three, CallClass.EXTRACT)
        assertEquals(1, prompts.size)
        assertEquals(direct.output.facts, out.batch.output.facts)
        assertEquals(direct.output.captions, out.batch.output.captions)
        assertEquals(direct.output.provenance, out.batch.output.provenance)
    }

    // ---- (c) failure, timeout, transient ----

    @Test fun failedFallsBackToLive() = runBlocking<Unit> {
        graph { p -> if (isIntent(p)) "not json at all" else extractReply(p) }
        val pid = publish(intentPipeline(), LiveMode.LIVE)
        val out = LiveExtract.batch(deps(), photos("ex1", "ex2", "ex3"), CallClass.EXTRACT)
        assertEquals(listOf("Mug in ex1", "Mug in ex2", "Mug in ex3"), out.batch.output.facts.map { it.text })
        assertEquals("exactly one live extraction call", 1, extractCalls)
        assertTrue(out.effects.fellBack); assertTrue("labels are discarded", out.effects.labels.isEmpty())
        LiveExtract.commit(deps(), out.effects, MemoryWipe.generation.get(), setOf("ex1", "ex2", "ex3"))
        val batchRows = allTraces(pid).filter { it.nodeId == "_batch" }
        assertEquals(setOf("ex1", "ex2", "ex3"), batchRows.map { it.frameId }.toSet())
        assertTrue(batchRows.all { it.status == "ERROR" })
        assertTrue("fallbackDiscardsLabels", recorded.isEmpty())
    }

    @Test fun retryableErrorPropagatesNoFallback() = runBlocking<Unit> {
        graph { p -> if (isIntent(p)) throw IOException("offline") else extractReply(p) }
        val pid = publish(intentPipeline(), LiveMode.LIVE)
        try {
            LiveExtract.batch(deps(), photos("ex1", "ex2"), CallClass.EXTRACT)
            fail("expected the transient failure to propagate")
        } catch (e: Exception) {
            assertTrue(generateSequence<Throwable>(e) { it.cause }.any { it is IOException })
        }
        assertEquals("no fallback call", 0, extractCalls)
        assertTrue(allTraces(pid).isEmpty())
    }

    @Test fun fallbackThatFailsPropagatesLikeLive() = runBlocking<Unit> {
        graph { p -> if (isIntent(p)) "garbage" else throw IOException("offline") }
        publish(intentPipeline(), LiveMode.LIVE)
        try { LiveExtract.batch(deps(), photos("ex1"), CallClass.EXTRACT); fail("expected the live failure") }
        catch (e: Exception) { assertTrue(generateSequence<Throwable>(e) { it.cause }.any { it is IOException }) }
    }

    @Test fun versionDecodeFailureFallsBack() = runBlocking<Unit> {
        graph { extractReply(it) }
        val pid = publish(intentPipeline(), LiveMode.LIVE, json = "{ definitely not a pipeline")
        val out = LiveExtract.batch(deps(), photos("ex1", "ex2"), CallClass.EXTRACT)
        assertEquals(1, extractCalls)
        assertTrue(out.effects.fellBack)
        LiveExtract.commit(deps(), out.effects, MemoryWipe.generation.get(), setOf("ex1", "ex2"))
        assertEquals(listOf("internal", "internal"), allTraces(pid).map { it.error })
    }

    @Test fun missingVersionFallsBack() = runBlocking<Unit> {
        graph { extractReply(it) }
        dao.insertPublish(CanvasPublishEntity(pipelineId = "gone", version = 9, hash = "h", publishedAt = 1, rolledBackAt = null, snapshotJson = "{}", kind = "interpreter", mode = "LIVE"))
        val out = LiveExtract.batch(deps(), photos("ex1"), CallClass.EXTRACT)
        assertTrue(out.effects.fellBack); assertEquals(1, extractCalls)
    }

    @Test fun slowProviderHitsDeadlineAndFallsBack() = runBlocking<Unit> {
        graph { p -> if (isIntent(p)) { delay(30_000); intentReply(p)!! } else extractReply(p) }
        val pid = publish(intentPipeline(), LiveMode.LIVE)
        val started = System.nanoTime()
        val out = LiveExtract.batch(deps(deadlineMs = 300), photos("ex1", "ex2", "ex3"), CallClass.EXTRACT)
        assertTrue("took the deadline, not the provider", (System.nanoTime() - started) / 1_000_000 < 10_000)
        assertEquals(1, extractCalls)
        assertTrue(out.effects.fellBack)
        val direct = ExtractSave.extract(ai.runner, photos("ex1", "ex2", "ex3"), CallClass.EXTRACT)
        assertEquals(direct.output.facts, out.batch.output.facts); assertEquals(direct.output.captions, out.batch.output.captions)
        LiveExtract.commit(deps(), out.effects, MemoryWipe.generation.get(), setOf("ex1", "ex2", "ex3"))
        assertEquals(listOf("deadline", "deadline", "deadline"), allTraces(pid).filter { it.nodeId == "_batch" }.map { it.error })
        assertEquals(3, allTraces(pid).size)
    }

    @Test fun budgetWaitCountsAgainstDeadline() = runBlocking<Unit> {
        gateHook = { n -> if (n == 1) delay(30_000) } // the first permit (the first intent call) waits for budget
        intentGraph()
        publish(intentPipeline(), LiveMode.LIVE)
        val out = LiveExtract.batch(deps(deadlineMs = 300), photos("ex1", "ex2"), CallClass.EXTRACT)
        assertTrue(out.effects.fellBack)
        assertEquals(1, extractCalls)
        assertEquals("only the live extraction was ever sent", 0, intentCalls)
    }

    @Test fun deadlineIsNotCancellation() = runBlocking<Unit> {
        // an outer cancellation still propagates and starts no fallback call
        val reached = CompletableDeferred<Unit>()
        graph { p -> if (isIntent(p)) { reached.complete(Unit); delay(60_000); intentReply(p)!! } else extractReply(p) }
        publish(intentPipeline(), LiveMode.LIVE)
        val job = async { LiveExtract.batch(deps(deadlineMs = 50_000), photos("ex1"), CallClass.EXTRACT) }
        withTimeout(20_000) { reached.await() }
        job.cancel()
        try { job.await(); fail("expected cancellation") } catch (c: CancellationException) { /* expected */ }
        assertEquals(0, extractCalls)
    }

    // ---- auto-rollback ----

    @Test fun autoRollbackAfterTenOfThirty() = runBlocking<Unit> {
        graph { p -> if (isIntent(p)) "garbage" else extractReply(p) }
        val pid = publish(intentPipeline(), LiveMode.LIVE)
        for (b in 1..10) {
            assertEquals("still live before batch $b", pid, dao.activeInterpreter()?.id)
            val ids = listOf("ex${b}1", "ex${b}2", "ex${b}3")
            val out = LiveExtract.batch(deps(), photos(*ids.toTypedArray()), CallClass.EXTRACT)
            LiveExtract.commit(deps(), out.effects, MemoryWipe.generation.get(), ids.toSet())
        }
        // evaluated inside commit, before it returned: no further batch can reach the interpreter
        assertNull(dao.activeInterpreter())
        val row = dao.publishHistory(5).single { it.id == pid }
        assertEquals("auto: 10 of 30 fell back", row.rollbackReason)
        assertTrue(row.rolledBackAt != null)
        val before = intentCalls
        LiveExtract.batch(deps(), photos("ex991"), CallClass.EXTRACT)
        assertEquals(before, intentCalls)
    }

    @Test fun noAutoRollbackBelowThirtyInterpretedPhotos() = runBlocking<Unit> {
        graph { p -> if (isIntent(p)) "garbage" else extractReply(p) }
        val pid = publish(intentPipeline(), LiveMode.LIVE)
        for (b in 1..9) {
            val ids = listOf("ex${b}1", "ex${b}2", "ex${b}3")
            LiveExtract.commit(deps(), LiveExtract.batch(deps(), photos(*ids.toTypedArray()), CallClass.EXTRACT).effects, MemoryWipe.generation.get(), ids.toSet())
        }
        assertEquals(pid, dao.activeInterpreter()?.id)
    }

    @Test fun healthyBatchesNeverAutoRollBack() = runBlocking<Unit> {
        intentGraph()
        val pid = publish(intentPipeline(), LiveMode.LIVE)
        for (b in 1..12) {
            val ids = listOf("ex${b}1", "ex${b}2", "ex${b}3")
            LiveExtract.commit(deps(), LiveExtract.batch(deps(), photos(*ids.toTypedArray()), CallClass.EXTRACT).effects, MemoryWipe.generation.get(), ids.toSet())
        }
        assertEquals(pid, dao.activeInterpreter()?.id)
        assertEquals(36, dao.interpretedCount(pid))
    }

    // ---- the intent sink ----

    @Test fun sinkCalledWithLabels() = runBlocking<Unit> {
        intentGraph()
        publish(intentPipeline(), LiveMode.LIVE)
        val ids = setOf("ex1", "ex2", "ex3")
        val out = LiveExtract.batch(deps(), photos("ex1", "ex2", "ex3"), CallClass.EXTRACT)
        LiveExtract.commit(deps(), out.effects, MemoryWipe.generation.get(), ids)
        assertEquals(setOf(Triple("ex1", "driving", 0.8f), Triple("ex2", "computer_work", null), Triple("ex3", "other", 0.4f)), recorded.toSet())
    }

    @Test fun sinkOnlyForSavedFrames() = runBlocking<Unit> {
        intentGraph()
        publish(intentPipeline(), LiveMode.LIVE)
        val out = LiveExtract.batch(deps(), photos("ex1", "ex2", "ex3"), CallClass.EXTRACT)
        LiveExtract.commit(deps(), out.effects, MemoryWipe.generation.get(), setOf("ex1"))
        assertEquals(listOf("ex1"), recorded.map { it.first })
    }

    @Test fun sinkErrorIgnored() = runBlocking<Unit> {
        intentGraph()
        val pid = publish(intentPipeline(), LiveMode.LIVE)
        val seen = AtomicInteger()
        val out = LiveExtract.batch(deps(), photos("ex1", "ex2"), CallClass.EXTRACT)
        LiveExtract.commit(deps(sink = FrameIntentSink { _, _, _, _ -> seen.incrementAndGet(); throw IllegalStateException("boom") }), out.effects, MemoryWipe.generation.get(), setOf("ex1", "ex2"))
        assertEquals("the other label still gets its call", 2, seen.get())
        assertTrue(allTraces(pid).isNotEmpty())
        assertEquals(listOf("Mug in ex1", "Mug in ex2"), out.batch.output.facts.map { it.text })
    }

    @Test fun traceDaoFailureKeepsExtraction() = runBlocking<Unit> {
        intentGraph()
        publish(intentPipeline(), LiveMode.LIVE)
        val out = LiveExtract.batch(deps(), photos("ex1", "ex2"), CallClass.EXTRACT)
        MemoryGraph.get(app).db.openHelper.writableDatabase.execSQL("DROP TABLE canvas_live_trace") // the trace commit throws from here on
        LiveExtract.commit(deps(), out.effects, MemoryWipe.generation.get(), setOf("ex1", "ex2")) // must not throw
        assertEquals(listOf("Mug in ex1", "Mug in ex2"), out.batch.output.facts.map { it.text })
        assertTrue("no label without a confirmed trace commit", recorded.isEmpty())
    }

    // ---- (d) Forget and wipe during the batch ----

    @Test fun forgetDuringLiveCallWritesNoTrace() = runBlocking<Unit> {
        val reached = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        graph { p -> if (isIntent(p)) intentReply(p)!! else { reached.complete(Unit); release.await(); extractReply(p) } }
        val pid = publish(intentPipeline(), LiveMode.LIVE)
        val gen = MemoryWipe.generation.get()
        val out = async { LiveExtract.batch(deps(), photos("ex1"), CallClass.EXTRACT) }
        withTimeout(20_000) { reached.await() }
        // what Forget leaves behind for a stored photo: its tombstone (a frame row is not needed to model the fence)
        MemoryGraph.get(app).db.forgetDao().insertTombstone(ForgetTombstoneEntity("ex1", 1_000))
        release.complete(Unit)
        val live = withTimeout(20_000) { out.await() }
        LiveExtract.commit(deps(), live.effects, gen, setOf("ex1"))
        assertTrue(allTraces(pid).isEmpty()); assertTrue(dao.tracesForFrame("ex1").isEmpty())
        assertTrue("no label for a forgotten frame", recorded.isEmpty())
    }

    @Test fun wipeDuringLiveCallWritesNoTraceAndNoLabel() = runBlocking<Unit> {
        intentGraph()
        val pid = publish(intentPipeline(), LiveMode.LIVE)
        val gen = MemoryWipe.generation.get()
        val live = LiveExtract.batch(deps(), photos("ex1", "ex2"), CallClass.EXTRACT)
        MemoryWipe.generation.incrementAndGet()
        LiveExtract.commit(deps(), live.effects, gen, setOf("ex1", "ex2"))
        assertTrue(allTraces(pid).isEmpty()); assertTrue(recorded.isEmpty())
    }

    @Test fun wipeDuringShadowWritesNoTrace() = runBlocking<Unit> {
        val reached = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        graph { p -> reached.complete(Unit); release.await(); extractReply(p) }
        val pid = publish(DefaultPipelines.liveExtract(), LiveMode.SHADOW)
        val d = deps(); val gen = MemoryWipe.generation.get()
        repeat(LiveExtract.SHADOW_EVERY - 1) { LiveExtract.shadow(d, photos("ex1"), gen) }
        val job = async { LiveExtract.shadow(d, photos("ex1"), gen) }
        withTimeout(20_000) { reached.await() }
        MemoryWipe.generation.incrementAndGet()
        release.complete(Unit)
        withTimeout(20_000) { job.await() }
        assertTrue(allTraces(pid).isEmpty())
    }

    // ---- shadow ----

    @Test fun shadowEveryFourthAsExperiment() = runBlocking<Unit> {
        graph { extractReply(it) }
        val pid = publish(DefaultPipelines.liveExtract(), LiveMode.SHADOW)
        val d = deps(); val gen = MemoryWipe.generation.get()
        repeat(8) { i -> LiveExtract.shadow(d, photos("ex${i}0"), gen) }
        assertEquals(2, prompts.size)
        assertTrue(classes.all { it == CallClass.EXPERIMENT })
        val rows = allTraces(pid)
        assertTrue(rows.isNotEmpty() && rows.all { it.mode == "SHADOW" })
        assertTrue(recorded.isEmpty())
    }

    @Test fun shadowSkippedWhenDevModeOff() = runBlocking<Unit> {
        graph { extractReply(it) }
        val pid = publish(DefaultPipelines.liveExtract(), LiveMode.SHADOW)
        devMode = false
        val d = deps()
        repeat(8) { i -> LiveExtract.shadow(d, photos("ex${i}0"), MemoryWipe.generation.get()) }
        assertTrue(prompts.isEmpty()); assertTrue(allTraces(pid).isEmpty())
    }

    @Test fun shadowNeverThrows() = runBlocking<Unit> {
        graph { throw IOException("offline") }
        publish(DefaultPipelines.liveExtract(), LiveMode.SHADOW)
        val d = deps()
        repeat(8) { i -> LiveExtract.shadow(d, photos("ex${i}0"), MemoryWipe.generation.get()) }
    }

    @Test fun shadowOnlyForAShadowPublish() = runBlocking<Unit> {
        graph { extractReply(it) }
        publish(DefaultPipelines.liveExtract(), LiveMode.LIVE)
        val d = deps()
        repeat(8) { i -> LiveExtract.shadow(d, photos("ex${i}0"), MemoryWipe.generation.get()) }
        assertTrue(prompts.isEmpty())
    }

    // ---- fix round 1 ----

    @Test fun shadowSkippedAfterWipeBeforeAnyModelCall() = runBlocking<Unit> {
        graph { extractReply(it) }
        val pid = publish(DefaultPipelines.liveExtract(), LiveMode.SHADOW)
        val d = deps(); val gen = MemoryWipe.generation.get()
        MemoryWipe.generation.incrementAndGet() // Delete all landed between the live save and the shadow run
        repeat(LiveExtract.SHADOW_EVERY) { LiveExtract.shadow(d, photos("ex1"), gen) }
        assertTrue(prompts.isEmpty()); assertTrue(allTraces(pid).isEmpty())
    }

    @Test fun shadowSkippedWhenAPhotoWasForgottenBeforeAnyModelCall() = runBlocking<Unit> {
        graph { extractReply(it) }
        val pid = publish(DefaultPipelines.liveExtract(), LiveMode.SHADOW)
        MemoryGraph.get(app).db.forgetDao().insertTombstone(ForgetTombstoneEntity("ex2", 1))
        val d = deps(); val gen = MemoryWipe.generation.get()
        repeat(LiveExtract.SHADOW_EVERY) { LiveExtract.shadow(d, photos("ex1", "ex2"), gen) }
        assertTrue(prompts.isEmpty()); assertTrue(allTraces(pid).isEmpty())
    }

    @Test fun contextFactoryThrowingFallsBackAsInternal() = runBlocking<Unit> {
        graph { extractReply(it) }
        val pid = publish(intentPipeline(), LiveMode.LIVE)
        val base = deps()
        val d = LiveDeps(dao, ai.runner, { _, _ -> throw IllegalStateException("no context") }, base.interpreter, base.sink, base.publisher, base.shadowAllowed, base.clock, base.deadlineMs)
        val out = LiveExtract.batch(d, photos("ex1", "ex2"), CallClass.EXTRACT)
        assertEquals(1, extractCalls); assertTrue(out.effects.fellBack)
        LiveExtract.commit(d, out.effects, MemoryWipe.generation.get(), setOf("ex1", "ex2"))
        assertEquals(listOf("internal", "internal"), allTraces(pid).map { it.error })
    }

    private fun row(pid: Long, frame: String, status: String, at: Long) =
        CanvasLiveTraceEntity(pid, frame, "_batch", "LIVE", status, null, null, if (status == "ERROR") "x" else null, null, at)
    private fun failEffects(pid: Long, vararg ids: String) =
        LiveEffects(pid, ids.map { row(pid, it, "ERROR", now) }, emptyMap(), true)

    @Test fun oldFailuresOutsideTheThirtyPhotoWindowDoNotRollBack() = runBlocking<Unit> {
        graph { extractReply(it) }
        val pid = publish(intentPipeline(), LiveMode.LIVE)
        dao.upsertTrace((1..20).map { row(pid, "old$it", "ERROR", it.toLong()) } + (1..30).map { row(pid, "ok$it", "OK", 100L + it) })
        LiveExtract.commit(deps(), failEffects(pid, "new1"), MemoryWipe.generation.get(), setOf("new1"))
        assertEquals(pid, dao.activeInterpreter()?.id)
        assertEquals(1, dao.recentFallbacks(pid, 30))
    }

    @Test fun windowOrderIsDeterministicWhenTimestampsTie() = runBlocking<Unit> {
        graph { extractReply(it) }
        val pid = publish(intentPipeline(), LiveMode.LIVE)
        // 12 failures inserted first, then 25 successes, ALL with the same timestamp: the 30-row window is the newest by insertion order
        dao.upsertTrace((1..12).map { row(pid, "e$it", "ERROR", 500) })
        dao.upsertTrace((1..25).map { row(pid, "g$it", "OK", 500) })
        LiveExtract.commit(deps(), failEffects(pid, "new1"), MemoryWipe.generation.get(), setOf("new1"))
        assertEquals("window = new failure + 25 OK + 4 older failures", 5, dao.recentFallbacks(pid, 30))
        assertEquals(pid, dao.activeInterpreter()?.id)
    }

    @Test fun aRetriedPhotoCountsOnce() = runBlocking<Unit> {
        graph { extractReply(it) }
        val pid = publish(intentPipeline(), LiveMode.LIVE)
        repeat(12) { LiveExtract.commit(deps(), failEffects(pid, "a", "b", "c"), MemoryWipe.generation.get(), setOf("a", "b", "c")) }
        assertEquals(3, dao.interpretedCount(pid)); assertEquals(3, dao.recentFallbacks(pid, 30))
        assertEquals(pid, dao.activeInterpreter()?.id)
    }
}
