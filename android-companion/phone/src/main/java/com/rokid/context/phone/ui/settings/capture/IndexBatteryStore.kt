package com.rokid.context.phone.ui.settings.capture

import android.content.Context
import android.content.SharedPreferences
import com.rokid.context.ai.embed.LocalModelPower
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "On-device indexing on battery": on-device index work (embedding backfill, reindex) runs while charging or above this battery level
 * (30 / 50 / 70 %, default 50 %). Persisted in the phone prefs key [KEY]; [LocalModelPower.minBatteryPct] reads [pct]. With no prefs it is in memory only.
 */
class IndexBatteryStore(private val prefs: SharedPreferences? = null) {
    private val state = MutableStateFlow(clean(prefs?.getInt(KEY, LocalModelPower.DEFAULT_MIN_BATTERY_PCT) ?: LocalModelPower.DEFAULT_MIN_BATTERY_PCT))
    val pct: StateFlow<Int> = state.asStateFlow()

    fun set(pct: Int) {
        val v = clean(pct)
        prefs?.edit()?.putInt(KEY, v)?.apply()
        state.value = v
    }

    companion object {
        const val KEY = "index_battery_pct"
        const val PREFS = "index_battery"

        /** Snaps a stored or wild value to the nearest allowed level (ties to the lower). */
        fun clean(pct: Int): Int = LocalModelPower.OPTIONS_PCT.minByOrNull { kotlin.math.abs(it - pct) } ?: LocalModelPower.DEFAULT_MIN_BATTERY_PCT

        @Volatile private var instance: IndexBatteryStore? = null
        fun get(context: Context): IndexBatteryStore = instance ?: synchronized(this) {
            instance ?: IndexBatteryStore(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)).also { instance = it }
        }
    }
}
