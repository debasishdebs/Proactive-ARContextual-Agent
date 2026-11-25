package com.rokid.context.phone.canvas

import com.rokid.context.phone.hub.HubGraph
import android.content.Context
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.canvas.Pipeline
import com.rokid.context.ai.canvas.PipelineJson
import com.rokid.context.canvas.CanvasPublisher
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.CanvasMaintenance
import com.rokid.context.core.log.Level
import com.rokid.context.phone.canvas.api.CanvasApi
import com.rokid.context.phone.canvas.api.CanvasBackendImpl
import com.rokid.context.phone.canvas.server.ApiHandler
import com.rokid.context.phone.canvas.server.AssetSource
import com.rokid.context.phone.canvas.server.CanvasAuth
import com.rokid.context.phone.canvas.server.CanvasRouter
import com.rokid.context.phone.canvas.server.CanvasServer
import com.rokid.context.phone.canvas.server.HelperAuth
import com.rokid.context.phone.canvas.helper.HelperBackend
import com.rokid.context.phone.canvas.helper.HelperConfirmActions
import com.rokid.context.phone.canvas.helper.HelperDevSession
import com.rokid.context.phone.canvas.helper.SessionEnd
import com.rokid.context.phone.canvas.helper.HelperNativeActions
import com.rokid.context.phone.log.PhoneLogging
import com.rokid.context.phone.canvas.server.HostPolicy
import com.rokid.context.phone.canvas.server.KeystoreTlsIdentity
import com.rokid.context.phone.canvas.server.TlsIdentity
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
import kotlin.coroutines.cancellation.CancellationException

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
    /** False when the phone cannot make or serve the TLS key: LAN stays off, there is no plain-HTTP fallback. */
    val lanAvailable: Boolean = true,
    /** SHA-256 of the LAN certificate in 16 groups of 4 hex digits (public; the user compares it in the browser). */
    val fingerprint: String? = null,
    /** Pairing is locked out and waits for the explicit "New code". */
    val pairLocked: Boolean = false,
    /** Wrong pairing attempts since the code was minted, from any device. */
    val wrongAttempts: Int = 0,
) {
    /** The scheme the server speaks now: https while LAN is on (loopback included), plain http on loopback otherwise. */
    val scheme: String get() = if (lan) "https" else "http"
    val localUrl: String? get() = if (on && port != null) "$scheme://127.0.0.1:$port" else null
    val lanUrl: String? get() = if (on && lan && lanIp != null && port != null) "https://$lanIp:$port" else null
}

data class PipelineRow(val id: String, val name: String, val versions: Int, val liveVersion: Int?)
data class RunRow(val status: String, val photos: Int, val calls: Int, val startedAt: Long)
/** The newest active publish of either kind (the stack's first row): what "Roll back" would undo, named so the click can say which one it means. */
data class PublishRow(val id: Long, val kind: String, val mode: String?)
data class HomeData(val pipelines: List<PipelineRow>, val runs: List<RunRow>, val publishActive: Boolean, val newestPublish: PublishRow? = null)

