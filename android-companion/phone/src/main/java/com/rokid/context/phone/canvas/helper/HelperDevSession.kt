package com.rokid.context.phone.canvas.helper

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.rokid.context.canvas.helper.CanvasLogSink
import com.rokid.context.core.log.Level
import com.rokid.context.memory.canvas.HelperDeletion
import com.rokid.context.memory.canvas.HelperDeletionCause
import com.rokid.context.memory.canvas.HelperSessionHooks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Every way the helper dev session (and with it the canvas server and service) ends (S52). They all go through [HelperDevSession.terminate]: there is no other end path.
 * [deletion] is the reason code stored on the voided jobs and their cleanup rows.
 */
enum class SessionEnd(val deletion: HelperDeletionCause) {
    /** The notification's Stop action. */
    NOTIFICATION_STOP(HelperDeletionCause.SESSION_END),
    /** `IdlePolicy`: 30 min without an authenticated request, no run, no open screen. */
    IDLE_STOP(HelperDeletionCause.SESSION_END),
    /** Four hours without user canvas activity. */
    EXPIRY(HelperDeletionCause.SESSION_END),
    /** The session's own Wi-Fi observer saw the LAN go. */
    LAN_LOSS(HelperDeletionCause.SESSION_END),
    DEVELOPER_MODE_OFF(HelperDeletionCause.DEVELOPER_MODE_OFF),
    /** The service was destroyed, or the server stopped under it. */
    SHUTDOWN(HelperDeletionCause.SESSION_END),
    /** Android's foreground-service time limit, or a start the system refused. */
    TIME_LIMIT(HelperDeletionCause.SESSION_END),
}

/**
 * The job half of a session end, behind a seam so the session is testable: the production binding is [DeletionJobEnder] over WP4's `HelperDeletion`, which runs the
 * credential hooks (grants off, token revoked) and then voids EVERY open job (also one awaiting its tap) and writes its cleanup row in one database transaction.
 */
interface HelperJobEnder {
    suspend fun grantOff(): Int
    suspend fun endSession(cause: HelperDeletionCause): Int
    /** Process start: every open job belonged to a dead process. */
    suspend fun recover(): Int
}

class DeletionJobEnder(private val deletion: HelperDeletion) : HelperJobEnder {
    override suspend fun grantOff() = deletion.grantOff()
    override suspend fun endSession(cause: HelperDeletionCause) = deletion.endSession(cause)
    override suspend fun recover() = deletion.restart()
}

/**
 * Binds WP4's credential seam to WP2's classes: grant off = every grant cleared, revoke = the helper token AND the memory-only confirmation key (a tag made before the end
 * never verifies again). Both fail closed: a grant file that cannot be written throws (WP4 still voids the jobs, then rethrows).
 */
class PhoneSessionHooks(private val grants: HelperGrants, private val revoke: () -> Unit, private val rotateKey: () -> Unit) : HelperSessionHooks {
    override fun grantsOff() {
        if (!grants.allOff() && !grants.set(HelperGrants.PY_EXEC, false)) throw IllegalStateException("grant off failed")
    }

    override fun revokeHelper() {
        revoke()
        rotateKey()
    }
}

/** What watches the LAN for the session (a `ConnectivityManager` callback in production). Owned by [HelperDevSession], registered while a session is active. */
interface NetworkWatch {
    val registered: Boolean
    fun register(onLost: () -> Unit)
    fun unregister()
}

class ConnectivityNetworkWatch(context: Context) : NetworkWatch {
    private val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    @Volatile private var callback: ConnectivityManager.NetworkCallback? = null
    override val registered: Boolean get() = callback != null

    @Synchronized
    override fun register(onLost: () -> Unit) {
        if (callback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) { onLost() }
        }
        try {
            cm?.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), cb)
            callback = cb
        } catch (_: Exception) { /* no permission or no service: there is nothing to watch; the inactivity expiry still ends the session */ }
    }

    @Synchronized
    override fun unregister() {
        val cb = callback ?: return
        callback = null
        try { cm?.unregisterNetworkCallback(cb) } catch (_: Exception) { }
    }
}

/**
 * The laptop-helper dev session (core plan WP6, S52). A session starts at the first pairing acceptance or grant-on (native actions only) and ends in exactly ONE way,
 * [terminate]: turn the grants off, revoke the helper token (and the confirmation key), void every open job and enqueue its cleanup (WP4's hooks, one transaction), then stop
 * the canvas server and service ([stopServer]). It is idempotent, and it runs for every end: notification Stop, idle stop, four-hour expiry, Wi-Fi loss, developer mode off,
 * Disconnect, service shutdown and the time limit. The server always stops, even when an earlier step failed.
 *
 * Accepted: a server restart (LAN toggle, certificate rotation) ABORTS open helper jobs through the backend start while this session lives on, and the four-hour expiry can only fire
 * while the service lives (a helper-only session falls under the existing 30 min idle rule).
 *
 * Two clocks never mix. The inactivity expiry reads [userActivityMs] (the newest authenticated BROWSER request: helper polls and heartbeats never reach it) and the open canvas
 * screen; [inactivityMs] (four hours) without either ends the session. The Wi-Fi observer belongs to the session (registered at start, unregistered at the end), not to any screen.
 */
