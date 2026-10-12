package com.rokid.context.link

import android.app.Activity
import android.content.Context
import android.util.Log
import com.rokid.context.core.model.LinkState
import com.rokid.cxr.Caps
import com.rokid.cxr.session.AiInterceptMode
import com.rokid.cxr.session.CloseReason
import com.rokid.cxr.session.CxrSession
import com.rokid.cxr.session.CxrSessionManager
import com.rokid.cxr.session.GlassPermission
import com.rokid.cxr.session.ICustomCmdSessionCallback
import com.rokid.cxr.session.ISessionLifecycleCbk
import com.rokid.cxr.session.PausedReason
import com.rokid.cxr.session.SessionConfig
import com.rokid.cxr.session.SessionErrorCode
import com.rokid.cxr.session.SessionTimeouts
import com.rokid.cxr.session.SessionType
import com.rokid.cxr.session.TerminatingReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Live glasses link over Rokid CXR-L via the Hi Rokid app (spec §1.5, §1.7), ported from Live Fit's CxrGlassesLink.
 * Lifecycle decisions come from [GlassesSessionPolicy]; this class only performs actions. Everything below is touched
 * only on [scope] (Main): CXR callbacks are posted there first, and callers use [send] from that scope.
 */
class CxrGlassesLink(context: Context, private val scope: CoroutineScope) : GlassesLink {
    private val app = context.applicationContext
    private val manager = CxrSessionManager.getInstance(app)
    private val policy = GlassesSessionPolicy()
    private var session: CxrSession? = null
    private val auth = AuthGate()
    private var authJob: Job? = null
    private var authDeclined = false // a real decline (or a failed launch); a timeout is not one
    private var authTimedOut = false
    private var retryJob: Job? = null
    private var connectJob: Job? = null
    private val inbound = GlassesInbound() // thread-safe: fed from CXR callback threads
    /** Session-less CXRLink kept bound only while "closed on glasses", to notice the wearer reopening our app there. */
    private val watcher = GlassAppWatcher(app, GLASSES_PKG) { post { act(policy.onEvent(LinkEvent.GlassesAppOpened)) } }

    private val _state = MutableStateFlow(if (token() == null) LinkState.NOT_AUTHORIZED else LinkState.OFFLINE)
    override val state: StateFlow<LinkState> = _state
    private val _events = MutableSharedFlow<GlassesEvent>(extraBufferCapacity = 256) // audio arrives as ~10 chunks/s
    override val events: SharedFlow<GlassesEvent> = _events
    private val _ready = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    override val ready: SharedFlow<Unit> = _ready

    init { instance = this }

    private fun post(block: () -> Unit) { scope.launch { block() } }
    private fun token(): String? = app.getSharedPreferences(PREFS, 0).getString(KEY_TOKEN, null)
    private fun now() = System.currentTimeMillis()
    private fun setState(s: LinkState) { if (_state.value != s) { _state.value = s; Log.i(TAG, "link=$s") } }

    /** Silent when already authorized in Hi Rokid; sets CXR-L's in-memory flags (Live Fit quirk 2.1.1). */
    fun authorize(activity: Activity, onDone: (Boolean) -> Unit) {
        try {
            manager.requestAuthorization(activity, listOf(GlassPermission.MICROPHONE, GlassPermission.DEVICE_MANAGE, GlassPermission.MEDIA)) { r ->
                Log.i(TAG, "authorize ok=${r.isSuccess} code=${r.errorCode}")
                r.token?.takeIf { r.isSuccess }?.let { app.getSharedPreferences(PREFS, 0).edit().putString(KEY_TOKEN, it).apply() }
                // Success without any usable token would loop AuthActivity: treat it as a failure.
                val ok = r.isSuccess && (r.token != null || token() != null)
                post { onAuthResult(ok) }
                onDone(ok)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "authorize failed (${e.javaClass.simpleName})")
            post { onAuthResult(false) }
            onDone(false)
        }
    }

    /** Result of an in-process authorization (also fed by [AuthActivity.onActivityResult]). */
    internal fun onAuthResult(ok: Boolean) {
        if (!auth.result(ok)) return // late or duplicate report for an attempt already resolved
        authJob?.cancel(); authJob = null
        authTimedOut = false
        if (ok) { authDeclined = false; act(policy.manualConnect()) }
        else { authDeclined = true; act(policy.onEvent(LinkEvent.AuthFailed)) } // no auto-relaunch
    }

