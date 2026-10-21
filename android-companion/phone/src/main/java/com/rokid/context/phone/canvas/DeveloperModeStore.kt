package com.rokid.context.phone.canvas

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The Developer mode switch (Settings > Diagnostics). Off by default; the experiment canvas never runs while it is off. */
class DeveloperModeStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun set(on: Boolean) {
        prefs.edit().putBoolean(KEY, on).apply()
        _enabled.value = on
    }

    companion object {
        const val PREFS = "pc_dev"
        const val KEY = "developer_mode"
        @Volatile private var instance: DeveloperModeStore? = null

        /** One store per process, so every screen and the graph see the same flow. */
        fun get(context: Context): DeveloperModeStore = instance ?: synchronized(this) { instance ?: DeveloperModeStore(context).also { instance = it } }
    }
}
