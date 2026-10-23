package com.rokid.context.phone.canvas.helper

import android.content.Context
import com.rokid.context.canvas.helper.CanvasLogSink
import com.rokid.context.canvas.helper.DaoHelperJobStore
import com.rokid.context.canvas.helper.HelperJobPort
import com.rokid.context.canvas.helper.PyItem
import com.rokid.context.canvas.helper.PyOutcome
import com.rokid.context.canvas.helper.PyRequest
import com.rokid.context.memory.canvas.HelperDeletion
import com.rokid.context.memory.canvas.HelperFence
import com.rokid.context.memory.canvas.HelperFenceLive
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.DeletionEpoch
import com.rokid.context.phone.canvas.server.ApiHandler
import com.rokid.context.phone.canvas.server.AssetSource
import com.rokid.context.phone.canvas.server.CanvasAuth
import com.rokid.context.phone.canvas.server.CanvasRouter
import com.rokid.context.phone.canvas.server.HostPolicy
import com.rokid.context.phone.canvas.server.HttpRequest
import com.rokid.context.phone.canvas.server.HttpResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** A [NetworkWatch] the test fires by hand. */
class FakeNetworkWatch : NetworkWatch {
    var onLost: (() -> Unit)? = null
    var registrations = 0
    var unregistrations = 0
    override val registered: Boolean get() = onLost != null
    override fun register(onLost: () -> Unit) { registrations++; this.onLost = onLost }
    override fun unregister() { if (onLost != null) unregistrations++; onLost = null }
    fun lose() { onLost?.invoke() }
}

/** Records the order of the session end's effects (the plan's order: grant off, token revoked, jobs voided, server stopped). */
class OrderLog { val events = java.util.Collections.synchronizedList(mutableListOf<String>()); fun add(e: String) { events += e } }

/** Wraps the real [HelperJobEnder] and records what it was asked, in order. */
class RecordingEnder(private val real: HelperJobEnder, private val order: OrderLog) : HelperJobEnder {
    override suspend fun grantOff(): Int { order.add("jobs:grant_off"); return real.grantOff() }
    override suspend fun endSession(cause: com.rokid.context.memory.canvas.HelperDeletionCause): Int { order.add("jobs:" + cause.code); return real.endSession(cause) }
    override suspend fun recover(): Int { order.add("jobs:recover"); return real.recover() }
}

/** Records every call of the port that matters to the per-run confirmation. */
class RecordingPort(private val real: HelperJobPort) : HelperJobPort by real {
    val confirms = java.util.Collections.synchronizedList(mutableListOf<String>())
    val declines = java.util.Collections.synchronizedList(mutableListOf<String>())
    override suspend fun confirm(jobId: String, binding: com.rokid.context.memory.canvas.ConfirmBinding): com.rokid.context.canvas.helper.ConfirmResult {
        confirms += jobId; return real.confirm(jobId, binding)
    }
    override suspend fun declineConfirm(jobId: String): Boolean { declines += jobId; return real.declineConfirm(jobId) }
}

class RecordingGrantStore : GrantStore {
    val inner = MemoryGrantStore()
    val writes = java.util.Collections.synchronizedList(mutableListOf<String>())
    override fun read(key: String) = inner.read(key)
    override fun write(key: String, on: Boolean): Boolean { writes += "$key=$on"; return inner.write(key, on) }
    override fun clear(): Boolean { writes += "clear"; return inner.clear() }
}

/**
 * The PRODUCTION wiring ([HelperBackend.create], the one [CanvasGraph] uses) over an in-memory Room database, a fake clock and a fake network, behind the REAL router.
 * Only the leaves are fakes: the clock, the Wi-Fi callback, the server stop and the grant file. The job service, DAO, fences, deletion hooks, auth, HMAC, adapter and
 * routes are the real classes.
 */
class HelperHarness(ctx: Context, log: CanvasLogSink = com.rokid.context.phone.log.PhoneLogging.canvasPort(), inactivityMs: Long = HelperDevSession.INACTIVITY_MS) {
    @Volatile var now = 1_700_000_000_000L
    val db: ContextDatabase = DatabaseFactory.inMemory(ctx)
    val dao = db.canvasSlice3Dao()
    @Volatile var wipeGen = 5L
    @Volatile var epoch = DeletionEpoch("install-a", 3, 1_000)
    val live = HelperFenceLive({ wipeGen }, { epoch })
    val order = OrderLog()
    val grantStore = RecordingGrantStore()
    val network = FakeNetworkWatch()
    val browserAuth = CanvasAuth({ now })
    @Volatile var screenOpen = false
    @Volatile var serverStops = 0
    /** What the production `stopServer` does besides counting (a graph test points it at `graph.stop()`). */
    @Volatile var onStop: () -> Unit = {}
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    lateinit var recordingPort: RecordingPort
    private val activityOverride = java.util.concurrent.atomic.AtomicReference<(() -> Long)?>(null)

    val backend: HelperBackend = HelperBackend.create(
        store = DaoHelperJobStore(dao, live),
        grantStore = grantStore,
        enderFactory = { hooks -> RecordingEnder(DeletionJobEnder(HelperDeletion(dao, object : com.rokid.context.memory.canvas.HelperSessionHooks {
            override fun grantsOff() { order.add("hooks:grants_off"); hooks.grantsOff() }
            override fun revokeHelper() { order.add("hooks:revoke"); hooks.revokeHelper() }
        }, { now })), order) },
        clock = { now }, scope = scope, network = network,
        userActivityMs = { activityOverride.get()?.invoke() ?: browserAuth.lastActivityMs }, screenOpen = { screenOpen },
        stopServer = { serverStops++; order.add("server:stop"); onStop() }, log = log, inactivityMs = inactivityMs, checkEveryMs = Long.MAX_VALUE / 4,
        portWrapper = { RecordingPort(it).also { p -> recordingPort = p } },
    )
    val port: RecordingPort get() = recordingPort

