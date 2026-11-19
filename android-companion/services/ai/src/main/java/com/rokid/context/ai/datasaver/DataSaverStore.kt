package com.rokid.context.ai.datasaver

import android.content.Context
import android.content.SharedPreferences
import com.rokid.context.core.net.CounterStore
import com.rokid.context.core.net.DataMode
import com.rokid.context.core.net.DataSaverSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Data saver settings and today's skipped counts, in one app-private prefs file ([PREFS]). Per-device state: excluded from backup and device transfer (an "I'm abroad" or a
 * mode restored onto another phone would be wrong there). Delete all leaves it, like the neighbouring Place Guide and Ask-photos switches: it is a setting that protects the
 * user's mobile data, and a wipe must never silently re-open it (DeletionGraph keeps it).
 * Defaults (nothing stored, or an unreadable value): Wi-Fi and home mobile data (roaming, unknown networks and abroad stay blocked), not abroad, text allowed on roaming.
 */
class DataSaverStore(private val prefs: SharedPreferences) {
    private val state = MutableStateFlow(read())
    val settings: StateFlow<DataSaverSettings> = state.asStateFlow()

    private fun read() = DataSaverSettings(
        mode = DataMode.parse(prefs.getString(K_MODE, null)),
        abroad = prefs.getBoolean(K_ABROAD, false),
        smallOnRoaming = prefs.getBoolean(K_SMALL, true),
    )

    fun setMode(m: DataMode) = write { it.putString(K_MODE, m.name) }
    fun setAbroad(on: Boolean) = write { it.putBoolean(K_ABROAD, on) }
    fun setSmallOnRoaming(on: Boolean) = write { it.putBoolean(K_SMALL, on) }

    private fun write(edit: (SharedPreferences.Editor) -> Unit) {
        prefs.edit().also(edit).apply()
        state.value = read()
    }

    /** The counters live in the same file under their own keys. */
    val counters: CounterStore = object : CounterStore {
        override fun getLong(key: String, default: Long) = prefs.getLong(key, default)
        override fun putLongs(values: Map<String, Long>) { prefs.edit().also { e -> values.forEach { (k, v) -> e.putLong(k, v) } }.apply() }
    }

    companion object {
        const val PREFS = "data_saver"
        private const val K_MODE = "mode"
        private const val K_ABROAD = "abroad"
        private const val K_SMALL = "small_on_roaming"
        fun of(context: Context) = DataSaverStore(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    }
}
