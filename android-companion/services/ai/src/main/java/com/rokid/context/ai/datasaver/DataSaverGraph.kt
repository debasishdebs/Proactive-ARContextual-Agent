package com.rokid.context.ai.datasaver

import android.content.Context
import com.rokid.context.core.log.PcLog
import com.rokid.context.core.net.DailyCaps
import com.rokid.context.core.net.DailyLimit
import com.rokid.context.core.net.DataDecision
import com.rokid.context.core.net.DataGate
import com.rokid.context.core.net.DataKind
import com.rokid.context.core.net.DataPolicy
import com.rokid.context.core.net.DataSaverSettings
import com.rokid.context.core.net.EndpointReach
import com.rokid.context.core.net.ModelPrivacy
import com.rokid.context.core.net.ModelPrivacyPolicy
import com.rokid.context.core.net.NetSnapshot
import com.rokid.context.core.net.NetworkCost
import com.rokid.context.core.net.NetworkCosts
import com.rokid.context.core.net.MobileDataLedger
import com.rokid.context.core.net.Skipped
import com.rokid.context.core.net.SkippedLedger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The process-wide data saver: the settings, the network reading and today's counts behind the one [DataGate] every call site asks. Fail closed: until the network reading
 * arrives the cost is UNKNOWN, which is costly. Log lines (tag PcData) carry only fixed tokens and counts: never a host, endpoint, country, position or content.
 */
