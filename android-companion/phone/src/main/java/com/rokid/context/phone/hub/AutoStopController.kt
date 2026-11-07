package com.rokid.context.phone.hub

import com.rokid.context.core.log.PcLog
import com.rokid.context.core.model.LinkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor

/**
 * Drives [BackgroundPolicy.autoStop] (user ruling 2026-11-07): while the hub runs and the glasses are off-head or disconnected, it counts the time away and, once
 * the configured minutes have passed (and nothing is in flight, the app was not in front lately, no run is active), stops the hub by itself: [stopHub] ends the service
 * (the hub's own stop cancels the link, probe, reconnect and relaunch attempts; the service releases what it holds). It logs one content-free line (`auto_stop_not_worn`
 * or `auto_stop_disconnected`) and keeps the reason, so the status can say "Stopped: glasses not worn since HH:mm. Tap to start".
 *
 * It sleeps until the exact due time (no polling): [poke] re-evaluates at once (link or wear change, app in front, a setting change). While the hub is stopped
 * it waits for the next [BackgroundPolicy.wakeDue] time, which needs the battery-optimization exemption ([exempt]); without it nothing here ever starts the hub.
 * A stop or a refused wake never waits for a poke: it sleeps to the next rung and also hands that time to [scheduleWake] (a WorkManager wake in production).
 *
 * Actor rewrite (Codex review 4): the controller holds NO state. Each look is an [AutoStopEvent.Evaluate] submitted to the process-wide [AutoStopEngine] (the one
 * serialised state machine the WorkManager worker, the process start and the service's user start / manual stop also feed); the inputs are sampled when the
 * event is reduced, a stop is committed and then RE-VALIDATED behind every input that arrived during the commit, and every effect runs after the commit.
 */
class AutoStopController(
    private val now: () -> Long,
    private val minutes: () -> Int,
    private val hubRunning: () -> Boolean,
    private val link: () -> LinkState,
    private val worn: () -> Boolean?,
    private val inFront: () -> Boolean,
    private val lastFrontMs: () -> Long,
    private val busy: () -> Boolean,
    private val runActive: () -> Boolean,
    private val stopHub: () -> Unit,
    /** Starts the hub from the background; true = the start was issued (Android may still refuse it, the caller logs that). */
    private val wakeHub: () -> Boolean,
    private val exempt: () -> Boolean,
    private val linkAllowed: () -> Boolean,
    /** The user-visible line ("Stopped: ... Tap to start"), null = none. */
    private val publishNote: (String?) -> Unit,
    private val timeText: (Long) -> String = { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it)) },
    private val log: (String) -> Unit = { PcLog.i(TAG, it) },
    /** The next wake rung is in this many ms (a WorkManager wake in production, see [AutoStopWake.schedule]); called after a stop and after a refused wake. */
    private val scheduleWake: (Long) -> Unit = {},
    /** The hub service is up ([ContextHubService.isRunning] in production): tells "already running" from a refused start. */
    private val serviceRunning: () -> Boolean = hubRunning,
    /** A private engine over this store (tests); production passes the shared [engine] instead. */
    store: AutoStopStore? = null,
    /** Where a private engine's steps (and their commits) run: Dispatchers.IO; tests pass [SerialStateMachine.DIRECT]. */
    executor: Executor = Dispatchers.IO.asExecutor(),
    /** The process-wide engine ([AutoStopWake.engine]); null = a private one over [store]. */
    engine: AutoStopEngine? = null,
) {
    val engine: AutoStopEngine = engine ?: AutoStopEngine(
        requireNotNull(store) { "a store or an engine" }, now, executor, wakeInputs = ::wakeInputs, startWake = wakeHub, scheduleWake = scheduleWake, log = log, warn = log,
    )
    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        this.engine.attach(AutoStopEngine.HubPort(
            sample = { HubSignals(hubRunning(), link(), worn(), inFront(), lastFrontMs(), busy(), runActive()) }, wake = ::wakeInputs,
            stopHub = stopHub, wakeHub = wakeHub, scheduleWake = scheduleWake, publishNote = publishNote, timeText = timeText, log = log,
        ))
    }

    private fun wakeInputs() = WakeInputs(exempt(), minutes(), linkAllowed(), serviceRunning())

    fun poke() { wake.trySend(Unit) }

    /** [evaluateNow] from a plain thread (tests). */
    internal fun evaluate(): Long? = runBlocking { evaluateNow() }

    /** One decision (its stop re-validated); returns how long to sleep (null = until the next [poke]). Suspends, never blocks the caller on IO. */
    suspend fun evaluateNow(): Long? = (engine.submit(AutoStopEvent.Evaluate()).await() as? AutoStopSleep)?.ms

    fun start(scope: CoroutineScope): Job = scope.launch {
        while (true) {
            val sleep = try { evaluateNow() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { PcLog.w(TAG, "auto stop check failed: ${e.javaClass.simpleName}"); BackgroundPolicy.IDLE_RECHECK_MS }
            if (sleep == null) wake.receive() else withTimeoutOrNull(sleep) { wake.receive() }
        }
    }

    companion object { const val TAG = "PcAutoStop" }
}

