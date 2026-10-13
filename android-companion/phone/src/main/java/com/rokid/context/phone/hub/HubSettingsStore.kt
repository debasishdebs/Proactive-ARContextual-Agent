package com.rokid.context.phone.hub

import android.content.SharedPreferences
import com.rokid.context.core.model.Decoded
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.Verbosity
import com.rokid.context.core.model.Wire

/** The phone's copy of [HudSettings] (`pc_settings`). Pauses are not stored here: the hub adds them at push time. */
class HubSettingsStore(private val prefs: SharedPreferences, private val legacyMode: () -> String) {
    fun read(): HudSettings = prefs.getString(KEY, null)?.let { (Wire.decode<HudSettings>(it) as? Decoded.Ok)?.value }
        ?: HudSettings(verbosity = verbosityFor(legacyMode()))

    fun write(s: HudSettings) { prefs.edit().putString(KEY, Wire.encode(s.copy(pauses = emptyList()))).apply() }

    companion object {
        /** The prefs file HubGraph opens for this store. */
        const val PREFS = "hub_settings"
        private const val KEY = "hud_settings"
        /** Phase 7: the legacy pipeline-off flag has been turned into a PHONE pause (once). */
        private const val PAUSE_MIGRATED = "pause_migrated_v7"

        fun pauseMigrated(prefs: SharedPreferences): Boolean = prefs.getBoolean(PAUSE_MIGRATED, false)
        /** Committed synchronously (callers are on IO): the migration must not run twice after a crash. */
        fun markPauseMigrated(prefs: SharedPreferences) { prefs.edit().putBoolean(PAUSE_MIGRATED, true).commit() }

        /** Legacy Config screen values (`AppConfigStore.glassesSilencyMode`) → spec §3.4 verbosity. */
        fun verbosityFor(mode: String): Verbosity = when (mode.trim().lowercase()) {
            "stealth", "silent" -> Verbosity.SILENT
            "compact", "max" -> Verbosity.MAX
            else -> Verbosity.MEDIUM
        }
    }
}
