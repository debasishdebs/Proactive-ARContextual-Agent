package com.rokid.context.phone.canvas.helper

import android.content.Context
import android.graphics.Bitmap
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.Capability
import com.rokid.context.ai.Endpoint
import com.rokid.context.ai.EndpointKind
import com.rokid.context.ai.InMemoryKeyVault
import com.rokid.context.ai.InMemorySeedState
import com.rokid.context.ai.ProviderBuilder
import com.rokid.context.ai.Role
import com.rokid.context.ai.RoleBinding
import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.canvas.Pipeline
import com.rokid.context.ai.canvas.PipelineEdge
import com.rokid.context.ai.canvas.PipelineJson
import com.rokid.context.ai.canvas.PipelineNode
import com.rokid.context.canvas.ExperimentExecutors
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.db.DeletionEpochStore
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.forget.ForgetService
import com.rokid.context.memory.frames.FrameImages
import com.rokid.context.memory.frames.TinkFrameCipher
import com.rokid.context.model.ModelProvider
import com.rokid.context.orchestration.MemoryWipe
import com.rokid.context.phone.canvas.api.CanvasApi
import com.rokid.context.phone.canvas.api.CanvasBackendImpl
import com.rokid.context.phone.canvas.server.AssetSource
import com.rokid.context.phone.canvas.server.CanvasRouter
import com.rokid.context.phone.canvas.server.HostPolicy
import com.rokid.context.phone.canvas.server.HttpResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * WP9 test kit: the PRODUCTION chain of the browser run path, behind the real router, over one in-memory Room database.
 *
 * Real: `CanvasApi` (the browser routes), `CanvasBackendImpl.create` with the production `PhotoSource` over seeded frame files, `CanvasRunner`, `PythonExecutor` (through
 * `ExperimentExecutors.forApp`, the line `CanvasGraph` uses), `HelperJobService`, the fenced job DAO, `HelperConfirmAdapter`, the helper routes and both auth classes, and `ForgetService`.
 * Fakes: the model provider, the clock, the Wi-Fi callback, the grant file and the laptop helper (the test, through [helperLease], [helperResult] ...).
 *
 * Entry points are HTTP only ([call]); the one native action is [tap].
 */
class BrowserRunKit(val app: Context, tmp: File) {
    val h = HelperHarness(app)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val modelPrompts = CopyOnWriteArrayList<String>()
    /** Every request line the router wrote (method, route template, status, ms). */
    val routerLines = CopyOnWriteArrayList<String>()
    private val files = tmp
    private val jpeg: ByteArray = ByteArrayOutputStream().also { Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    val ai: AiGraph
    val backend: CanvasBackendImpl
    val router: CanvasRouter
    val helperToken: String
    val browserToken: String

    init {
        MemoryGraph.resetForTests(h.db)
        FrameImages.configureForTests(TinkFrameCipher.inMemoryForTests()) { true }
        // the fence the production composition reads (`ExperimentExecutors.forApp`) is the one the job DAO of the harness re-checks
        h.wipeGen = MemoryWipe.generation.get()
        h.epoch = DeletionEpochStore.of(app).read()
        ai = AiGraph(MemoryGraph.get(app), InMemoryKeyVault(), InMemorySeedState(), ProviderBuilder { _, _, _ ->
            object : ModelProvider { override suspend fun generate(prompt: String): String { modelPrompts += prompt; return """{"label":"desk"}""" } }
        }).also { AiGraph.resetForTests(it) }
        runBlocking {
            ai.endpoints.save(Endpoint("az", "Azure", EndpointKind.AZURE_OPENAI, "https://az.example", "2024-10-21", listOf("gpt"), setOf(Capability.CHAT, Capability.VISION), 1), apiKey = "k").getOrThrow()
            Role.entries.filter { it != Role.EMBED }.forEach { ai.bindings.set(RoleBinding(it, "az", "gpt")).getOrThrow() }
        }
        backend = CanvasBackendImpl.create(
            app, ai, h.browserAuth, { false }, scope,
            helperJobs = h.backend.service, extraExecutors = ExperimentExecutors.forApp(app, h.backend.service),
        )
        router = CanvasRouter(
            h.browserAuth, HostPolicy({ 8765 }, { null }), CanvasApi(backend, extras = listOf(h.backend.browserRoutes)), AssetSource { name -> File("src/main/assets/canvas/$name").takeIf { it.isFile }?.readBytes() },
            helperAuth = h.backend.auth, helperApi = h.backend.routes, log = { routerLines += it },
        )
        helperToken = h.pairHelper()
        browserToken = json(call("POST", "/api/pair", null, buildJsonObject { put("code", h.browserAuth.newCode().code) }))["token"]!!.jsonPrimitive.content
    }

    fun close() {
        scope.cancel()
        FrameImages.configureForTests(null) { false }
        AiGraph.resetForTests(null)
        h.close()
        MemoryGraph.resetForTests(null)
    }

    // ---- HTTP (the router is the only way in) ----
    fun call(method: String, path: String, token: String?, body: JsonElement? = null, query: Map<String, String> = emptyMap()): HttpResponse =
        router.handle(h.req(method, path, token, body?.toString().orEmpty(), query))

    fun json(r: HttpResponse): JsonObject = h.json(r)
    fun ok(r: HttpResponse): JsonObject { check(r.status == 200) { "answered ${r.status}: ${String(r.body)}" }; return json(r) }
    fun browser(method: String, path: String, body: JsonElement? = null) = call(method, path, browserToken, body)

    // ---- fixtures ----
    /** A photo/frame fixture: a frame row and its encrypted JPEG, as the capture path leaves them. */
    fun seedFrame(id: String, ts: Long = 1_000L) {
        val file = File(files, "rokid_${ts}_$id.jpg").apply { writeBytes(FrameImages.forDisk(jpeg)) }
        runBlocking { MemoryGraph.get(app).db.frameDao().insertIgnore(FrameEntity(id, ts, file.path, "rokid")) }
    }

    private val outSchema = """{"type":"object","required":["n"],"properties":{"n":{"type":"integer","minimum":0}},"additionalProperties":false}"""

    /** photos -> prompt (JSON per frame) -> py.fn, saved as a user draft THROUGH the browser API. Returns the pipeline id. */
    fun savePythonPipeline(): String {
        val id = ok(browser("POST", "/api/pipelines", buildJsonObject { put("from", "intent-draft"); put("name", "Python") }))["id"]!!.jsonPrimitive.content
        val rev = ok(browser("GET", "/api/pipelines/$id"))["draftRev"]!!.jsonPrimitive.int
        val p = Pipeline(
            2, id, "Python",
            listOf(
                PipelineNode("photos", NodeTypes.INPUT_PHOTOS, "photos", JsonObject(emptyMap())),
                PipelineNode("intent", NodeTypes.PROMPT, "intent", buildJsonObject { put("role", "VISION_EXTRACT"); put("name", "intent"); put("images", true); put("template", "Label the scene."); put("schema", "{\"type\":\"object\"}") }),
                PipelineNode("fn", NodeTypes.PY_FN, "fn", buildJsonObject { put("source", "def run(x):\n    return {'n': 1}\n"); put("outSchema", outSchema) }),
            ),
            listOf(PipelineEdge("photos", "intent"), PipelineEdge("intent", "fn")),
        )
        val saved = ok(browser("PUT", "/api/pipelines/$id", buildJsonObject { put("json", PipelineJson.encode(p)); put("draftRev", rev) }))
        check(saved["problems"]!!.jsonArray.isEmpty()) { "pipeline problems: ${saved["problems"]}" }
        return id
    }

    private fun ids(v: List<String>): JsonArray = buildJsonArray { v.forEach { add(JsonPrimitive(it)) } }

    /** `POST /api/runs/estimate` then `POST /api/runs` with `frameIds`: the run id. */
    fun startRun(pipelineId: String, frames: List<String>): String {
        val est = ok(browser("POST", "/api/runs/estimate", buildJsonObject { put("pipelineId", pipelineId); put("frameIds", ids(frames)) }))
        return ok(browser("POST", "/api/runs", buildJsonObject { put("pipelineId", pipelineId); put("frameIds", ids(frames)); put("confirmToken", est["confirmToken"]!!.jsonPrimitive.content) }))["runId"]!!.jsonPrimitive.content
    }

    fun run(runId: String): JsonObject = ok(browser("GET", "/api/runs/$runId"))
    fun runStatus(runId: String): String = run(runId)["status"]!!.jsonPrimitive.content

    fun awaitStatus(runId: String, vararg wanted: String): String {
        val end = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < end) {
            val s = runStatus(runId)
            if (s in wanted) return s
            Thread.sleep(25)
        }
        throw AssertionError("run $runId did not reach ${wanted.toList()}, status ${runStatus(runId)}")
    }

