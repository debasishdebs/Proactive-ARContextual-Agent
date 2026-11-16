package com.rokid.context.phone.hub

import com.rokid.context.core.log.PcLog
import com.rokid.context.core.model.LinkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import java.util.concurrent.Executor

/**
 * Phone-side watchdog (2026-10-22): Rokid force-stops the glasses app on calls and glasses removal, which kills its own alarms and
 * receivers, so the periodic trigger that brings it back lives on the phone (Rokid does not kill it). One [tick] every ~[TICK_MS]
 * (no wakelock, the period is not exact) decides, while the reopen setting is on and capture is active:
 *  - the hub service is dead (the process lived, the service did not): [startHub];
 *  - the wearer exited on purpose: nothing, only the note "stopped by you";
 *  - nothing heard from the glasses for [SILENT_MS] and the link is down: [reconnect] (the existing path, never a relaunch);
 *  - silent while the link says up: MINIMISED = [probeAlive]; otherwise [relaunch] = [GlassesRelauncher.attemptNow], which owns
 *    every guard (call idle with its post-call settle, media, worn, hourly cap, back-off). The watchdog itself never launches anything.
 * Every tick publishes the user-visible note (null = nothing to say) through [publish].
 *
 * Logging (2026-10-25, a 21 min dead gap had no phone line): one counts-only line per verdict CHANGE and every [LOG_EVERY]th tick otherwise (`verdict`, `why` or the
 * relaunch result, the link state, seconds silent), a warning for a late tick (the phone process was suspended: the proof for Doze) and for a dead hub.
 *
 * Actor rewrite (Codex review 4): the back-offs (the reconnect ladder and the persisted hub restart ladder + last start) live in ONE [SerialStateMachine] reduced by
 * [WatchdogLadder]; positive signals, "hub wanted", "hub running" and the tick's attempts are events in one order, and every save writes the latest snapshot, so a
 * delayed save can never overwrite a newer reset (the persisted and in-memory ladders cannot diverge). An attempt's start runs only after its commit returned.
 */
