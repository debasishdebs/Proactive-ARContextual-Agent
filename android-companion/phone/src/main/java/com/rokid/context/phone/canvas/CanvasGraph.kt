package com.rokid.context.phone.canvas

import android.content.Context
import android.util.Log
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.canvas.Pipeline
import com.rokid.context.ai.canvas.PipelineJson
import com.rokid.context.canvas.CanvasPublisher
import com.rokid.context.phone.canvas.api.CanvasApi
import com.rokid.context.phone.canvas.api.CanvasBackendImpl
import com.rokid.context.phone.canvas.server.ApiHandler
import com.rokid.context.phone.canvas.server.AssetSource
import com.rokid.context.phone.canvas.server.CanvasAuth
import com.rokid.context.phone.canvas.server.CanvasRouter
import com.rokid.context.phone.canvas.server.CanvasServer
import com.rokid.context.phone.canvas.server.HostPolicy
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException

/**
 * What the app shows about the server. [port] is the port actually bound (8765, else a fallback); [lanIp] is the phone's
 * current Wi-Fi IPv4 whether or not [lan] is on (the Use-from-a-laptop switch needs it); [code] is the live pairing code, if any.
 */
data class CanvasStatus(
    val on: Boolean = false,
    val port: Int? = null,
    val lan: Boolean = false,
    val lanIp: String? = null,
    val code: String? = null,
    val codeExpiresAt: Long = 0L,
    /** A short fixed-text notice for the canvas screen (e.g. the system would not let the service run). */
    val notice: String? = null,
) {
    val localUrl: String? get() = if (on && port != null) "http://127.0.0.1:$port" else null
    val lanUrl: String? get() = if (on && lan && lanIp != null && port != null) "http://$lanIp:$port" else null
}

data class PipelineRow(val id: String, val name: String, val versions: Int, val liveVersion: Int?)
data class RunRow(val status: String, val photos: Int, val calls: Int, val startedAt: Long)
data class HomeData(val pipelines: List<PipelineRow>, val runs: List<RunRow>, val publishActive: Boolean)

/** The data side of the canvas, behind one seam so the graph is testable without the AI graph and the database. */
interface CanvasCore {
    val api: ApiHandler
    suspend fun start()
    fun runActive(): Boolean
    /** Cancels the active run, if any (Developer mode turned off). */
    suspend fun cancelActive()
    suspend fun home(): HomeData
    suspend fun rollback(): CanvasPublisher.Outcome
    suspend fun pipeline(id: String): Pipeline?
}

/** What the canvas screens and the service use. */
interface CanvasHost {
    val status: StateFlow<CanvasStatus>
    /** True while a canvas screen is showing (the idle rule never stops the server then). */
    var screenOpen: Boolean
    /** (Re)binds the server; [lan] binds 0.0.0.0. Does nothing while Developer mode is off. */
    fun start(lan: Boolean = false)
    fun stop()
    /** On: needs Wi-Fi, makes a fresh pairing code and rebinds on all interfaces. Off: rebinds on loopback. */
    fun setLan(on: Boolean)
    fun revokeAll()
    /** The live pairing code, or a new one when none is live or it expired. */
    fun pairingCode(): CanvasAuth.Code
    fun webViewToken(): String
    /** Re-reads the Wi-Fi address into [status]. */
    fun refreshNetwork()
    /** The service's idle rule (see [CanvasGraph.shouldIdleStop]). */
    fun shouldIdleStop(): Boolean
    /** Stops the server and tells the canvas screen why, in fixed text. */
    fun stopWithNotice(text: String)
    suspend fun home(): HomeData
    suspend fun rollback(): CanvasPublisher.Outcome
    suspend fun pipeline(id: String): Pipeline?
}

/**
 * The process-wide canvas: ONE auth, router, server and backend. The server binds loopback unless LAN is on; the Host
 * policy reads the port actually bound. Turning Developer mode off stops the server and revokes every session.
 */
