package com.rokid.context.ai.datasaver

import android.content.Context
import android.content.SharedPreferences
import com.rokid.context.core.net.CounterStore
import com.rokid.context.core.net.DailyCaps
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
    private val state: MutableStateFlow<DataSaverSettings>
    val settings: StateFlow<DataSaverSettings>

    /**
     * Every key is read on its own and a wrong-typed value (a [ClassCastException] from the prefs file) is caught: the switch reads as its PROTECTIVE value (mode Wi-Fi only, abroad on,
     * small-on-roaming off, own limit on, hotspot-as-metered on) and the whole reading says [DataSaverSettings.unreadable], which [com.rokid.context.core.net.DataPolicy] answers by refusing costly traffic.
     * Never throws: graph construction can not crash on a bad file. Writing the key again (a setter) heals it.
     */
    private fun read(): DataSaverSettings {
        var bad = false
        fun <T> safe(protective: T, f: () -> T): T = try { f() } catch (e: Exception) { bad = true; protective }
        val mode = safe(DataMode.WIFI_ONLY) {
            DataMode.parse(prefs.getString(K_MODE, null)) // an unknown (newer) value still reads as the default; only a wrong TYPE is unreadable
        }
        val abroad = safe(true) { prefs.getBoolean(K_ABROAD, false) }
        val small = safe(false) { prefs.getBoolean(K_SMALL, true) }
        val limitOn = safe(true) { prefs.getBoolean(K_LIMIT_ON, false) }
        val mb = safe(DailyCaps.DEFAULT_MB) { prefs.getInt(K_LIMIT_MB, DailyCaps.DEFAULT_MB) }.let { if (it in 0..1024) it else DailyCaps.DEFAULT_MB }
        val photos = safe(DailyCaps.DEFAULT_PHOTOS) { prefs.getInt(K_LIMIT_PHOTOS, DailyCaps.DEFAULT_PHOTOS) }.let { if (it in 0..10_000) it else DailyCaps.DEFAULT_PHOTOS }
        val metered = safe(true) { prefs.getBoolean(K_WIFI_METERED, false) }
        return DataSaverSettings(mode, abroad, small, limitOn, mb, photos, metered, unreadable = bad || badCounters.isNotEmpty())
    }

    /** Counter keys whose stored value had the wrong type (read as their default); cleared as each is written again. A non-empty set makes the settings [DataSaverSettings.unreadable]. */
    private val badCounters = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    init {
        state = MutableStateFlow(read())
        settings = state.asStateFlow()
    }

    fun setMode(m: DataMode) = write { it.putString(K_MODE, m.name) }
    fun setAbroad(on: Boolean) = write { it.putBoolean(K_ABROAD, on) }
    fun setSmallOnRoaming(on: Boolean) = write { it.putBoolean(K_SMALL, on) }
    fun setDailyLimitOn(on: Boolean) = write { it.putBoolean(K_LIMIT_ON, on) }
    fun setLimitMb(mb: Int) = write { it.putInt(K_LIMIT_MB, mb) }
    fun setLimitPhotos(n: Int) = write { it.putInt(K_LIMIT_PHOTOS, n) }
    fun setWifiMeteredAbroad(on: Boolean) = write { it.putBoolean(K_WIFI_METERED, on) }

    private fun write(edit: (SharedPreferences.Editor) -> Unit) {
        prefs.edit().also(edit).apply()
        state.value = read()
    }

    /** When photo uploads were first seen blocked since the mark was last cleared (ms; 0 = not blocked). Persisted so a restart while roaming still knows the backlog was parked. */
    fun blockedSince(): Long = blockedRaw()
    private fun blockedRaw(): Long = try { prefs.getLong(K_BLOCKED, 0L) } catch (e: Exception) { 0L } // a wrong-typed mark reads as none; the next markBlocked overwrites it
    private val markLock = Any()

    /**
     * Writes a NEW value (later than the old one) when there is no mark or the old one is at least [MARK_REFRESH_MS] old. Throttled so a screen refreshing every 2 s does not write the file each time.
     * The real guarantee is NOT "a clear that read the old value can never erase a later block": inside the throttle window a new block keeps the old value, so a [clearBlocked] with that value does clear.
     * Nothing is lost by that, because (1) a frame that arrives while uploads are held back is inserted already parked, (2) every blocked drain parks the whole backlog again, and (3) parked frames are never
     * shed (up to the protected cap). The mark only exists so a restart still knows the backlog was parked; it can be lost only in a window of milliseconds, and the frames themselves stay tagged.
     */
    fun markBlocked(nowMs: Long) = synchronized(markLock) {
        val old = blockedRaw()
        if (old == 0L || nowMs - old >= MARK_REFRESH_MS) prefs.edit().putLong(K_BLOCKED, maxOf(nowMs, old + 1)).apply()
    }

    /** Compare-and-clear: removes the mark only if it still equals [expected] (the value read before the backlog was parked). Returns whether it cleared. */
    fun clearBlocked(expected: Long): Boolean = synchronized(markLock) {
        if (expected == 0L || blockedRaw() != expected) false else { prefs.edit().remove(K_BLOCKED).apply(); true }
    }

    /** The counters live in the same file under their own keys. */
    val counters: CounterStore = object : CounterStore {
        override fun getLong(key: String, default: Long): Long = try { prefs.getLong(key, default) } catch (e: Exception) {
            if (badCounters.add(key)) state.value = read() // a wrong-typed counter: reads as the default, and the settings say unreadable (costly traffic is refused) until it is written again
            default
        }
        override fun putLongs(values: Map<String, Long>) {
            prefs.edit().also { e -> values.forEach { (k, v) -> e.remove(k); e.putLong(k, v) } }.apply()
            if (values.keys.any { badCounters.remove(it) }) state.value = read()
        }
    }

    companion object {
        const val PREFS = "data_saver"
        private const val K_MODE = "mode"
        private const val K_ABROAD = "abroad"
        private const val K_SMALL = "small_on_roaming"
        private const val K_BLOCKED = "blocked_since"
        private const val K_LIMIT_ON = "daily_limit_on"
        private const val K_LIMIT_MB = "daily_limit_mb"
        private const val K_LIMIT_PHOTOS = "daily_limit_photos"
        private const val K_WIFI_METERED = "wifi_metered_abroad"
        const val MARK_REFRESH_MS = 10_000L
        fun of(context: Context) = DataSaverStore(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    }
}