/**
 * The AutoStop actor: [AutoStopMachine] (the pure reducer) on a [SerialStateMachine] over the persisted [AutoStopStore], plus the effect runner. One per process
 * in production ([AutoStopWake.engine]); the live controller [attach]es its inputs and effects (the hub port), the cold path (no hub yet) uses [wakeInputs],
 * [startWake] and [scheduleWake]. Effects run on the engine's serial context after the commit.
 */
class AutoStopEngine(
    private val store: AutoStopStore,
    now: () -> Long,
    executor: Executor,
    private val wakeInputs: () -> WakeInputs?,
    private val startWake: () -> Boolean,
    private val scheduleWake: (Long) -> Unit,
    private val log: (String) -> Unit = { PcLog.i(AutoStopController.TAG, it) },
    private val warn: (String) -> Unit = { PcLog.w(AutoStopController.TAG, it) },
    onStep: (AutoStopEvent, Long, AutoStopState) -> Unit = { _, _, _ -> },
) {
    /** The live hub's side: its inputs, its effects. */
    class HubPort(
        val sample: () -> HubSignals?, val wake: () -> WakeInputs?, val stopHub: () -> Unit, val wakeHub: () -> Boolean, val scheduleWake: (Long) -> Unit,
        val publishNote: (String?) -> Unit, val timeText: (Long) -> String, val log: (String) -> Unit,
    )

    @Volatile private var port: HubPort? = null
    fun attach(p: HubPort) { port = p }

    val machine = SerialStateMachine<AutoStopState, AutoStopEvent, AutoStopEffect>(
        load = { AutoStopState(saved = store.load()) }, reduce = AutoStopMachine::reduce, persisted = { it.saved }, save = { store.save(it.saved) },
        now = now, executor = executor, prepare = ::prepare, run = ::run, onStep = onStep,
        onError = { warn("auto_stop step failed: ${it.javaClass.simpleName}") },
    )

    fun submit(e: AutoStopEvent) = machine.submit(e)

    /** What the rest of the app sees (the [BackgroundPolicy.Needs] inputs): a stop still being committed is not a stop yet. Null before the first step. */
    fun visible(): AutoStopSnapshot? = machine.state()?.let { s -> if (s.pendingStop != null) s.saved.copy(autoStoppedAtMs = 0L, reason = null) else s.saved }

    private fun wakeNow(): WakeInputs? = port?.wake?.invoke() ?: wakeInputs()

    /** Fills an event's missing inputs from the live sources (on the serial context, just before it is reduced). */
    private fun prepare(@Suppress("UNUSED_PARAMETER") s: AutoStopState, e: AutoStopEvent): AutoStopEvent = when (e) {
        is AutoStopEvent.Evaluate -> if (e.wake != null && e.hub != null) e else AutoStopEvent.Evaluate(e.wake ?: wakeNow(), e.hub ?: port?.sample?.invoke())
        is AutoStopEvent.ConfirmStop -> if (e.wake != null && e.hub != null) e else AutoStopEvent.ConfirmStop(e.wake ?: wakeNow(), e.hub ?: port?.sample?.invoke())
        is AutoStopEvent.ProcessStarted -> if (e.wake != null) e else e.copy(wake = wakeNow())
        else -> e
    }

    private fun run(f: AutoStopEffect): AutoStopEvent? {
        val p = port
        val log = p?.log ?: this.log
        val warn = p?.log ?: this.warn
        when (f) {
            is AutoStopEffect.Note -> p?.let { it.publishNote(f.reason?.let { r -> BackgroundPolicy.stoppedNote(r, it.timeText(f.sinceMs)) }) }
            is AutoStopEffect.StopHub -> {
                when (f.reason) { BackgroundPolicy.Away.NOT_WORN -> log("auto_stop_not_worn"); BackgroundPolicy.Away.DISCONNECTED -> log("auto_stop_disconnected") }
                p?.stopHub?.invoke()
            }
            is AutoStopEffect.StartWake -> {
                val issued = runCatching { if (f.controller && p != null) p.wakeHub() else startWake() }.getOrDefault(false)
                return AutoStopEvent.WakeResult(issued, runCatching { wakeNow()?.serviceRunning }.getOrNull() == true)
            }
            is AutoStopEffect.ScheduleWake -> if (f.controller && p != null) p.scheduleWake(f.delayMs) else scheduleWake(f.delayMs)
            is AutoStopEffect.Log -> when (f.line) {
                AutoStopEffect.Line.WAKE_ATTEMPT -> log("auto_stop wake attempt (${f.via})")
                AutoStopEffect.Line.WAKE_ALREADY_RUNNING -> log("auto_stop wake: hub already running (${f.via})")
                AutoStopEffect.Line.WAKE_REFUSED -> warn("auto_stop wake refused (${f.via})")
            }
        }
        return null
    }
}