class CanvasGraph(
    private val auth: CanvasAuth,
    private val core: CanvasCore,
    assets: AssetSource,
    private val devMode: StateFlow<Boolean>,
    private val wifiIp: () -> String?,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    private val newServer: (CanvasRouter, Boolean) -> CanvasServer = { r, lan -> CanvasServer(r, lan, log) },
) : CanvasHost {
    @Volatile private var server: CanvasServer? = null
    @Volatile private var lanOn = false
    @Volatile private var startedAt = 0L

    private val router = CanvasRouter(
        auth, HostPolicy(port = { server?.port ?: 0 }, lanIp = { if (lanOn) wifiIp() else null }), core.api, assets, log,
    )
    private val _status = MutableStateFlow(CanvasStatus())
    override val status: StateFlow<CanvasStatus> = _status.asStateFlow()
    @Volatile override var screenOpen = false

    /** Whether LAN is on right now (the backend's session info reads this). */
    val lan: Boolean get() = lanOn

    init {
        scope.launch { devMode.collect { on -> if (!on) { stop(); revokeAll(); try { core.cancelActive() } catch (e: Exception) { log("canvas cancel failed (${e.javaClass.simpleName})") } } } }
    }

    @Synchronized
    override fun start(lan: Boolean) {
        if (!devMode.value) return
        if (server != null && lanOn == lan) { publish(); return }
        server?.stop()
        server = null
        lanOn = lan
        val s = newServer(router, lan)
        try { s.start() } catch (e: IOException) {
            log("canvas server did not start")
            lanOn = false
            publish()
            return
        }
        server = s
        notice = null
        startedAt = clock()
        scope.launch { try { core.start() } catch (e: Exception) { log("canvas backend start failed (${e.javaClass.simpleName})") } }
        publish()
    }

    @Synchronized
    override fun stop() {
        server?.stop()
        server = null
        lanOn = false
        publish()
    }

    @Synchronized
    override fun setLan(on: Boolean) {
        if (on) {
            if (wifiIp() == null) return
            auth.newCode()
        }
        start(on)
    }

    override fun revokeAll() { auth.revokeAll() }

    @Synchronized
    override fun pairingCode(): CanvasAuth.Code = (auth.currentCode() ?: auth.newCode()).also { publish() }

    override fun webViewToken(): String = auth.newWebViewToken()

    override fun refreshNetwork() = publish()

    override suspend fun home() = core.home()
    override suspend fun rollback() = core.rollback()
    override suspend fun pipeline(id: String) = core.pipeline(id)

    /** The service's idle rule: no authenticated request for 30 min, no run, no canvas screen open. */
    override fun shouldIdleStop(): Boolean = shouldIdleStop(clock())

    fun shouldIdleStop(now: Long): Boolean =
        server != null && IdlePolicy.shouldStop(maxOf(auth.lastActivityMs, startedAt), now, core.runActive(), screenOpen)

    @Volatile private var notice: String? = null

    override fun stopWithNotice(text: String) {
        notice = text
        stop()
    }

    private fun publish() {
        val s = server
        val code = auth.currentCode()
        _status.value = CanvasStatus(on = s != null, port = s?.port, lan = lanOn, lanIp = wifiIp(), code = code?.code, codeExpiresAt = code?.expiresAt ?: 0L, notice = notice)
    }

    companion object {
        private const val TAG = "Canvas"
        @Volatile private var instance: CanvasGraph? = null
        @Volatile private var override: CanvasHost? = null

        /** What the canvas screens use: the process graph (a fake in route tests, see [installForTests]). */
        fun host(app: Context): CanvasHost = override ?: get(app)

        fun installForTests(host: CanvasHost?) { override = host }

        /** Cheap to call from any screen: the AI graph and the backend are built on the first server start. */
        fun get(app: Context): CanvasGraph = instance ?: synchronized(this) { instance ?: build(app.applicationContext).also { instance = it } }

        private fun build(app: Context): CanvasGraph {
            val auth = CanvasAuth(System::currentTimeMillis)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t -> Log.w(TAG, "canvas task failed (${t.javaClass.simpleName})") })
            val ref = java.util.concurrent.atomic.AtomicReference<CanvasGraph>()
            val backend = lazy { CanvasBackendImpl.create(app, AiGraph.get(app), auth, lan = { ref.get()?.lan == true }, scope = scope) }
            val graph = CanvasGraph(
                auth, BackendCore(backend), AssetSource { name -> runCatching { app.assets.open("canvas/$name").use { it.readBytes() } }.getOrNull() },
                DeveloperModeStore.get(app).enabled, wifiIp = { LanAddress.wifiIpv4(app) }, scope = scope, log = { Log.i(TAG, it) },
            )
            ref.set(graph)
            return graph
        }
    }
}

/** [CanvasCore] over the one [CanvasBackendImpl] (built on first use, then reused). */
class BackendCore(private val backend: Lazy<CanvasBackendImpl>) : CanvasCore {
    private val apiLazy = lazy { CanvasApi(backend.value) }
    override val api = ApiHandler { apiLazy.value.handle(it) }
    override suspend fun start() = backend.value.start()
    override fun runActive() = backend.isInitialized() && backend.value.runActive()
    override suspend fun cancelActive() { if (backend.isInitialized()) backend.value.cancelActiveRun() }

    override suspend fun home(): HomeData {
        val b = backend.value
        val pipelines = (b.pipelines() as JsonArray).mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            PipelineRow(
                id, o["name"]?.jsonPrimitive?.contentOrNull ?: id, (o["versions"] as? JsonArray)?.size ?: 0,
                (o["live"] as? JsonObject)?.get("version")?.jsonPrimitive?.intOrNull,
            )
        }
        val runs = b.runs().take(5).mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            RunRow(
                o["status"]?.jsonPrimitive?.contentOrNull.orEmpty(), o["frameCount"]?.jsonPrimitive?.intOrNull ?: 0,
                o["calls"]?.jsonPrimitive?.intOrNull ?: 0, o["startedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
            )
        }
        return HomeData(pipelines, runs, b.activePublish()["active"]?.jsonPrimitive?.contentOrNull == "true")
    }

    override suspend fun rollback() = backend.value.rollback(null)

    override suspend fun pipeline(id: String): Pipeline? =
        backend.value.pipeline(id)?.get("json")?.jsonPrimitive?.contentOrNull?.let { PipelineJson.decode(it).getOrNull() }
}
