package com.rokid.context.phone.ui.settings.capture

import android.content.Context
import android.content.SharedPreferences
import com.rokid.context.capture.plan.FusionSettings
import com.rokid.context.core.log.PcLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * The phone fusion thresholds the user changed (v0.9.7 B): only NON-default keys, every write and every load passes [FusionSettings.clean]. Prefs file [PREFS] (a per-device setting
 * like the window tolerance: numbers only, no memory content; excluded from backup/transfer). The context hub follows [overrides] without a restart. Logging by event name only.
 */
class FusionSettingsStore(private val prefs: SharedPreferences? = null) {
    private val state = MutableStateFlow(load())
    val overrides: StateFlow<Map<String, Double>> = state.asStateFlow()

    private fun load(): Map<String, Double> = runCatching {
        val o = Json.parseToJsonElement(prefs?.getString(KEY, null) ?: "{}") as JsonObject
        FusionSettings.clean(o.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.doubleOrNull?.let { k to it } }.toMap())
    }.getOrDefault(emptyMap())

    private fun persist(m: Map<String, Double>) {
        val c = FusionSettings.clean(m)
        prefs?.edit()?.putString(KEY, JsonObject(c.mapValues { JsonPrimitive(it.value) }).toString())?.apply()
        state.value = c
        PcLog.i(TAG, "fusion settings saved")
    }

    @Synchronized fun set(key: String, value: Double) {
        if (key !in FusionSettings.RANGES) { PcLog.w(TAG, "fusion settings rejected"); return }
        persist(state.value + (key to value))
    }

    /** "Back to defaults". */
    @Synchronized fun reset() = persist(emptyMap())

    companion object {
        const val PREFS = "fusion_settings"
        private const val KEY = "values"
        private const val TAG = "FusionSettings"

        @Volatile private var instance: FusionSettingsStore? = null
        fun get(context: Context): FusionSettingsStore = instance ?: synchronized(this) {
            instance ?: FusionSettingsStore(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)).also { instance = it }
        }
    }
}