    /** Mutation seam: the user-activity clock the session reads. */
    fun overrideActivity(f: () -> Long) { activityOverride.set(f) }

    val handler = ApiHandler { req -> kotlinx.coroutines.runBlocking { backend.browserRoutes.handle(req) } ?: HttpResponse.error(404, "not_found", "Not found.") }
    val router = CanvasRouter(browserAuth, HostPolicy({ 8765 }, { null }), handler, AssetSource { null }, helperAuth = backend.auth, helperApi = backend.routes)

    fun close() { scope.cancel(); db.close() }

    // ---- the fake helper (the Kotlin test client) ----
    fun req(method: String, path: String, token: String?, body: String = "", query: Map<String, String> = emptyMap()) =
        HttpRequest(method, path, query, buildMap { put("host", "127.0.0.1:8765"); if (token != null) put("authorization", "Bearer $token") }, body.toByteArray(), remoteIp = "10.0.0.9")

    fun call(method: String, path: String, token: String?, body: String = "", query: Map<String, String> = emptyMap()): HttpResponse = router.handle(req(method, path, token, body, query))

    fun json(r: HttpResponse): JsonObject = Json.parseToJsonElement(String(r.body, Charsets.UTF_8)).jsonObject

    /** Native Accept tap, then the helper's pairing request; returns the helper token. */
    fun pairHelper(grantOn: Boolean = true): String {
        backend.native.acceptPairing()
        val code = backend.auth.pairingCode()!!.code
        val r = call("POST", "/api/helper/pair", null, """{"helperProtocol":1,"code":"$code","name":"laptop"}""")
        check(r.status == 200) { "pair answered ${r.status}" }
        if (grantOn) backend.native.setGrant(HelperGrants.PY_EXEC, true)
        return json(r)["token"]!!.let { (it as JsonPrimitive).content }
    }

    fun poll(token: String, active: String = "[]", acks: String = "[]", ready: Boolean = true) =
        call("POST", "/api/helper/poll", token, """{"helperProtocol":1,"active":$active,"cleanupAcks":$acks,"ready":{"py_exec":$ready}}""")

    fun lease(token: String) = call("POST", "/api/helper/lease", token, """{"helperProtocol":1,"types":["py_exec"]}""")
    fun payload(token: String, jobId: String, leaseGen: Long) = call("GET", "/api/helper/jobs/$jobId/payload", token, query = mapOf("leaseGen" to "$leaseGen"))
    fun heartbeat(token: String, jobId: String, leaseGen: Long) = call("POST", "/api/helper/jobs/$jobId/heartbeat", token, """{"leaseGen":$leaseGen}""")
    fun result(token: String, jobId: String, leaseGen: Long, result: String) =
        call("POST", "/api/helper/jobs/$jobId/result", token, """{"leaseGen":$leaseGen,"resultHash":"${"0".repeat(64)}","result":$result}""")
    fun fail(token: String, jobId: String, leaseGen: Long, code: String) = call("POST", "/api/helper/jobs/$jobId/fail", token, """{"leaseGen":$leaseGen,"code":"$code"}""")

    // ---- the executor side ----
    val outSchema: JsonObject = Json.parseToJsonElement("""{"type":"object","required":["n"],"properties":{"n":{"type":"integer"}},"additionalProperties":false}""").jsonObject

    suspend fun request(frameIds: List<String> = listOf("frame-1"), items: Int = 1): PyRequest {
        val fence = HelperFence(wipeGen, epoch, dao.frameForgetRev())
        return PyRequest(
            source = "def run(x):\n    return {'n': 1}\n", items = (0 until items).map { PyItem("i$it", buildJsonObject { put("k", it) }) },
            outSchema = outSchema, frameIds = frameIds, fence = fence,
        )
    }

    /** Starts a run on a real thread (the runner's side); the caller reads [await]. */
    fun startRun(frameIds: List<String> = listOf("frame-1"), items: Int = 1): Deferred<PyOutcome> =
        CoroutineScope(Dispatchers.Default).async { port.runPy(request(frameIds, items)) }

    /** Waits (real time, bounded) until one job is awaiting the tap, and returns its id. */
    fun awaitPendingJob(): String = runBlocking {
        withTimeout(5_000) {
            while (true) {
                val rows = backend.service.pendingConfirmations()
                if (rows.isNotEmpty()) return@withTimeout rows.first().jobId
                delay(5)
            }
            @Suppress("UNREACHABLE_CODE") ""
        }
    }

    /** The native tap, run to completion (the card's `onConfirm` is asynchronous). */
    fun tap(jobId: String) = runBlocking { backend.adapter.confirmNow(jobId) }

    fun okResult(items: Int = 1): String = JsonObject(mapOf(
        "helperProtocol" to JsonPrimitive(1), "jobType" to JsonPrimitive("py_exec"),
        "outputs" to JsonArray((0 until items).map { buildJsonObject { put("id", "i$it"); put("ok", true); put("output", buildJsonObject { put("n", 1) }) } }),
        "stats" to buildJsonObject { put("ms", 5) },
    )).toString()

    /** Confirm, lease, fetch the payload: returns (jobId, leaseGen). */
    fun confirmAndLease(token: String): Pair<String, Long> {
        val id = awaitPendingJob()
        tap(id)
        val l = json(lease(token))["job"]!!.jsonObject
        val gen = (l["leaseGen"] as JsonPrimitive).content.toLong()
        check(payload(token, id, gen).status == 200)
        return id to gen
    }
}
