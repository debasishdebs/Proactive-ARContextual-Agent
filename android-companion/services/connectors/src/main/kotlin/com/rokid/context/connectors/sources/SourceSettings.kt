package com.rokid.context.connectors.sources

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Settings › Data sources (R-64): which sources feed `{{context_sources}}` (all off by default) and the nudge daily
 * cap ("Nudges per day" 0/3/6/10, default 6; 0 = off). Build it off Main: the constructor reads prefs.
 */
class SourceSettingsStore(private val prefs: SharedPreferences) {
    private val on = MutableStateFlow(SourceKind.entries.filter { prefs.getBoolean(key(it), false) }.toSet())
    val enabled: StateFlow<Set<SourceKind>> = on.asStateFlow()

    private val cap = MutableStateFlow(prefs.getInt(KEY_CAP, DEFAULT_CAP).takeIf { it in CAPS } ?: DEFAULT_CAP)
    val nudgesPerDay: StateFlow<Int> = cap.asStateFlow()

    fun set(k: SourceKind, on: Boolean) {
        prefs.edit().putBoolean(key(k), on).apply()
        this.on.update { if (on) it + k else it - k }
    }

    /** Values other than 0/3/6/10 are ignored. */
    fun setNudgesPerDay(n: Int) {
        if (n !in CAPS) return
        prefs.edit().putInt(KEY_CAP, n).apply()
        cap.value = n
    }

    companion object {
        const val PREFS = "context_sources"
        val CAPS = listOf(0, 3, 6, 10)
        const val DEFAULT_CAP = 6
        private const val KEY_CAP = "nudges_per_day"
        private fun key(k: SourceKind) = "source_" + k.name.lowercase()
    }
}
