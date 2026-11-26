package com.rokid.context.work.frames.place

import android.content.Context
import com.rokid.context.connectors.place.OverpassMirrorSource
import com.rokid.context.connectors.place.PlaceCellSource
import com.rokid.context.connectors.place.UrlConnectionPlaceHttp
import com.rokid.context.connectors.place.WikidataHttpSource
import com.rokid.context.connectors.place.WikipediaHttpSource
import com.rokid.context.core.log.PcLog
import com.rokid.context.memory.place.PlacePack
import com.rokid.context.memory.place.PlacesDatabase
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The process-wide place pack (`places.db`) and the card port. Built lazily, off Main: the first call reads the Keystore. */
object PlaceGraph {
    private const val TAG = "PcPlace"
    @Volatile private var pack: PlacePack? = null
    val port: PlaceCardHolder = PlaceCardHolder()

    /** F4: the id of a pin that was just saved (only by the pin dialog; imports, edits and restores never emit). One buffered value: two pins within a moment give one retry. */
    val pinCreated = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 1)

    /**
     * THE zone fence of Place Guide, shared by the sampler, the pack's fetches, the upload, the memory write and the card. The zone controller [protect]s it; the switch, Delete all and Forget
     * trip it. Everything in flight is cancelled by it.
     */
    val fence = com.rokid.context.memory.place.ZoneFence()

    /** The zone controller's word: [active] = some zone is approached, entered, recorded or unreadable. Closes the fence and withdraws the card at once; released when clear. */
    fun protect(active: Boolean) { if (active) { if (!fence.held) { fence.hold(); port.clear() } } else fence.release() }

    private val cardListener = object : com.rokid.context.memory.forget.ForgetListener {
        override suspend fun onFramesForgotten(frameIds: List<String>) { fence.trip(); port.withdrawFrames(frameIds); PlaceCardHistory.notifyChanged() }
        override suspend fun onRangeForgotten(fromMs: Long, toMs: Long, traceIds: List<String>) { fence.trip(); port.clear(); PlaceCardHistory.notifyChanged() }
        override suspend fun onPhotosExpired(frameIds: List<String>) { PlaceCardHistory.notifyChanged() }
        override suspend fun onDeleteAll() { fence.trip(); port.clear(); PlaceCardHistory.notifyChanged() }
    }

    // The phone-only card history lives in context.db (v18): every Forget deletes or unlinks its rows INSIDE its own transaction, so there is no history listener and nothing is ever owed.

    init { registerListeners() }

    /** Registers the card Forget listener (idempotent). A published card holds photo-derived content: Forget of its photo withdraws it, a range Forget or Delete all withdraws every card (committed deletions only; fenced against publication by the holder's epoch). */
    fun registerListeners() {
        com.rokid.context.memory.forget.ForgetListeners.register(cardListener)
    }

    @Volatile private var historyRef: PlaceCardHistory? = null

    /** Pins whose removal is in flight (savePins step 2..5): ids only, in memory, never logged. A history insert that names one is refused. */
    internal val removing: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** The card history over `context.db`, built lazily. Off Main (the first call may open the database). */
    fun history(context: Context): PlaceCardHistory {
        historyRef?.let { return it }
        val app = context.applicationContext
        return synchronized(this) {
            historyRef ?: PlaceCardHistory(
                com.rokid.context.memory.MemoryGraph.get(app).db, log = { PcLog.i(TAG, it) }, enabled = { com.rokid.context.orchestration.forget.DeveloperFlags.placeCardHistory(app) },
                pinActive = { id -> pins(app).pins().any { it.id == id } }, removing = removing,
                generation = { com.rokid.context.orchestration.MemoryWipe.generation.get() }, fenceEpoch = { fence.epoch() },
            ).also { historyRef = it }
        }
    }

    /**
     * CH 7: the owed-token key of the old places.db history is removed once at startup (the history now lives in context.db and nothing is owed). Before it goes, an EXISTING places.db is opened once so Room
     * migrates it to v3 (which drops `place_card_log`): legacy v2 history cannot then survive a Forget. No file: nothing is created. An open that fails keeps the key, so the next start retries.
     */
    fun dropOwedKey(context: Context, migrate: () -> Unit = { migratePlacesDb(context) }) {
        val prefs = context.applicationContext.getSharedPreferences(PlaceGuideSettings.PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains("history_deletions_owed")) return
        try { migrate() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { PcLog.w(TAG, "places.db migration not done: ${e.javaClass.simpleName}"); return }
        prefs.edit().remove("history_deletions_owed").commit()
    }

    /** Opens the existing places.db through the factory once (the migration runs on open). Does nothing when the file does not exist. */
    private fun migratePlacesDb(context: Context) {
        val app = context.applicationContext
        if (!app.getDatabasePath(PlacesDatabase.NAME).exists()) return
        val db = PlacesDatabase.build(app)
        try { db.openHelper.writableDatabase } finally { db.close() }
    }

    /** The card history; kept for the readers that must not build anything extra (Home, quota eviction). `context.db` always exists. */
    fun existingHistory(context: Context): PlaceCardHistory? = history(context)

    /** The user's pinned places (prefs `place_pins`, the source of truth: kept by Delete all, exported on request; EXCLUDED from backup and device transfer, see UserPlace.kt and BackupRulesTest). */
    fun pins(context: Context): com.rokid.context.memory.place.UserPinStore =
        com.rokid.context.memory.place.UserPinStore(context.applicationContext.getSharedPreferences(com.rokid.context.memory.place.UserPinStore.PREFS, Context.MODE_PRIVATE))

    @Volatile private var pinsSynced = false
    private val syncLock = kotlinx.coroutines.sync.Mutex()

    /** The pack with the user's pins in it: the first call of the process copies them from the pins list into `places.db` (which is not backed up, so a restore is repaired here). Off Main. */
    suspend fun readyPack(context: Context): PlacePack {
        val p = pack(context)
        if (!pinsSynced) syncLock.withLock {
            if (!pinsSynced) {
                val store = pins(context)
                if (!store.corrupt) p.replaceUserPlaces(store.pins()) // an unreadable list never empties the pack's copies
                pinsSynced = true
                // C1: the model attribution of reference captions lives in context.db, the captions in places.db: once per process, drop the attribution rows whose caption is gone (best effort)
                runCatching { com.rokid.context.memory.place.PlaceRefSweep.run(com.rokid.context.memory.MemoryGraph.get(context).db, p) }
            }
        }
        return p
    }

    /**
     * Stores [list] as the pins and mirrors it into the pack. False = refused or not stored (nothing changes, or only the history rows of a removed pin are gone: fail closed). The ORDER for a removed pin (CH 5):
     * (1) `gone` = the ids that disappear; (2) they enter [removing] and the fence trips and the card is withdrawn; (3) their history rows are deleted in one Room transaction, and a failure refuses the removal;
     * (4) the pin store is written, and a failure keeps the pin (its rows are already gone); (5) [removing] is cleared and the pack mirror follows.
     */
    suspend fun savePins(context: Context, list: List<com.rokid.context.memory.place.UserPlace>): Boolean {
        val p = readyPack(context)
        val before = runCatching { pins(context).pins().map { it.id } }.getOrDefault(emptyList())
        val gone = before - list.map { it.id }.toSet()
        if (gone.isEmpty()) { if (!pins(context).write(list)) return false; p.replaceUserPlaces(list); return true }
        removing.addAll(gone)
        try {
            fence.trip(); port.clear()
            try { history(context).deletePins(gone) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { PcLog.w(TAG, "place history pin rows not deleted: pin kept"); return false }
            if (!pins(context).write(list)) { PcLog.i(TAG, "place history pin removed=${gone.size} pin_kept"); return false }
        } finally { removing.removeAll(gone.toSet()) }
        p.replaceUserPlaces(list)
        return true
    }

    fun pack(context: Context): PlacePack = pack ?: synchronized(this) { pack ?: build(context.applicationContext).also { pack = it } }

    private fun build(app: Context): PlacePack {
        val guard = com.rokid.context.memory.place.PlaceNetGuard(log = { PcLog.i(TAG, it) }, env = { netEnv(app) }) // one limiter + counters for the HTTP client and the pack
        val http = UrlConnectionPlaceHttp(guard = guard, gate = com.rokid.context.ai.datasaver.DataSaverGraph.get(app), privacy = { com.rokid.context.ai.resolve.ModelPrivacyStore.get(app).current() }) // cell fetches count in the mobile-data ledger (class SMALL)
        val source = PlaceCellSource(WikipediaHttpSource(http), WikidataHttpSource(http), OverpassMirrorSource.default(http) { PcLog.i(TAG, it) }, extraTypes = { PlaceGuideSettings.of(app).extraTypes }, log = { PcLog.i(TAG, it) }) // counts and class names only
        val bulk = com.rokid.context.connectors.place.WikidataBulkSource(http, extraTypes = { PlaceGuideSettings.of(app).extraTypes }, log = { PcLog.i(TAG, it) }) // used only by a seed that asks for it (Developer flag bulkSeed, default ON)
        return PlacePack(PlacesDatabase.build(app), source, budgetBytes = PlaceGuideSettings.of(app).budgetMb * 1024L * 1024L, log = { PcLog.i(TAG, it) }, net = guard, bulk = bulk)
    }

    /** The seed summary's tail: network type token (no SSID), battery saver, data saver, the worker's state token. Numbers and fixed tokens only. */
    private fun netEnv(app: Context): String = try {
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val net = when { caps == null -> "none"; caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "wifi"; caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"; else -> "other" }
        val saver = if (app.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode == true) 1 else 0
        val dsaver = if (cm?.restrictBackgroundStatus == android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED) 1 else 0
        " net=$net saver=$saver dsaver=$dsaver state=${com.rokid.context.memory.place.PlaceNetGuard.workerState}"
    } catch (e: Exception) { " env=unreadable" }

    /**
     * Delete all: clears the fetched places and the recency; seeded areas, what they seeded, pinned places and the pause zones stay. Nothing to do (and nothing created) when the pack was
     * never opened and its file does not exist.
     */
    suspend fun deleteAll(context: Context) {
        fence.trip() // attempts in flight (a fetch, an upload, a write) end first
        port.clear() // the card goes first, and a card still being made is fenced out
        val app = context.applicationContext
        if (pack == null && !app.getDatabasePath(PlacesDatabase.NAME).exists()) return
        pack(app).deleteAll() // the card history is cleared by MemoryWipe's wipeMemory transaction (context.db), not here
    }

    fun installForTests(p: PlacePack?) { synchronized(this) { pack = p; pinsSynced = false; historyRef = null; removing.clear() } }
    fun installHistoryForTests(h: PlaceCardHistory?) { historyRef = h }
}

/** The Place Guide switch: default OFF. Read on every frame, so a plain SharedPreferences read. Also the pack budget (per device, like the switch: not exported, not backed up). */
class PlaceGuideSettings private constructor(private val prefs: android.content.SharedPreferences) {
    /** The pack budget in MB: one of [PlacePack.BUDGET_CHOICES_MB], 50 by default. */
    var budgetMb: Int
        get() = prefs.getInt(BUDGET_KEY, DEFAULT_BUDGET_MB).takeIf { it in PlacePack.BUDGET_CHOICES_MB } ?: DEFAULT_BUDGET_MB
        set(v) { if (v in PlacePack.BUDGET_CHOICES_MB) prefs.edit().putInt(BUDGET_KEY, v).apply() }

    /** B2: where the glasses HUD shows the place card ([com.rokid.context.core.model.PlaceSide.CENTRE] by default; the setting lives in Developer mode). A stored unknown value reads as CENTRE. */
    var cardSide: com.rokid.context.core.model.PlaceSide
        get() = com.rokid.context.core.model.PlaceSide.parse(prefs.getString(SIDE_KEY, null))
        set(v) { prefs.edit().putString(SIDE_KEY, v.name).apply() }

    /** V2b "Offline place guess (Maybe)": the user's switch, default OFF. It only matters while the `offlineTier` and `offlineCaption` Developer flags are on and the captioner model is downloaded. */
    var offlineGuess: Boolean
        get() = runCatching { prefs.getBoolean(OFFLINE_GUESS_KEY, false) }.getOrDefault(false)
        set(v) { prefs.edit().putBoolean(OFFLINE_GUESS_KEY, v).apply() }

    /** "Maybe cut-off": the lowest caption-to-place score that may show a "Maybe" card, [OfflineTier.MIN_CUTOFF]..[OfflineTier.MAX_CUTOFF], 0.55 by default. A stored value out of range reads as the default. */
    var maybeCutoff: Float
        get() = runCatching { prefs.getFloat(CUTOFF_KEY, OfflineTier.DEFAULT_CUTOFF) }.getOrDefault(OfflineTier.DEFAULT_CUTOFF).takeIf { it in OfflineTier.MIN_CUTOFF..OfflineTier.MAX_CUTOFF } ?: OfflineTier.DEFAULT_CUTOFF
        set(v) { if (v.isFinite()) prefs.edit().putFloat(CUTOFF_KEY, v.coerceIn(OfflineTier.MIN_CUTOFF, OfflineTier.MAX_CUTOFF)).apply() }

    /** "Candidate radius" in metres, [OfflineTier.MIN_RADIUS_M]..[OfflineTier.WIDE_RADIUS_M], 150 by default. A stored value out of range reads as the default. */
    var candidateRadiusM: Int
        get() = runCatching { prefs.getInt(RADIUS_KEY, OfflineTier.DEFAULT_RADIUS_M) }.getOrDefault(OfflineTier.DEFAULT_RADIUS_M).takeIf { it in OfflineTier.MIN_RADIUS_M..OfflineTier.WIDE_RADIUS_M } ?: OfflineTier.DEFAULT_RADIUS_M
        set(v) { prefs.edit().putInt(RADIUS_KEY, v.coerceIn(OfflineTier.MIN_RADIUS_M, OfflineTier.WIDE_RADIUS_M)).apply() }

    /** One "Extra place type" switch (Settings > Place Guide; [com.rokid.context.connectors.place.ExtraPlaceTypes]): ON by default (owner decision 27 Nov night), an unreadable pref reads as the default. Stored places are never purged by switching one off. */
    fun extraType(key: String): Boolean {
        val def = com.rokid.context.connectors.place.ExtraPlaceTypes.byKey(key)?.defaultOn ?: false
        return runCatching { prefs.getBoolean(EXTRA_PREFIX + key, def) }.getOrDefault(def)
    }
    fun setExtraType(key: String, on: Boolean) { if (key in com.rokid.context.connectors.place.ExtraPlaceTypes.KEYS) prefs.edit().putBoolean(EXTRA_PREFIX + key, on).apply() }

    /** The keys that are ON now: what NEW seeds (per-cell and bulk) and cell fetches admit on top of the strict list. */
    val extraTypes: Set<String> get() = com.rokid.context.connectors.place.ExtraPlaceTypes.KEYS.filter { extraType(it) }.toSet()

    var enabled: Boolean
        get() = prefs.getBoolean(KEY, false)
        set(v) { prefs.edit().putBoolean(KEY, v).apply(); if (!v) PlaceGraph.fence.trip() }

    companion object {
        const val PREFS = "place_guide"
        private const val KEY = "enabled"
        private const val EXTRA_PREFIX = "extraType."
        private const val BUDGET_KEY = "budgetMb"
        private const val SIDE_KEY = "cardSide"
        private const val OFFLINE_GUESS_KEY = "offlineGuess"
        private const val CUTOFF_KEY = "maybeCutoff"
        private const val RADIUS_KEY = "candidateRadiusM"
        const val DEFAULT_BUDGET_MB = 50
        fun of(context: Context) = PlaceGuideSettings(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
        fun over(prefs: android.content.SharedPreferences) = PlaceGuideSettings(prefs)
    }
}
