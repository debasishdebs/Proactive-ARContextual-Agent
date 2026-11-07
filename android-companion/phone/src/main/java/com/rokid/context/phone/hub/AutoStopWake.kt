package com.rokid.context.phone.hub

import android.content.Context
import android.os.PowerManager
import com.rokid.context.core.log.PcLog
import com.rokid.context.phone.ui.settings.capture.CaptureTimingStore

/**
 * The wake path of a hub that stopped itself (see the 2026-11-07 AutoStop handoff). Android 12+ refuses a foreground service start from the background unless the app
 * is exempt from battery optimization; with that exemption (the user granted it from Settings > Capture > Run in the background) a process start by the platform
 * (the 15 minute WorkManager jobs, a Data Layer message) may start the hub again, at most once per [BackgroundPolicy.WAKE_LADDER_MS] step and never after a manual stop.
 * A process that is already alive does the same from [AutoStopController]. There is NO event that wakes a stopped hub when the glasses are put on: the wear signal exists only
 * inside an open Rokid session, which the stopped hub does not hold.
 */
object AutoStopWake {
    fun exempt(context: Context): Boolean =
        runCatching { (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(false)

    /** Application.onCreate: true = a start was issued. Cheap exits first (one prefs file) so an ordinary start costs nothing. */
    fun onProcessStart(
        app: Context,
        now: Long = System.currentTimeMillis(),
        store: AutoStopStore = PrefsAutoStopStore.of(app),
        start: () -> Boolean = { ContextHubService.ensureRunning(app, wake = true) },
    ): Boolean {
        if (store.autoStoppedAtMs() == 0L || store.manualStop() || now < store.nextWakeAtMs()) return false
        val minutes = CaptureTimingStore.get(app).autoStopMinNow()
        val allowed = BackgroundPolicy.linkMachineryAllowed(KeepRunningNearby.of(app).enabled(), com.rokid.context.link.CxrGlassesLink.isPaired(app))
        if (!BackgroundPolicy.wakeDue(exempt(app), true, false, minutes, ContextHubService.isRunning(), allowed, now, store.nextWakeAtMs())) return false
        store.wakeAttempted(now + BackgroundPolicy.wakeDelayMs(store.wakeStep() + 1))
        PcLog.i(AutoStopController.TAG, "auto_stop wake attempt (process start)")
        val issued = start()
        if (issued) store.clearAutoStopped() else PcLog.w(AutoStopController.TAG, "auto_stop wake refused")
        return issued
    }
}
