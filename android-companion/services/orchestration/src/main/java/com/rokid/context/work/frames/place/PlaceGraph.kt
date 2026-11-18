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

/** The process-wide place pack (`places.db`) and the card port. Built lazily, off Main: the first call reads the Keystore. */
object PlaceGraph {
    private const val TAG = "PcPlace"
    @Volatile private var pack: PlacePack? = null
    val port: PlaceCardHolder = PlaceCardHolder()

    /**
     * THE zone fence of Place Guide, shared by the sampler, the pack's fetches, the upload, the memory write and the card. The zone controller [protect]s it; the switch, Delete all and Forget
     * trip it. Everything in flight is cancelled by it.
     */
    val fence = com.rokid.context.memory.place.ZoneFence()

    /** The zone controller's word: [active] = some zone is approached, entered, recorded or unreadable. Closes the fence and withdraws the card at once; released when clear. */
    fun protect(active: Boolean) { if (active) { if (!fence.held) { fence.hold(); port.clear() } } else fence.release() }

    init {
        // A published card holds photo-derived content: Forget of its photo withdraws it, a range Forget or Delete all withdraws every card (committed deletions only; fenced against publication by the holder's epoch).
        com.rokid.context.memory.forget.ForgetListeners.register(object : com.rokid.context.memory.forget.ForgetListener {
            override suspend fun onFramesForgotten(frameIds: List<String>) { fence.trip(); port.withdrawFrames(frameIds) }
            override suspend fun onRangeForgotten(fromMs: Long, toMs: Long, traceIds: List<String>) { fence.trip(); port.clear() }
            override suspend fun onDeleteAll() { fence.trip(); port.clear() }
        })
    }

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
            }
        }
        return p
    }

    /** Stores [list] as the pins and mirrors it into the pack. False = refused or not stored (nothing changes). */
    suspend fun savePins(context: Context, list: List<com.rokid.context.memory.place.UserPlace>): Boolean {
        val p = readyPack(context)
        if (!pins(context).write(list)) return false
        p.replaceUserPlaces(list)
        return true
    }

    fun pack(context: Context): PlacePack = pack ?: synchronized(this) { pack ?: build(context.applicationContext).also { pack = it } }

    private fun build(app: Context): PlacePack {
        val http = UrlConnectionPlaceHttp()
        val source = PlaceCellSource(WikipediaHttpSource(http), WikidataHttpSource(http), OverpassMirrorSource.default(http) { PcLog.i(TAG, it) }, log = { PcLog.i(TAG, it) }) // counts and class names only
        return PlacePack(PlacesDatabase.build(app), source, budgetBytes = PlaceGuideSettings.of(app).budgetMb * 1024L * 1024L, log = { PcLog.i(TAG, it) })
    }

    /**
     * Delete all: clears the fetched places and the recency; seeded areas, what they seeded, pinned places and the pause zones stay. Nothing to do (and nothing created) when the pack was
     * never opened and its file does not exist.
     */
    suspend fun deleteAll(context: Context) {
        fence.trip() // attempts in flight (a fetch, an upload, a write) end first
        port.clear() // the card goes first, and a card still being made is fenced out
        val app = context.applicationContext
        if (pack == null && !app.getDatabasePath(PlacesDatabase.NAME).exists()) return
        pack(app).deleteAll()
    }

    fun installForTests(p: PlacePack?) { synchronized(this) { pack = p; pinsSynced = false } }
}

/** The Place Guide switch: default OFF. Read on every frame, so a plain SharedPreferences read. Also the pack budget (per device, like the switch: not exported, not backed up). */
class PlaceGuideSettings private constructor(private val prefs: android.content.SharedPreferences) {
    /** The pack budget in MB: one of [PlacePack.BUDGET_CHOICES_MB], 50 by default. */
    var budgetMb: Int
        get() = prefs.getInt(BUDGET_KEY, DEFAULT_BUDGET_MB).takeIf { it in PlacePack.BUDGET_CHOICES_MB } ?: DEFAULT_BUDGET_MB
        set(v) { if (v in PlacePack.BUDGET_CHOICES_MB) prefs.edit().putInt(BUDGET_KEY, v).apply() }

    var enabled: Boolean
        get() = prefs.getBoolean(KEY, false)
        set(v) { prefs.edit().putBoolean(KEY, v).apply(); if (!v) PlaceGraph.fence.trip() }

    companion object {
        const val PREFS = "place_guide"
        private const val KEY = "enabled"
        private const val BUDGET_KEY = "budgetMb"
        const val DEFAULT_BUDGET_MB = 50
        fun of(context: Context) = PlaceGuideSettings(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
        fun over(prefs: android.content.SharedPreferences) = PlaceGuideSettings(prefs)
    }
}
