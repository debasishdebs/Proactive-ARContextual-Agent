package com.rokid.context.orchestration.proactive

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Suggestions switches (ruling: switches live in prefs, everything OFF by default). Per-device consent: the file is excluded from backup and transfer; Delete all resets it (master OFF).
 * [master] gates the whole loop; [placeRecall] is the F2 family switch; [replay] is the separate default-OFF flag of the Developer-only replay route.
 */
class ProactiveSettings(private val prefs: SharedPreferences) {
    private val _master = MutableStateFlow(read(MASTER, false))
    private val _f2 = MutableStateFlow(read(F2, true))
    private val _replay = MutableStateFlow(read(REPLAY, false))
    val master: StateFlow<Boolean> = _master.asStateFlow()
    val placeRecall: StateFlow<Boolean> = _f2.asStateFlow()
    val replay: StateFlow<Boolean> = _replay.asStateFlow()

    private fun read(k: String, d: Boolean) = try { prefs.getBoolean(k, d) } catch (e: Exception) { false }
    private fun write(k: String, v: Boolean) { try { prefs.edit().putBoolean(k, v).commit() } catch (_: Exception) { } }

    fun setMaster(on: Boolean) { write(MASTER, on); _master.value = on }
    fun setPlaceRecall(on: Boolean) { write(F2, on); _f2.value = on }
    fun setReplay(on: Boolean) { write(REPLAY, on); _replay.value = on }

    /** The loop runs only with the master switch AND the family switch on. */
    fun active(): Boolean = _master.value && _f2.value

    /** Delete all: back to the defaults (everything OFF), also on disk. */
    fun reset() {
        try { prefs.edit().clear().commit() } catch (_: Exception) { }
        _master.value = false; _f2.value = true; _replay.value = false
    }

    companion object {
        const val PREFS = "proactive_settings"
        private const val MASTER = "master"
        private const val F2 = "f2_place_recall"
        private const val REPLAY = "replay"
        @Volatile private var instance: ProactiveSettings? = null
        fun of(context: Context): ProactiveSettings = instance ?: synchronized(this) {
            instance ?: ProactiveSettings(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)).also { instance = it }
        }
        internal fun resetForTests() { instance = null }
    }
}
