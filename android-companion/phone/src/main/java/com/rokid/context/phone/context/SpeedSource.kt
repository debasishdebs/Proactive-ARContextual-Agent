package com.rokid.context.phone.context

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.rokid.context.core.model.context.Evidence
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * `phone_speed`: speed on demand (spec §5). The hub asks for it ([setWanted]) only while a vehicle or flight is suspected; off
 * otherwise. Location privacy: a fix enters [onFix] and only `kmh` leaves; the one baseline fix lives in private fields, is never
 * stored, logged or sent, and is dropped when the source is no longer wanted. Counts only (`fixes`, `stale`, `onMs`).
 *
 * `speed` is reported when the fix carries it (`precise`); otherwise it is derived from a **baseline** fix: the baseline is kept
 * (not advanced) until `dt >= 30 s` and the displacement exceeds `3 x (acc1 + acc2)`, then the average speed since it is emitted
 * (`precise = false`) and the current fix becomes the baseline. A baseline older than [BASELINE_MAX_MS] is replaced, not used, and
 * a reported-speed fix resets it. Fix time is the elapsed-realtime clock: a fix from before the request, older than the last, or
 * older than [MAX_AGE_MS] is dropped (a provider's cached/last-known fix never replays), and the evidence time is translated to the
 * fusion clock. After a fast reading (>= [FAST_KMH]) a quick follow-up interval is requested so the engine's two-fast-readings
 * confirmation fits.
 */
class SpeedSource(
    private val sinkFor: (String) -> EvidenceSink,
    private val time: ObsTime = ObsTime(),
    private val platform: Platform,
) : ContextSource, SpeedControl {
    interface Platform {
        /** Location access of any kind (FINE or COARSE) is granted. */
        fun permitted(): Boolean
        /** The shell's whole job: updates on/off; [quick] = the short follow-up interval. Idempotent per state. */
        fun setUpdates(wanted: Boolean, quick: Boolean)
    }

    override val id = ID
    private val accepted = AtomicInteger(); private val staleN = AtomicInteger(); private val fixesN = AtomicInteger()
    override val events: Int get() = accepted.get()
    val fixes: Int get() = fixesN.get()
    val stale: Int get() = staleN.get()

    private val gate = ObsGate(time, MAX_AGE_MS)
    private var sink: EvidenceSink? = null
    @Volatile var wanted = false; private set
    private var quick = false
    private var onSince = 0L
    private var onAccum = 0L

    private class Base(val atMs: Long, val lat: Double, val lon: Double, val acc: Float)
    private var base: Base? = null

    /** Milliseconds this source has been requested (accumulates only while wanted). */
    val onMs: Long @Synchronized get() = onAccum + if (wanted) time.nowElapsed() - onSince else 0L

    /** Location is a runtime permission the hub only asks for once granted; no Play services check beyond the client itself. */
    override fun available(): Boolean = true

    @Synchronized
    override fun start(): Boolean {
        stopLocked()
        if (!platform.permitted()) return false
        sink = sinkFor(ID)
        return true
    }

    @Synchronized override fun stop() { stopLocked() }

    private fun stopLocked() {
        if (sink == null) return
        applyWanted(false)
        sink = null
    }

    @Synchronized
    override fun setWanted(wanted: Boolean) {
        if (sink == null) return
        if (wanted && !platform.permitted()) { applyWanted(false); return }
        applyWanted(wanted)
    }

    private fun applyWanted(on: Boolean) {
        if (on == wanted) return
        val now = time.nowElapsed()
        if (on) { onSince = now; gate.begin() } else onAccum += now - onSince
        wanted = on; base = null; quick = false
        platform.setUpdates(on, false)
    }

    /**
     * One location fix: [timeMs] is its elapsed-realtime time. [speedMps] is non-null only when the provider reported a speed.
     * The coordinates and accuracy are used for the baseline comparison only.
     */
    @Synchronized
    fun onFix(timeMs: Long, lat: Double, lon: Double, accuracyM: Float, speedMps: Float?) {
        val out = sink ?: return
        if (!wanted) return
        val obs = gate.admit(timeMs) ?: run { staleN.incrementAndGet(); return }
        fixesN.incrementAndGet()
        val at = time.toWall(obs)
        if (speedMps != null && speedMps.isFinite() && speedMps >= 0f) {
            base = Base(obs, lat, lon, accuracyM)
            emit(out, at, speedMps * 3.6f, precise = true)
            return
        }
        val b = base
        if (b == null || obs - b.atMs > BASELINE_MAX_MS) { base = Base(obs, lat, lon, accuracyM); return }
        val dt = obs - b.atMs
        if (dt < MIN_DT_MS) return
        val d = haversineM(b.lat, b.lon, lat, lon)
        if (d > 3.0 * (accuracyM + b.acc)) {
            base = Base(obs, lat, lon, accuracyM)
            emit(out, at, (d / (dt / 1000.0) * 3.6).toFloat(), precise = false)
        }
    }

    private fun emit(out: EvidenceSink, at: Long, kmh: Float, precise: Boolean) {
        accepted.incrementAndGet()
        out.accept(Evidence.Speed(at, kmh, precise))
        val q = kmh >= FAST_KMH
        if (q != quick) { quick = q; platform.setUpdates(true, q) }
    }

    companion object {
        const val ID = "phone_speed"
        const val MIN_DT_MS = 30_000L
        const val BASELINE_MAX_MS = 10 * 60_000L
        const val MAX_AGE_MS = 2 * 60_000L
        const val FAST_KMH = 25f
        const val INTERVAL_MS = 30_000L
        const val QUICK_INTERVAL_MS = 15_000L

        fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
            val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
            val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            return 2 * r * asin(sqrt(a))
        }

        /**
         * [foreground] and [locationHeld] say whether location access is currently valid for the process (the app is visible, or the
         * foreground service holds the `location` type); fixes are requested only then. [onChange] asks the service to re-evaluate its
         * foreground types.
         */
        fun create(context: Context, sinkFor: (String) -> EvidenceSink, foreground: () -> Boolean, locationHeld: () -> Boolean, onChange: () -> Unit): SpeedSource {
            lateinit var self: SpeedSource
            self = SpeedSource(sinkFor, platform = AndroidPlatform(context.applicationContext, { self }, foreground, locationHeld, onChange))
            return self
        }

        internal fun hasLocationPermission(ctx: Context): Boolean =
            ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    /** BALANCED power, 30 s (quick 15 s), speed only. Runs on the main looper; re-checks access every 15 s while wanted. */
    @SuppressLint("MissingPermission") // checked through hasLocationPermission before every request
    private class AndroidPlatform(
        private val ctx: Context, private val source: () -> SpeedSource,
        private val foreground: () -> Boolean, private val locationHeld: () -> Boolean, private val onChange: () -> Unit,
    ) : Platform {
        private val main = Handler(Looper.getMainLooper())
        private var wanted = false
        private var quick = false
        private var requesting: Boolean? = null // null = no request; else the quick flag requested
        private val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                if (!accessValid()) return
                for (l in result.locations) {
                    source().onFix(l.elapsedRealtimeNanos / 1_000_000L, l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy else 5_000f, if (l.hasSpeed()) l.speed else null)
                }
            }
        }
        private val tick = object : Runnable { override fun run() { ensure(); if (wanted) main.postDelayed(this, QUICK_INTERVAL_MS) } }

        override fun permitted() = hasLocationPermission(ctx)

        override fun setUpdates(wanted: Boolean, quick: Boolean) {
            main.post {
                val was = this.wanted
                this.wanted = wanted; this.quick = quick
                if (wanted != was) { runCatching { onChange() }; main.removeCallbacks(tick); if (wanted) main.postDelayed(tick, QUICK_INTERVAL_MS) }
                ensure()
            }
        }

        private fun accessValid() = hasLocationPermission(ctx) && (locationHeld() || foreground())

        private fun ensure() {
            val client = LocationServices.getFusedLocationProviderClient(ctx)
            if (!wanted || !accessValid()) {
                if (requesting != null) { requesting = null; runCatching { client.removeLocationUpdates(callback) } }
                return
            }
            if (requesting == quick) return
            val interval = if (quick) QUICK_INTERVAL_MS else INTERVAL_MS
            val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, interval)
                .setMinUpdateIntervalMillis(QUICK_INTERVAL_MS).build()
            requesting = if (runCatching { client.requestLocationUpdates(request, callback, Looper.getMainLooper()) }.isSuccess) quick else null
        }
    }
}
