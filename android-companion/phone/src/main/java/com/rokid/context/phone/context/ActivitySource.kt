package com.rokid.context.phone.context

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import com.rokid.context.core.model.context.Evidence
import com.rokid.context.core.model.context.PhoneActivity
import java.util.concurrent.atomic.AtomicInteger

/**
 * `phone_activity`: Play services Activity Recognition **Transition API** (spec §5), event-driven, no polling. The pure core is
 * [onTransition]; the Android shell is the [Platform]. Counts only (`events`, `ignored`, `stale`); never logs a payload.
 * [start]/[stop] may run on a Looper-less thread; the sink is taken inside [start] (per-run generation, see [ContextSource.start]).
 * The observation time of a transition (`elapsedRealTimeNanos`) is translated to the fusion clock; a transition older than this
 * run, older than the last accepted one or older than [MAX_AGE_MS] is dropped (Play services may deliver batched history).
 */
class ActivitySource(
    private val sinkFor: (String) -> EvidenceSink,
    private val failFor: (String) -> (() -> Unit) = { {} },
    private val time: ObsTime = ObsTime(),
    private val platform: Platform,
) : ContextSource {
    interface Platform {
        fun playServices(): Boolean
        fun permitted(): Boolean
        /** Asks for ENTER+EXIT updates of the five types; false when refused. [onFailed] is called if the request fails later (async). */
        fun register(onFailed: () -> Unit): Boolean
        fun unregister()
    }

    override val id = ID
    private val accepted = AtomicInteger(); private val ignoredN = AtomicInteger(); private val staleN = AtomicInteger()
    override val events: Int get() = accepted.get()
    val ignored: Int get() = ignoredN.get()
    val stale: Int get() = staleN.get()

    private val gate = ObsGate(time, MAX_AGE_MS)
    private var sink: EvidenceSink? = null
    private var firstResult = true

    override fun available(): Boolean = platform.playServices()

    @Synchronized
    override fun start(): Boolean {
        stopLocked()
        if (!platform.permitted()) return false
        val s = sinkFor(ID)
        val failed = failFor(ID)
        // The first result after registering carries the current activity, stamped before start(): admit up to MAX_AGE_MS back.
        gate.begin(); firstResult = true; sink = s
        if (!runCatching { platform.register(failed) }.getOrDefault(false)) { sink = null; return false }
        current = this
        return true
    }

    @Synchronized override fun stop() { stopLocked() }

    private fun stopLocked() {
        if (sink == null) return
        sink = null
        if (current === this) current = null
        runCatching { platform.unregister() }
    }

    class Transition(val activityType: Int, val transitionType: Int, val obsElapsedMs: Long)

    /** One Play services result (all its events). The first result of a run may report the current activity, up to [MAX_AGE_MS] old. */
    @Synchronized
    fun onResult(events: List<Transition>) {
        if (sink == null) return
        if (firstResult) gate.relax(MAX_AGE_MS)
        for (e in events) onTransition(e.activityType, e.transitionType, e.obsElapsedMs)
        if (firstResult) { firstResult = false; gate.tighten() }
    }

    /**
     * One Play services transition. [obsElapsedMs] is the observation time on the elapsed-realtime clock (null = now).
     * Types other than STILL/WALKING/RUNNING/ON_BICYCLE/IN_VEHICLE and transition types other than ENTER/EXIT are ignored.
     */
    @Synchronized
    fun onTransition(activityType: Int, transitionType: Int, obsElapsedMs: Long? = null) {
        val out = sink ?: return
        val activity = when (activityType) {
            DetectedActivity.STILL -> PhoneActivity.STILL
            DetectedActivity.WALKING -> PhoneActivity.WALKING
            DetectedActivity.RUNNING -> PhoneActivity.RUNNING
            DetectedActivity.ON_BICYCLE -> PhoneActivity.ON_BICYCLE
            DetectedActivity.IN_VEHICLE -> PhoneActivity.IN_VEHICLE
            else -> { ignoredN.incrementAndGet(); return }
        }
        val enter = when (transitionType) {
            ActivityTransition.ACTIVITY_TRANSITION_ENTER -> true
            ActivityTransition.ACTIVITY_TRANSITION_EXIT -> false
            else -> { ignoredN.incrementAndGet(); return }
        }
        val obs = gate.admit(obsElapsedMs) ?: run { staleN.incrementAndGet(); return }
        accepted.incrementAndGet()
        out.accept(Evidence.ActivityTransition(time.toWall(obs), activity, enter))
    }

    companion object {
        const val ID = "phone_activity"
        const val MAX_AGE_MS = 15 * 60_000L
        internal const val REQUEST_CODE = 0x4143
        /** The process-wide instance the [ActivityTransitionReceiver] forwards to; null while not running. */
        @Volatile var current: ActivitySource? = null

        fun create(context: Context, sinkFor: (String) -> EvidenceSink, failFor: (String) -> (() -> Unit) = { {} }): ActivitySource =
            ActivitySource(sinkFor, failFor, platform = AndroidPlatform(context.applicationContext))
    }

    private class AndroidPlatform(private val ctx: Context) : Platform {
        private var pi: PendingIntent? = null

        override fun playServices() =
            runCatching { GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx) == ConnectionResult.SUCCESS }.getOrDefault(false)

        override fun permitted() = ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

        @SuppressLint("MissingPermission", "UnspecifiedImmutableFlag") // checked in permitted(); Play services fills the mutable PendingIntent
        override fun register(onFailed: () -> Unit): Boolean {
            val types = listOf(DetectedActivity.STILL, DetectedActivity.WALKING, DetectedActivity.RUNNING, DetectedActivity.ON_BICYCLE, DetectedActivity.IN_VEHICLE)
            val request = ActivityTransitionRequest(types.flatMap { t ->
                listOf(ActivityTransition.ACTIVITY_TRANSITION_ENTER, ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                    .map { ActivityTransition.Builder().setActivityType(t).setActivityTransition(it).build() }
            })
            // Explicit component, FLAG_MUTABLE: Play services adds the result extras to it.
            val p = PendingIntent.getBroadcast(
                ctx, REQUEST_CODE, Intent(ctx, ActivityTransitionReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            pi = p
            ActivityRecognition.getClient(ctx).requestActivityTransitionUpdates(request, p).addOnFailureListener { onFailed() }
            return true
        }

        @SuppressLint("MissingPermission")
        override fun unregister() {
            val p = pi ?: return
            pi = null
            ActivityRecognition.getClient(ctx).removeActivityTransitionUpdates(p)
            p.cancel()
        }

    }
}
