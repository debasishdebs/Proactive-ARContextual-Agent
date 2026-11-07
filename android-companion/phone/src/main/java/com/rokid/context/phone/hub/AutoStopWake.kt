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
import java.util.concurrent.TimeUnit

/**
 * The wake path of a hub that stopped itself (see the 2026-11-07 AutoStop handoff). Android 12+ refuses a foreground service start from the background unless the app
 * is exempt from battery optimization; with that exemption (the user granted it from Settings > Capture > Run in the background) a process start by the platform
 * (the 15 minute WorkManager jobs, a Data Layer message, the one-shot [AutoStopWakeWorker] scheduled for the next rung) may start the hub again, at most once per
 * [BackgroundPolicy.WAKE_LADDER_MS] step and never after a manual stop. A process that is already alive does the same from [AutoStopController].
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

    /**
     * Application.onCreate (and [AutoStopWakeWorker]): true = a start was issued. Cheap exits first (one prefs file) so an ordinary start costs nothing.
     * Early (the rung not yet due) it schedules a wake for the remaining time; a refused start schedules the next rung. The start saves the probe flag first, so the
     * woken hub's controller (built later in this process) looks for the glasses for [BackgroundPolicy.WAKE_PROBE_MS], not the full N minutes.
     */
    fun onProcessStart(
        app: Context,
        now: Long = System.currentTimeMillis(),
        store: AutoStopStore = PrefsAutoStopStore.of(app),
        exempt: () -> Boolean = { exempt(app) },
        minutes: () -> Int = { CaptureTimingStore.get(app).autoStopMinNow() },
        allowed: () -> Boolean = { BackgroundPolicy.linkMachineryAllowed(KeepRunningNearby.of(app).enabled(), com.rokid.context.link.CxrGlassesLink.isPaired(app)) },
        running: () -> Boolean = { ContextHubService.isRunning() },
        schedule: (Long) -> Unit = { schedule(app, it) },
        start: () -> Boolean = { ContextHubService.ensureRunning(app, wake = true) },
    ): Boolean {
        if (store.autoStoppedAtMs() == 0L || store.manualStop()) return false // the common case, without the lock (re-checked under it)
        val max = BackgroundPolicy.WAKE_LADDER_MS.last()
        return when (claim(now, store, exempt, minutes, allowed, running, start, via = "process start")) {
            Claim.STARTED -> true
            Claim.NOT_DUE, Claim.REFUSED -> { schedule(BackgroundPolicy.remainingMs(now, store.nextWakeAtMs(), max)); false }
            Claim.NONE, Claim.ALREADY_RUNNING -> false
        }
    }

    /** What [claim] did. Only [STARTED] issued a start; [NONE] and [NOT_DUE] touched nothing. */
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

    private val claimLock = Any()

    /**
     * Review fix 2026-11-07 (Codex): the one owner of "claim a wake", shared by the WorkManager/process-start path ([onProcessStart], off the main thread) and the
     * live [AutoStopController] (its IO hop). Check / consume the rung / save the probe / start / settle run under ONE process-wide lock, so two entry points can
     * never both pass the deadline: the loser finds the auto stop cleared (the winner started) or the deadline moved (the winner was refused) and returns [NONE] or
     * [NOT_DUE] without touching the probe flag. It blocks on disk commits: never call it on the main thread.
     */
    fun claim(
        now: Long, store: AutoStopStore, exempt: () -> Boolean, minutes: () -> Int, allowed: () -> Boolean, running: () -> Boolean, start: () -> Boolean,
        via: String, log: (String) -> Unit = { PcLog.i(AutoStopController.TAG, it) }, warn: (String) -> Unit = { PcLog.w(AutoStopController.TAG, it) },
    ): Claim = synchronized(claimLock) {
        if (store.autoStoppedAtMs() == 0L || store.manualStop()) return Claim.NONE
        if (!BackgroundPolicy.wakeAllowed(exempt(), true, false, minutes(), running(), allowed())) return Claim.NONE
        if (!BackgroundPolicy.deadlinePassed(now, store.nextWakeAtMs(), BackgroundPolicy.WAKE_LADDER_MS.last())) return Claim.NOT_DUE
        store.wakeAttempted(now + BackgroundPolicy.wakeDelayMs(store.wakeStep() + 1)) // committed before the start it guards
        store.setWakeProbe(true) // saved before the start: the woken hub's first away window is the short probe
        log("auto_stop wake attempt ($via)")
        val issued = runCatching(start).getOrDefault(false)
        when {
            issued -> { store.clearAutoStopped(); Claim.STARTED }
            running() -> { store.clearAutoStopped(); log("auto_stop wake: hub already running ($via)"); Claim.ALREADY_RUNNING }
            else -> { warn("auto_stop wake refused ($via)"); store.setWakeProbe(false); Claim.REFUSED }
        }
    }
}

/** The one-shot wake for the next rung ([AutoStopWake.schedule]); it only runs the same checks as a process start. */
class AutoStopWakeWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        runCatching { AutoStopWake.onProcessStart(applicationContext) }
        return Result.success()
    }
}
