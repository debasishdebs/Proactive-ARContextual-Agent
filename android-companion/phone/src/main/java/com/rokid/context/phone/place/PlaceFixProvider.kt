package com.rokid.context.phone.place

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.rokid.context.phone.hub.ContextHubService
import com.rokid.context.phone.zone.ZoneController
import com.rokid.context.work.frames.place.PlaceFix
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The fix Place Guide matches against, from the hub's foreground location only (no background location, no geofencing): the newest fix the pause-zone location stream already holds when it is
 * fresh, otherwise one on-demand request while the hub's foreground service holds the `location` type (or the app is visible). The fix is handed to the sampler as a value; it is never stored,
 * logged or sent. A permission or system switch that is off, or a request that fails or times out, is simply no fix.
 */
class PlaceFixProvider(
    private val controller: () -> ZoneController?,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val oneShot: suspend () -> PlaceFix?,
) {
    suspend fun fresh(): PlaceFix? {
        controller()?.lastFix()?.let { f ->
            val age = elapsed() - f.timeMs
            if (age in 0..MAX_AGE_MS && !f.accuracyM.isNaN() && f.accuracyM <= MAX_ACCURACY_M) return PlaceFix(f.lat, f.lon, age, f.accuracyM)
        }
        return oneShot()
    }

    companion object {
        fun create(ctx: Context, controller: () -> ZoneController?) = PlaceFixProvider(controller, oneShot = { requestOnce(ctx.applicationContext) })

        const val MAX_AGE_MS = 60_000L
        const val MAX_ACCURACY_M = 50f
        private const val ONE_SHOT_TIMEOUT_MS = 8_000L

        private fun accessValid(ctx: Context): Boolean {
            val perm = ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!perm || !(ContextHubService.locationHeld.value || ContextHubService.appInForeground())) return false
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
            return runCatching { LocationManagerCompat.isLocationEnabled(lm) }.getOrDefault(false)
        }

        @SuppressLint("MissingPermission") // accessValid checks the permission first
        suspend fun requestOnce(ctx: Context): PlaceFix? {
            if (!accessValid(ctx)) return null
            return withTimeoutOrNull(ONE_SHOT_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    val cts = CancellationTokenSource()
                    cont.invokeOnCancellation { cts.cancel() }
                    try {
                        LocationServices.getFusedLocationProviderClient(ctx).getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                            .addOnSuccessListener { l ->
                                val age = l?.let { (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000L }
                                cont.resume(if (l != null && age != null && l.hasAccuracy()) PlaceFix(l.latitude, l.longitude, age, l.accuracy) else null)
                            }
                            .addOnFailureListener { cont.resume(null) }
                    } catch (_: Exception) { cont.resume(null) }
                }
            }
        }
    }
}