    override fun connect() = post { auth.clearStale(now()); authDeclined = false; authTimedOut = false; act(policy.manualConnect()) }

    override fun connectOnce() = post { auth.clearStale(now()); act(policy.autoConnect()) }

    override fun connectOnResume() = post {
        if (!shouldReconnectOnResume(_state.value, token() != null, authDeclined, authTimedOut)) return@post
        Log.i(TAG, "phone app resumed: reconnecting")
        authTimedOut = false
        auth.clearStale(now())
        act(policy.manualConnect())
    }

    override fun close() = post {
        watcher.stop()
        retryJob?.cancel(); connectJob?.cancel(); authJob?.cancel()
        auth.cancel() // a late AuthActivity result must not reopen a session after stop
        authTimedOut = false
        policy.reset() // session is nulled below, so its close callback never reaches the policy
        val s = session
        session = null
        s?.close()
        setState(if (token() == null) LinkState.NOT_AUTHORIZED else LinkState.OFFLINE)
    }

    private fun act(actions: List<LinkAction>) {
        if (actions.isNotEmpty()) { retryJob?.cancel(); retryJob = null; connectJob?.cancel(); connectJob = null }
        for (a in actions) when (a) {
            LinkAction.Connect -> openSession()
            LinkAction.SendSettings -> _ready.tryEmit(Unit)
            is LinkAction.ScheduleRetry -> retryJob = scope.launch { delay(a.delayMs); act(policy.onEvent(LinkEvent.RetryTimer)) }
            else -> a.linkState()?.let(::setState)
        }
        syncWatcher()
    }

    /** Watch the glasses' foreground app only while closed on glasses; any other state releases the extra bind. */
    private fun syncWatcher() {
        if (!policy.closedOnGlasses) { watcher.stop(); return }
        if (watcher.active) return
        val t = token() ?: return
        preferGlobalHiRokid()
        watcher.start(t)
    }

    private fun openSession() {
        watcher.stop() // unbind the watcher before a session binds its own CXRLink
        val token = token()
        if (token == null || !auth.authorized) {
            // CXR-L only works after an in-process requestAuthorization, so connect waits for AuthActivity.
            if (authDeclined) { act(policy.onEvent(LinkEvent.AuthFailed)); return }
            if (!auth.begin(now())) return
            // A blocked background activity start never reports back: give up after the timeout.
            authJob = scope.launch {
                delay(AuthGate.AUTH_TIMEOUT_MS)
                auth.expire()
                authTimedOut = true // not a decline: with a token, the next phone-app resume retries
                act(policy.onEvent(LinkEvent.AuthFailed))
            }
            if (AuthActivity.launch(app).isFailure) onAuthResult(false)
            return
        }
        connectJob = scope.launch { delay(CONNECT_TIMEOUT_MS); act(policy.onEvent(LinkEvent.ConnectTimeout)) }
        try {
            preferGlobalHiRokid()
            // Detach the old session first so its late close callback cannot clobber the new one.
            val old = session
            session = null
            old?.close()
            inbound.reset()
            val s = manager.create(
                SessionConfig(
                    sessionType = SessionType.CUSTOM_APP,
                    glassesPackageName = GLASSES_PKG,
                    aiInterceptMode = AiInterceptMode.ALLOW_WITH_PAUSE,
                    terminatingGracePeriodMs = 5_000L,
                    timeouts = SessionTimeouts(),
                    viewData = "",
                    viewIconData = "",
                    glassesActivityName = GLASSES_ACTIVITY,
                    glassesApkPath = "",
                ),
            )
            s.addLifecycleCallback(lifecycleFor(s))
            s.addCustomCmdCallback(object : ICustomCmdSessionCallback {
                override fun onCustomCmdResult(cmd: String, bytes: ByteArray?) = onGlassesMessage(cmd, bytes)
            })
            session = s
            s.connect(token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "openSession failed (${e.javaClass.simpleName})")
            session = null
            act(policy.onEvent(LinkEvent.ConnectFailed))
        }
    }

