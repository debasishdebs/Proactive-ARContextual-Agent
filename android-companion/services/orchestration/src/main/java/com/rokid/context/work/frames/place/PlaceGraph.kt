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
        set(v) { prefs.edit().putBoolean(KEY, v).apply() }

    companion object {
        const val PREFS = "place_guide"
        private const val KEY = "enabled"
        fun of(context: Context) = PlaceGuideSettings(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
        fun over(prefs: android.content.SharedPreferences) = PlaceGuideSettings(prefs)
    }
}
