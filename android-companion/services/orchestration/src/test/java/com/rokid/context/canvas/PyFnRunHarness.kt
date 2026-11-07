package com.rokid.context.canvas

import android.content.Context
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.canvas.Pipeline
import com.rokid.context.ai.canvas.PipelineEdge
import com.rokid.context.ai.canvas.PipelineNode
import com.rokid.context.canvas.helper.ConfirmResult
import com.rokid.context.canvas.helper.DaoHelperJobStore
import com.rokid.context.canvas.helper.HelperJobPort
import com.rokid.context.canvas.helper.HelperJobService
import com.rokid.context.canvas.helper.PendingConfirmation
import com.rokid.context.canvas.helper.PyOutcome
import com.rokid.context.canvas.helper.PyRequest
import com.rokid.context.canvas.helper.okResult
import com.rokid.context.canvas.helper.commitResult
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.canvas.ConfirmBinding
import com.rokid.context.memory.canvas.HelperFence
import com.rokid.context.memory.canvas.HelperFenceLive
import com.rokid.context.memory.db.CanvasDao
import com.rokid.context.memory.db.CanvasRunEntity
import com.rokid.context.memory.db.CanvasSlice3Dao
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.DeletionEpoch
import com.rokid.context.model.ModelImageInput
import com.rokid.context.orchestration.ExtractPhoto
import com.rokid.context.orchestration.TestAi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * WP8 test kit: the REAL `CanvasRunner`, `PythonExecutor`, `HelperJobService` and WP3 DAO over one in-memory Room database (the one `MemoryGraph` owns), so a Forget
 * through `ForgetService` really moves the fence the runner and the job service read. The test plays the native tap ([tap], [decline]) and the laptop helper ([answer]).
 */
internal class PyFnRunHarness(private val app: Context) {
    val db: ContextDatabase = DatabaseFactory.inMemory(app).also { MemoryGraph.resetForTests(it) }
    val dao: CanvasDao = db.canvasDao()
    val dao3: CanvasSlice3Dao = db.canvasSlice3Dao()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile var gen = 0L
    var epoch = DeletionEpoch("install-a", 2, 0)
    @Volatile var now = 1_000_000L
    var sessionGen = 7L
    val live = HelperFenceLive({ gen }, { epoch })
    val store = DaoHelperJobStore(dao3, live)
    val lines = CopyOnWriteArrayList<String>()
    val service = HelperJobService(store, { now }, { sessionGen }, log = { _, _, m -> lines += m }, waiterPollMs = 20L)

    /** The one port the executor sees: counts submissions so a test can say "one job". */
    val submitted = AtomicInteger(0)
    val port: HelperJobPort = object : HelperJobPort by service {
        override suspend fun runPy(req: PyRequest): PyOutcome { submitted.incrementAndGet(); return service.runPy(req) }
    }
    val fenceSource: suspend () -> HelperFence = { HelperFence(gen, epoch, dao3.frameForgetRev()) }
    val py = PythonExecutor(port, fenceSource)

    val tokens = ConfirmTokens({ now })
    var ids = 0
    var hotNow = { false }
    var photoLoad: (List<String>) -> Unit = {}
    val events = CopyOnWriteArrayList<String>()
    val log = CopyOnWriteArrayList<String>()

    fun photo(id: String) = ExtractPhoto(id, "ts=1", ModelImageInput("image/jpeg", "AAAA"))

    /** `ai.prompt` stand-in: every frame yields the JSON `{text: "t-<frame>"}` (the JSON a `py.fn` consumes). */
    val prompt: NodeExecutor = object : NodeExecutor {
        override val type = NodeTypes.PROMPT
        override fun nominalCalls(node: PipelineNode, items: Int) = 0
        override fun maxCalls(node: PipelineNode, items: Int) = 0
        override suspend fun run(node: PipelineNode, items: List<CanvasItem>, ctx: RunContext) =
            items.map { NodeResult(it.frameId, CanvasValue.Json(JsonObject(mapOf("text" to JsonPrimitive("t-${it.frameId}"))))) }
    }

    fun runner(py: NodeExecutor = this.py, extra: Map<String, NodeExecutor> = emptyMap()) = CanvasRunner(
        dao, { f -> photoLoad(f); PhotoLoad(f.map(::photo), emptySet()) }, mapOf(NodeTypes.PROMPT to prompt, py.type to py) + extra,
        ::context, tokens, { hotNow() }, { now }, { "run${++ids}" }, { gen }, { _, _, m -> log += m },
    )

