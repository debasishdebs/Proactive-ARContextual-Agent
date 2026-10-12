package com.rokid.context.glasses

import android.content.Context

enum class SilencyMode(val persistedValue: String) {
    HUD("hud"),
    COMPACT("compact"),
    STEALTH("stealth"),
    ;

    fun isSilent(): Boolean = this == STEALTH

    fun isMedium(): Boolean = this == HUD

    fun isMax(): Boolean = this == COMPACT

    fun next(): SilencyMode {
        return when (this) {
            STEALTH -> HUD
            HUD -> COMPACT
            COMPACT -> STEALTH
        }
    }

    companion object {
        fun fromPersisted(raw: String?): SilencyMode {
            return when (raw?.trim()?.lowercase()) {
                "stealth", "silent" -> STEALTH
                "compact", "max", "verbose_max" -> COMPACT
                "hud", "medium", "verbose_medium" -> HUD
                else -> HUD
            }
        }
    }
}

object GlassesSettingsStore {
    private const val PREF_NAME = "rokid_glasses_settings"
    private const val KEY_SILENCY_MODE = "silency_mode"

    fun silencyMode(context: Context): SilencyMode {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return SilencyMode.fromPersisted(prefs.getString(KEY_SILENCY_MODE, SilencyMode.HUD.persistedValue))
    }

    fun setSilencyMode(context: Context, mode: SilencyMode) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SILENCY_MODE, mode.persistedValue)
            .apply()
    }
}
