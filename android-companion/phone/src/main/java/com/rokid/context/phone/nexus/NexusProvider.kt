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
import io.github.pca.nexusport.NexusAskHandler
import io.github.pca.nexusport.NexusSkillProvider
import io.github.pca.nexusport.NexusConfig
import io.github.pca.nexusport.NexusGates
import io.github.pca.nexusport.NexusHudSink
import io.github.pca.nexusport.NexusResult
import io.github.pca.nexusport.Outcome
import io.github.pca.nexusport.PackageSignerSource
import io.github.pca.nexusport.PcaSignerTrust
import io.github.pca.nexusport.SignerSource
import io.github.pca.nexusport.Slot
import io.github.pca.nexusport.WipeLedger
import io.github.pca.nexusport.MemoryWipeLedger
import io.github.pca.nexusport.TripReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlin.math.min
import kotlinx.coroutines.launch

/** Where PCA's HUD items go (the provider in production, a fake in tests). Null = nothing was sent (the host is not Nexus, or the bridge is not connected). */
fun interface HudShow {
    suspend fun show(event: HudEvent): Outcome?
}

/** The connection to the bridge APK's `NexusPortService`. */
interface BridgeLink {
    /** [UNTRUSTED]: a package with the bridge's name is installed but is not signed like PCA (or a pinned key): it is never bound and never given a binder. */
    enum class Bind { BOUND, NOT_INSTALLED, REFUSED, UNTRUSTED }

    fun bind(onConnected: (IBinder) -> Unit, onGone: () -> Unit): Bind
    fun unbind()

    /** The installed bridge is signed like PCA right now (checked again on every bind, every connection and every package change). Default true for fakes. */
    fun trusted(): Boolean = true

    /** The bridge package is installed at all (a removed bridge is "not installed", never "not trusted"). Default true for fakes. */
    fun present(): Boolean = true
}

/**
 * The history wipe PCA owes the Nexus hub, kept until the hub acknowledges it (persisted by [NexusHostStore]; in memory in tests). A GENERATION counter (Codex v2 r2 P1-3) correlates acknowledgements: each new debt bumps it, a
 * wipe request carries the generation it covers and [settle] clears the debt only up to that generation.
 */
interface WipeDebt : WipeLedger {
    fun wipeOwed(): Boolean = owed()
    /** The debt is paid up to the newest generation (tests and callers that know nothing newer was incurred). */
    fun settle() = settle(Long.MAX_VALUE)
    /**
     * Provider start (Codex v2 r2 P1-4): when the host is PCA and a notice may have been shown to Nexus since the last acknowledged wipe (a persisted marker written BEFORE the first notice of a generation is sent), a wipe
     * is owed, whatever the process died before recording.
     */
    fun reconcile(hostIsPca: Boolean) = Unit
}

class MemoryWipeDebt(owed: Boolean = false) : MemoryWipeLedger(owed), WipeDebt