    private fun context(runId: String): RunContext {
        val ai = TestAi.graph(app) { "" }
        return RunContext(runId, ai.runner, ai.router, ai.prompts, ai.persona, { role -> ai.bindings.get(role) }, liveness = com.rokid.context.orchestration.AllLive)
    }

    fun close() { scope.cancel(); AiGraph.resetForTests(null); db.close(); MemoryGraph.resetForTests(null) }

    // ---- the pipeline: photos -> intent (JSON per frame) -> fn (py.fn) ----

    val outSchema = "{\"type\":\"object\",\"required\":[\"n\"],\"properties\":{\"n\":{\"type\":\"integer\",\"minimum\":0}},\"additionalProperties\":false}"

    fun pipeline(id: String = "p"): Pipeline = Pipeline(
        2, id, "P",
        listOf(
            PipelineNode("photos", NodeTypes.INPUT_PHOTOS, "photos", JsonObject(emptyMap())),
            PipelineNode("intent", NodeTypes.PROMPT, "intent", buildJsonObject { put("schema", "{\"type\":\"object\"}") }),
            PipelineNode("fn", NodeTypes.PY_FN, "fn", buildJsonObject { put("source", "def run(x):\n    return {'n': 1}\n"); put("outSchema", outSchema) }),
        ),
        listOf(PipelineEdge("photos", "intent"), PipelineEdge("intent", "fn")),
    )

    fun frames(n: Int) = (1..n).map { "f$it" }
    private val noLabel: suspend (PipelineNode) -> ModelLabel? = { n -> ModelLabel(n.id, "az", "gpt") }

    suspend fun begin(r: CanvasRunner, p: Pipeline, f: List<String>): String =
        r.start(p, p.id, null, f, (r.estimate(p, f, noLabel) as Estimate.Ok).confirmToken, scope)

    suspend fun beginAb(r: CanvasRunner, a: Pipeline, b: Pipeline, f: List<String>): AbStarted =
        r.startAb(AbSpec(a to null, a.id, b to null, b.id), f, (r.estimateAb(a, b, f, noLabel) as Estimate.Ok).confirmToken, scope)

    suspend fun finish(r: CanvasRunner) { withTimeout(20_000) { r.active.first { it == null } } }
    suspend fun run(r: CanvasRunner, p: Pipeline, f: List<String>): CanvasRunEntity { val id = begin(r, p, f); finish(r); return checkNotNull(dao.run(id)) }

    // ---- the native tap and the laptop helper ----

    suspend fun pending(): PendingConfirmation = withTimeout(10_000) {
        var p = service.pendingConfirmations().firstOrNull()
        while (p == null) { delay(5); p = service.pendingConfirmations().firstOrNull() }
        p
    }
    fun binding(p: PendingConfirmation) = ConfirmBinding(p.jobId, p.bundleHash, p.sessionGen, p.expiresAtMs)

    /** The user taps Confirm on the pending job. Returns its id. */
    suspend fun tap(): String { val p = pending(); check(service.confirm(p.jobId, binding(p)) == ConfirmResult.CONFIRMED); return p.jobId }
    suspend fun decline(): String { val p = pending(); check(service.declineConfirm(p.jobId)); return p.jobId }

    class Lease(val jobId: String, val leaseGen: Long, val itemIds: List<String>)

    /** The helper leases the confirmed job and fetches its payload. */
    suspend fun lease(): Lease {
        val l = checkNotNull(service.lease("h1", listOf("py_exec"))) { "no job to lease" }
        val payload = checkNotNull(service.releasePayload(l.jobId, "h1", l.leaseGen).payload) { "payload refused" }
        val ids = Json.parseToJsonElement(payload).jsonObject.getValue("items").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
        return Lease(l.jobId, l.leaseGen, ids)
    }

    /** The helper answers every item `{n: 1}`. */
    suspend fun answer(l: Lease) { check(service.commitResult(l.jobId, "h1", l.leaseGen, okResult(l.itemIds)) is com.rokid.context.canvas.helper.ResultCommit.Committed) }

    private val Json = kotlinx.serialization.json.Json
}
