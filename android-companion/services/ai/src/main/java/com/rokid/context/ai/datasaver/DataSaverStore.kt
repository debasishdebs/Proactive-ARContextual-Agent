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

    /** When photo uploads were first seen blocked since the mark was last cleared (ms; 0 = not blocked). Persisted so a restart while roaming still knows the backlog was parked. */
    fun blockedSince(): Long = prefs.getLong(K_BLOCKED, 0L)
    private val markLock = Any()

    /**
     * Writes a NEW value (later than the old one) when there is no mark or the old one is at least [MARK_REFRESH_MS] old, so a clear that read the old value cannot erase a block seen after it
     * (a frame that arrives inside the throttle window is born parked anyway). Throttled so a screen refreshing every 2 s does not write the file every time.
     */
    fun markBlocked(nowMs: Long) = synchronized(markLock) {
        val old = prefs.getLong(K_BLOCKED, 0L)
        if (old == 0L || nowMs - old >= MARK_REFRESH_MS) prefs.edit().putLong(K_BLOCKED, maxOf(nowMs, old + 1)).apply()
    }

    /** Compare-and-clear: removes the mark only if it still equals [expected] (the value read before the backlog was parked). Returns whether it cleared. */
    fun clearBlocked(expected: Long): Boolean = synchronized(markLock) {
        if (expected == 0L || prefs.getLong(K_BLOCKED, 0L) != expected) false else { prefs.edit().remove(K_BLOCKED).apply(); true }
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
        private const val K_BLOCKED = "blocked_since"
        const val MARK_REFRESH_MS = 10_000L
        fun of(context: Context) = DataSaverStore(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    }
}