/** The data side of the canvas, behind one seam so the graph is testable without the AI graph and the database. */
interface CanvasCore {
    val api: ApiHandler
    suspend fun start()
    fun runActive(): Boolean
    /** Cancels the active run, if any (Developer mode turned off). */
    suspend fun cancelActive()
    suspend fun home(): HomeData
    /** Rolls back the publish [expectedId], the one the user saw as the newest. */
    suspend fun rollback(expectedId: Long): CanvasPublisher.Outcome
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
    /** The live pairing code, or a new one when none is live or it expired; null after a lockout until [newPairingCode]. */
    fun pairingCode(): CanvasAuth.Code?
    /** The explicit "New code" (the only way out of a pairing lockout). */
    fun newPairingCode(): CanvasAuth.Code
    /** "New certificate": rotates the TLS key, revokes every session and restarts LAN. */
    fun rotateCertificate()
    /** The LAN certificate identity, for the WebView's pinning; null when LAN is unavailable. */
    fun tlsIdentity(): TlsIdentity?
    /** Ends a WebView session the screen no longer uses (a re-key must not evict a paired laptop). */
    fun endWebViewSession(token: String)
    fun webViewToken(): String
    /** Re-reads the Wi-Fi address into [status]. */
    fun refreshNetwork()
    /** The service's idle rule (see [CanvasGraph.shouldIdleStop]). */
    fun shouldIdleStop(): Boolean
    /** Stops the server and tells the canvas screen why, in fixed text. */
    fun stopWithNotice(text: String, cause: SessionEnd = SessionEnd.SHUTDOWN)
    /**
     * 3b WP2 seams, inert by default (the production host answers "no helper, nothing pending" until WP6 binds the real ones): the native laptop-helper card actions
     * and the per-run confirmation callbacks. Neither has an HTTP surface.
     */
    val helperNative: HelperNativeActions get() = HelperNativeActions.UNAVAILABLE
    val helperConfirm: HelperConfirmActions get() = HelperConfirmActions.NONE
    /**
     * 3b WP6 (S52): THE way the canvas stops. The service's every stop path (notification Stop, idle stop, developer mode off, time limit, shutdown) calls this with its cause; a host with
     * a helper session ends that session first (grants off, token revoked, jobs voided, cleanup enqueued) and stops the server last. A host without one just stops.
     */
    fun terminateSession(cause: SessionEnd) { stop() }
    suspend fun home(): HomeData
    /** Rolls back the publish [expectedId], the one the user saw as the newest. */
    suspend fun rollback(expectedId: Long): CanvasPublisher.Outcome
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
    /** Makes the LAN certificate identity (the keystore on a phone); null or a throw means "Secure LAN isn't available". Called once, off the UI thread when it can be. */
    private val tlsFactory: () -> TlsIdentity? = { null },
    /** Trace retention (S44): once at graph creation and on each server start. */
    private val prune: suspend () -> Unit = {},
    private val newServer: (CanvasRouter, Boolean, TlsIdentity?) -> CanvasServer = { r, lan, tls ->
        CanvasServer(r, lan, tls, log, onStart = { scope.launch { try { prune() } catch (e: CancellationException) { throw e } catch (e: Exception) { /* history only */ } } })
    },
    /** 3b WP2: the laptop helper's identity and its authenticated routes (WP6 binds both and the two seams below; null = no helper). */
    private val helperAuth: HelperAuth? = null,
    private val helperApi: ApiHandler? = null,
    override val helperNative: HelperNativeActions = HelperNativeActions.UNAVAILABLE,
    override val helperConfirm: HelperConfirmActions = HelperConfirmActions.NONE,
    /** 3b WP6: the helper dev session (S52); every stop path of this graph goes through its one termination operation. Null = no helper. */
    private val helperSession: HelperDevSession? = null,
) : CanvasHost {
    @Volatile private var server: CanvasServer? = null
    @Volatile private var lanOn = false
    @Volatile private var startedAt = 0L

    // The LAN certificate identity. Only a success is remembered: a keystore error is retried (at most every 30 s, from the
    // screen's network refresh), so one transient failure does not disable LAN until the process dies. Keygen never runs on the caller.
    private val tlsLock = Any()
    @Volatile private var tlsId: TlsIdentity? = null
    @Volatile private var tlsFailed = false
    @Volatile private var tlsAttemptAt = Long.MIN_VALUE
    private val tls: TlsIdentity? get() = tlsId

    private fun ensureTls() {
        synchronized(tlsLock) {
            if (tlsId != null) return
            tlsAttemptAt = clock()
            try {
                tlsId = tlsFactory()
                tlsFailed = tlsId == null
            } catch (e: Exception) {
                log("canvas TLS unavailable (${e.javaClass.simpleName})")
                tlsFailed = true
            }
        }
        publish()
    }

    private val router = CanvasRouter(
        auth, HostPolicy(port = { server?.port ?: 0 }, lanIp = { if (lanOn) wifiIp() else null }, secure = { lanOn }), core.api, assets, helperAuth = helperAuth, helperApi = helperApi, log = log,
    )
    private val _status = MutableStateFlow(CanvasStatus())
    override val status: StateFlow<CanvasStatus> = _status.asStateFlow()
    @Volatile private var screen = false
    /** True while a canvas screen shows. Each change counts as user activity for the helper session's four-hour clock. */
    override var screenOpen: Boolean
        get() = screen
        set(v) { screen = v; helperSession?.noteScreen() }

    /** Whether LAN is on right now (the backend's session info reads this). */
    val lan: Boolean get() = lanOn

    init {
        // The certificate (a keystore key on first use) and one trace prune, both off the caller's thread.
        scope.launch { ensureTls() }
        scope.launch { try { prune() } catch (e: CancellationException) { throw e } catch (e: Exception) { /* history only */ } }
        scope.launch { devMode.collect { on -> if (!on) { terminateSession(SessionEnd.DEVELOPER_MODE_OFF); revokeAll(); try { core.cancelActive() } catch (e: Exception) { log("canvas cancel failed (${e.javaClass.simpleName})") } } } }
    }

    @Synchronized
    override fun start(lan: Boolean) {
        if (!devMode.value) return
        if (server != null && lanOn == lan) { publish(); return }
        server?.stop()
        server = null
        val identity = if (lan) tls else null
        if (lan && identity == null) { // never a plain-HTTP LAN
            lanOn = false
            notice = LAN_UNAVAILABLE_NOTICE
            publish()
            return
        }
        lanOn = lan
        val s = newServer(router, lan, identity)
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
            if (wifiIp() == null || tls == null) { publish(); return }
            auth.newCode()
        }
        start(on)
    }

    override fun revokeAll() { auth.revokeAll() }

    @Synchronized
    override fun pairingCode(): CanvasAuth.Code? {
        if (auth.awaitingNewCode()) { publish(); return null } // a lockout: only the explicit "New code" mints
        return (auth.currentCode() ?: auth.newCode()).also { publish() }
    }

    @Synchronized
    override fun newPairingCode(): CanvasAuth.Code = auth.newCode().also { publish() }

    override fun tlsIdentity(): TlsIdentity? = tls

    override fun endWebViewSession(token: String) { auth.logout(token) }

    /** Off the caller's thread (keygen is a keystore round-trip). */
    override fun rotateCertificate() { scope.launch { rotateNow() } }

    /**
     * New key and certificate: every session was bound to the old one, so all are revoked, and LAN restarts on the new certificate.
     * Failure-safe: any failure (the identity keeps its old key) revokes every session, stops LAN and says LAN is unavailable.
     */
    @Synchronized
    private fun rotateNow() {
        val identity = tls ?: return
        try {
            identity.rotate()
        } catch (e: Exception) {
            log("canvas certificate rotation failed (${e.javaClass.simpleName})")
            auth.revokeAll()
            server?.stop()
            server = null
            lanOn = false
            tlsId = null // loaded again from the keystore on the next retry
            tlsFailed = true
            tlsAttemptAt = clock()
            start(false)
            notice = LAN_UNAVAILABLE_NOTICE
            publish()
            return
        }
        auth.revokeAll()
        if (lanOn && server != null) {
            server?.stop()
            server = null
            start(true)
        } else publish()
    }

    override fun webViewToken(): String = auth.newWebViewToken()

    override fun refreshNetwork() {
        if (tlsId == null && clock() - tlsAttemptAt >= TLS_RETRY_MS) scope.launch { ensureTls() }
        publish()
    }

    override suspend fun home() = core.home()
    override suspend fun rollback(expectedId: Long) = core.rollback(expectedId)
    override suspend fun pipeline(id: String) = core.pipeline(id)

    /** The service's idle rule: no authenticated request for 30 min, no run, no canvas screen open. */
    override fun shouldIdleStop(): Boolean = shouldIdleStop(clock())

    fun shouldIdleStop(now: Long): Boolean =
        server != null && IdlePolicy.shouldStop(maxOf(auth.lastActivityMs, startedAt), now, core.runActive(), screenOpen)

    @Volatile private var notice: String? = null

    override fun stopWithNotice(text: String, cause: SessionEnd) {
        notice = text
        terminateSession(cause)
    }

    /** One way to end: the session's termination (which stops this server last); without a session, just [stop]. */
    override fun terminateSession(cause: SessionEnd) {
        val session = helperSession
        if (session == null) stop() else session.terminate(cause)
    }

    private fun publish() {
        val s = server
        val code = auth.currentCode()
        val identity = tlsId
        _status.value = CanvasStatus(
            on = s != null, port = s?.port, lan = lanOn, lanIp = wifiIp(), code = code?.code, codeExpiresAt = code?.expiresAt ?: 0L, notice = notice,
            lanAvailable = identity != null || !tlsFailed, fingerprint = if (lanOn) identity?.fingerprint else null,
            pairLocked = auth.awaitingNewCode(), wrongAttempts = auth.wrongAttempts(),
        )
    }

    companion object {
        private const val TAG = "Canvas"
        private const val TLS_RETRY_MS = 30_000L
        const val LAN_UNAVAILABLE_NOTICE = "Secure LAN isn't available on this phone"
        @Volatile private var instance: CanvasGraph? = null
        @Volatile private var override: CanvasHost? = null

        /** What the canvas screens use: the process graph (a fake in route tests, see [installForTests]). */
        fun host(app: Context): CanvasHost = override ?: get(app)

        fun installForTests(host: CanvasHost?) { override = host }

        /** Cheap to call from any screen: the AI graph and the backend are built on the first server start. */
        fun get(app: Context): CanvasGraph = instance ?: synchronized(this) { instance ?: build(app.applicationContext).also { instance = it } }

        private fun build(app: Context): CanvasGraph {
            val dev = DeveloperModeStore.get(app)
            val auth = CanvasAuth(System::currentTimeMillis, devLifetimeMs = dev::sessionLifetimeMs, vault = dev.vault)
            val port = PhoneLogging.canvasPort() // the gated core port, never the platform logger (always-on rule)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t -> port.log(Level.W, TAG, "canvas task failed (${t.javaClass.simpleName})") })
            val ref = java.util.concurrent.atomic.AtomicReference<CanvasGraph>()
            // 3b WP6: the laptop helper, built once with the ONE gated logger port; its session stops THIS graph's server last.
            val helper = HelperBackend.forApp(app, auth, scope, screenOpen = { ref.get()?.screenOpen == true }, stopServer = { ref.get()?.stop() }, log = port)
            val backend = lazy { com.rokid.context.phone.canvas.api.AskPhotoBinding.bind(com.rokid.context.orchestration.AnswerService.forApp(app, extractFirst = false).askDebugPhotos()); HubGraph.get(app); CanvasBackendImpl.create(app, AiGraph.get(app), auth, lan = { ref.get()?.lan == true }, scope = scope, plans = { PlanGraph.current()?.publisher }, sharedPublisher = PlanGraph.current()?.canvasPublisher, helperJobs = helper.service, extraExecutors = com.rokid.context.canvas.ExperimentExecutors.forApp(app, helper.service), log = port) }
            val extras = lazy { CanvasExtras.create(app, scope, DeveloperModeStore.get(app).enabled) }
            val maintenance = lazy { CanvasMaintenance(MemoryGraph.get(app).db.canvasDao()) }
            val graph = CanvasGraph(
                auth, BackendCore(backend, extras, listOf(helper.browserRoutes), testRun = { b -> com.rokid.context.phone.canvas.api.TestRunRoutes(DeveloperModeStore.get(app).enabled::value, { com.rokid.context.orchestration.forget.DeveloperFlags.canvasTestRun(app) },
            // the zone state is the hub's own (fails closed until the hub installs it); it lives here, not in the backend, which must stay free of the location graph
            object : com.rokid.context.phone.canvas.api.TestRunPort by b {
                override suspend fun zoneHeldOrUnknown(): Boolean = try { HubGraph.get(app); com.rokid.context.canvas.moment.MomentLocationGraph.zoneBlockedNow() } catch (c: kotlin.coroutines.cancellation.CancellationException) { throw c } catch (e: Exception) { true }
            }) }), AssetSource { name -> runCatching { app.assets.open("canvas/$name").use { it.readBytes() } }.getOrNull() },
                DeveloperModeStore.get(app).enabled, wifiIp = { LanAddress.wifiIpv4(app) }, scope = scope, log = { port.log(Level.I, TAG, it) },
                tlsFactory = { KeystoreTlsIdentity(app) }, prune = { maintenance.value.pruneTraces() },
                helperAuth = helper.auth, helperApi = helper.routes, helperNative = helper.native, helperConfirm = helper.adapter, helperSession = helper.session,
            )
            ref.set(graph)
            helper.session.recoverAtStart() // a restart killed every token and confirmation: grants off, open jobs aborted with their cleanup (never stops the server)
            return graph
        }
    }
}

