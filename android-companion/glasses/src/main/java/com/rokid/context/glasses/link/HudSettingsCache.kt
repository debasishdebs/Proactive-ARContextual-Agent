package com.rokid.context.glasses.link

import android.content.SharedPreferences
import com.rokid.context.core.model.Decoded
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.Wire

/** Last `pc_settings`, kept across restarts: verbosity, gestures and cadence apply offline (spec §5.5). */
class HudSettingsCache(private val prefs: SharedPreferences) {
    fun read(): HudSettings = prefs.getString(KEY, null)?.let { (Wire.decode<HudSettings>(it) as? Decoded.Ok)?.value } ?: HudSettings()
    fun write(s: HudSettings) { prefs.edit().putString(KEY, Wire.encode(s)).apply() }

    companion object {
        const val PREFS = "hud"
        private const val KEY = "hudSettings"
    }
}
