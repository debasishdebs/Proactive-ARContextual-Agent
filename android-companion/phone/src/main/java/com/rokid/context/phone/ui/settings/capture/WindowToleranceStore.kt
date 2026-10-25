package com.rokid.context.phone.ui.settings.capture

import android.content.Context
import android.content.SharedPreferences
import com.rokid.context.ai.canvas.plan.ExprProgram
import com.rokid.context.ai.canvas.plan.WindowTolerance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The global window tolerance (3d): the percent of a window term's seconds that must hold sensor data (50..100, default 90), written into every canvas window term that
 * names none when a plan is COMPILED. Changing it never touches a plan already published. Persisted in the phone prefs key [KEY]; with no prefs it is in memory only.
 */
class WindowToleranceStore(private val prefs: SharedPreferences? = null) {
    private val state = MutableStateFlow(clean(prefs?.getInt(KEY, DEFAULT) ?: DEFAULT))
    val pct: StateFlow<Int> = state.asStateFlow()

    fun set(pct: Int) {
        val v = clean(pct)
        prefs?.edit()?.putInt(KEY, v)?.apply()
        state.value = v
    }

    companion object {
        const val KEY = "window_min_valid_pct"
        const val PREFS = "window_tolerance"
        const val DEFAULT = ExprProgram.DEFAULT_MIN_VALID_PCT
        val PRESETS = listOf(50, 60, 70, 80, 90, 95, 100)

        fun clean(pct: Int): Int = WindowTolerance.clean(pct)
        /** The presets, plus a stored custom value so it stays selectable. */
        fun options(current: Int): List<Int> = (PRESETS + clean(current)).distinct().sorted()

        @Volatile private var instance: WindowToleranceStore? = null
        fun get(context: Context): WindowToleranceStore = instance ?: synchronized(this) {
            instance ?: WindowToleranceStore(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)).also { instance = it }
        }
    }
}
