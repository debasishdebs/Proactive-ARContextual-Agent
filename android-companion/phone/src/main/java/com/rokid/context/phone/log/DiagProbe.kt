package com.rokid.context.phone.log

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import com.rokid.context.core.log.PcLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.TimeZone

/**
 * Trip diagnostics: the runtime facts around the app that the feature logs do not see, so a log read days later can tell "the phone was offline / in power save / low on battery / its clock jumped / a permission was
 * revoked / the worker was stopped" from "the feature broke". Everything is a fixed token, a count, a bucket or a class name of OUR OWN worker: never a network name, an address, a package list, a position or any user text.
 * Registered once from `ContextApp` on an IO scope; every callback only logs (non-blocking, through [PcLog]) and nothing here can throw into the app. The lines are INFO (file only while the Detailed log is on), except the
 * app start marker which is a milestone.
 */
class DiagProbe(
    private val app: Application, private val scope: CoroutineScope, private val onTimeChange: () -> Unit = {},
    /** Where the battery and time receivers run (production: a background thread of their own, never the main thread: [onTimeChange] may build the data saver graph). Tests inject one. */
    receiverHandler: Handler? = null,
) {
    private val handler: Handler by lazy { receiverHandler ?: Handler(HandlerThread("pca-diag-receivers").apply { start() }.looper) }
    @Volatile private var lastBattery = ""
    @Volatile private var lastNet = ""
    @Volatile private var lastWallMinusElapsed = System.currentTimeMillis() - SystemClock.elapsedRealtime()
    private val perms = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val workStates = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** The activities that are started right now (by identity): the foreground state is "not empty", so a missed or doubled callback can never make it negative. */
    private val startedActivities = HashSet<Int>()
    private val startedLock = Any()
    private val startedOnce = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var snapshotLogged = false

    /**
     * Returns at once: every registration (sticky battery read, receivers, the default-network callback, the permission checks) is binder IPC, so it runs on [scope] (IO), not on the
     * main thread of `Application.onCreate`. All of those APIs are thread-safe; a second call does nothing, so receivers and callbacks are registered once.
     * The activity lifecycle callbacks are the exception: they are registered HERE, synchronously, before any activity can start (it is a plain in-process list add, not IPC), so no start is missed.
     */
    fun start() {
        if (!startedOnce.compareAndSet(false, true)) return
        guard { registerForeground() }
        guard { scope.launch { startNow() } }
    }

    private suspend fun startNow() {
        guard { appStart() }
        guard { registerBattery() }
        guard { registerTime() }
        guard { registerNetwork() }
        guard { checkPermissions(initial = true) }
        guard { scope.launch { watchWork() } }
        guard { scope.launch { while (true) { delay(HEARTBEAT_MS); guard { heartbeat() } } } }
    }

    private inline fun guard(block: () -> Unit) { try { block() } catch (_: Throwable) { } }

    private fun appStart() {
        val sdk = Build.VERSION.SDK_INT
        val uptimeS = SystemClock.elapsedRealtime() / 1000
        val offsetMin = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
        PcLog.milestone(TAG, "app start cold process sdk=$sdk uptimeS=$uptimeS tzOffsetMin=$offsetMin")
    }

    // ---- battery ----

    private fun registerBattery() {
        val receiver = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { guard { batteryLine(i, "change") } } }
        val sticky = app.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), null, handler)
        if (sticky != null) batteryLine(sticky, "start")
    }

    /** A line only when the 10 % bucket or the charging state changed (the receiver fires on every 1 %); the first look and the heartbeat always log. */
    private fun batteryLine(i: Intent, why: String) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val pct = if (level < 0 || scale <= 0) -1 else level * 100 / scale
        val bucket = if (pct < 0) -1 else pct / 10 * 10
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val tempBucket = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 100 * 10      // tenths of a degree C, in 10 degree steps
        val key = "$bucket/$charging"
        if (why == "change" && key == lastBattery) return
        lastBattery = key
        PcLog.i(TAG, "battery bucket=$bucket charging=$charging tempC=$tempBucket why=$why")
    }

    // ---- network ----

    private fun registerNetwork() {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { guard { netLine(caps) } }
            override fun onLost(network: Network) { guard { netLine(null) } }
        })
        netLine(cm.getNetworkCapabilities(cm.activeNetwork))
    }

    private fun netLine(caps: NetworkCapabilities?) {
        val online = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val metered = caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val roaming = caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
        val transport = when {
            caps == null -> "none"; caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"; caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"; caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"; else -> "other"
        }
        val key = "$online/$validated/$metered/$roaming/$transport"
        if (key == lastNet) return
        lastNet = key
        PcLog.i(TAG, "network online=$online validated=$validated metered=$metered roaming=$roaming transport=$transport")
    }

    // ---- clock and time zone ----

    internal fun registerTime() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { guard { timeLine(i.action) }; guard { onTimeChange() } }
        }
        app.registerReceiver(receiver, IntentFilter().apply { addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED) }, null, handler)
    }

    private fun timeLine(action: String?) {
        val wallMinusElapsed = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val jumpS = (wallMinusElapsed - lastWallMinusElapsed) / 1000      // how far the wall clock moved against the monotonic one
        lastWallMinusElapsed = wallMinusElapsed
        val offsetMin = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
        if (action == Intent.ACTION_TIMEZONE_CHANGED) PcLog.i(TAG, "timezone changed tzOffsetMin=$offsetMin")
        else PcLog.i(TAG, "clock changed jumpS=$jumpS tzOffsetMin=$offsetMin")
    }

    // ---- foreground / background and permissions ----

    internal fun registerForeground() {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) {
                val first = synchronized(startedLock) { val wasEmpty = startedActivities.isEmpty(); startedActivities.add(System.identityHashCode(a)) && wasEmpty }
                if (first) guard { PcLog.i(TAG, "app foreground"); scope.launch { guard { checkPermissions(initial = false) } } } // the permission look is binder IPC: never on the main thread
            }
            override fun onActivityStopped(a: Activity) {
                val last = synchronized(startedLock) { startedActivities.remove(System.identityHashCode(a)) && startedActivities.isEmpty() }
                if (last) guard { PcLog.i(TAG, "app background") }
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    internal fun isForeground(): Boolean = synchronized(startedLock) { startedActivities.isNotEmpty() }

    /** The runtime permissions the app uses, by fixed short name. A change since the last look (the user toggled it in system Settings) is one line; [initial] logs the whole set once. */
    private fun checkPermissions(initial: Boolean) {
        var granted = 0; var denied = 0
        for ((what, perm) in WATCHED) {
            if ((what == "NOTIFICATIONS" && Build.VERSION.SDK_INT < 33) || (what == "BLUETOOTH_CONNECT" && Build.VERSION.SDK_INT < 31)) continue
            val ok = app.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
            if (ok) granted++ else denied++
            val before = perms.put(what, ok)
            if (before != null && before != ok) PcLog.i(TAG, "permission changed what=$what ok=$ok")
            else if (initial) PcLog.i(TAG, "permission state what=$what ok=$ok")
        }
        if (initial) PcLog.i(TAG, "permissions granted=$granted denied=$denied")
    }

    // ---- workers ----

    /**
     * One line per state change of any of OUR workers (class name only; the tags of a request can carry other text, so only a `com.rokid.` Worker class tag is read).
     * The query is restricted to the UNFINISHED states (enqueued, running, blocked): a query over every state re-reads the whole finished history at each WorkManager change. A worker that leaves that set has finished,
     * so its final state is read once by id ([finalState]), which is a single-row lookup.
     */
    private suspend fun watchWork() {
        val active = WorkQuery.Builder.fromStates(listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)).build()
        val wm = WorkManager.getInstance(app)
        wm.getWorkInfosFlow(active).catch { }.collect { infos ->
            val first = !snapshotLogged
            var running = 0; var enqueued = 0
            val present = HashSet<String>()
            for (w in infos) {
                if (w.state == WorkInfo.State.RUNNING) running++
                if (w.state == WorkInfo.State.ENQUEUED) enqueued++
                val worker = ourWorker(w) ?: continue
                present += w.id.toString()
                logWorker(worker, w, first)
            }
            // Gone from the unfinished set: finished (or pruned by WorkManager). Read the final state once, by id.
            if (!first) for ((key, value) in workStates.entries.toList()) {
                if (key in present || isFinished(value)) continue
                val fin = finalState(wm, key)
                val worker = fin?.let { ourWorker(it) }
                if (fin != null && worker != null && fin.state.isFinished) logWorker(worker, fin, first = false) else workStates.remove(key)
            }
            if (first) { snapshotLogged = true; PcLog.i(TAG, "workers snapshot running=$running enqueued=$enqueued total=${infos.size}") }
            pruneWorkStates(workStates, MAX_WORK_STATES)
        }
    }

    private fun ourWorker(w: WorkInfo): String? = w.tags.firstOrNull { it.startsWith("com.rokid.") && it.endsWith("Worker") }?.substringAfterLast('.')

    private fun logWorker(worker: String, w: WorkInfo, first: Boolean) {
        val state = w.state.name
        val seen = workStates.put(w.id.toString(), "$state/${w.runAttemptCount}")
        if (!first && seen != "$state/${w.runAttemptCount}") {
            val attempt = w.runAttemptCount
            val code = w.stopReason
            PcLog.i(TAG, "worker $worker state=$state attempt=$attempt stopReason=$code")
        }
    }

    private fun isFinished(v: String) = v.startsWith("SUCCEEDED/") || v.startsWith("FAILED/") || v.startsWith("CANCELLED/")

    private suspend fun finalState(wm: WorkManager, id: String): WorkInfo? = try {
        kotlinx.coroutines.withTimeoutOrNull(5_000) { wm.getWorkInfoByIdFlow(java.util.UUID.fromString(id)).first() }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { null }

    // ---- hourly heartbeat ----

    private fun heartbeat() {
        val rt = Runtime.getRuntime()
        val heapMb = (rt.totalMemory() - rt.freeMemory()) / MB; val maxMb = rt.maxMemory() / MB
        val pm = app.getSystemService(PowerManager::class.java)
        val powerSave = pm?.isPowerSaveMode == true
        val idle = pm?.isDeviceIdleMode == true
        val exempt = pm?.isIgnoringBatteryOptimizations(app.packageName) == true
        val uptimeS = SystemClock.elapsedRealtime() / 1000
        val foreground = isForeground()
        PcLog.i(TAG, "heartbeat uptimeS=$uptimeS heapMb=$heapMb maxMb=$maxMb powerSave=$powerSave idle=$idle batteryExempt=$exempt foreground=$foreground")
        app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { batteryLine(it, "hourly") }
        checkPermissions(initial = false)
    }

    companion object {
        const val TAG = "PcDiag"
        const val MAX_WORK_STATES = 200

        /** Bounds [states] (work id -> "STATE/attempt"): above [cap] it drops finished entries (SUCCEEDED, FAILED, CANCELLED) until it fits; unfinished work is always kept. */
        internal fun pruneWorkStates(states: MutableMap<String, String>, cap: Int) {
            if (states.size <= cap) return
            val it = states.entries.iterator()
            while (it.hasNext() && states.size > cap) { val v = it.next().value; if (v.startsWith("SUCCEEDED/") || v.startsWith("FAILED/") || v.startsWith("CANCELLED/")) it.remove() }
        }

        const val HEARTBEAT_MS = 60 * 60 * 1000L
        private const val MB = 1024L * 1024L
        /** Fixed short names for the runtime permissions the app uses. */
        private val WATCHED = listOf(
            "CAMERA" to Manifest.permission.CAMERA, "MIC" to Manifest.permission.RECORD_AUDIO, "FINE_LOCATION" to Manifest.permission.ACCESS_FINE_LOCATION,
            "COARSE_LOCATION" to Manifest.permission.ACCESS_COARSE_LOCATION, "BACKGROUND_LOCATION" to Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            "NOTIFICATIONS" to Manifest.permission.POST_NOTIFICATIONS, "BLUETOOTH_CONNECT" to Manifest.permission.BLUETOOTH_CONNECT,
            "CALENDAR" to Manifest.permission.READ_CALENDAR, "CONTACTS" to Manifest.permission.READ_CONTACTS, "ACTIVITY" to Manifest.permission.ACTIVITY_RECOGNITION,
        )
    }
}
