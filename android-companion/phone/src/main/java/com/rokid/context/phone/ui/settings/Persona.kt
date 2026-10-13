package com.rokid.context.phone.ui.settings

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import com.rokid.context.prompt.Persona
import com.rokid.context.prompt.PersonaSettings
import com.rokid.context.prompt.ReplyLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PersonaUiState(val text: String = "", val language: ReplyLanguage = ReplyLanguage.AUTO, val notice: String? = null)

/** spec §2.4 AI › Persona & language → `{{persona}}` and `{{language}}` in every prompt (English primary, Hindi optional). */
class PersonaViewModel(private val settings: PersonaSettings) : ViewModel() {
    private val _state = MutableStateFlow(settings.persona.value.let { PersonaUiState(it.text, it.language) })
    val state: StateFlow<PersonaUiState> = _state.asStateFlow()

    fun onText(t: String) = _state.update { it.copy(text = t.take(Persona.MAX_CHARS + 50), notice = null) }
    fun onLanguage(l: ReplyLanguage) = _state.update { it.copy(language = l, notice = null) }
    fun save() {
        settings.set(Persona(_state.value.text, _state.value.language))
        _state.update { settings.persona.value.let { p -> it.copy(text = p.text, language = p.language, notice = "Saved") } }
    }
}

@Composable
fun PersonaScreen(state: PersonaUiState, onBack: () -> Unit, onText: (String) -> Unit, onLanguage: (ReplyLanguage) -> Unit, onSave: () -> Unit) {
    SettingsPage("Persona & language", onBack, tag = "persona") {
        item {
            SettingsCard {
                TextFieldRow("How answers should sound", state.text, onText, tag = "persona_text", singleLine = false,
                    supporting = "Up to ${Persona.MAX_CHARS} characters, e.g. \"A concise, friendly assistant.\"")
                val langs = ReplyLanguage.entries
                ChoiceRow("Reply language", langs.map { it.label }, langs.indexOf(state.language), { onLanguage(langs[it]) }, tag = "persona_language")
                NoticeText("Hindi voice needs an on-device speech pack (set up in a later update).")
                PrimaryAction("Save", onSave, tag = "persona_save")
                state.notice?.let { NoticeText(it, tag = "persona_notice") }
            }
        }
    }
}
