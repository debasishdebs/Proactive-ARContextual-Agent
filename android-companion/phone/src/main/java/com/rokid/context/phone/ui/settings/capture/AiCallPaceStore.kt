package com.rokid.context.phone.ui.settings.capture

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The "AI call pace": one background AI call per this many ms (10 / 12 / 15 s, default 12 s).
 * Persisted in the phone prefs key [KEY]; the call budget reads [paceMs]. With no prefs it is in memory only.
 */
class AiCallPaceStore(private val prefs: SharedPreferences? = null) {
    private val state = MutableStateFlow(clean(prefs?.getLong(KEY, DEFAULT_MS) ?: DEFAULT_MS))
    val paceMs: StateFlow<Long> = state.asStateFlow()

    fun set(ms: Long) {
        val v = clean(ms)
        prefs?.edit()?.putLong(KEY, v)?.apply()
        state.value = v
    }

    companion object {
        const val KEY = "ai_call_pace_ms"
        const val PREFS = "ai_call_pace"
        const val DEFAULT_MS = 12_000L
        val OPTIONS_MS = listOf(10_000L, 12_000L, 15_000L)

        /** Snaps a stored or wild value to the nearest allowed pace (ties to the smaller). */
        fun clean(ms: Long): Long = OPTIONS_MS.minByOrNull { kotlin.math.abs(it - ms) } ?: DEFAULT_MS

        @Volatile private var instance: AiCallPaceStore? = null
        fun get(context: Context): AiCallPaceStore = instance ?: synchronized(this) {
            instance ?: AiCallPaceStore(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)).also { instance = it }
        }
    }
}
