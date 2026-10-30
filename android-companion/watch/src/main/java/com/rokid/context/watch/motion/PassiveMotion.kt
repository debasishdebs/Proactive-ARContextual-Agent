package com.rokid.context.watch.motion

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import com.rokid.context.core.model.Wire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

// Provenance: ported from live-fitness-tracker watch/.../motion/PassiveMotion.kt (send path replaced by MotionSender; on/off switch added).
/** Health Services passive monitoring (steps and user activity) feeding [MotionStateDetector]; sends on [WatchWire.PATH]. Logs: enum names and counts only. */
object PassiveMotion {
    const val TAG = "PcWatch"
    private const val TICK_MS = 600_000L
    private const val PREFS = "motion"
    private const val KEY = "memory"
    private const val KEY_ENABLED = "enabled"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val sendJobs: MutableSet<Job> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val mutex = Mutex()

    /** Random per process start, never 0 (0 means "no epoch" on the phone). */
    private val epoch: Long = Random.nextLong(1, Long.MAX_VALUE)
    @Volatile private var sender: MotionSender? = null

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun load(context: Context): MotionMemory =
        prefs(context).getString(KEY, null)?.let { s -> runCatching { Wire.json.decodeFromString<MotionMemory>(s) }.getOrNull() } ?: MotionMemory()

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context.applicationContext, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    /** The toggle. Off cancels every in-flight or queued send, unregisters the passive listener and stops the alarm; on registers again. Serialised: the last call wins. */
    fun setEnabled(context: Context, on: Boolean): Job? {
        val app = context.applicationContext
        prefs(app).edit().putBoolean(KEY_ENABLED, on).commit()
        Log.i(TAG, "enabled=$on")
        if (!on) cancelSends()
        return scope.launch { switchFor(app).reconcile() }
    }

    /** Idempotent: safe to call from init, the permission callback, onResume and boot. Returns the work in flight, if any. */
    fun register(context: Context): Job? {
        val app = context.applicationContext
        if (!isEnabled(app)) return null
        if (!hasPermission(app)) {
            Log.i(TAG, "ACTIVITY_RECOGNITION not granted")
            return null
        }
        return scope.launch { switchFor(app).reconcile() }
    }

    private fun cancelSends() { sendJobs.toList().forEach { it.cancel() } }

    @Volatile private var switch: MonitoringSwitch? = null

    @Synchronized private fun switchFor(app: Context): MonitoringSwitch = switch ?: MonitoringSwitch(
        client = object : MonitoringClient {
            override suspend fun register() {
                val client = HealthServices.getClient(app).passiveMonitoringClient
                val caps = client.getCapabilitiesAsync().await()
                val types = setOf(DataType.STEPS).filter { it in caps.supportedDataTypesPassiveMonitoring }.toSet()
                val config = PassiveListenerConfig.builder()
                    .setDataTypes(types)
                    .setShouldUserActivityInfoBeRequested(caps.supportedUserActivityStates.isNotEmpty())
                    .build()
                client.setPassiveListenerServiceAsync(MotionPassiveService::class.java, config).await()
                Log.i(TAG, "registered dataTypes=${types.size}")
            }
            override suspend fun unregister() { HealthServices.getClient(app).passiveMonitoringClient.clearPassiveListenerServiceAsync().await() }
        },
        scheduleTick = { scheduleTick(app) }, cancelTick = { cancelAlarms(app) },
        want = { isEnabled(app) && hasPermission(app) }, log = { Log.i(TAG, it) },
    ).also { switch = it }

    private fun tickIntent(app: Context, code: Int): PendingIntent = PendingIntent.getBroadcast(
        app, code, Intent(app, MotionReceiver::class.java).setAction(MotionReceiver.ACTION_TICK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun scheduleTick(context: Context) {
        val app = context.applicationContext
        val alarms = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarms.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + TICK_MS, TICK_MS, tickIntent(app, 0))
    }

    private fun cancelAlarms(app: Context) {
        val alarms = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarms.cancel(tickIntent(app, 0))
        alarms.cancel(tickIntent(app, 1))
    }

    /** One-shot inexact alarm for when a pending candidate has been held long enough; a no-op when none is pending. */
    private fun scheduleHold(app: Context, m: MotionMemory, now: Long) {
        val due = MotionStateDetector.holdDueAtMs(m) ?: return
        val alarms = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarms.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + maxOf(due - now, 1_000L), tickIntent(app, 1))
    }

    private fun senderFor(app: Context): MotionSender =
        sender ?: MotionSender(WearPhoneLink(app), System::currentTimeMillis, { delay(it) }, epoch, enabled = { isEnabled(app) }).also { sender = it }

    fun onEvents(context: Context, events: List<MotionEvent>): Job = scope.launch {
        val self = coroutineContext[Job]!!
        sendJobs += self
        try { handle(context, events) } finally { sendJobs -= self }
    }

    private suspend fun handle(context: Context, events: List<MotionEvent>) {
        val app = context.applicationContext
        if (!isEnabled(app)) return
        // One batch at a time across the suspending send: load, fold, send and save are a single critical section.
        mutex.withLock {
            if (!isEnabled(app)) return
            val prefs = prefs(app)
            var m = load(app)
            val now = System.currentTimeMillis()
            val step = MotionStateDetector.fold(m, events, now)
            m = step.memory
            try {
                val report = step.report
                if (report != null) {
                    val tries = try {
                        senderFor(app).send(report)
                    } catch (c: kotlin.coroutines.cancellation.CancellationException) {
                        throw c
                    } catch (_: Exception) {
                        null
                    }
                    if (tries != null) m = MotionStateDetector.markSent(m, System.currentTimeMillis())
                    Log.i(TAG, "send ${report.state} ok=${tries != null} tries=${tries ?: 0}")
                }
            } finally {
                // Even a cancelled send must not lose the fold result.
                prefs.edit().putString(KEY, Wire.encode(m)).commit()
                if (isEnabled(app)) scheduleHold(app, m, now)
            }
        }
    }
}
