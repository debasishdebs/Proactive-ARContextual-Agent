package com.rokid.context.phone.ui.settings.privacy.zones.online

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.rokid.context.BuildConfig
import com.rokid.context.connectors.place.UrlConnectionPlaceHttp
import com.rokid.context.phone.ui.settings.privacy.zones.LatLon
import com.rokid.context.phone.ui.settings.privacy.zones.tiles.CentreFix
import com.rokid.context.phone.ui.settings.privacy.zones.tiles.OnlineMapDeps
import com.rokid.context.phone.ui.settings.privacy.zones.tiles.OnlineMapModel
import com.rokid.context.phone.ui.settings.privacy.zones.tiles.OnlineMapStore
import com.rokid.context.phone.ui.settings.privacy.zones.tiles.OnlineMapWipeListener
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

/** The app's one street-map wiring: the saved switch, the tile cache in the app cache folder and (only when the build has a provider) the tile source. Built on first use. */
class OnlineMapGraph private constructor(app: Context) {
    val provider: OnlineProvider? = OnlineProvider.select(BuildConfig.MAP_TILES_KEY, BuildConfig.DEBUG)
    private val root = cacheRoot(app)
    val source: OnlineTileSource? = provider?.let {
        // The User-Agent names the app only (the same one the Place Guide fetchers use): no email, no personal data.
        OnlineTileSource(it, UrlConnectionTileTransport(), OnlineTileDiskCache(root, it.cacheId), UrlConnectionPlaceHttp.DEFAULT_USER_AGENT)
    }
    val model = OnlineMapModel(OnlineMapStore(app.getSharedPreferences(OnlineMapStore.PREFS, Context.MODE_PRIVATE)), provider != null) { purge(root) }
    val deps = OnlineMapDeps(model, source, AndroidCentreFix(app))

    companion object {
        const val CACHE_DIR = "online_tiles"
        private fun cacheRoot(app: Context) = File(app.cacheDir, CACHE_DIR)
        private fun purge(root: File) { runCatching { root.deleteRecursively() } }

        @Volatile private var instance: OnlineMapGraph? = null
        fun get(context: Context): OnlineMapGraph = instance ?: synchronized(this) { instance ?: OnlineMapGraph(context.applicationContext).also { instance = it } }

        /** Delete all (and the resume of an interrupted one): `MemoryWipe` clears the prefs file and deletes the cache folder itself; this resets the in-memory switch and consent. */
        fun wipeListener(): OnlineMapWipeListener = OnlineMapWipeListener { instance?.model }
    }
}

/** ONE foreground fix on request with the existing location permission (no new permission). The result goes to the caller only: not stored, not logged. */
class AndroidCentreFix(private val ctx: Context) : CentreFix {
    @SuppressLint("MissingPermission") // the screen calls this only after it checked the permission
    override suspend fun current(): LatLon? {
        val client = LocationServices.getFusedLocationProviderClient(ctx)
        val cts = CancellationTokenSource()
        return try {
            withTimeoutOrNull(FIX_TIMEOUT_MS) {
                suspendCancellableCoroutine<LatLon?> { cont ->
                    cont.invokeOnCancellation { cts.cancel() }
                    try {
                        client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
                            .addOnSuccessListener { l -> cont.resume(l?.let { LatLon(it.latitude, it.longitude) }) }
                            .addOnFailureListener { cont.resume(null) }
                    } catch (e: SecurityException) { cont.resume(null) }
                }
            }
        } finally { cts.cancel() }
    }

    private companion object { const val FIX_TIMEOUT_MS = 12_000L }
}