class GlassesWatchdog(
    private val now: () -> Long,
    private val enabled: () -> Boolean,
    /** Capture is wanted: the pipeline is on and nothing is paused. */
    private val captureActive: () -> Boolean,
    private val hubRunning: () -> Boolean,
    private val link: () -> LinkState,
    /** Phone clock of the last message from the glasses app (hello, frame, command...), null = none yet. */
    private val lastHeardAtMs: () -> Long?,
    private val standingDown: () -> Boolean,
    private val startHub: () -> Unit,
    private val reconnect: () -> Unit,
    private val probeAlive: () -> Unit,
    /** Null = the relauncher reopened the app; otherwise why not. */
    private val relaunch: () -> String?,
    private val publish: (String?) -> Unit,
    /** The silence threshold, read at EVERY tick (Settings > Capture; default [SILENT_MS]), so a changed setting applies on the next tick. */
    private val silentMs: () -> Long = { SILENT_MS },
    private val log: (String) -> Unit = { PcLog.i(TAG, it) },
    private val warn: (String) -> Unit = { PcLog.w(TAG, it) },
    /** The tick period, read before EVERY sleep (Settings > Capture; default [TICK_MS]). */
    private val periodMs: () -> Long = { TICK_MS },
    /** A pair exists ([com.rokid.context.link.CxrGlassesLink.isPaired]); without one nothing here runs ([BackgroundPolicy]). */
    private val paired: () -> Boolean = { true },
    /** The hub foreground service is wanted ([BackgroundPolicy.hubNeeded]); when it is not and the service runs, [stopHub] ends it. */
    private val hubNeeded: () -> Boolean = { true },
    private val stopHub: () -> Unit = {},
    /** The hub restart ladder and last start time; persisted in production so process death does not reset them ([PrefsHubRestartStore]). */
    private val hubStore: HubRestartStore = InMemoryHubRestartStore(),
    /** Where the ladder actor's steps and commits run (Dispatchers.IO; the loop's scope is Main.immediate); tests pass [SerialStateMachine.DIRECT]. */
    executor: Executor = Dispatchers.IO.asExecutor(),
    /** Runs the ladder record's write retry (null = the engine's timer thread; tests pass their own). */
    persistRetry: ((Long, Runnable) -> Unit)? = null,
) {
    sealed interface Verdict {
        object Off : Verdict
        object Ok : Verdict
        object HubDown : Verdict
        data class LinkDown(val ageMs: Long) : Verdict
        data class StoppedByYou(val ageMs: Long) : Verdict
        data class Reopening(val ageMs: Long) : Verdict
        data class Waiting(val reason: String, val ageMs: Long) : Verdict
        data class Probing(val ageMs: Long) : Verdict
    }

    /** Where silence is measured from when nothing was heard yet: the first tick of this enabled window. */
    private var baselineMs: Long? = null
    private var lastTickAt: Long? = null
    private var lastLogged: String? = null
    private var quietTicks = 0
    /** The back-offs (reconnect, hub restart, last start): the one actor; see [WatchdogLadder]. Loaded from [hubStore] on its first step (on [executor]). */
    private val ladder = SerialStateMachine<WatchdogLadder.State, WatchdogLadder.Event, WatchdogLadder.Effect>(
        load = { WatchdogLadder.State(saved = hubStore.load()) }, reduce = WatchdogLadder::reduce, persisted = { it.saved }, save = { hubStore.save(it.saved) },
        now = now, executor = executor, onError = { warn("watchdog ladder step failed: ${it.javaClass.simpleName}") },
        run = { warn("persist_failed"); null }, // the one effect: WatchdogLadder.Effect.PersistFailedLog (at most once a minute)
        persistFailed = { n -> WatchdogLadder.Event.PersistFailed(n) }, persistRecovered = WatchdogLadder.Event.PersistRecovered, schedule = persistRetry,
    )
    /** One tick at a time (the tick's own log/baseline fields); the ladder itself is serialised by its actor. */
    private val ticking = Mutex()
    private var gate = BackgroundPolicy.Gate.ACTIVE
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /**
     * A positive signal (link up, wear event, app in front, a tapped Reconnect, the setting switched on): the back-off starts again and the loop
     * ticks now instead of finishing its sleep. Thread-safe.
     */
    fun onPositiveSignal() { ladder.submit(WatchdogLadder.Event.PositiveSignal); wake.trySend(Unit) }

    /** A phone context source was switched on (the hub is wanted now): the hub restart ladder starts again and the loop ticks now. */
    fun onHubWanted() { ladder.submit(WatchdogLadder.Event.HubWanted); wake.trySend(Unit) }

    /**
     * A hub start attempt at [t]: true = allowed. The attempt is recorded in memory at once and its record written (write-behind, on the actor's IO executor)
     * before this returns, so normally it is durable BEFORE the caller's start; if the write fails the start still goes ahead (keep running) and the write is retried.
     */
    private suspend fun tryHubStart(t: Long): Boolean = ladder.submit(WatchdogLadder.Event.TryHubStart(t)).await() == true

    private suspend fun tryReconnect(t: Long): Boolean = ladder.submit(WatchdogLadder.Event.TryReconnect(t)).await() == true

    private fun reconnectBackoff(): BackgroundPolicy.Backoff = ladder.state()?.reconnect ?: BackgroundPolicy.reset()

    /** [periodMs] null = the configured one, re-read before every sleep, and the policy's sleep (long with no pair or the setting off, the back-off while away); a number fixes it (tests). */
    fun start(scope: CoroutineScope, periodMs: Long? = null): Job = scope.launch {
        while (true) {
            try { tickNow() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { /* never end the loop */ }
            val sleep = periodMs ?: BackgroundPolicy.sleepMs(gate, this@GlassesWatchdog.periodMs(), reconnectBackoff(), now())
            withTimeoutOrNull(sleep) { wake.receive() }
        }
    }

    /** [tickNow] from a plain thread (tests). Never on the main thread: it blocks for the IO hop. */
    internal fun tick(): Verdict = runBlocking { tickNow() }

    suspend fun tickNow(): Verdict = ticking.withLock { tickOnce() }

    private suspend fun tickOnce(): Verdict {
        val t = now()
        val period = periodMs()
        lastTickAt?.let { prev -> if (t - prev > 2 * period) warn("watchdog late tick gap_s=${(t - prev) / 1000} period_s=${period / 1000}") }
        lastTickAt = t
        val on = enabled(); val pair = paired()
        gate = BackgroundPolicy.gate(on, pair, link())
        // The hub service stays only while something needs it; the watchdog never restarts one that is not needed.
        if (!hubNeeded()) { baselineMs = null; if (hubRunning()) { log("watchdog hub not needed, stopping it"); stopHub() }; return finish(Verdict.Off, if (!pair) "why=no_pair" else if (!on) "why=setting_off" else "why=not_needed") }
        // Hub recovery is separate from permission to run glasses machinery: a needed hub (a context source, an ask) is restarted whatever the glasses gate says; no reconnect, no relaunch.
        suspend fun recoverHub() { if (!hubRunning() && tryHubStart(t)) { warn("watchdog hub down, starting it"); startHub() } }
        if (hubRunning()) ladder.submit(WatchdogLadder.Event.HubRunning) // resets the hub restart ladder (a no-op, no save, when it is already reset)
        if (!pair) { baselineMs = null; recoverHub(); return finish(Verdict.Off, "why=no_pair") }
        if (!on) { baselineMs = null; recoverHub(); return finish(Verdict.Off, "why=setting_off") }
        if (!captureActive()) { baselineMs = null; recoverHub(); return finish(Verdict.Off, "why=capture_inactive") }
        if (gate == BackgroundPolicy.Gate.ACTIVE) ladder.submit(WatchdogLadder.Event.GlassesPresent)
        if (!hubRunning()) {
            // Away: the restart waits for its back-off; the status line still says what is going on (silence counts from the last message, or from this window's first tick).
            if (!tryHubStart(t)) {
                val first = baselineMs ?: t.also { baselineMs = it }
                return finish(Verdict.LinkDown((t - (lastHeardAtMs() ?: first)).coerceAtLeast(0L)), "why=hub_down action=backoff")
            }
            baselineMs = null
            warn("watchdog hub down, starting it"); startHub()
            return finish(Verdict.HubDown, "why=hub_down")
        }
        val base = baselineMs ?: t.also { baselineMs = it }
        val age = (t - (lastHeardAtMs() ?: base)).coerceAtLeast(0L)
        val silentS = "silent_s=${age / 1000}"
        if (standingDown()) return finish(Verdict.StoppedByYou(age), "why=stood_down $silentS")
        if (age < silentMs()) return finish(Verdict.Ok, silentS)
        val l = link()
        var detail = "link=$l $silentS"
        return finish(
            when (l) {
                LinkState.OFFLINE, LinkState.PAUSED_BT ->
                    if (tryReconnect(t)) { reconnect(); detail += " action=reconnect"; Verdict.LinkDown(age) }
                    else { detail += " action=backoff"; Verdict.LinkDown(age) }
                LinkState.NOT_AUTHORIZED -> Verdict.LinkDown(age)
                LinkState.CONNECTING -> Verdict.Ok
                LinkState.MINIMISED -> { probeAlive(); detail += " action=probe"; Verdict.Probing(age) }
                LinkState.CONNECTED, LinkState.CLOSED_ON_GLASSES, LinkState.VERSION_MISMATCH -> when (val why = relaunch()) {
                    null -> { detail += " relaunch=reopened"; Verdict.Reopening(age) }
                    "pending" -> { detail += " relaunch=pending"; Verdict.Reopening(age) }
                    "stood down" -> { detail += " relaunch=stood down"; Verdict.StoppedByYou(age) }
                    else -> { detail += " relaunch=$why"; Verdict.Waiting(why, age) }
                }
            },
            detail,
        )
    }

    /** Logs the decision: at once when the verdict or its detail changed, else every [LOG_EVERY]th tick. */
    private fun finish(v: Verdict, detail: String = ""): Verdict {
        publish(note(v))
        val key = v.javaClass.simpleName + " " + detail.replace(Regex("silent_s=\\d+"), "")
        if (key != lastLogged || ++quietTicks >= LOG_EVERY) { log("watchdog tick verdict=${v.javaClass.simpleName}${if (detail.isBlank()) "" else " $detail"}"); lastLogged = key; quietTicks = 0 }
        return v
    }

    companion object {
        const val TAG = "PcWatchdog"
        const val LOG_EVERY = 10
        const val TICK_MS = 150_000L
        /** No two hub start attempts closer than this, whatever resets the ladder (foreground, a source toggled, a positive signal). */
        const val MIN_HUB_START_MS = 60_000L
        /** N: minutes of silence before the watchdog acts (3). */
        const val SILENT_MS = 3 * 60_000L

        fun note(v: Verdict): String? = when (v) {
            Verdict.Off, Verdict.Ok, Verdict.HubDown -> null
            is Verdict.LinkDown -> "Glasses not connected · ${heard(v.ageMs)}"
            is Verdict.StoppedByYou -> "Glasses app stopped by you · ${heard(v.ageMs)}"
            is Verdict.Reopening -> "Glasses app not running, reopening\u2026 \u00b7 ${heard(v.ageMs)}"
            is Verdict.Probing -> "Checking the glasses app\u2026 \u00b7 ${heard(v.ageMs)}"
            is Verdict.Waiting -> when (v.reason) {
                "hourly limit" -> "Glasses app not running · reopen limit reached, open it on the glasses · ${heard(v.ageMs)}"
                else -> "Glasses app not running, reopening… · ${heard(v.ageMs)}"
            }
        }

        private fun heard(ageMs: Long): String { val m = ageMs / 60_000L; return if (m < 1) "last heard just now" else "last heard $m min ago" }
    }
}

/**
 * The watchdog's back-off reducer (PURE; run by [SerialStateMachine], the same engine as [AutoStopMachine]). [State.saved] is the persisted part
 * ([HubRestartStore]); [State.reconnect] lives in memory only.
 */
object WatchdogLadder {
    /** [saved] mirrors the persisted record (write-behind: the in-memory state is authoritative); [reconnect] lives in memory only. */
    data class State(
        val saved: HubRestartSnapshot = HubRestartSnapshot(), val reconnect: BackgroundPolicy.Backoff = BackgroundPolicy.reset(),
        val persistFailing: Boolean = false, val persistLoggedAtMs: Long? = null,
    )

    sealed interface Event {
        /** Link up, a wear event, the app in front, a tapped Reconnect, the setting switched on: both ladders start again (the last start time stays). */
        object PositiveSignal : Event
        /** A phone context source was switched on: the hub restart ladder starts again. */
        object HubWanted : Event
        /** The hub runs: its restart ladder starts again. */
        object HubRunning : Event
        /** The glasses are reachable (gate ACTIVE): the reconnect ladder starts again. */
        object GlassesPresent : Event
        /** A hub start attempt at [atMs]; reply true = allowed and recorded (in memory at once; the record is written behind). */
        data class TryHubStart(val atMs: Long) : Event
        /** A reconnect attempt at [atMs]; reply true = due and recorded. */
        data class TryReconnect(val atMs: Long) : Event
        /** Write-behind bookkeeping: writing the latest record failed ([failures] in a row); nothing is undone, the hub keeps running. */
        data class PersistFailed(val failures: Int) : Event
        object PersistRecovered : Event
    }

    sealed interface Effect { object PersistFailedLog : Effect }

    private fun startAllowed(s: State, t: Long): Boolean = startWaitMs(s.saved, t) == 0L

    /** Pure: how long until a hub start attempt is allowed (0 = now): the back-off deadline and the minimum interval since the last start. A clock set back never blocks for ever. */
    fun startWaitMs(saved: HubRestartSnapshot, t: Long): Long {
        val rung = BackgroundPolicy.remainingMs(t, saved.backoff.nextAtMs, BackgroundPolicy.BACKOFF_MS.last())
        val d = t - saved.lastStartAtMs
        val gap = if (d < 0 || d >= GlassesWatchdog.MIN_HUB_START_MS) 0L else GlassesWatchdog.MIN_HUB_START_MS - d
        return maxOf(rung, gap)
    }

    fun reduce(s: State, e: Event, now: Long): Transition<State, Event, Effect> = when (e) {
        Event.PositiveSignal -> Transition(s.copy(reconnect = BackgroundPolicy.reset(), saved = s.saved.copy(backoff = BackgroundPolicy.reset())))
        // Resets the ladder step only: the minimum interval between hub start attempts ([GlassesWatchdog.MIN_HUB_START_MS]) survives every reset signal.
        Event.HubWanted, Event.HubRunning -> Transition(s.copy(saved = s.saved.copy(backoff = BackgroundPolicy.reset())))
        Event.GlassesPresent -> Transition(s.copy(reconnect = BackgroundPolicy.reset()))
        // Saved or not, the in-memory last start holds the minimum interval (so a recovery can never bypass it).
        is Event.TryHubStart ->
            if (startAllowed(s, e.atMs)) Transition(s.copy(saved = HubRestartSnapshot(BackgroundPolicy.attempted(s.saved.backoff, e.atMs), e.atMs)), reply = true)
            else Transition(s, reply = false)
        is Event.TryReconnect ->
            if (s.reconnect.due(e.atMs)) Transition(s.copy(reconnect = BackgroundPolicy.attempted(s.reconnect, e.atMs)), reply = true) else Transition(s, reply = false)
        Event.PersistRecovered -> Transition(s.copy(persistFailing = false))
        is Event.PersistFailed -> {
            val logNow = s.persistLoggedAtMs.let { it == null || now - it >= AutoStopMachine.PERSIST_LOG_EVERY_MS || now < it }
            Transition(s.copy(persistFailing = true, persistLoggedAtMs = if (logNow) now else s.persistLoggedAtMs), if (logNow) listOf<Effect>(Effect.PersistFailedLog) else emptyList())
        }
    }
}

/** Persisted state of the hub restart attempts: the ladder (step, next allowed time) and the time of the last start attempt. One record. */
data class HubRestartSnapshot(val backoff: BackgroundPolicy.Backoff = BackgroundPolicy.reset(), val lastStartAtMs: Long = 0L)

interface HubRestartStore {
    fun load(): HubRestartSnapshot
    /** The whole snapshot (saved before the start it guards); true = durable, false = the commit failed. */
    fun save(s: HubRestartSnapshot): Boolean
}

class InMemoryHubRestartStore(initial: HubRestartSnapshot = HubRestartSnapshot()) : HubRestartStore {
    @Volatile private var s = initial
    override fun load() = s
    override fun save(s: HubRestartSnapshot): Boolean { this.s = s; return true }
}

class PrefsHubRestartStore(private val prefs: () -> android.content.SharedPreferences) : HubRestartStore {
    override fun load() = prefs().let { HubRestartSnapshot(BackgroundPolicy.Backoff(it.getInt("step", 0), it.getLong("next_at", 0L)), it.getLong("last_start", 0L)) }
    override fun save(s: HubRestartSnapshot): Boolean = prefs().edit().putInt("step", s.backoff.step).putLong("next_at", s.backoff.nextAtMs).putLong("last_start", s.lastStartAtMs).commit()
}

/**
 * Review hub-wp2 r1 P2: the lost-hub recovery shares the watchdog's persisted ladder ([HubRestartStore]: the 60 s minimum between starts, then 5/15/30/60 min) with its
 * last-start time. [dueInMs] is read-only (an input of the pure reducer); [record] writes the attempt BEFORE the start (blocking: call it on the AutoStop actor's IO context).
 * [callIdle] is the existing call guard (mode, telephony and the 15 s settle): a recovery never connects or launches during a call.
 */
interface HubStartGate {
    fun dueInMs(nowMs: Long): Long
    fun record(nowMs: Long)
    fun callIdle(): Boolean

    object None : HubStartGate { override fun dueInMs(nowMs: Long) = 0L; override fun record(nowMs: Long) {}; override fun callIdle() = true }
}

class LadderHubStartGate(private val store: HubRestartStore, private val call: () -> Boolean) : HubStartGate {
    override fun dueInMs(nowMs: Long) = WatchdogLadder.startWaitMs(store.load(), nowMs)
    override fun record(nowMs: Long) {
        val s = store.load()
        store.save(HubRestartSnapshot(BackgroundPolicy.attempted(s.backoff, nowMs), nowMs))
    }
    override fun callIdle() = call()
}
