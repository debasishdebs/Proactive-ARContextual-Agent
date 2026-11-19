package com.rokid.context.phone.ui.settings.privacy.zones.tiles

import android.content.SharedPreferences
import com.rokid.context.phone.ui.settings.privacy.zones.LatLon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Where raster map tiles come from, as the zones map sees them: a zoom range, one tile's image bytes (blocking: call from a background dispatcher) and the text the map must show.
 * The offline PMTiles archive and the opt-in online source both fit it. This file and its package name no network class: the online source lives in the `online` package.
 */
interface RasterTileSource {
    val minZoom: Int
    val maxZoom: Int
    /** Text the map must show while tiles of this source are drawn (null: none needed). */
    val attribution: String?
    /** The provider's logo text, shown beside [attribution] (null: none). */
    val logoText: String? get() = null
    fun tile(k: TileKey): ByteArray?
    /** The screen is in the foreground: requests may start. A source that never goes online ignores both. */
    fun start() {}
    /** The screen left the foreground: no further request, and in-flight ones are cancelled. */
    fun stop() {}
    /** The user entered the zones screen: per-visit limits (the request cap) start again. Pausing and resuming inside one visit does NOT call this. */
    fun newVisit() {}
}

/** The one-shot "Centre on me" fix. The platform version asks for ONE foreground fix with the existing location permission; nothing is stored or logged. */
fun interface CentreFix {
    /** The current position, or null when none could be had in time. */
    suspend fun current(): LatLon?
}

/** One "Centre on me" result held on screen only; [id] makes each tap a new request even for the same spot. */
data class CentreRequest(val id: Int, val at: LatLon)

/** The opt-in switch and whether the first-time explanation was accepted. Prefs file [PREFS]: per-device consent, excluded from backup and transfer, and wiped by Delete all. Memory only when [prefs] is null (tests). */
class OnlineMapStore(private val prefs: SharedPreferences? = null) {
    private var memEnabled = false
    private var memConsented = false

    var enabled: Boolean
        get() = prefs?.getBoolean(K_ENABLED, false) ?: memEnabled
        set(v) { memEnabled = v; prefs?.edit()?.putBoolean(K_ENABLED, v)?.apply() }

    /** The first-time dialog was accepted once. */
    var consented: Boolean
        get() = prefs?.getBoolean(K_CONSENTED, false) ?: memConsented
        set(v) { memConsented = v; prefs?.edit()?.putBoolean(K_CONSENTED, v)?.apply() }

    fun clear() { memEnabled = false; memConsented = false; prefs?.edit()?.clear()?.apply() }

    companion object {
        const val PREFS = "online_map"
        private const val K_ENABLED = "enabled"
        private const val K_CONSENTED = "consented"
    }
}

data class OnlineMapUi(
    /** This build has a tile provider (a key, or a debug build). Without one the switch is not offered and the grid shows. */
    val available: Boolean = false,
    val enabled: Boolean = false,
    /** The first-time explanation is showing and the switch is still OFF. */
    val askFirst: Boolean = false,
    /** The provider the build really uses (MapTiler with a key; OpenStreetMap in a debug build without one): the switch label and the dialog name it. */
    val providerName: String = "MapTiler",
)

/**
 * The switch's state machine. OFF by default. Turning it on the FIRST time only raises [OnlineMapUi.askFirst]; the switch stays OFF until [confirmFirst] (Cancel and a dismissal leave it OFF).
 * Turning it off, and [wipe] (Delete all), first run [stop] (the tile source: no request in flight may write a tile back), then [purge] (the tile cache); [wipe] also forgets the consent, so the explanation shows again.
 */
class OnlineMapModel(private val store: OnlineMapStore, available: Boolean, providerName: String = "MapTiler", private val stop: () -> Unit = {}, private val purge: () -> Unit) {
    private val _ui = MutableStateFlow(OnlineMapUi(available = available, enabled = available && store.consented && store.enabled, providerName = providerName))
    val ui: StateFlow<OnlineMapUi> = _ui.asStateFlow()

    fun requestEnabled(on: Boolean) {
        if (!_ui.value.available) return
        if (!on) { store.enabled = false; stop(); purge(); _ui.update { it.copy(enabled = false, askFirst = false) }; return }
        if (_ui.value.enabled) return
        if (!store.consented) { _ui.update { it.copy(askFirst = true) }; return }
        store.enabled = true; _ui.update { it.copy(enabled = true) }
    }

    fun confirmFirst() {
        if (!_ui.value.askFirst) return
        store.consented = true; store.enabled = true
        _ui.update { it.copy(enabled = true, askFirst = false) }
    }

    fun cancelFirst() { _ui.update { it.copy(askFirst = false) } }

    /** Delete all: switch OFF, consent forgotten, tile cache deleted. */
    fun wipe() { store.clear(); stop(); purge(); _ui.update { it.copy(enabled = false, askFirst = false) } }
}

/** Delete all: the in-memory switch and consent follow the prefs file and the cache folder that `MemoryWipe` already cleared (also on the resume of an interrupted wipe). [model] is null while the screen's graph was never built. */
class OnlineMapWipeListener(private val model: () -> OnlineMapModel?) : com.rokid.context.memory.forget.ForgetListener {
    override suspend fun onDeleteAll() { model()?.wipe() }
}

/** What the zones screen is built with to offer the street map and "Centre on me" (null in builds and tests without them). [source] is null when the build has no tile provider. */
class OnlineMapDeps(val model: OnlineMapModel, val source: RasterTileSource?, val fix: CentreFix?)

object OnlineMapWords {
    fun switchLabel(provider: String) = "Show a street map (downloads map tiles from $provider)"
    const val UNAVAILABLE = "A street map is not available in this build (no map-tile key). The plain grid is used."
    const val DIALOG_TITLE = "Download map tiles?"
    fun dialogBody(provider: String) = "With this on, the app asks $provider for map pictures (tiles) of the area you are looking at on this screen. What is sent: the tile requests, which reveal roughly where you are looking on the map, and your IP address, to the tile provider. " +
        "Nothing else is sent. Your zones and coordinates are not sent as such, and nothing is requested while this screen is closed. Tiles are kept on the phone (up to 50 MB) until you turn this off or use Delete all."
    const val NO_FIX = "No location fix yet. Try again with location on, ideally outdoors."
}
