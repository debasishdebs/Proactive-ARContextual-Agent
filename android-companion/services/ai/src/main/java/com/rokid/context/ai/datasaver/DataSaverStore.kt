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
    private val state = MutableStateFlow(read())
    val settings: StateFlow<DataSaverSettings> = state.asStateFlow()

    private fun read() = DataSaverSettings(
        mode = DataMode.parse(prefs.getString(K_MODE, null)),
        abroad = prefs.getBoolean(K_ABROAD, false),
        smallOnRoaming = prefs.getBoolean(K_SMALL, true),
        dailyLimitOn = prefs.getBoolean(K_LIMIT_ON, false),
        limitMb = prefs.getInt(K_LIMIT_MB, DailyCaps.DEFAULT_MB).let { if (it in 0..1024) it else DailyCaps.DEFAULT_MB },
        limitPhotos = prefs.getInt(K_LIMIT_PHOTOS, DailyCaps.DEFAULT_PHOTOS).let { if (it in 0..10_000) it else DailyCaps.DEFAULT_PHOTOS },
        wifiMeteredAbroad = prefs.getBoolean(K_WIFI_METERED, false),
    )

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
    fun blockedSince(): Long = prefs.getLong(K_BLOCKED, 0L)
    private val markLock = Any()

    /**
     * Writes a NEW value (later than the old one) when there is no mark or the old one is at least [MARK_REFRESH_MS] old. Throttled so a screen refreshing every 2 s does not write the file each time.
     * The real guarantee is NOT "a clear that read the old value can never erase a later block": inside the throttle window a new block keeps the old value, so a [clearBlocked] with that value does clear.
     * Nothing is lost by that, because (1) a frame that arrives while uploads are held back is inserted already parked, (2) every blocked drain parks the whole backlog again, and (3) parked frames are never
     * shed (up to the protected cap). The mark only exists so a restart still knows the backlog was parked; it can be lost only in a window of milliseconds, and the frames themselves stay tagged.
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
        private const val K_LIMIT_ON = "daily_limit_on"
        private const val K_LIMIT_MB = "daily_limit_mb"
        private const val K_LIMIT_PHOTOS = "daily_limit_photos"
        private const val K_WIFI_METERED = "wifi_metered_abroad"
        const val MARK_REFRESH_MS = 10_000L
        fun of(context: Context) = DataSaverStore(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    }
}
