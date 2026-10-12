package com.rokid.context.prompt

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The Prompts screen's "Keep prompt versions" toggle (spec §2.4, default on). */
interface PromptSettings {
    val keepVersions: StateFlow<Boolean>
    fun setKeepVersions(on: Boolean)
}

class InMemoryPromptSettings(initial: Boolean = true) : PromptSettings {
    private val state = MutableStateFlow(initial)
    override val keepVersions: StateFlow<Boolean> = state.asStateFlow()
    override fun setKeepVersions(on: Boolean) { state.value = on }
}

class PrefsPromptSettings(context: Context) : PromptSettings {
    private val prefs = context.applicationContext.getSharedPreferences("prompt_settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(prefs.getBoolean(KEY, true))
    override val keepVersions: StateFlow<Boolean> = state.asStateFlow()
    override fun setKeepVersions(on: Boolean) { prefs.edit().putBoolean(KEY, on).apply(); state.value = on }
    private companion object { const val KEY = "keep_versions" }
}
