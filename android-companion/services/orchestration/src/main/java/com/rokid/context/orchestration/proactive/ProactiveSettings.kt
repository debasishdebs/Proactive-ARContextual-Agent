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
    private val _phone = MutableStateFlow(read(DELIVER_PHONE, false))
    private val _hud = MutableStateFlow(read(DELIVER_HUD, false))
    private val _nexus = MutableStateFlow(read(DELIVER_NEXUS, false))
    /** v3.1 delivery switches, each independent and default OFF; each ALSO needs the master (and family) switch ([deliversTo]). */
    val deliverPhone: StateFlow<Boolean> = _phone.asStateFlow()
    val deliverHud: StateFlow<Boolean> = _hud.asStateFlow()
    val deliverNexus: StateFlow<Boolean> = _nexus.asStateFlow()
    val master: StateFlow<Boolean> = _master.asStateFlow()
    val placeRecall: StateFlow<Boolean> = _f2.asStateFlow()
    val replay: StateFlow<Boolean> = _replay.asStateFlow()
    private val _storageDown = MutableStateFlow(false)
    /** The encrypted store could not be opened this process: the loop is OFF and cannot be switched on (fail closed; see [ProactiveGraph.store]). */
    val storageUnavailable: StateFlow<Boolean> = _storageDown.asStateFlow()

    private fun read(k: String, d: Boolean) = try { prefs.getBoolean(k, d) } catch (e: Exception) { false }
    private fun write(k: String, v: Boolean) { try { prefs.edit().putBoolean(k, v).commit() } catch (_: Exception) { } }

    fun setMaster(on: Boolean) { val v = on && !_storageDown.value; write(MASTER, v); _master.value = v }

    /** The persisted history (Never list, budget, cooldown) cannot be read: force the master switch OFF, on disk too, and refuse to turn it on until the process restarts. */
    fun markStorageUnavailable() { _storageDown.value = true; write(MASTER, false); _master.value = false }
    fun setPlaceRecall(on: Boolean) { write(F2, on); _f2.value = on }
    fun setDeliver(surface: Surface, on: Boolean) = when (surface) {
        Surface.PHONE -> { write(DELIVER_PHONE, on); _phone.value = on }
        Surface.HUD -> { write(DELIVER_HUD, on); _hud.value = on }
        Surface.NEXUS -> { write(DELIVER_NEXUS, on); _nexus.value = on }
    }
    private fun surfaceOn(s: Surface) = when (s) { Surface.PHONE -> _phone.value; Surface.HUD -> _hud.value; Surface.NEXUS -> _nexus.value }
    /** Delivery to [surface] is wanted: the loop is active AND the surface's own switch is on. Fresh installs: false everywhere. */
    fun deliversTo(surface: Surface): Boolean = active() && surfaceOn(surface)
    fun setReplay(on: Boolean) { write(REPLAY, on); _replay.value = on }

    /** The loop runs only with the master switch AND the family switch on. */
    fun active(): Boolean = _master.value && _f2.value && !_storageDown.value

    /** Delete all: back to the defaults (everything OFF), also on disk. */
    fun reset() {
        try { prefs.edit().clear().commit() } catch (_: Exception) { }
        _master.value = false; _f2.value = true; _replay.value = false; _phone.value = false; _hud.value = false; _nexus.value = false
    }

    companion object {
        const val PREFS = "proactive_settings"
        private const val MASTER = "master"
        private const val F2 = "f2_place_recall"
        private const val REPLAY = "replay"
        private const val DELIVER_PHONE = "deliver_phone"
        private const val DELIVER_HUD = "deliver_hud"
        private const val DELIVER_NEXUS = "deliver_nexus"
        @Volatile private var instance: ProactiveSettings? = null
        fun of(context: Context): ProactiveSettings = instance ?: synchronized(this) {
            instance ?: ProactiveSettings(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)).also { instance = it }
        }
        /** Tests only (also used from the phone module). */ fun resetForTests() { instance = null }
    }
}
