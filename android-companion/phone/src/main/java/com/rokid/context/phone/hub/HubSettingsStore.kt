package com.rokid.context.phone.hub

import android.content.SharedPreferences
import com.rokid.context.core.model.CaptureMode
import com.rokid.context.core.model.Decoded
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.Verbosity
import com.rokid.context.core.model.Wire

/** The phone's copy of [HudSettings] (`pc_settings`). Pauses are not stored here: the hub adds them at push time. */
class HubSettingsStore(private val prefs: SharedPreferences, private val legacyMode: () -> String) {
    /** Smart is the default for new installs and for settings stored before keyframe capture (no `captureMode` key). */
    fun read(): HudSettings = (prefs.getString(KEY, null)?.let { raw ->
        (Wire.decode<HudSettings>(raw) as? Decoded.Ok)?.value?.let { if ("\"captureMode\"" in raw) it else it.copy(captureMode = CaptureMode.SMART) }
    } ?: HudSettings(verbosity = verbosityFor(legacyMode()), captureMode = CaptureMode.SMART)).let(::validQuiet)

    fun write(s: HudSettings) { prefs.edit().putString(KEY, Wire.encode(validQuiet(s).copy(pauses = emptyList()))).apply() }

    companion object {
        /** The prefs file HubGraph opens for this store. */
        const val PREFS = "hub_settings"
        private const val KEY = "hud_settings"
        /** Phase 7: the legacy pipeline-off flag has been turned into a PHONE pause (once). */
        private const val PAUSE_MIGRATED = "pause_migrated_v7"
        /** The migrated PHONE pause whose Resume releases the backlog held while the legacy flag was off. */
        private const val HELD_PAUSE = "pause_migrated_v7_held"

        fun pauseMigrated(prefs: SharedPreferences): Boolean = prefs.getBoolean(PAUSE_MIGRATED, false)
        /** Committed synchronously (callers are on IO): the migration must not run twice after a crash. */
        fun markPauseMigrated(prefs: SharedPreferences, heldPauseId: String?) {
            prefs.edit().putBoolean(PAUSE_MIGRATED, true).apply { if (heldPauseId != null) putString(HELD_PAUSE, heldPauseId) }.commit()
        }
        fun heldPauseId(prefs: SharedPreferences): String? = prefs.getString(HELD_PAUSE, null)
        fun clearHeldPause(prefs: SharedPreferences) { prefs.edit().remove(HELD_PAUSE).commit() }

        /** Fix round 1 (M12): quiet hours with a minute outside 0..1439 are dropped (no window), never stored or pushed. */
        fun validQuiet(s: HudSettings): HudSettings =
            s.quietHours?.takeUnless { it.startMinute in 0..1439 && it.endMinute in 0..1439 }?.let { s.copy(quietHours = null) } ?: s

        /** Legacy Config screen values (`AppConfigStore.glassesSilencyMode`) → spec §3.4 verbosity. */
        fun verbosityFor(mode: String): Verbosity = when (mode.trim().lowercase()) {
            "stealth", "silent" -> Verbosity.SILENT
            "compact", "max" -> Verbosity.MAX
            else -> Verbosity.MEDIUM
        }
    }
}
