package com.rokid.context.glasses.link

import android.content.SharedPreferences
import com.rokid.context.core.model.Decoded
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.Wire

/** Last `pc_settings`, kept across restarts: verbosity, gestures and cadence apply offline (spec §5.5). */
class HudSettingsCache(private val prefs: SharedPreferences) {
    /** The last value read or written, so per-capture checks (quiet hours) don't decode the prefs each time. */
    @Volatile private var last: HudSettings? = null

    fun read(): HudSettings = last
        ?: (prefs.getString(KEY, null)?.let { (Wire.decode<HudSettings>(it) as? Decoded.Ok)?.value } ?: HudSettings()).also { last = it }
    fun write(s: HudSettings) {
        last = s
        prefs.edit().putString(KEY, Wire.encode(s)).apply()
    }

    companion object {
        const val PREFS = "hud"
        private const val KEY = "hudSettings"
    }
}