    /** The items of the `fn` node of a run, as the browser API shows them. */
    fun fnItems(runId: String): List<JsonObject> = run(runId)["items"]!!.jsonArray.map { it.jsonObject }.filter { it["nodeId"]!!.jsonPrimitive.content == "fn" }

    /** The `n` of a py.fn item's output as the browser API shows it (`{kind:"json", value:{...}}`), or null. */
    fun fnN(item: JsonObject): String? = ((item["output"] as? JsonObject)?.get("value") as? JsonObject)?.get("n")?.jsonPrimitive?.content

    // ---- the native tap and the fake helper ----
    fun awaitPending(): String = h.awaitPendingJob()
    fun tap(jobId: String) = h.tap(jobId)

    /** The helper leases the next job; null when there is none. Returns (jobId, leaseGen). */
    fun helperLease(): Pair<String, Long>? {
        val job = json(h.lease(helperToken))["job"] as? JsonObject ?: return null
        return job["jobId"]!!.jsonPrimitive.content to job["leaseGen"]!!.jsonPrimitive.content.toLong()
    }

    fun helperPayload(jobId: String, gen: Long) = h.payload(helperToken, jobId, gen)

    /**
     * THE one place the fake helper builds and posts a RESULT. Decision D1 changes the envelope (resultCanonical text plus resultHash over it): adapting is a change of this
     * one function's body, nothing else in the WP9 tests builds a result.
     */
    fun helperResult(jobId: String, gen: Long, items: Int = 1): HttpResponse = h.result(helperToken, jobId, gen, h.okResult(items))

    /** Lease, fetch the payload, answer every item `{n:1}`: the laptop doing its job. Returns the job id. */
    fun helperCompletes(items: Int): String {
        val (id, gen) = checkNotNull(helperLease()) { "nothing to lease" }
        check(helperPayload(id, gen).status == 200) { "payload refused" }
        check(helperResult(id, gen, items).status == 200) { "result refused" }
        return id
    }

    // ---- Forget (the real service) ----
    fun forget(frameId: String) = runBlocking { ForgetService(h.db, ProposalRepository(h.db.proposalDao(), h.db.actionDao(), h.db)) { h.now }.forgetMoment(frameId) }

    fun jobState(jobId: String): String? = runBlocking { h.dao.helperJob(jobId)?.state }

    fun waitUntil(cond: () -> Boolean) = runBlocking { withTimeout(15_000) { while (!cond()) delay(10) } }
}
