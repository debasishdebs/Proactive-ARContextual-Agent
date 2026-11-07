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
        if (store.autoStoppedAtMs() == 0L || store.manualStop()) return false
        if (!BackgroundPolicy.wakeAllowed(exempt(), true, false, minutes(), running(), allowed())) return false
        val max = BackgroundPolicy.WAKE_LADDER_MS.last()
        if (!BackgroundPolicy.deadlinePassed(now, store.nextWakeAtMs(), max)) { schedule(BackgroundPolicy.remainingMs(now, store.nextWakeAtMs(), max)); return false }
        store.wakeAttempted(now + BackgroundPolicy.wakeDelayMs(store.wakeStep() + 1))
        store.setWakeProbe(true)
        PcLog.i(AutoStopController.TAG, "auto_stop wake attempt (process start)")
        val issued = start()
        if (issued) store.clearAutoStopped() else {
            PcLog.w(AutoStopController.TAG, "auto_stop wake refused")
            store.setWakeProbe(false)
            schedule(BackgroundPolicy.remainingMs(now, store.nextWakeAtMs(), max))
        }
        return issued
    }
}

/** The one-shot wake for the next rung ([AutoStopWake.schedule]); it only runs the same checks as a process start. */
class AutoStopWakeWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        runCatching { AutoStopWake.onProcessStart(applicationContext) }
        return Result.success()
    }
}