/** [CanvasCore] over the one [CanvasBackendImpl] (built on first use, then reused). */
class BackendCore(private val backend: Lazy<CanvasBackendImpl>, private val extras: Lazy<CanvasExtras>? = null, private val helperExtras: List<com.rokid.context.phone.canvas.api.ApiExtra> = emptyList(),
    /** Canvas v2.1: the Developer-only, default-OFF Test run routes (they read the flag on every call). Asked before the built-in routes, so only a body with a `source` is theirs. */
    private val testRun: ((CanvasBackendImpl) -> com.rokid.context.phone.canvas.api.ApiExtra)? = null) : CanvasCore {
    private val apiLazy = lazy { CanvasApi(backend.value, extras = (extras?.value?.extras ?: emptyList()) + helperExtras + listOfNotNull(testRun?.invoke(backend.value))) }
    override val api = ApiHandler { apiLazy.value.handle(it) }
    override suspend fun start() { backend.value.start(); extras?.value?.start() }
    override fun runActive() = backend.isInitialized() && backend.value.runActive()
    override suspend fun cancelActive() { if (backend.isInitialized()) backend.value.cancelActiveRun() } // a replay stops by itself: ReplayService watches the switch

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
        val newest = (b.activePublish()["stack"] as? JsonArray)?.firstOrNull() as? JsonObject
        val row = newest?.let { o -> o["id"]?.jsonPrimitive?.longOrNull?.let { PublishRow(it, o["kind"]?.jsonPrimitive?.contentOrNull.orEmpty(), o["mode"]?.jsonPrimitive?.contentOrNull) } }
        return HomeData(pipelines, runs, row != null, row)
    }

    override suspend fun rollback(expectedId: Long) = backend.value.rollback(expectedId)

    override suspend fun pipeline(id: String): Pipeline? =
        backend.value.pipeline(id)?.get("json")?.jsonPrimitive?.contentOrNull?.let { PipelineJson.decode(it).getOrNull() }
}
