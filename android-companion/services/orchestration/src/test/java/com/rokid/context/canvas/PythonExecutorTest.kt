package com.rokid.context.canvas

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.canvas.PipelineNode
import com.rokid.context.canvas.helper.ConfirmResult
import com.rokid.context.canvas.helper.HelperJobPort
import com.rokid.context.canvas.helper.InMemoryHelperJobPort
import com.rokid.context.canvas.helper.PendingConfirmation
import com.rokid.context.canvas.helper.PyItemOutput
import com.rokid.context.canvas.helper.PyOutcome
import com.rokid.context.canvas.helper.PyRequest
import com.rokid.context.canvas.helper.fenceAt
import com.rokid.context.canvas.helper.okResult
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.canvas.ConfirmBinding
import com.rokid.context.memory.canvas.HelperFence
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.model.CallClass
import com.rokid.context.model.ModelImageInput
import com.rokid.context.orchestration.ExtractPhoto
import com.rokid.context.orchestration.TestAi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
import java.io.File

/** WP8: the `py.fn` executor. A `py.fn` node always goes through a [HelperJobPort]; nothing here ever runs Python on the phone. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PythonExecutorTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val fence = fenceAt(5)
    private var fenceNow = fence

    @Before fun setUp() { MemoryGraph.resetForTests(DatabaseFactory.inMemory(app)) }
    @After fun tearDown() { AiGraph.resetForTests(null); MemoryGraph.get(app).db.close(); MemoryGraph.resetForTests(null) }

    private fun node(source: String = "def run(x):\n    return {'n': 1}\n", outSchema: String = "{\"type\":\"object\",\"required\":[\"n\"],\"properties\":{\"n\":{\"type\":\"integer\"}},\"additionalProperties\":false}") =
        PipelineNode("fn", NodeTypes.PY_FN, "fn", buildJsonObject { put("source", source); put("outSchema", outSchema) })

    private fun photo(id: String) = ExtractPhoto(id, "ts=1", ModelImageInput("image/jpeg", "AAAA"))
    private fun json(i: Int) = CanvasValue.Json(JsonObject(mapOf("text" to JsonPrimitive("t$i"))))
    private fun items(n: Int) = (0 until n).map { CanvasItem("frame-$it", photo("frame-$it"), json(it)) }
    private fun ctx(callClass: CallClass = CallClass.EXPERIMENT): RunContext {
        val ai = TestAi.graph(app) { "" }
        return RunContext("r1", ai.runner, ai.router, ai.prompts, ai.persona, { role -> ai.bindings.get(role) }, callClass = callClass)
    }
    private fun exec(port: HelperJobPort?) = PythonExecutor(port) { fenceNow }
    private suspend fun run(e: PythonExecutor, items: List<CanvasItem>, n: PipelineNode = node(), c: RunContext = ctx(), withFence: Boolean = true) =
        if (withFence) withContext(RunHelperFence(fence)) { e.run(n, items, c) } else e.run(n, items, c)

    /** A port that records the request and answers [outcome] at once. */
    private class StubPort(val outcome: PyOutcome) : HelperJobPort {
        val requests = ArrayList<PyRequest>()
        override suspend fun runPy(req: PyRequest): PyOutcome { requests += req; return outcome }
        override suspend fun awaitingConfirm(jobId: String) = false
        override suspend fun confirm(jobId: String, binding: ConfirmBinding) = ConfirmResult.NOT_FOUND
        override suspend fun declineConfirm(jobId: String) = false
        override suspend fun pendingConfirmations(): List<PendingConfirmation> = emptyList()
    }

    private fun done(vararg o: PyItemOutput) = PyOutcome.Done(o.toList(), 5)
    private fun ok(id: String, n: Int) = PyItemOutput(id, true, JsonObject(mapOf("n" to JsonPrimitive(n))), null)
    private fun bad(id: String, code: String) = PyItemOutput(id, false, null, code)

    // ---- shape ----

    @Test fun chunkSizeIsFiftyAndIgnoresItemCountAndNoModelCallsAreCounted() {
        val e = exec(null)
        assertEquals("py.fn", e.type)
        assertEquals(50, e.chunkSize(node()))
        assertEquals(0, e.nominalCalls(node(), 50)); assertEquals(0, e.maxCalls(node(), 50))
    }

    @Test fun pyFnIsNeverExecutedOnThePhone() {
        val text = listOf("PythonExecutor.kt", "ExperimentExecutors.kt", "HelperExecutor.kt").joinToString("\n") { File("src/main/java/com/rokid/context/canvas/$it").readText() }
        for (banned in listOf("ProcessBuilder", "Runtime.getRuntime", "ScriptEngine", ".exec(", "java.lang.Process")) assertFalse("$banned in the py.fn executor files", banned in text)
    }

    // ---- refusals before any job exists ----

    @Test fun noHelperFailsItems() = runBlocking {
        val r = run(exec(null), items(2))
        assertEquals(listOf("no_helper", "no_helper"), r.map { it.error }); assertTrue(r.all { it.value == null && it.calls == 0 })
        assertEquals(listOf("frame-0", "frame-1"), r.map { it.frameId })
    }

    @Test fun notExperimentRefused() = runBlocking {
        val p = StubPort(done())
        val r = run(exec(p), items(2), c = ctx(CallClass.EXTRACT))
        assertEquals(listOf("not_experiment", "not_experiment"), r.map { it.error }); assertTrue(p.requests.isEmpty())
    }

    @Test fun noFenceFromTheRunnerFailsClosedWithoutAJob() = runBlocking {
        val p = StubPort(done())
        val r = run(exec(p), items(1), withFence = false)
        assertEquals(listOf("no_fence"), r.map { it.error }); assertTrue("an executor never invents a fence of its own", p.requests.isEmpty())
    }

    @Test fun badOutSchemaIsRefusedBeforeAJobExists() = runBlocking {
        val p = StubPort(done())
        for (s in listOf("not json", "[1]", "{\"type\":\"string\",\"pattern\":\"a+\"}")) {
            assertEquals(s, listOf("bad_schema"), run(exec(p), items(1), node(outSchema = s)).map { it.error })
        }
        assertTrue(p.requests.isEmpty())
    }

    @Test fun anItemThatIsNotJsonFailsOnlyThatItem() = runBlocking {
        val p = StubPort(done(ok("i0", 1)))
        val mixed = listOf(items(1).single(), CanvasItem("frame-9", photo("frame-9"), CanvasValue.Photo))
        val r = run(exec(p), mixed)
        assertEquals(listOf(null, "bad_input"), r.map { it.error })
        assertEquals("only the readable item is sent", listOf("i0"), p.requests.single().items.map { it.id })
        assertEquals(listOf("frame-0"), p.requests.single().frameIds)
    }

    @Test fun aChunkWithNoReadableItemSendsNothing() = runBlocking {
        val p = StubPort(done())
        val r = run(exec(p), listOf(CanvasItem("frame-9", photo("frame-9"), CanvasValue.Photo)))
        assertEquals(listOf("bad_input"), r.map { it.error }); assertTrue(p.requests.isEmpty())
    }

    // ---- what the helper receives ----

    @Test fun requestCarriesTheRunnersFenceAuthoritativeFrameIdsAndOpaqueItemIds() = runBlocking {
        val p = StubPort(done(ok("i0", 0), ok("i1", 1), ok("i2", 2)))
        run(exec(p), items(3))
        val req = p.requests.single()
        assertEquals(fence, req.fence)
        assertEquals(listOf("frame-0", "frame-1", "frame-2"), req.frameIds)
        assertEquals(listOf("i0", "i1", "i2"), req.items.map { it.id })
        val wire = req.payload().toString()
        assertFalse("a frame id never reaches the helper", "frame-" in wire)
        assertEquals("the item input is the upstream JSON of that frame", JsonObject(mapOf("text" to JsonPrimitive("t1"))), req.items[1].input)
        assertEquals("def run(x):\n    return {'n': 1}\n", req.source)
    }

    @Test fun theFenceTheExecutorSendsIsTheOneInTheContextNotAFreshOne() = runBlocking {
        val p = StubPort(done(ok("i0", 0)))
        fenceNow = fenceAt(99) // a Forget landed after the capture
        run(exec(p), items(1))
        assertEquals(5, p.requests.single().fence.frameForgetRev)
    }

    // ---- outputs ----

    @Test fun outputsAreMappedByOpaqueIdNotByPosition() = runBlocking {
        val p = StubPort(done(ok("i2", 20), ok("i0", 0), ok("i1", 10)))
        val r = run(exec(p), items(3))
        assertEquals(listOf("frame-0", "frame-1", "frame-2"), r.map { it.frameId })
        assertEquals(listOf(0, 10, 20), r.map { (((it.value as CanvasValue.Json).value as JsonObject)["n"] as JsonPrimitive).content.toInt() })
        assertTrue(r.all { it.error == null && it.calls == 0 })
    }

    @Test fun perItemCodesBecomeItemErrors() = runBlocking {
        val p = StubPort(done(bad("i0", "timeout"), bad("i1", "schema_mismatch"), bad("i2", "exception"), bad("i3", "oom"), bad("i4", "too_big"), ok("i5", 5)))
        val r = run(exec(p), items(6))
        assertEquals(listOf("timeout", "schema_mismatch", "exception", "oom", "too_big", null), r.map { it.error })
        assertNull(r[0].value); assertNotNull(r[5].value)
    }

    @Test fun anItemTheHelperDidNotAnswerFailsNoResult() = runBlocking {
        val r = run(exec(StubPort(done(ok("i0", 0)))), items(2))
        assertEquals(listOf(null, "no_result"), r.map { it.error })
    }

    // ---- the outcome table (every service outcome is one fixed run item code) ----

    private suspend fun codeOf(o: PyOutcome, rev: Long = 5): List<String?> { fenceNow = fenceAt(rev); return run(exec(StubPort(o)), items(2)).map { it.error } }

    @Test fun serviceFailuresMapToFixedItemCodes() = runBlocking {
        assertEquals("helper silent / lease expired", listOf("killed", "killed"), codeOf(PyOutcome.Failed("killed")))
        assertEquals("deadline", listOf("timeout", "timeout"), codeOf(PyOutcome.Failed("timeout")))
        assertEquals("queue wait", listOf("resources_busy", "resources_busy"), codeOf(PyOutcome.Failed("resources_busy")))
        for (c in listOf("needs_runtime", "oom", "runtime_error", "bad_output", "too_large", "disk_low")) assertEquals(c, listOf(c, c), codeOf(PyOutcome.Failed(c)))
        assertEquals("an unknown code never leaks", listOf("runtime_error", "runtime_error"), codeOf(PyOutcome.Failed("secret text")))
    }

    @Test fun otherOutcomesMapToFixedItemCodes() = runBlocking {
        assertEquals(listOf("not_confirmed", "not_confirmed"), codeOf(PyOutcome.NotConfirmed))
        assertEquals(listOf("cancelled", "cancelled"), codeOf(PyOutcome.Cancelled))
        assertEquals(listOf("no_helper", "no_helper"), codeOf(PyOutcome.NoHelper))
        assertEquals(listOf("refused", "refused"), codeOf(PyOutcome.Refused("payload is not canonical")))
    }

    @Test fun voidedByAForgetIsAnItemCodeOfItsOwn() = runBlocking {
        assertEquals("the frame-Forget revision moved since the capture", listOf("frame_forget", "frame_forget"), codeOf(PyOutcome.Voided, rev = 6))
        assertEquals("a void with the revision unmoved (grant off, Disconnect, session end)", listOf("voided", "voided"), codeOf(PyOutcome.Voided, rev = 5))
        assertEquals(PythonExecutor.FRAME_FORGET, "frame_forget")
    }

    @Test fun aFloatInTheInputIsRefusedAsAWholeChunkTodayD1() = runBlocking {
        // D1 (float results) is OPEN: helper payloads and results are integer-only, so a float in an item's upstream JSON refuses the job (BundleHash is not changed here).
        val real = InMemoryHelperJobPort(now = { 1_000L }, sessionGen = { 7 })
        val frac = listOf(CanvasItem("frame-0", photo("frame-0"), CanvasValue.Json(JsonObject(mapOf("score" to JsonPrimitive(0.73))))))
        assertEquals(listOf("refused"), run(exec(real), frac).map { it.error })
        assertTrue(real.pendingConfirmations().isEmpty())
    }

    // ---- end to end through the in-memory port ----

    @Test fun confirmedJobCompletesAndItemsGetTheirOutputs() = runBlocking {
        val port = InMemoryHelperJobPort(now = { 1_000L }, sessionGen = { 7 }, newId = { "job-1" })
        val run = async(start = CoroutineStart.UNDISPATCHED) { run(exec(port), items(2)) }
        val p = port.pendingConfirmations().single()
        assertEquals(ConfirmResult.CONFIRMED, port.confirm(p.jobId, ConfirmBinding(p.jobId, p.bundleHash, p.sessionGen, p.expiresAtMs)))
        assertEquals("job-1", port.leaseNext())
        assertEquals(emptyList<String>(), port.complete("job-1", okResult(listOf("i0", "i1"))))
        assertEquals(listOf(null, null), run.await().map { it.error })
    }
}