/** The real link: an explicit bind to the bridge's service (signature permission BIND_NEXUS_PORT; an unrelated key makes Android refuse it). Never starts anything else. */
class ServiceBridgeLink(
    private val app: Context,
    private val signers: SignerSource = PackageSignerSource(app.packageManager),
    private val pinned: Set<String> = PcaSignerTrust.PINNED,
) : BridgeLink {
    private var conn: ServiceConnection? = null

    /** An explicit bind names only a package, and any app can claim the bridge's name while the genuine one is absent: the signer is verified first (Codex v2 r1 P1-3). */
    override fun trusted(): Boolean = PcaSignerTrust.trusted(signers, app.packageName, NexusPackages.BRIDGE, pinned)

    override fun present(): Boolean = try { signers.signing(NexusPackages.BRIDGE) != null } catch (_: Exception) { false }

    override fun bind(onConnected: (IBinder) -> Unit, onGone: () -> Unit): BridgeLink.Bind {
        if (try { signers.signing(NexusPackages.BRIDGE) } catch (_: Exception) { null } == null) return BridgeLink.Bind.NOT_INSTALLED
        if (!trusted()) return BridgeLink.Bind.UNTRUSTED
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
 * v2.0: PCA's side of the Nexus bridge. FLAG-OFF: it binds only while the user chose Nexus as the HUD host ([host]), and does nothing at all otherwise (the one exception: an unacknowledged history wipe, below). It holds the ONE [NexusHudSink] (the choke point:
 * provenance, zone fences, pause, not worn, fail closed) over the real fences ([gates]), forwards HUD items to it and fans every close event ([trip]) into it.
 *
 * **Trust (Codex v2 r1 P1-3).** The bridge is bound by package name, which any app can claim while the genuine bridge is absent. [BridgeLink.trusted] (the package's signing certificate against PCA's own or a pinned one)
 * is checked before every bind, again when a connection arrives (nothing is handed to the binder before that), and on every package change ([onPackageChanged]). An untrusted package is [BridgeLink.Bind.UNTRUSTED].
 *
 * **Wipe debt (Codex v2 r1 P1-5).** Forget, Delete all and mode off owe the glasses hub a history wipe (Nexus keeps notices up to 24 h). The debt is a stored flag ([debt], in `nexus_host`, not backed up) set BEFORE anything else,
 * whether or not the bridge is connected, and cleared only when the hub acknowledges. A bridge that is not connected, a teardown that times out and a process restart all keep it: the next bind (provider start, [refresh],
 * app to the front, the backoff retry of [retryBaseMs] doubling up to [retryMaxMs]) sends it again, binding the bridge for just that purpose when the host is not Nexus. Nothing else is ever drawn then.
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
    /** How long a stop waits for the hub's acknowledgement before it lets go of the bridge (the debt stays and a retry is scheduled). */
    private val teardownMaxMs: Long = 30_000L,
    /** The user's Nexus switches (all OFF by default). */
    private val switches: () -> NexusSwitches = { NexusSwitches() },
    /** The assist-button ask and the four skills; inert while the matching switch is off (the sink also checks). */
    private val asks: NexusAskHandler? = null,
    private val skills: NexusSkillProvider? = null,
    private val debt: WipeDebt = MemoryWipeDebt(),
    private val retryBaseMs: Long = 30_000L,
    private val retryMaxMs: Long = 900_000L,
    /** Tests only: the sink runs its transport calls on the caller's thread (no dispatcher thread, no timeout). */
    private val directDispatch: Boolean = false,
) : HudShow {
    private val lock = Any()
    @Volatile private var sink: NexusHudSink? = null
    private var wanted = false
    /** The connection was lost and not restored since (the bound service may be gone for good); cleared only by a connection. */
    private var dead = false
    /** The bridge was connected and then lost; the rebind retries go on (backoff) until it is connected again or Nexus is no longer the host. */
    private var lost = false
    private var stopping = false
    private var stoppingSince = 0L
    private var tickJob: Job? = null
    private var watchJob: Job? = null
    private var retryJob: Job? = null
    private var retryDelay = retryBaseMs

    private val _bound = MutableStateFlow(false)
    /** The bridge is connected. */
    val bound: StateFlow<Boolean> = _bound
    private val _bind = MutableStateFlow<BridgeLink.Bind?>(null)
    /** What the last bind attempt said (null = none yet). */
    val bind: StateFlow<BridgeLink.Bind?> = _bind
    private val _lastCode = MutableStateFlow<Int?>(null)
    /** The last [NexusResult] code a HUD call ended with (null = none yet): the evidence of the settings screen. */
    val lastCode: StateFlow<Int?> = _lastCode

    /** A history wipe is still owed to the hub (the stored flag; read on demand by the settings screen). */
    fun wipeOwed(): Boolean = try { debt.wipeOwed() } catch (_: Exception) { true }

    fun liveSlots(): Set<Slot> = sink?.liveSlots() ?: emptySet()

    /** Brings the connection in line with the host choice and the wipe debt. Idempotent; call it after a switch, at start and when the app comes to the front. */
    fun refresh() {
        synchronized(lock) {
            try { debt.reconcile(host() != HudHost.NEXUS) } catch (_: Exception) { log("nexus wipe record failed") }
            if (host() != HudHost.NEXUS) lost = false
            if (host() == HudHost.NEXUS) {
                stopping = false
                watchJob?.cancel(); watchJob = null
                // Nexus is the host and nothing is bound (first start, or the bridge died, B1 of smoke 3): try now; after a loss keep trying with the backoff.
                releaseDeadLocked()
                if (!wanted) { bindLocked(); if ((!wanted && debt.wipeOwed()) || lost) scheduleRetryLocked() }   // after a loss also while the fresh bind has not connected yet (a watchdog; a connection cancels it)
            } else if (wanted && !stopping) {
                lost = false; dead = false
                debt.owe()   // switching Nexus off is a MODE_OFF: the hub's history must be wiped, connected or not
                val s = sink
                if (s == null) { link.unbind(); wanted = false; _bound.value = false; scheduleRetryLocked() }
                else {
                    s.trip(TripReason.MODE_OFF)
                    stopping = true
                    stoppingSince = clock()
                    startWatchLocked()
                    log("nexus switched off: waiting for the history wipe")
                }
            } else if (!wanted && debt.wipeOwed()) {
                // A wipe is still owed and the bridge is not bound (restart, a timed-out teardown, a disconnected trip): bind it for that alone.
                stopping = true
                stoppingSince = clock()
                bindLocked()
                if (wanted) { startWatchLocked(); log("nexus wipe retry bound") } else { stopping = false; scheduleRetryLocked() }     // a failed bind must not end the automatic retries
            }
        }
    }

    /** Lock held. A lost connection that did not come back: let go of the dead binding so the next bind is a fresh one. */
    private fun releaseDeadLocked() {
        if (wanted && dead && sink == null && !stopping) { try { link.unbind() } catch (_: Exception) { }; wanted = false }
    }

    /** Lock held. Verifies the signer, then binds; [wanted] follows the result. */
    private fun bindLocked() {
        wanted = true // before the bind: a connection may be reported before bindService returns
        val r = if (!safeTrusted()) BridgeLink.Bind.UNTRUSTED else link.bind(::onConnected, ::onGone)
        _bind.value = r
        wanted = r == BridgeLink.Bind.BOUND
        when (r) {
            BridgeLink.Bind.BOUND -> log("nexus bind bound")
            BridgeLink.Bind.NOT_INSTALLED -> log("nexus bind not installed")
            BridgeLink.Bind.REFUSED -> log("nexus bind refused")
            BridgeLink.Bind.UNTRUSTED -> log("nexus bind untrusted")
        }
    }

    private fun safeTrusted(): Boolean = try { link.trusted() } catch (_: Exception) { false }
    private fun safePresent(): Boolean = try { link.present() } catch (_: Exception) { true }

    private fun onConnected(binder: IBinder) {
        synchronized(lock) {
            if (!wanted) return
            // Re-verified on EVERY connection (a reconnect after the package was replaced is a new process): nothing is handed to the binder before this passes.
            if (!safeTrusted()) { untrustedLocked(); return }
            sink = try {
                NexusHudSink.connect(binder, gates, ::config, actions, log, asks = asks, skills = skills, ledger = debt, onForgetAcked = ::onWipeAcked, direct = directDispatch)
            } catch (_: Exception) { log("nexus connect failed"); null }
            _bound.value = sink != null
            if (sink != null) {
                dead = false; lost = false
                startTick()
                // A healthy connection ends the backoff, unless a wipe is still owed (its own retry cadence goes on).
                if (!debt.wipeOwed()) { retryDelay = retryBaseMs; retryJob?.cancel(); retryJob = null }
            }
        }
    }

    /** Lock held. The package behind the bridge's name is not (or no longer) signed like PCA: let go of it, say so, keep nothing connected. */
    private fun untrustedLocked() {
        // The package behind the name may simply be gone (uninstalled while bound): that is "not installed", not "not trusted" (found by the real-hub smoke, 2026-11-19).
        val gone = !safePresent()
        try { link.unbind() } catch (_: Exception) { }
        closeSinkLocked()
        sink = null; tickJob?.cancel(); tickJob = null; watchJob?.cancel(); watchJob = null
        wanted = false; stopping = false; _bound.value = false
        _bind.value = if (gone) BridgeLink.Bind.NOT_INSTALLED else BridgeLink.Bind.UNTRUSTED
        if (gone) log("nexus bridge removed") else log("nexus bridge untrusted")
    }

    /**
     * The bridge package was added, replaced or removed (the phone's package broadcast). A bound bridge is verified again at once; an unbound one is retried when Nexus is the host or a wipe is owed. Safe from any thread.
     */
    fun onPackageChanged() {
        synchronized(lock) {
            if (wanted) { if (!safeTrusted()) untrustedLocked() }
            else if (host() == HudHost.NEXUS || debt.wipeOwed()) refresh()
        }
    }

    /** Lock held. Stops the sink's dispatcher thread (what it still had queued is discarded unsent). */
    private fun closeSinkLocked() { try { sink?.close() } catch (_: Exception) { } }

    /** The hub accepted a history wipe. The sink already settled the ledger up to the generation of that request (a late acknowledgement of an older wipe leaves a newer debt standing); here only the retry follows. */
    private fun onWipeAcked() {
        synchronized(lock) {
            if (debt.wipeOwed()) return
            retryDelay = retryBaseMs
            retryJob?.cancel(); retryJob = null
        }
        log("nexus wipe acknowledged")
    }

    /** Read on every gate check, so a switch takes effect at once. STT and the assist takeover are ONE user switch ("Nexus voice"). */
    private fun config(): NexusConfig {
        val s = switches()
        return NexusConfig(enabled = host() == HudHost.NEXUS, ttsEnabled = s.tts, sttEnabled = s.voice, assistTakeover = s.voice, skillsEnabled = s.skills)
    }

    /** Call whenever a Nexus switch changes: pushes the feature bits (turning the ask off stops an open listening). A no-op while nothing is connected. */
    fun refreshFeatures() {
        val s = sink ?: return
        try { s.refreshFeatures() } catch (_: Exception) { log("nexus refresh features failed") }
    }

    private fun onGone() {
        // The wipe debt is stored, not held by the sink: a lost connection owes nothing new and forgets nothing.
        synchronized(lock) {
            closeSinkLocked(); sink = null; _bound.value = false; tickJob?.cancel(); tickJob = null
            // B1 (smoke 3): the bridge process died (force-stop, kill) while Nexus is the host or a wipe is owed. Android may still restart a bound service by itself (the binding stays, `wanted` stays), but a force-stop
            // drops the binding for good. So a retry is scheduled with the SAME backoff as the wipe retries (30 s, 60 s, 120 s ... up to the cap): when it fires and nothing reconnected, the dead binding is released and the
            // bridge is bound afresh. One delayed job at most: no loop, no spin. While a stop is in progress its own watch decides (it unbinds and schedules the retry).
            if (!stopping && wanted) {
                dead = true; lost = true
                if (host() == HudHost.NEXUS || debt.wipeOwed()) scheduleRetryLocked()
            }
        }
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

    /** Lock held. Watches a stop even while nothing is connected (no tick runs then). */
    private fun startWatchLocked() {
        watchJob?.cancel()
        watchJob = scope.launch {
            while (isActive) {
                delay(tickMs)
                if (finishStopIfDue()) break
            }
        }
    }

    private fun finishStopIfDue(): Boolean = synchronized(lock) {
        if (!stopping) return false
        val owed = debt.wipeOwed()
        if (owed && clock() - stoppingSince < teardownMaxMs) return false
        link.unbind(); closeSinkLocked(); sink = null; wanted = false; stopping = false; _bound.value = false; tickJob?.cancel(); tickJob = null; watchJob = null
        log("nexus unbound")
        if (owed) scheduleRetryLocked()   // NOT dropped: the debt is stored and the next attempt comes with a longer wait
        true
    }

    /** Lock held. One pending retry at most; the wait doubles up to [retryMaxMs] and resets on an acknowledgement. */
    private fun scheduleRetryLocked() {
        if (retryJob?.isActive == true) return
        val d = retryDelay
        retryDelay = min(retryDelay * 2, retryMaxMs)
        retryJob = scope.launch {
            delay(d)
            synchronized(lock) { retryJob = null }
            if (debt.wipeOwed()) { log("nexus wipe retry"); refresh() }
            else if (host() == HudHost.NEXUS) { log("nexus rebind retry"); refresh() }
        }
    }

    /** v3.1: takes the notice of [ref] off Nexus (a suggestion dismissed or forgotten elsewhere). No-op while nothing is connected. */
    fun withdraw(ref: String) { val s = sink ?: return; try { s.withdraw(ref) } catch (_: Exception) { log("nexus withdraw failed") } }

    /**
     * A close event. Hides everything on Nexus at once (and asks for the history wipe on Forget, Delete all and mode off). Safe from any thread. The wipe debt is recorded FIRST, connected or not (the bridge may be
     * down, mid-reconnect or about to be unbound); the sink sends it now when it can and the provider sends it again on the next bind until the hub acknowledges.
     */
    fun trip(reason: TripReason) {
        if (reason == TripReason.FORGET || reason == TripReason.DELETE_ALL || reason == TripReason.MODE_OFF) {
            synchronized(lock) {
                if (wanted || sink != null || debt.wipeOwed()) {
                    debt.owe()
                    if (sink == null && !stopping) scheduleRetryLocked()
                }
            }
        }
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
