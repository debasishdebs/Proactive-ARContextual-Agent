package com.rokid.context.connectors.sources

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.LocationManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

data class Fix(val lat: Double, val lon: Double, val timeMs: Long)
data class Place(val subLocality: String?, val locality: String?, val adminArea: String?)

/**
 * Location (R-64): the last known network (else passive) fix, no older than 30 min, reverse-geocoded on [io] within
 * 3 s into `"<subLocality or locality>, <locality or adminArea>"`. A label is cached for 10 min. No Play services.
 */
class LocationSource(
    private val lastFix: () -> Fix?,
    private val geocode: suspend (Double, Double) -> Place?,
    private val io: CoroutineDispatcher,
) : ContextSource {
    override val kind = SourceKind.LOCATION
    override val permissions = SourcePermissions.of(SourceKind.LOCATION)

    private data class Cached(val label: String, val atMs: Long)
    @Volatile private var cached: Cached? = null

    override suspend fun lines(question: String?, nowMs: Long): List<String> = listOfNotNull(placeLabel(nowMs)?.let { "place \u00b7 $it" })

    /** Never throws. Null when there is no fresh fix or the geocoder gives nothing in time. */
    suspend fun placeLabel(nowMs: Long): String? {
        cached?.takeIf { nowMs - it.atMs in 0 until CACHE_MS }?.let { return it.label }
        return try {
            val fix = withContext(io) { lastFix() } ?: return null
            if (nowMs - fix.timeMs > MAX_AGE_MS) return null
            // A blocking Geocoder call can't be cancelled: it runs detached on io and the wait for it is bounded.
            val pending = CoroutineScope(io).async { geocode(fix.lat, fix.lon) }
            val place = withTimeoutOrNull(GEOCODE_TIMEOUT_MS) { pending.await() } ?: run { pending.cancel(); return null }
            label(place)?.also { cached = Cached(it, nowMs) }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val MAX_AGE_MS = 30 * 60_000L
        private const val CACHE_MS = 10 * 60_000L
        private const val GEOCODE_TIMEOUT_MS = 3_000L

        fun label(p: Place): String? {
            val first = p.subLocality.nonBlank() ?: p.locality.nonBlank() ?: return null
            val second = (if (p.subLocality.nonBlank() != null) p.locality.nonBlank() else null) ?: p.adminArea.nonBlank()
            return if (second == null || second == first) first else "$first, $second"
        }

        private fun String?.nonBlank() = this?.trim()?.takeIf { it.isNotEmpty() }

        @SuppressLint("MissingPermission") // EnabledSources/SourcesGraph check ACCESS_COARSE_LOCATION first; a revoke throws → caught
        fun lastKnown(context: Context): () -> Fix? = {
            val lm = context.getSystemService(LocationManager::class.java)
            val loc = lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) ?: lm?.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
            loc?.let { Fix(it.latitude, it.longitude, it.time) }
        }

        @Suppress("DEPRECATION") // the listener API is 33+; minSdk is 29 and this always runs on IO with a timeout
        fun geocoder(context: Context): suspend (Double, Double) -> Place? = { lat, lon ->
            if (!Geocoder.isPresent()) null
            else Geocoder(context, Locale.ENGLISH).getFromLocation(lat, lon, 1)?.firstOrNull()?.let { Place(it.subLocality, it.locality, it.adminArea) }
        }
    }
}