class HelperDevSession(
    private val clock: () -> Long,
    private val scope: CoroutineScope,
    private val ender: HelperJobEnder,
    private val hooks: HelperSessionHooks,
    private val network: NetworkWatch,
    private val userActivityMs: () -> Long,
    private val screenOpen: () -> Boolean,
    private val stopServer: () -> Unit,
    private val onStart: () -> Unit = {},
    private val onEnded: () -> Unit = {},
    private val inactivityMs: Long = INACTIVITY_MS,
    private val checkEveryMs: Long = CHECK_EVERY_MS,
    private val log: CanvasLogSink = CanvasLogSink { _, _, _ -> },
) {
    companion object {
        const val INACTIVITY_MS = 4 * 3_600_000L
        const val CHECK_EVERY_MS = 60_000L
    }

    private val lock = Any()
    @Volatile var active = false
        private set
    /** True until an end has run for the session that is (or may be) open: the first [terminate] of a process always runs the full end, so a grant left on by a dead process is cleared. */
    private var needsEnd = true
    private var startedAt = 0L
    @Volatile private var lastScreenAt = 0L
    private var endJob: Job? = null
    private var watcher: Job? = null

    private fun line(msg: String) { try { log.log(Level.I, "canvas", msg) } catch (_: Throwable) { } }

    /** Starts a session (first pairing acceptance or grant-on). A second call while one is active does nothing: the confirmation key is made once per session. */
    fun start() {
        synchronized(lock) {
            if (active) return
            if (endJob?.isActive == true) return // an end is still queued: it would revoke this session's credentials; the card shows the truth and the user taps again
            active = true
            needsEnd = true
            startedAt = clock()
            lastScreenAt = 0L
            onStart()
            network.register { terminate(SessionEnd.LAN_LOSS) }
            watcher?.cancel()
            watcher = scope.launch { while (isActive) { delay(checkEveryMs); checkExpiry() } }
            line("session started")
        }
    }

    /** The canvas screen opened or closed: user activity (the last moment of a screen counts even when it fell between two checks). */
    fun noteScreen() { lastScreenAt = clock() }

    /** The four-hour rule. True when it ended the session (the end itself runs on [scope]). */
    fun checkExpiry(): Boolean {
        if (!active) return false
        val now = clock()
        if (screenOpen()) lastScreenAt = now
        val last = maxOf(startedAt, userActivityMs(), lastScreenAt)
        if (now - last < inactivityMs) return false
        terminate(SessionEnd.EXPIRY)
        return true
    }

    /** The one end of a session that also stops the server. Never blocks the caller: the database work runs on [scope]; the returned job completes when the end (including the server stop) is done. */
    fun terminate(cause: SessionEnd): Job = end(cause.deletion, stop = true)

    /**
     * The user's Disconnect: ends the HELPER session only (grants off, token revoked, confirmation key rotated, every open job voided with its cleanup, observer and watcher
     * stopped) and does NOT stop the canvas server or service, so the screen the user is on stays up. Idempotent and sharing the latch of [terminate].
     */
    fun endHelperSession(): Job = end(HelperDeletionCause.DISCONNECT, stop = false)

    private fun end(deletion: HelperDeletionCause, stop: Boolean): Job {
        val runEnd = synchronized(lock) {
            if (!needsEnd) false else {
                needsEnd = false
                active = false
                watcher?.cancel(); watcher = null
                network.unregister()
                true
            }
        }
        if (runEnd) {
            val job = scope.launch {
                try {
                    ender.endSession(deletion)
                } catch (e: CancellationException) { throw e } catch (e: Exception) { line("session end failed") } finally {
                    try { onEnded() } catch (_: Throwable) { }
                    if (stop) stopServer() // last, and whatever happened before
                    line("session ended")
                }
            }
            synchronized(lock) { endJob = job }
            return job
        }
        // already ended (or ending). An end that is still running stops the server itself; otherwise stop it NOW, inline (a cheap synchronized stop): a queued stop could land on a server
        // that was started in the meantime.
        val running = synchronized(lock) { endJob?.takeIf { it.isActive } }
        if (running != null) return running
        if (stop) stopServer()
        return kotlinx.coroutines.CompletableDeferred(Unit)
    }

    /** Waits for the end that is running or ran last (tests). */
    suspend fun awaitEnd() { synchronized(lock) { endJob }?.join() }

    /**
     * Process start: a restart killed every token and confirmation (they are memory-only) but not the persisted grant or the open job rows. Clears the grants, revokes, and
     * ABORTS the open jobs with their cleanup (WP4 `restart`). It does NOT stop the server: a screen may already have started one.
     */
    fun recoverAtStart(): Job = scope.launch {
        try { hooks.grantsOff() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        try { hooks.revokeHelper() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        try { ender.recover() } catch (e: CancellationException) { throw e } catch (_: Exception) { line("restart recovery failed") }
        line("restart recovered")
    }
}