class DataSaverGraph internal constructor(
    private val store: DataSaverStore,
    private val snapshotNow: () -> NetSnapshot,
    epochDay: () -> Long = { LocalDate.now().toEpochDay() },
    /** Re-reads the network facts that change without a callback and restarts a failed network watch (production: the monitor's reread). */
    private val reread: () -> Unit = {},
    /** Runs the one-off re-check after a pre-validation blip (production: a Default-dispatcher scope; tests: a test scope). */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val clock: () -> Long = System::currentTimeMillis,
    /** Model privacy (slice 1.4.3): under OFFLINE_ONLY nothing that reaches a remote endpoint is allowed, so every caller that parks on this gate (the keyframe loop, the queue, Ask photos, Place Guide) parks without a call. Default AUTO (tests). */
    private val privacy: () -> ModelPrivacy = { ModelPrivacy.AUTO },
    /** The local time zone, read at each use (the midnight wake follows a zone change). */
    private val zone: () -> java.time.ZoneId = { java.time.ZoneId.systemDefault() },
) : DataGate {
    val ledger = SkippedLedger(store.counters, epochDay)
    /** Slice 2: bytes and photos sent over costly networks today (counts only). */
    val mobile = MobileDataLedger(store.counters, epochDay)
    val settings get() = store.settings
    val settingsStore: DataSaverStore get() = store
    private val openListeners = CopyOnWriteArrayList<() -> Unit>()
    @Volatile private var photoOpen: Boolean? = null
    @Volatile private var lastCost: NetworkCost? = null

    fun snapshot(): NetSnapshot = snapshotNow()
    fun settingsNow(): DataSaverSettings = store.settings.value
    /** True when Wi-Fi is connected and unmetered but has not validated for over 10 minutes (the status line says remote photos still wait). */
    fun stuckNow(): Boolean = NetworkCosts.stuckUnvalidated(snapshotNow(), clock())
    fun costNow(): NetworkCost = NetworkCosts.classify(snapshotNow(), settingsNow())

    /** The daily limit that applies on the network right now (null = none: Wi-Fi, a default user at home, offline). */
    fun limitNow(): DailyLimit? = DailyCaps.limitFor(settingsNow(), costNow())
    /** Today's limit is used up on the network we are on right now (the status line says "Daily limit reached"). */
    fun limitReachedNow(): Boolean = DailyCaps.reached(limitNow(), mobile.today().usage)

    /** Photo uploads were blocked and are now allowed (Wi-Fi came back, the mode or the abroad switch changed): the caller wakes its parked loop. */
    fun addOpenListener(l: () -> Unit) { openListeners += l }

    override fun decide(kind: DataKind, reach: EndpointReach): DataDecision {
        // Offline only comes before every data-saver rule: a remote endpoint is never allowed (an unreadable setting counts as OFFLINE_ONLY).
        if (!ModelPrivacyPolicy.allows(try { privacy() } catch (e: Exception) { ModelPrivacy.OFFLINE_ONLY }, reach)) return DataDecision(false, ModelPrivacyPolicy.R_OFFLINE_ONLY)
        return decideWith(kind, reach, sendOnce = false)
    }

    private fun decideWith(kind: DataKind, reach: EndpointReach, sendOnce: Boolean): DataDecision {
        val s = settingsNow(); val snap = snapshotNow()
        return DataPolicy.decide(kind, reach, NetworkCosts.classify(snap, s), snap.transport, s, mobile.today().usage, sendOnce)
    }

    /** USER-class photo call the user sent "once" (the router checks the class and the [SendOnce] element). Offline only still refuses. Logged as a fixed line, no content. */
    override fun decideOnce(kind: DataKind, reach: EndpointReach): DataDecision {
        if (!ModelPrivacyPolicy.allows(try { privacy() } catch (e: Exception) { ModelPrivacy.OFFLINE_ONLY }, reach)) return DataDecision(false, ModelPrivacyPolicy.R_OFFLINE_ONLY)
        val d = decideWith(kind, reach, sendOnce = true)
        if (d.reason == DataPolicy.R_SEND_ONCE) PcLog.i(TAG, "send once allowed kind=${kind.name}")
        return d
    }

    /** Byte counter at the router's choke point: only while the network is costly (mobile, roaming, unknown); Wi-Fi is not counted. */
    override fun sent(kind: DataKind, txBytes: Long, rxBytes: Long, photos: Int) = sentAt(kind, txBytes, rxBytes, photos, NetworkCosts.isCostly(costNow()))

    /** The class was read when the call started: a call that began on mobile data counts even if it ends on Wi-Fi, and the other way round. */
    override fun sentAt(kind: DataKind, txBytes: Long, rxBytes: Long, photos: Int, costlyAtStart: Boolean) {
        if (!costlyAtStart) return
        val before = limitReachedNow()
        mobile.add(kind, txBytes + rxBytes, if (kind == DataKind.PHOTO) photos else 0)
        if (!before && limitReachedNow()) {
            if (mobile.firstNoticeToday()) PcLog.i(TAG, "daily limit reached")
            refreshState() // photo uploads just became held back: mark it so frames park, and the status line follows
        }
    }

    /**
     * Wakes at each local midnight: the day's counters start from zero, so a limit that held photo uploads is lifted and the open listeners (the parked keyframe loop) are woken.
     * Follows the injected clock and zone, so a test on virtual time drives it. Safe to call once; the production graph starts it at creation.
     */
    fun startMidnightWatch(): kotlinx.coroutines.Job = scope.launch {
        while (true) {
            // Sleeps until the next local midnight, or until the clock or the zone changes (then the wait is recomputed from the new time and zone).
            val changed = kotlinx.coroutines.withTimeoutOrNull(msUntilLocalMidnight(clock(), zone()) + MIDNIGHT_SLACK_MS) { clockChanged.receive(); true } == true
            if (changed) PcLog.i(TAG, "clock or zone changed") else PcLog.i(TAG, "day rollover")
            refreshState()
        }
    }

    private val clockChanged = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    /** The clock was set or the time zone changed (the app's time-change receiver): the midnight wake is recomputed now instead of at the old midnight. Safe from any thread. */
    fun onClockChanged() { clockChanged.trySend(Unit) }

    /** Where the photo calls go (the vision endpoint's kind). Set by the app (`KeyframeGraph`); REMOTE, the fail-closed answer, until then. The same input the pipeline's own gate uses. */
    @Volatile var reachProvider: () -> EndpointReach = { EndpointReach.REMOTE }

    /**
     * Photo uploads are held back right now for THIS app's photo endpoint (reach-aware, like the pipeline) and not merely in the pre-validation blip every Wi-Fi reconnect shows (cost UNKNOWN for a moment).
     * This is the only input of the persisted "blocked" mark, of the insert-time parking probe and of the waiting line.
     */
    fun heldBack(): Boolean {
        val d = decide(DataKind.PHOTO, reachProvider())
        // Offline only is a standing refusal, not a network blip: parked frames stay parked (and protected) for as long as it is on and the photo endpoint is remote.
        return !d.allowed && (d.reason == ModelPrivacyPolicy.R_OFFLINE_ONLY || !NetworkCosts.preValidationBlip(snapshotNow(), clock()))
    }

    /**
     * "Send over mobile data once?" is asked only when the hold is real: the debounced [heldBack] (not the Wi-Fi reconnect blip) and not the standing Offline only refusal, which "once" cannot lift.
     */
    fun heldBackForPrompt(): Boolean = heldBack() && decide(DataKind.PHOTO, reachProvider()).reason != ModelPrivacyPolicy.R_OFFLINE_ONLY

    /** When photo uploads were first held back since the pipeline last handled it (0 = not; persisted: survives a restart). */
    fun blockedSince(): Long = store.blockedSince()
    /** Clears the mark only if it still equals [expected] (read before the backlog was parked). */
    fun clearBlocked(expected: Long): Boolean = store.clearBlocked(expected)

    override fun skipped(kind: DataKind, what: Skipped, d: DataDecision) {
        if (kind == DataKind.PHOTO && heldBack()) store.markBlocked(clock())
        // Offline only refusals are not a data cost: they are logged but not added to the "Blocked today" counts (those say "waiting for Wi-Fi").
        if (d.reason != ModelPrivacyPolicy.R_OFFLINE_ONLY) ledger.add(what)
        // a blocked CALL: a parked frame can be blocked again at each recheck, so this counts calls, not photos (the live waiting count comes from the database)
        PcLog.i(TAG, "blocked kind=${kind.name} what=$what reason=${d.reason}")
    }

    override fun costlyNow(): Boolean = NetworkCosts.isCostly(costNow())

    /** Re-reads the Android Data Saver status and country facts, restarts a failed network watch, then [refreshState]. Called by the pipeline's recheck and the status screen. */
    override fun refresh() { try { reread() } catch (e: Exception) { PcLog.w(TAG, "reread failed: ${e.javaClass.simpleName}") }; refreshState() }

    /** Called on every network or settings change: logs the cost class on change, and wakes the listeners when photo uploads turn from blocked to allowed. */
    fun refreshState() {
        val cost = costNow()
        if (cost != lastCost) {
            lastCost = cost
            PcLog.i(TAG, "net cost=${cost.name}")
        }
        // Everything below is driven by the debounced, reach-aware [heldBack]: the persisted mark, the open transition that wakes the pipeline, and the re-check after a blip.
        val held = heldBack()
        val open = !held
        if (held) store.markBlocked(clock())
        else if (!decide(DataKind.PHOTO, reachProvider()).allowed) recheckAfterBlip() // pre-validation blip: look again once the debounce is over; still blocked then = marked
        val was = photoOpen
        photoOpen = open
        if (open && was == false) {
            PcLog.i(TAG, "photo uploads open")
            openListeners.forEach { l -> try { l() } catch (e: Exception) { PcLog.w(TAG, "open hook failed: ${e.javaClass.simpleName}") } }
        }
    }

    @Volatile private var blipPending = false
    private fun recheckAfterBlip() {
        if (blipPending) return
        blipPending = true
        scope.launch { kotlinx.coroutines.delay(NetworkCosts.BLIP_MS + 1_000); blipPending = false; refreshState() }
    }

    companion object {
        private const val TAG = "PcData"
        const val MIDNIGHT_SLACK_MS = 1_000L
        /** Milliseconds from [nowMs] to the next local midnight in [zone] (DST-safe: asks the zone for the start of the next day). */
        fun msUntilLocalMidnight(nowMs: Long, zone: java.time.ZoneId): Long {
            val now = java.time.Instant.ofEpochMilli(nowMs)
            val next = now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
            return (next.toEpochMilli() - nowMs).coerceAtLeast(1L)
        }
        @Volatile private var instance: DataSaverGraph? = null

        fun get(context: Context): DataSaverGraph = instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

        private fun create(app: Context): DataSaverGraph {
            val store = DataSaverStore.of(app)
            lateinit var graph: DataSaverGraph
            val monitor = NetworkCostMonitor(app) { graph.refreshState() }
            val privacy = com.rokid.context.ai.resolve.ModelPrivacyStore.get(app)
            graph = DataSaverGraph(store, { monitor.snapshot }, reread = monitor::reread, privacy = privacy::current)
            monitor.start()
            graph.refreshState()
            graph.startMidnightWatch()
            // A mode or abroad change can open the gate as well.
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch { store.settings.drop(1).collect { graph.refreshState() } }
            // Model privacy turning off Offline only can open the gate as well.
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch { privacy.mode.drop(1).collect { graph.refreshState() } }
            return graph
        }

        fun resetForTests(graph: DataSaverGraph?) { synchronized(this) { instance = graph } }

        /** Tests only: a graph over [app]'s real settings file with a scripted network reading (Robolectric has none). Install it with [resetForTests]. */
        fun forTests(app: Context, snapshot: () -> NetSnapshot): DataSaverGraph = DataSaverGraph(DataSaverStore.of(app), snapshot)
    }
}
