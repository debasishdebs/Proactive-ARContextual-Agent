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

/**
 * One street-map wiring: the saved switch, the tile cache in the app cache folder and (only when a map key is set in Settings, or this is a debug build with the dev key) the tile source.
 * Built fresh each time the map screen opens, so a key entered in Settings applies at once. Without a key there is no provider and nothing reaches the network.
 */
class OnlineMapGraph private constructor(private val app: Context, private val keys: MapKeyStore) {
    private val root = cacheRoot(app)
    private var current: Built? = null

    private inner class Built(key: String) {
        // debug = false on purpose: the unkeyed OpenStreetMap fallback is not used at runtime; no key means no tile request at all.
        val provider: OnlineProvider? = OnlineProvider.select(key, debug = false)
        val source: OnlineTileSource? = provider?.let {
            // The User-Agent names the app only (the same one the Place Guide fetchers use): no email, no personal data.
            OnlineTileSource(it, UrlConnectionTileTransport(), OnlineTileDiskCache(root, it.cacheId), UrlConnectionPlaceHttp.DEFAULT_USER_AGENT,
                offlineOnly = { com.rokid.context.ai.resolve.ModelPrivacyStore.get(app).current() != com.rokid.context.core.net.ModelPrivacy.AUTO },
                dataGate = com.rokid.context.ai.datasaver.DataSaverGraph.get(app))
        }
        val model = OnlineMapModel(OnlineMapStore(app.getSharedPreferences(OnlineMapStore.PREFS, Context.MODE_PRIVATE)), provider != null, provider?.name ?: "MapTiler", stop = { source?.stop() }) { purge(root) }
        val deps = OnlineMapDeps(model, source, AndroidCentreFix(app), keyControl())
    }

    private fun keyControl() = MapKeyControl(keys.hasKey(), keys.hasStoredKey(), keys::save) { keys.clear() }

    val provider: OnlineProvider? get() = current?.provider

    /** A fresh wiring from the key as it is now; the previous one is stopped. */
    @Synchronized fun deps(): OnlineMapDeps {
        current?.source?.stop()
        return Built(keys.key()).also { current = it }.deps
    }
    companion object {
        const val CACHE_DIR = "online_tiles"
        private fun cacheRoot(app: Context) = File(app.cacheDir, CACHE_DIR)
        private fun purge(root: File) { runCatching { root.deleteRecursively() } }

        @Volatile private var instance: OnlineMapGraph? = null
        fun get(context: Context): OnlineMapGraph = instance ?: synchronized(this) { instance ?: OnlineMapGraph(context.applicationContext, MapKeyStore(com.rokid.context.ai.EncryptedKeyVault(context), MapKeyStore.fallbackFor(BuildConfig.DEBUG, BuildConfig.MAP_TILES_KEY))).also { instance = it } }

        /** Delete all (and the resume of an interrupted one): `MemoryWipe` clears the prefs file and deletes the cache folder itself; this resets the in-memory switch and consent. */
        fun wipeListener(): OnlineMapWipeListener = OnlineMapWipeListener { instance?.current?.model }
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
