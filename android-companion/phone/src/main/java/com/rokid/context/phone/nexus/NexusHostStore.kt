package com.rokid.context.phone.nexus

import android.content.Context
import android.content.SharedPreferences
import com.rokid.context.core.model.HudHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * v2.0 T7: who draws the HUD popups and cards. PCA's own glasses app is the default and Rokid Nexus is an explicit opt-in (Settings > Rokid Nexus, Developer-visible). Nothing else is stored here
 * (no card text, no ids): the prefs file `nexus_host` is excluded from backup and transfer (BackupRulesTest) and kept by Delete all (DeletionGraph). Anything that is not exactly "NEXUS" reads as PCA.
 */
class NexusHostStore(private val prefs: SharedPreferences) {
    private val _host = MutableStateFlow(read())
    val host: StateFlow<HudHost> = _host

    private fun read(): HudHost = if (prefs.getString(KEY, null) == HudHost.NEXUS.name) HudHost.NEXUS else HudHost.PCA

    fun current(): HudHost = _host.value

    /** True when the choice changed. Committed synchronously: a host switch is a privacy-relevant switch and must survive a crash right after it. */
    fun set(h: HudHost): Boolean {
        if (_host.value == h) return false
        prefs.edit().putString(KEY, h.name).commit()
        _host.value = h
        return true
    }

    companion object {
        /** The prefs file (not backed up). */
        const val PREFS = "nexus_host"
        private const val KEY = "host"

        @Volatile private var shared: NexusHostStore? = null
        fun of(context: Context): NexusHostStore = shared ?: synchronized(this) {
            shared ?: NexusHostStore(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)).also { shared = it }
        }
        internal fun resetForTests() { shared = null }
    }
}
