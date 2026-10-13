package com.rokid.context.memory

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Settings → Memory (spec §2.4): retention per tier (24 h / 7 d / 30 d defaults) and the soft storage quota (100 MB). */
data class MemorySettings(val shortHours: Int = 24, val mediumDays: Int = 7, val longDays: Int = 30, val quotaMb: Int = 100) {
    val quotaBytes: Long get() = quotaMb.toLong() * 1024 * 1024
}

class MemorySettingsStore(private val prefs: SharedPreferences) {
    private val state = MutableStateFlow(read())
    val settings: StateFlow<MemorySettings> = state.asStateFlow()

    fun update(transform: (MemorySettings) -> MemorySettings) {
        val n = clean(transform(state.value))
        prefs.edit().putInt("short_hours", n.shortHours).putInt("medium_days", n.mediumDays).putInt("long_days", n.longDays).putInt("quota_mb", n.quotaMb).apply()
        state.value = n
    }

    private fun read() = clean(MemorySettings(prefs.getInt("short_hours", 24), prefs.getInt("medium_days", 7), prefs.getInt("long_days", 30), prefs.getInt("quota_mb", 100)))

    private fun clean(s: MemorySettings) = MemorySettings(
        shortHours = s.shortHours.takeIf { it in SHORT_HOURS } ?: 24,
        mediumDays = s.mediumDays.takeIf { it in MEDIUM_DAYS } ?: 7,
        longDays = s.longDays.takeIf { it in LONG_DAYS } ?: 30,
        quotaMb = s.quotaMb.coerceIn(QUOTA_MB.first, QUOTA_MB.last),
    )

    companion object {
        val SHORT_HOURS = listOf(12, 24, 48)
        val MEDIUM_DAYS = listOf(3, 7, 14)
        val LONG_DAYS = listOf(30, 90, 365)
        val QUOTA_MB = 10..2_000
        @Volatile private var instance: MemorySettingsStore? = null

        fun get(context: Context): MemorySettingsStore = instance ?: synchronized(this) {
            instance ?: MemorySettingsStore(context.applicationContext.getSharedPreferences("memory_settings", Context.MODE_PRIVATE)).also { instance = it }
        }
    }
}
