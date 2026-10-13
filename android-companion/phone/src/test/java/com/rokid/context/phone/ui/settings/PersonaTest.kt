package com.rokid.context.phone.ui.settings

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.cancelScope
import com.rokid.context.prompt.InMemoryPersonaSettings
import com.rokid.context.prompt.Persona
import com.rokid.context.prompt.ReplyLanguage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class PersonaTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val compose = createComposeRule()

    @Test fun savingCleansTheTextAndKeepsTheLanguage() {
        val settings = InMemoryPersonaSettings()
        val vm = PersonaViewModel(settings)
        vm.onText("  Warm,\n\tbrief butler  "); vm.onLanguage(ReplyLanguage.HINDI); vm.save()
        assertEquals(Persona("Warm, brief butler", ReplyLanguage.HINDI), settings.persona.value)
        assertEquals("Saved", vm.state.value.notice)
        vm.onText(""); vm.save()
        assertEquals(Persona.DEFAULT.text, settings.persona.value.text) // blank falls back to the default
        vm.cancelScope()
    }

    @Test fun screenHasTextLanguageAndSave() {
        compose.setContent { PcTheme { PersonaScreen(PersonaUiState("Brief", ReplyLanguage.AUTO), onBack = {}, onText = {}, onLanguage = {}, onSave = {}) } }
        listOf("persona_text", "persona_language", "persona_save").forEach { compose.onNodeWithTag(it).assertExists() }
    }
}
