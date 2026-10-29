package com.rokid.context.phone.context

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Opt-in switches and personal context state in the `ctx_hub_state` prefs file (never `context_sources`, which the
 * connectors own). The whole file is excluded from backup, device transfer and the settings export: after a restore every
 * source is off. [changes] is driven by a preference listener, so a write through any reference (or a direct edit) is seen.
 */
class ContextSourceSettings(private val prefs: SharedPreferences) {
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes

    // Held strongly: SharedPreferences keeps listeners weakly.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> _changes.update { it + 1 } }
    init { migrateLegacyWatch(); prefs.registerOnSharedPreferenceChangeListener(listener) }

    /** The active watch source: [WATCH_APP] or [NONE] (default, off until the user opts in). Stored as the `watch_app` source switch. */
    var activeWatchSource: String
        get() = if (enabled(WATCH_APP)) WATCH_APP else NONE
        set(v) { setEnabled(WATCH_APP, v == WATCH_APP) }

    /** One-time: the old `watch_livefit` switch and its certificate pin are deleted, so the watch source starts as [NONE] and the user opts in again. */
    private fun migrateLegacyWatch() {
        val old = SRC + LEGACY_WATCH
        if (!prefs.contains(old) && !prefs.contains(LEGACY_CERT)) return
        prefs.edit().remove(old).remove(LEGACY_CERT).putBoolean(SRC + WATCH_APP, false).apply()
    }

    fun enabled(id: String): Boolean = prefs.getBoolean(SRC + id, false)
    fun setEnabled(id: String, on: Boolean) { prefs.edit().putBoolean(SRC + id, on).apply() }

    /** false = shadow mode: the glasses log the state but do not apply it. */
    var applyToCapture: Boolean
        get() = prefs.getBoolean(APPLY, true)
        set(v) { prefs.edit().putBoolean(APPLY, v).apply() }

    /** Bluetooth addresses of the wearer's own cars (picker UI shows names; addresses stay here). */
    val myCars: Set<String> get() = prefs.getStringSet(MY_CARS, null)?.toSet() ?: emptySet()
    fun addMyCar(address: String) { prefs.edit().putStringSet(MY_CARS, myCars + address).apply() }
    fun removeMyCar(address: String) { prefs.edit().putStringSet(MY_CARS, myCars - address).apply() }

    /** The last accepted watch (epoch, seq), persisted so a phone restart does not accept a replay. */
    val watchEpoch: Long get() = prefs.getLong(WATCH_EPOCH, 0L)
    val watchLastSeq: Long get() = prefs.getLong(WATCH_SEQ, 0L)
    fun setWatchSeq(epoch: Long, seq: Long) { prefs.edit().putLong(WATCH_EPOCH, epoch).putLong(WATCH_SEQ, seq).apply() }

    /** Delete all: personal data goes, the watch source turns off (its pin is gone) and image intent turns off (the hub then drops its in-memory votes); the apply switch stays. */
    fun clearPersonal() {
        prefs.edit().putBoolean(SRC + WATCH_APP, false).putBoolean(SRC + "image_intent", false).remove(MY_CARS).remove(LEGACY_CERT).remove(WATCH_EPOCH).remove(WATCH_SEQ).apply()
    }

    /** Delete all: run the wipe, then clear personal state. */
    suspend fun wipeThenClear(wipe: suspend () -> Unit) { try { wipe() } finally { clearPersonal() } }

    companion object {
        const val PREFS = "ctx_hub_state"
        const val WATCH_APP = "watch_app"
        const val NONE = "none"
        private const val LEGACY_WATCH = "watch_livefit"
        private const val SRC = "src_"
        private const val APPLY = "apply_to_capture"
        private const val MY_CARS = "my_cars"
        private const val LEGACY_CERT = "livefit_cert_sha256" // deleted by the one-time migration; kept only to remove it
        private const val WATCH_EPOCH = "watch_epoch"
        private const val WATCH_SEQ = "watch_last_seq"

        @Volatile private var instance: ContextSourceSettings? = null

        /** One process-wide instance. */
        fun of(context: Context): ContextSourceSettings = instance ?: synchronized(this) {
            instance ?: ContextSourceSettings(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)).also { instance = it }
        }

        internal fun resetForTests() { synchronized(this) { instance = null } }
    }
}
