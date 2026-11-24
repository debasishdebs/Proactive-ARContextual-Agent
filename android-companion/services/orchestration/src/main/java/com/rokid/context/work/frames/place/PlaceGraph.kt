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
        override suspend fun onFramesForgotten(frameIds: List<String>) { fence.trip(); port.withdrawFrames(frameIds) }
        override suspend fun onRangeForgotten(fromMs: Long, toMs: Long, traceIds: List<String>) { fence.trip(); port.clear() }
        override suspend fun onDeleteAll() { fence.trip(); port.clear() }
    }

    // The phone-only card history (B1): a user Forget of a photo deletes its rows, photo retention only drops the photo link and keeps the row (expiry-independent), a range Forget deletes the rows of the range
    // (not for the time link of a retention expiry), Delete all clears the table. Memory expiry (SHORT / LONG) touches nothing.
    private val historyListener = object : com.rokid.context.memory.forget.ForgetListener {
        override suspend fun onFramesForgotten(frameIds: List<String>) { historyIfOpen()?.onFramesForgotten(frameIds) }
        override suspend fun onPhotosExpired(frameIds: List<String>) { historyIfOpen()?.onPhotosExpired(frameIds) }
        override suspend fun onRangeForgotten(fromMs: Long, toMs: Long, traceIds: List<String>) { historyIfOpen()?.onRangeForgotten(fromMs, toMs) }
        override suspend fun onRangeExpired(fromMs: Long, toMs: Long, traceIds: List<String>) { /* routine retention never deletes history rows */ }
        override suspend fun onDeleteAll() { historyIfOpen()?.clearAll("delete_all") }
    }

    init { registerListeners() }

    /** Registers the two Forget listeners (idempotent). A published card holds photo-derived content: Forget of its photo withdraws it, a range Forget or Delete all withdraws every card (committed deletions only; fenced against publication by the holder's epoch). */
    fun registerListeners() {
        com.rokid.context.memory.forget.ForgetListeners.register(cardListener)
        com.rokid.context.memory.forget.ForgetListeners.register(historyListener)
    }

    @Volatile private var appContext: Context? = null
    @Volatile private var historyRef: PlaceCardHistory? = null

    /** Gives the process-wide history listener the application context (called once by the Application). Without it (or without a `places.db` file) the listener has nothing to delete. */
    fun attach(context: Context) { appContext = context.applicationContext }

    /** The card history, built lazily over the pack's database. Off Main (the first call reads the Keystore). */
    fun history(context: Context): PlaceCardHistory {
        historyRef?.let { return it }
        val app = context.applicationContext
        return synchronized(this) {
            historyRef ?: PlaceCardHistory(
                pack(app).cardLogDao, log = { PcLog.i(TAG, it) }, enabled = { com.rokid.context.orchestration.forget.DeveloperFlags.placeCardHistory(app) },
                owed = PrefsHistoryOwed(owedPrefs(app)),
                pinActive = { id -> pins(app).pins().any { it.id == id } }, // P1-4: asked under the history's write lock, after the pin store was written
            ).also { historyRef = it }
        }
    }

    private fun owedPrefs(app: Context) = app.getSharedPreferences(PlaceGuideSettings.PREFS, Context.MODE_PRIVATE)

    /** True while a history deletion (Forget, Delete all, pin removal) is still owed. Reads one prefs key; never opens `places.db`. */
    fun historyOwed(context: Context): Boolean = PrefsHistoryOwed(owedPrefs(context.applicationContext)).all().isNotEmpty()

    /**
     * Finishes the history deletions an earlier Forget / Delete all could not complete (called at every start and right after a Forget): true = nothing is owed. When `places.db` does not exist there is
     * nothing to delete and the obligation is dropped. Off Main.
     */
    suspend fun settleHistory(context: Context): Boolean {
        val app = context.applicationContext
        if (!historyOwed(app)) return true
        val h = existingHistory(app)
        if (h == null) { owedPrefs(app).edit().remove(PrefsHistoryOwed.KEY).commit(); return true }
        return h.retryOwed()
    }

    /**
     * A user pin was removed (P1-4): attempts in flight that may hold it are fenced out (their publication, memory and history are refused), the card on the glasses is withdrawn, and the history rows that
     * name it are deleted under the history's write lock. The pin store is already written when this runs, so a row inserted after it is refused by the history's active-pin check.
     */
    internal suspend fun pinsRemoved(history: PlaceCardHistory, gone: Collection<String>) {
        if (gone.isEmpty()) return
        fence.trip()
        port.clear()
        history.onPinRemoved(gone)
    }

    /** The history for a Forget: null when nothing was ever stored (no `places.db` file and no pack in this process), so a Forget never creates the database. */
    private fun historyIfOpen(): PlaceCardHistory? = appContext?.let { existingHistory(it) }

    /** The history only when `places.db` exists (or is open in this process): a reader such as Home never creates the database. */
    fun existingHistory(context: Context): PlaceCardHistory? {
        historyRef?.let { return it }
        val app = context.applicationContext
        if (pack == null && !app.getDatabasePath(PlacesDatabase.NAME).exists()) return null
        return history(app)
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
        val before = runCatching { pins(context).pins().map { it.id } }.getOrDefault(emptyList())
        if (!pins(context).write(list)) return false
        p.replaceUserPlaces(list)
        val gone = before - list.map { it.id }.toSet() // a pin that disappeared takes its history rows (and the cluster rows that list it) with it
        if (gone.isNotEmpty()) try { pinsRemoved(history(context), gone) } catch (e: HistoryDeletionPending) { /* owed: retried at the next start; the pin removal itself stands */ }
        return true
    }

    fun pack(context: Context): PlacePack = pack ?: synchronized(this) { pack ?: build(context.applicationContext).also { pack = it } }

    private fun build(app: Context): PlacePack {
        val http = UrlConnectionPlaceHttp(gate = com.rokid.context.ai.datasaver.DataSaverGraph.get(app)) // cell fetches count in the mobile-data ledger (class SMALL)
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
        // The history is cleared (or its deletion recorded as owed) whatever happens to the pack; either failure is thrown, so Delete all does not report done while private rows may remain.
        var packFailure: Exception? = null
        try { pack(app).deleteAll() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { packFailure = e }
        history(app).clearAll("delete_all")
        packFailure?.let { throw it }
    }

    fun installForTests(p: PlacePack?) { synchronized(this) { pack = p; pinsSynced = false; historyRef = null; appContext = null } }
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

    var enabled: Boolean
        get() = prefs.getBoolean(KEY, false)
        set(v) { prefs.edit().putBoolean(KEY, v).apply(); if (!v) PlaceGraph.fence.trip() }

    companion object {
        const val PREFS = "place_guide"
        private const val KEY = "enabled"
        private const val BUDGET_KEY = "budgetMb"
        private const val SIDE_KEY = "cardSide"
        const val DEFAULT_BUDGET_MB = 50
        fun of(context: Context) = PlaceGuideSettings(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
        fun over(prefs: android.content.SharedPreferences) = PlaceGuideSettings(prefs)
    }
}
