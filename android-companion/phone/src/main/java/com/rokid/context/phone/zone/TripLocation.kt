package com.rokid.context.phone.zone

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.rokid.context.core.model.zone.FixCadence
import com.rokid.context.core.model.zone.FixPriority
import com.rokid.context.core.model.zone.ZoneFix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Foreground-only location for the pause zones (spec 2026-11-16 §B2): fixes are asked for ONLY while a zone exists, at the rate [com.rokid.context.core.model.zone.TripCadence] picks
 * (BALANCED 5 min far away, BALANCED 30 s within 25 km, HIGH 10 s within buffer + 2 km or while failing closed). No background-location permission and no geofencing: access is valid only
 * while the app is visible or the hub's foreground service holds the `location` type, like `SpeedSource`. A fix goes straight to the [ZoneController]; it is never stored, logged or sent.
 * Access that is not valid (permission, the system location switch, no foreground) is reported to the controller, where it fails closed for a zone at APPROACH or INSIDE.
 */
class TripLocation(
    private val controller: ZoneController,
    private val platform: Platform,
    private val scope: CoroutineScope,
    private val tickMs: Long = TICK_MS,
) {
    interface Platform {
        /** Location may be requested right now (permission granted, the system switch on, app visible or the FGS holds the location type). */
        fun accessValid(): Boolean
        /** Idempotent: updates at [cadence], or stopped (null). Fixes come back through [TripLocation.onFix]. */
        fun request(cadence: FixCadence?)
    }

    @Volatile private var wanted: FixCadence? = null
    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch { controller.cadence.collect { wanted = it; apply() } }
        scope.launch { while (true) { delay(tickMs); apply(); controller.tick() } }
    }

    /** From the platform callback (any thread). */
    fun onFix(fix: ZoneFix) { scope.launch { controller.onFix(fix) } }

    /** Re-checks access and (re)applies the wanted cadence; one place decides, so a revoked permission both stops the request and tells the controller. */
    suspend fun apply() {
        val want = wanted
        if (want == null) { platform.request(null); return }
        val ok = platform.accessValid()
        controller.onLocationAvailable(ok)
        platform.request(if (ok) want else null)
    }

    companion object {
        const val TICK_MS = 15_000L

        fun create(context: Context, controller: ZoneController, scope: CoroutineScope, foreground: () -> Boolean, locationHeld: () -> Boolean): TripLocation {
            lateinit var self: TripLocation
            self = TripLocation(controller, AndroidPlatform(context.applicationContext, { self }, foreground, locationHeld), scope)
            return self
        }
    }

    @SuppressLint("MissingPermission") // checked in accessValid before every request
    private class AndroidPlatform(
        private val ctx: Context, private val owner: () -> TripLocation,
        private val foreground: () -> Boolean, private val locationHeld: () -> Boolean,
    ) : Platform {
        private var current: FixCadence? = null
        private val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                if (!accessValid()) return
                for (l in result.locations) {
                    owner().onFix(ZoneFix(l.elapsedRealtimeNanos / 1_000_000L, l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy else Float.NaN))
                }
            }
        }

        override fun accessValid(): Boolean {
            val perm = ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!perm || !(locationHeld() || foreground())) return false
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
            return runCatching { LocationManagerCompat.isLocationEnabled(lm) }.getOrDefault(false)
        }

        @Synchronized
        override fun request(cadence: FixCadence?) {
            if (cadence == current) return
            val client = LocationServices.getFusedLocationProviderClient(ctx)
            runCatching { client.removeLocationUpdates(callback) }
            current = null
            if (cadence == null) return
            val prio = if (cadence.priority == FixPriority.HIGH) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
            val req = LocationRequest.Builder(prio, cadence.intervalMs).setMinUpdateIntervalMillis(cadence.intervalMs / 2).build()
            if (runCatching { client.requestLocationUpdates(req, callback, Looper.getMainLooper()) }.isSuccess) current = cadence
        }
    }
}
