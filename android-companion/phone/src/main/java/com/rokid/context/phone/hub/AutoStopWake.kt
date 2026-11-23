package com.rokid.context.phone.hub

import android.content.Context
import android.os.PowerManager
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.rokid.context.core.log.PcLog
import com.rokid.context.phone.ui.settings.capture.CaptureTimingStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * The wake path of a hub that stopped itself (see the 2026-11-07 AutoStop handoff). Android 12+ refuses a foreground service start from the background unless the app
 * is exempt from battery optimization; with that exemption (the user granted it from Settings > Capture > Run in the background) a process start by the platform
 * (the 15 minute WorkManager jobs, a Data Layer message, the one-shot [AutoStopWakeWorker] scheduled for the next rung) may start the hub again, at most once per
 * [BackgroundPolicy.WAKE_LADDER_MS] step and never after a manual stop. A process that is already alive does the same from [AutoStopController].
 * Every entry point only SUBMITS an event to the one [engine] (Codex review 4): the claim is a reducer step, never a lock shared by threads.
 * There is NO event that wakes a stopped hub when the glasses are put on: the wear signal exists only inside an open Rokid session, which the stopped hub does not hold.
 */
object AutoStopWake {
    const val WORK_NAME = "auto-stop-wake"

    fun exempt(context: Context): Boolean =
        runCatching { (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(false)

    /** One wake for the next rung, [delayMs] from now (replaces any earlier one): a process that is not alive then is started for it. Never throws. */
    fun schedule(context: Context, delayMs: Long) {
        runCatching {
            val req = OneTimeWorkRequestBuilder<AutoStopWakeWorker>().setInitialDelay(delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, req)
        }.onFailure { PcLog.w(AutoStopController.TAG, "auto_stop wake schedule failed: ${it.javaClass.simpleName}") }
    }

    /** The process-wide AutoStop actor (lazy; it reads nothing until its first step, which runs on Dispatchers.IO). */
    @Volatile private var shared: AutoStopEngine? = null
    fun engine(context: Context): AutoStopEngine = shared ?: synchronized(this) {
        shared ?: context.applicationContext.let { app ->
            AutoStopEngine(
                PrefsAutoStopStore.of(app), System::currentTimeMillis, Dispatchers.IO.asExecutor(),
                wakeInputs = {
                    WakeInputs(
                        exempt(app), CaptureTimingStore.get(app).autoStopMinNow(),
                        BackgroundPolicy.linkMachineryAllowed(KeepRunningNearby.of(app).enabled(), com.rokid.context.link.CxrGlassesLink.isPaired(app)), ContextHubService.isRunning(),
                    )
                },
                startWake = { ContextHubService.ensureRunning(app, wake = true) }, scheduleWake = { schedule(app, it) },
                startRecover = { ContextHubService.ensureRunning(app, wake = true) }, // unmarked = guarded: no glasses connect except through the call guard and the hourly cap
                hubStart = LadderHubStartGate(hubLadder(app)) { callIdle(app) },
                phonePaused = { phonePaused(app) },
            )
        }.also { shared = it }
    }

    /** An explicit (stored, active) phone pause exists; a start trigger never overrides it. Pause-zone records are not user pauses. Never throws (false = no pause known). */
    internal fun phonePaused(app: Context): Boolean = runCatching {
        runBlocking { HubPauses(com.rokid.context.memory.MemoryGraph.get(app).db.pauseDao()).stored().any { !it.pauseId.startsWith("zone:") } }
    }.getOrDefault(false)

    /** Review hub-wp2 r2 P2: the process-wide owner of the hub restart ladder; the watchdog and the recovery gate both use THIS instance (never two caches of one record). */
    @Volatile private var ladder: HubLadder? = null
    fun hubLadder(context: Context): HubLadder = ladder ?: synchronized(this) {
        ladder ?: context.applicationContext.let { app ->
            HubLadder(PrefsHubRestartStore { app.getSharedPreferences(HUB_RESTART_PREFS, Context.MODE_PRIVATE) }, System::currentTimeMillis, Dispatchers.IO.asExecutor(), { PcLog.w(GlassesWatchdog.TAG, it) })
        }.also { ladder = it }
    }

    /** The watchdog's persisted hub restart ladder (the recovery shares it). */
    const val HUB_RESTART_PREFS = "hub_restart"

    /** The existing call guard (the hub's own once built, else one of this process): a recovery never connects during a call or its 15 s settle. */
    private val coldGuards = java.util.concurrent.atomic.AtomicReference<RelaunchGuards>()
    private fun callIdle(app: Context): Boolean =
        (HubGraph.callGuards ?: coldGuards.get() ?: AndroidRelaunchGuards(app, { null }).also { coldGuards.compareAndSet(null, it) }.let { coldGuards.get()!! }).callIdle()

    private const val VIA_PROCESS = "process start"
    private const val WORKER_WAIT_MS = 60_000L

    /**
     * Application.onCreate: submits [AutoStopEvent.ProcessStarted] and returns at once. No disk read or write on the caller (main) thread (Codex review 4):
     * the claim, its commits and the start all run on the engine's IO context.
     */
    fun processStarted(app: Context) { engine(app).submit(AutoStopEvent.ProcessStarted(VIA_PROCESS)) }

    /** [AutoStopWakeWorker] (a background thread): the same event, waiting for its outcome. True = a start was issued. */
    fun onProcessStart(app: Context): Boolean =
        (runBlocking { withTimeoutOrNull(WORKER_WAIT_MS) { engine(app).submit(AutoStopEvent.ProcessStarted(VIA_PROCESS)).await() } } as? AutoStopClaimed)?.claim == Claim.STARTED

    /**
     * The same process-start event on a private, synchronous engine over [store] (tests): true = a start was issued. Early (the rung not yet due) it schedules a
     * wake for the remaining time; a refused start schedules the next rung. The start saves the probe flag first, so the woken hub's controller (built later in
     * this process) looks for the glasses for [BackgroundPolicy.WAKE_PROBE_MS], not the full N minutes.
     */
    fun onProcessStart(
        app: Context,
        now: Long,
        store: AutoStopStore,
        exempt: () -> Boolean = { exempt(app) },
        minutes: () -> Int = { CaptureTimingStore.get(app).autoStopMinNow() },
        allowed: () -> Boolean = { BackgroundPolicy.linkMachineryAllowed(KeepRunningNearby.of(app).enabled(), com.rokid.context.link.CxrGlassesLink.isPaired(app)) },
        running: () -> Boolean = { ContextHubService.isRunning() },
        schedule: (Long) -> Unit = { schedule(app, it) },
        start: () -> Boolean = { ContextHubService.ensureRunning(app, wake = true) },
    ): Boolean = claimOn(now, store, exempt, minutes, allowed, running, start, schedule, VIA_PROCESS, { PcLog.i(AutoStopController.TAG, it) }, { PcLog.w(AutoStopController.TAG, it) }) == Claim.STARTED

    /** What a claim did. Only [STARTED] issued a start; [NONE] and [NOT_DUE] touched nothing. */
    enum class Claim {
        /** No wake applies (not auto-stopped, a manual stop, no exemption, Never, the hub runs, no link machinery). */
        NONE,
        /** The rung is not due yet: the caller waits or schedules the remaining time. */
        NOT_DUE,
        /** The rung was consumed, the probe saved and the start issued; the auto stop is over. */
        STARTED,
        /** The rung was consumed but the start returned false because the service is already up: not a refusal, the probe flag is kept. */
        ALREADY_RUNNING,
        /** Android refused the start: the probe flag is cleared, the next rung stands. */
        REFUSED,
    }

    /**
     * One claim on a private synchronous engine over [store] (tests and diagnostics). Production never calls it: the worker, the process start and the live
     * controller all submit events to the ONE [engine], whose reducer ([AutoStopMachine]) owns the claim, so two entry points can never both pass the deadline.
     */
    fun claim(
        now: Long, store: AutoStopStore, exempt: () -> Boolean, minutes: () -> Int, allowed: () -> Boolean, running: () -> Boolean, start: () -> Boolean,
        via: String, log: (String) -> Unit = { PcLog.i(AutoStopController.TAG, it) }, warn: (String) -> Unit = { PcLog.w(AutoStopController.TAG, it) },
    ): Claim = claimOn(now, store, exempt, minutes, allowed, running, start, {}, via, log, warn)

    private fun claimOn(
        now: Long, store: AutoStopStore, exempt: () -> Boolean, minutes: () -> Int, allowed: () -> Boolean, running: () -> Boolean, start: () -> Boolean,
        schedule: (Long) -> Unit, via: String, log: (String) -> Unit, warn: (String) -> Unit,
    ): Claim {
        val e = AutoStopEngine(store, { now }, SerialStateMachine.DIRECT, { WakeInputs(exempt(), minutes(), allowed(), running()) }, start, schedule, log, warn)
        return (runBlocking { e.submit(AutoStopEvent.ProcessStarted(via)).await() } as? AutoStopClaimed)?.claim ?: Claim.NONE
    }
}

/** The one-shot wake for the next rung ([AutoStopWake.schedule]); it only runs the same checks as a process start. */
class AutoStopWakeWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        runCatching { AutoStopWake.onProcessStart(applicationContext) }
        return Result.success()
    }
}

/**
 * Review hub-wp3 r1 P2-4: a manifest receiver's work. [goAsync] first (the broadcast's lifetime then covers the work), everything else on a worker thread, and the
 * [PendingResult.finish] only AFTER [work] returned (it waits for the engine's outcome, bounded by [WAIT_MS], so the platform never ends the process under a half-done event).
 */
internal object ReceiverWork {
    const val WAIT_MS = 8_000L

    fun run(pending: android.content.BroadcastReceiver.PendingResult?, name: String, work: () -> Unit) {
        kotlin.concurrent.thread(name = name) { try { runCatching { work() } } finally { runCatching { pending?.finish() } } }
    }

    /** Waits (on this worker thread) for the engine's reply to [e], at most [WAIT_MS]. */
    fun await(engine: AutoStopEngine, e: AutoStopEvent) { runBlocking { withTimeoutOrNull(WAIT_MS) { engine.submit(e).await() } } }
}
