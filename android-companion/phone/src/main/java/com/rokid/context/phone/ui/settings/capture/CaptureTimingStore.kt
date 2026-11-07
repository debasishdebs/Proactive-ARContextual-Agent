package com.rokid.context.phone.ui.settings.capture

import android.content.Context
import android.content.SharedPreferences
import com.rokid.context.core.log.PcLog
import com.rokid.context.memory.forget.ForgetScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Four stored settings of Settings > Capture (user ruling 2026-10-25), each one a short list of presets with the OLD constant as the default, so an untouched install behaves
 * exactly as before. A stored value that is not a preset (a bad or hand-edited pref) falls back to the default instead of being used.
 * - Phone watchdog silence threshold, minutes (2 / 3 / 5 / 10, default 3): read by the watchdog at every tick.
 * - Phone watchdog tick, seconds (60 / 150 / 300, default 150): read by the watchdog loop before every sleep.
 * - Photo Forget radius, seconds (5 / 10 / 15 / 30, default 10): bound to [com.rokid.context.memory.forget.ForgetRadius] at start, read once per Forget.
 * - Forget scope (Photo | Moment | Scene, default Photo; user ruling 2026-10-27): how much every granular Forget removes ([ForgetScope]); bound to [com.rokid.context.memory.forget.ForgetScopeSetting] at start. Anything stored that
 *   is not exactly one of the three names is Photo. Like the radius it is per-device (this prefs file is excluded from backup and transfer: a restore returns to Photo).
 * Persisted in the prefs [PREFS]; with no prefs it is in memory only.
 */
class CaptureTimingStore(private val prefs: SharedPreferences? = null, val developerMode: StateFlow<Boolean> = MutableStateFlow(false)) {
    private val silent = MutableStateFlow(clean(prefs?.getInt(KEY_SILENT_MIN, DEFAULT_SILENT_MIN) ?: DEFAULT_SILENT_MIN, SILENT_MIN_PRESETS, DEFAULT_SILENT_MIN))
    private val tick = MutableStateFlow(clean(prefs?.getInt(KEY_TICK_SEC, DEFAULT_TICK_SEC) ?: DEFAULT_TICK_SEC, TICK_SEC_PRESETS, DEFAULT_TICK_SEC))
    private val radius = MutableStateFlow(clean(prefs?.getInt(KEY_FORGET_RADIUS_SEC, DEFAULT_FORGET_RADIUS_SEC) ?: DEFAULT_FORGET_RADIUS_SEC, FORGET_RADIUS_SEC_PRESETS, DEFAULT_FORGET_RADIUS_SEC))
    private val autoStop = MutableStateFlow(readAutoStop(prefs))
    private val scope = MutableStateFlow(readScope(prefs))
    /** What is STORED (kept while Developer mode is off, so switching it on again restores the choice). */
    val storedForgetScope: StateFlow<ForgetScope> = scope.asStateFlow()
    /** What the app does and Settings shows: Moment and Scene are EXPERIMENTAL until a worn pass, so only with Developer mode on; otherwise Photo, whatever is stored. */
    val forgetScope: kotlinx.coroutines.flow.Flow<ForgetScope> = kotlinx.coroutines.flow.combine(scope, developerMode) { s, dev -> effective(s, dev) }
    /** Minutes the glasses may be off-head or disconnected before the hub stops itself (5..120, 0 = Never; [com.rokid.context.phone.hub.BackgroundPolicy.autoStop]). */
    val autoStopMin: StateFlow<Int> = autoStop.asStateFlow()
    val silentMin: StateFlow<Int> = silent.asStateFlow()
    val tickSec: StateFlow<Int> = tick.asStateFlow()
    val forgetRadiusSec: StateFlow<Int> = radius.asStateFlow()

    fun setSilentMin(v: Int) { val c = clean(v, SILENT_MIN_PRESETS, DEFAULT_SILENT_MIN); prefs?.edit()?.putInt(KEY_SILENT_MIN, c)?.apply(); silent.value = c }
    fun setAutoStopMin(v: Int) { val c = com.rokid.context.phone.hub.BackgroundPolicy.cleanAutoStopMin(v); prefs?.edit()?.putInt(KEY_AUTO_STOP_MIN, c)?.apply(); autoStop.value = c }
    fun autoStopMinNow(): Int = autoStop.value
    fun setTickSec(v: Int) { val c = clean(v, TICK_SEC_PRESETS, DEFAULT_TICK_SEC); prefs?.edit()?.putInt(KEY_TICK_SEC, c)?.apply(); tick.value = c }
    fun setForgetRadiusSec(v: Int) { val c = clean(v, FORGET_RADIUS_SEC_PRESETS, DEFAULT_FORGET_RADIUS_SEC); prefs?.edit()?.putInt(KEY_FORGET_RADIUS_SEC, c)?.apply(); radius.value = c }