    /** Forget the rejected token; the retry goes through the bounded authorization path in [openSession]. */
    private fun onTokenRejected() {
        app.getSharedPreferences(PREFS, 0).edit().remove(KEY_TOKEN).apply()
        if (auth.tokenRejected()) act(policy.onEvent(LinkEvent.ConnectFailed))
        else { authDeclined = true; act(policy.onEvent(LinkEvent.AuthFailed)) }
    }

    /** CXR callback thread. Glasses send text only (phase 0): read up to [MAX_PARTS] Caps strings in order. */
    private fun onGlassesMessage(cmd: String, bytes: ByteArray?) {
        val parts = try {
            val caps = Caps.fromBytes(bytes ?: return)
            val out = ArrayList<String>(MAX_PARTS)
            for (i in 0 until MAX_PARTS) out.add(runCatching { caps.at(i).string }.getOrNull() ?: break)
            out
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "bad payload on $cmd: ${e.javaClass.simpleName}")
            return
        }
        inbound.onMessage(cmd, parts).forEach { _events.tryEmit(it) }
    }

    override suspend fun send(channel: String, json: String): Boolean {
        if (_state.value != LinkState.CONNECTED) return false
        val s = session ?: return false
        return try {
            val r = s.sendCustomCmd(channel, Caps().apply { write(json) }, ByteArray(0))
            if (!r.isSuccess) Log.w(TAG, "send $channel failed ${r.code}")
            r.isSuccess
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "send $channel threw ${e.javaClass.simpleName}")
            false
        }
    }

    /** CXR callbacks arrive on binder threads: hop onto [scope] before touching the policy or session. */
    private fun lifecycleFor(s: CxrSession) = object : ISessionLifecycleCbk {
        private fun ifCurrent(event: LinkEvent) = post { if (s === session) act(policy.onEvent(event)) }
        override fun onSessionStarted() = post { if (s === session) { auth.sessionStarted(); act(policy.onEvent(LinkEvent.Started)) } }
        override fun onSessionPaused(reason: PausedReason) = ifCurrent(LinkEvent.Paused)
        override fun onSessionResumed() = ifCurrent(LinkEvent.Resumed)
        override fun onSessionTerminating(reason: TerminatingReason, graceMs: Long) = Unit
        override fun onSessionClosed(reason: CloseReason) = post {
            if (s !== session) return@post
            session = null
            val glassesLeft = reason == CloseReason.GLASSES_EXIT || reason == CloseReason.USER_CLOSED
            Log.i(TAG, "session closed $reason")
            act(policy.onEvent(if (glassesLeft) LinkEvent.GlassesExited else LinkEvent.Closed))
        }
        override fun onConnectResult(ok: Boolean, code: SessionErrorCode?) = post {
            if (ok || s !== session) return@post
            session = null
            Log.w(TAG, "connect failed $code")
            if (code == SessionErrorCode.TOKEN_EXPIRED || code == SessionErrorCode.NOT_AUTHENTICATED) onTokenRejected()
            else act(policy.onEvent(if (isTerminalConnectError(code)) LinkEvent.ConnectRefused else LinkEvent.ConnectFailed))
        }
    }

    /** Live Fit quirk 2.1.1: after a restart CXR-L would bind the China Hi Rokid package (phase 0 used the global build). */
    private fun preferGlobalHiRokid() {
        val global = runCatching { app.packageManager.getPackageInfo("com.rokid.sprite.global.aiapp", 0) }.isSuccess
        if (!global) return
        try {
            Class.forName("com.rokid.sprite.aiapp.externalapp.auth.AuthorizationHelper")
                .getDeclaredField("a").apply { isAccessible = true }.setBoolean(null, true)
        } catch (e: Exception) {
            Log.w(TAG, "could not force global Hi Rokid (${e.javaClass.simpleName})")
        }
    }

    companion object {
        const val TAG = "PcGlassesLink"
        /** The glasses app's applicationId (`glasses/build.gradle.kts`). */
        const val GLASSES_PKG = "com.rokid.context.glasses"
        const val GLASSES_ACTIVITY = "com.rokid.context.glasses.MainActivity"
        const val CONNECT_TIMEOUT_MS = 15_000L
        /** `pc_frame` carries two strings (meta + chunk); every other channel one. */
        private const val MAX_PARTS = 2
        internal const val PREFS = "rokid_link"
        internal const val KEY_TOKEN = "token"
        @Volatile var instance: CxrGlassesLink? = null
    }
}
