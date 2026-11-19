package com.rokid.context.ai.resolve

import android.content.Context
import android.content.SharedPreferences
import com.rokid.context.core.net.ModelPrivacy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings > Capture & privacy > Model privacy, in one app-private prefs file ([PREFS]). Per-device: excluded from backup and device transfer like the neighbouring Data saver and Place Guide
 * switches (a mode restored onto another phone would be wrong there; AUTO again after a restore). Delete all leaves it (a wipe must never silently re-open remote calls).
 * [current] reads the file every time (so the router, the data saver and the screen can never disagree) and FAILS CLOSED: a value that is not a known mode, or a read that throws, is [ModelPrivacy.OFFLINE_ONLY].
 */
class ModelPrivacyStore(private val prefs: SharedPreferences) {
    private val state = MutableStateFlow(current())
    val mode: StateFlow<ModelPrivacy> = state.asStateFlow()

    fun current(): ModelPrivacy = try { ModelPrivacy.parse(prefs.getString(K_MODE, null)) } catch (e: Exception) { ModelPrivacy.OFFLINE_ONLY }

    fun set(m: ModelPrivacy) {
        prefs.edit().putString(K_MODE, m.name).commit() // synchronous: the next call must see it
        state.value = current()
    }

    companion object {
        const val PREFS = "ai_backend"
        private const val K_MODE = "mode"
        @Volatile private var instance: ModelPrivacyStore? = null

        /** The process-wide store, so every reader and the screen share one flow. */
        fun get(context: Context): ModelPrivacyStore = instance ?: synchronized(this) {
            instance ?: ModelPrivacyStore(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)).also { instance = it }
        }

        fun resetForTests(store: ModelPrivacyStore?) { synchronized(this) { instance = store } }
    }
}
