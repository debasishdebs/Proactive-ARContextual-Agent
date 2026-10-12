package com.rokid.context.prompt

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** spec §0.1: English primary, Hindi the only other target. [promptText] fills `{{language}}`. */
enum class ReplyLanguage(val label: String, val promptText: String) {
    AUTO("Same as the question", "the language of the question (English or Hindi)"),
    ENGLISH("English", "English"),
    HINDI("Hindi", "Hindi"),
}

/** Settings → Persona & language → `{{persona}}`, `{{language}}`. */
data class Persona(val text: String, val language: ReplyLanguage) {
    companion object {
        const val MAX_CHARS = 300
        val DEFAULT = Persona("A concise, friendly assistant.", ReplyLanguage.AUTO)
        fun clean(text: String): String = text.replace(Regex("[\\s\\p{Cntrl}]+"), " ").trim().take(MAX_CHARS).ifEmpty { DEFAULT.text }
    }
}

interface PersonaSettings {
    val persona: StateFlow<Persona>
    fun set(p: Persona)
}

class InMemoryPersonaSettings(initial: Persona = Persona.DEFAULT) : PersonaSettings {
    private val state = MutableStateFlow(initial)
    override val persona: StateFlow<Persona> = state.asStateFlow()
    override fun set(p: Persona) { state.value = p.copy(text = Persona.clean(p.text)) }
}

class PrefsPersonaSettings(context: Context) : PersonaSettings {
    private val prefs = context.applicationContext.getSharedPreferences("persona_settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(
        Persona(
            prefs.getString(K_TEXT, null)?.let(Persona::clean) ?: Persona.DEFAULT.text,
            runCatching { ReplyLanguage.valueOf(prefs.getString(K_LANG, null) ?: "AUTO") }.getOrDefault(ReplyLanguage.AUTO),
        ),
    )
    override val persona: StateFlow<Persona> = state.asStateFlow()
    override fun set(p: Persona) {
        val clean = p.copy(text = Persona.clean(p.text))
        prefs.edit().putString(K_TEXT, clean.text).putString(K_LANG, clean.language.name).apply()
        state.value = clean
    }
    private companion object { const val K_TEXT = "text"; const val K_LANG = "language" }
}
