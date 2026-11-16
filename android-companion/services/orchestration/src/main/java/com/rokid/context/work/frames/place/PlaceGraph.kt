package com.rokid.context.work.frames.place

import android.content.Context
import com.rokid.context.connectors.place.OverpassHttpSource
import com.rokid.context.connectors.place.PlaceCellSource
import com.rokid.context.connectors.place.UrlConnectionPlaceHttp
import com.rokid.context.connectors.place.WikidataHttpSource
import com.rokid.context.connectors.place.WikipediaHttpSource
import com.rokid.context.core.log.PcLog
import com.rokid.context.memory.place.PlacePack
import com.rokid.context.memory.place.PlacesDatabase

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

    fun pack(context: Context): PlacePack = pack ?: synchronized(this) { pack ?: build(context.applicationContext).also { pack = it } }

    private fun build(app: Context): PlacePack {
        val http = UrlConnectionPlaceHttp()
        val source = PlaceCellSource(WikipediaHttpSource(http), WikidataHttpSource(http), OverpassHttpSource(http), log = { PcLog.i(TAG, it) }) // counts and class names only
        return PlacePack(PlacesDatabase.build(app), source, log = { PcLog.i(TAG, it) })
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

    fun installForTests(p: PlacePack?) { synchronized(this) { pack = p } }
}

/** The Place Guide switch: default OFF. Read on every frame, so a plain SharedPreferences read. */
class PlaceGuideSettings private constructor(private val prefs: android.content.SharedPreferences) {
    var enabled: Boolean
        get() = prefs.getBoolean(KEY, false)
        set(v) { prefs.edit().putBoolean(KEY, v).apply(); if (!v) PlaceGraph.fence.trip() }

    companion object {
        const val PREFS = "place_guide"
        private const val KEY = "enabled"
        fun of(context: Context) = PlaceGuideSettings(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
        fun over(prefs: android.content.SharedPreferences) = PlaceGuideSettings(prefs)
    }
}
