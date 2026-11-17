package com.rokid.context.phone.nexus

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.rokid.context.core.model.HudHost
import io.github.pca.nexusport.DropReason
import io.github.pca.nexusport.HudEvent
import io.github.pca.nexusport.NexusActionHandler
import io.github.pca.nexusport.NexusConfig
import io.github.pca.nexusport.NexusGates
import io.github.pca.nexusport.NexusHudSink
import io.github.pca.nexusport.NexusResult
import io.github.pca.nexusport.Outcome
import io.github.pca.nexusport.Slot
import io.github.pca.nexusport.TripReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Where PCA's HUD items go (the provider in production, a fake in tests). Null = nothing was sent (the host is not Nexus, or the bridge is not connected). */
fun interface HudShow {
    suspend fun show(event: HudEvent): Outcome?
}

/** The connection to the bridge APK's `NexusPortService`. */
interface BridgeLink {
    enum class Bind { BOUND, NOT_INSTALLED, REFUSED }

    fun bind(onConnected: (IBinder) -> Unit, onGone: () -> Unit): Bind
    fun unbind()
}

/** The real link: an explicit bind to the bridge's service (signature permission BIND_NEXUS_PORT; an unrelated key makes Android refuse it). Never starts anything else. */
class ServiceBridgeLink(private val app: Context) : BridgeLink {
    private var conn: ServiceConnection? = null

    override fun bind(onConnected: (IBinder) -> Unit, onGone: () -> Unit): BridgeLink.Bind {
        val c = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) { if (service != null) onConnected(service) }
            override fun onServiceDisconnected(name: ComponentName?) = onGone()
            override fun onBindingDied(name: ComponentName?) = onGone()
            override fun onNullBinding(name: ComponentName?) = onGone()
        }
        val intent = Intent().setComponent(ComponentName(NexusPackages.BRIDGE, NexusPackages.BRIDGE + ".NexusPortService"))
        return try {
            if (app.bindService(intent, c, Context.BIND_AUTO_CREATE)) { conn = c; BridgeLink.Bind.BOUND }
            else { try { app.unbindService(c) } catch (_: Exception) { }; BridgeLink.Bind.NOT_INSTALLED }
        } catch (_: SecurityException) {
            BridgeLink.Bind.REFUSED
        }
    }

    override fun unbind() {
        conn?.let { try { app.unbindService(it) } catch (_: Exception) { } }
        conn = null
    }
}

/**
 * v2.0: PCA's side of the Nexus bridge. FLAG-OFF: it binds only while the user chose Nexus as the HUD host ([host]), and does nothing at all otherwise. It holds the ONE [NexusHudSink] (the choke point:
 * provenance, zone fences, pause, not worn, fail closed) over the real fences ([gates]), forwards HUD items to it and fans every close event ([trip]) into it.
 * Switching the host away from Nexus is a MODE_OFF trip (everything hidden, history wipe asked), and the bridge stays bound until Nexus accepted the wipe or [teardownMaxMs] passed.
 * Logs: literal event names only (the sink's own lines are forwarded through [log]); never card text.
 */
class NexusProvider(
    private val link: BridgeLink,
    private val host: () -> HudHost,
    private val gates: NexusGates,
    private val actions: NexusActionHandler,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val tickMs: Long = 1_000L,
    private val teardownMaxMs: Long = 30_000L,
) : HudShow {
    private val lock = Any()
    @Volatile private var sink: NexusHudSink? = null
    private var wanted = false
    private var stopping = false
    private var stoppingSince = 0L
    private var tickJob: Job? = null

    private val _bound = MutableStateFlow(false)
    /** The bridge is connected. */
    val bound: StateFlow<Boolean> = _bound
    private val _bind = MutableStateFlow<BridgeLink.Bind?>(null)
    /** What the last bind attempt said (null = none yet). */
    val bind: StateFlow<BridgeLink.Bind?> = _bind
    private val _lastCode = MutableStateFlow<Int?>(null)
    /** The last [NexusResult] code a HUD call ended with (null = none yet): the evidence of the settings screen. */
    val lastCode: StateFlow<Int?> = _lastCode

    fun liveSlots(): Set<Slot> = sink?.liveSlots() ?: emptySet()

    /** Brings the connection in line with the host choice. Idempotent; call it after a switch, at start and when the app comes to the front. */
    fun refresh() {
        synchronized(lock) {
            if (host() == HudHost.NEXUS) {
                stopping = false
                if (!wanted) {
                    wanted = true // before the bind: a connection may be reported before bindService returns
                    val r = link.bind(::onConnected, ::onGone)
                    _bind.value = r
                    wanted = r == BridgeLink.Bind.BOUND
                    when (r) {
                        BridgeLink.Bind.BOUND -> log("nexus bind bound")
                        BridgeLink.Bind.NOT_INSTALLED -> log("nexus bind not installed")
                        BridgeLink.Bind.REFUSED -> log("nexus bind refused")
                    }
                }
            } else if (wanted && !stopping) {
                val s = sink
                if (s == null) { link.unbind(); wanted = false; _bound.value = false }
                else {
                    s.trip(TripReason.MODE_OFF)
                    stopping = true
                    stoppingSince = clock()
                    log("nexus switched off: waiting for the history wipe")
                }
            }
        }
    }

    private fun onConnected(binder: IBinder) {
        synchronized(lock) {
            if (!wanted) return
            sink = try {
                NexusHudSink.connect(binder, gates, { NexusConfig(enabled = host() == HudHost.NEXUS) }, actions, log)
            } catch (_: Exception) { log("nexus connect failed"); null }
            _bound.value = sink != null
            if (sink != null) startTick()
        }
    }

    private fun onGone() {
        synchronized(lock) { sink = null; _bound.value = false; tickJob?.cancel(); tickJob = null }
        log("nexus bridge gone")
    }

    private fun startTick() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive) {
                delay(tickMs)
                try { sink?.tick() } catch (c: CancellationException) { throw c } catch (_: Exception) { log("nexus tick failed") }
                if (finishStopIfDue()) break
            }
        }
    }

    private fun finishStopIfDue(): Boolean = synchronized(lock) {
        if (!stopping) return false
        val owed = sink?.forgetOwed() == true
        if (owed && clock() - stoppingSince < teardownMaxMs) return false
        link.unbind(); sink = null; wanted = false; stopping = false; _bound.value = false; tickJob = null
        log("nexus unbound")
        true
    }

    /** A close event. Hides everything on Nexus at once (and asks for the history wipe on Forget, Delete all and mode off). Safe from any thread; a no-op while nothing is connected. */
    fun trip(reason: TripReason) {
        val s = sink ?: return
        try { s.trip(reason) } catch (_: Exception) { log("nexus trip failed") }
    }

    override suspend fun show(event: HudEvent): Outcome? {
        if (host() != HudHost.NEXUS) return null
        val s = sink ?: return null
        val o = try { s.show(event) } catch (c: CancellationException) { throw c } catch (_: Exception) { log("nexus show failed"); return Outcome.Dropped(DropReason.GATE_ERROR) }
        when (o) {
            is Outcome.Shown -> _lastCode.value = NexusResult.OK
            is Outcome.Dropped -> if (o.reason == DropReason.TRANSPORT) _lastCode.value = o.code
        }
        return o
    }
}