    fun setForgetScope(v: ForgetScope) {
        if (v != ForgetScope.PHOTO && !developerMode.value) return // not offered without Developer mode
        val changed = scope.value != v
        prefs?.edit()?.putString(KEY_FORGET_SCOPE, v.name)?.apply(); scope.value = v
        if (changed) PcLog.i("PcForget", "forget scope changed") // the event only: never the value
    }
    /** The scope the Forget path reads (through [com.rokid.context.memory.forget.ForgetScopeSetting]) at the moment a Forget starts. */
    fun forgetScopeNow(): ForgetScope = effective(scope.value, developerMode.value)

    /** The watchdog's silence threshold in ms (read at every tick). */
    fun silentMs(): Long = silent.value * 60_000L
    /** The watchdog's tick in ms (read before every sleep). */
    fun tickMs(): Long = tick.value * 1_000L
    /** The photo Forget radius in ms (the one accessor of the Forget path reads this through [com.rokid.context.memory.forget.ForgetRadius]). */
    fun forgetRadiusMs(): Long = radius.value * 1_000L

    companion object {
        const val PREFS = "capture_timing"
        const val KEY_SILENT_MIN = "watchdog_silent_min"
        const val KEY_TICK_SEC = "watchdog_tick_sec"
        const val KEY_FORGET_RADIUS_SEC = "forget_radius_sec"
        const val KEY_AUTO_STOP_MIN = "auto_stop_min"
        const val KEY_FORGET_SCOPE = "forget_scope"
        const val DEFAULT_SILENT_MIN = 3
        const val DEFAULT_TICK_SEC = 150
        const val DEFAULT_FORGET_RADIUS_SEC = 10
        val SILENT_MIN_PRESETS = listOf(2, 3, 5, 10)
        val TICK_SEC_PRESETS = listOf(60, 150, 300)
        val FORGET_RADIUS_SEC_PRESETS = listOf(5, 10, 15, 30)

        private fun readAutoStop(prefs: SharedPreferences?): Int = try {
            com.rokid.context.phone.hub.BackgroundPolicy.cleanAutoStopMin(prefs?.getInt(KEY_AUTO_STOP_MIN, com.rokid.context.phone.hub.BackgroundPolicy.AUTO_STOP_DEFAULT_MIN) ?: com.rokid.context.phone.hub.BackgroundPolicy.AUTO_STOP_DEFAULT_MIN)
        } catch (e: ClassCastException) { com.rokid.context.phone.hub.BackgroundPolicy.AUTO_STOP_DEFAULT_MIN }

        private fun readScope(prefs: SharedPreferences?): ForgetScope = try { ForgetScope.parse(prefs?.getString(KEY_FORGET_SCOPE, null)) } catch (e: ClassCastException) { ForgetScope.PHOTO }

        fun effective(stored: ForgetScope, developerMode: Boolean): ForgetScope = if (developerMode) stored else ForgetScope.PHOTO

        fun clean(v: Int, presets: List<Int>, default: Int): Int = if (v in presets) v else default

        @Volatile private var instance: CaptureTimingStore? = null
        fun get(context: Context): CaptureTimingStore = instance ?: synchronized(this) {
            instance ?: CaptureTimingStore(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE), com.rokid.context.phone.canvas.DeveloperModeStore.get(context).enabled).also { instance = it }
        }
    }
}

/** Settings > Capture > Scene rebuilds per day: the stored `scene_rebuilds_per_day` of `canvas_meta` (read and written on the caller's dispatcher). */
interface SceneRebuildControl {
    suspend fun perDay(): Int
    suspend fun set(n: Int)

    object None : SceneRebuildControl {
        @Volatile private var n = com.rokid.context.canvas.moment.SceneCadence.DEFAULT_PER_DAY
        override suspend fun perDay() = n
        override suspend fun set(n: Int) { this.n = n }
    }
}

/** The production control: the Room `canvas_meta` row, the same one the cadence worker reads. */
class StoredSceneRebuilds(private val meta: () -> com.rokid.context.memory.db.CanvasSlice3Dao) : SceneRebuildControl {
    override suspend fun perDay() = com.rokid.context.canvas.moment.SceneCadence.storedPerDay(meta())
    override suspend fun set(n: Int) = com.rokid.context.canvas.moment.SceneCadence.storePerDay(meta(), n)
}
