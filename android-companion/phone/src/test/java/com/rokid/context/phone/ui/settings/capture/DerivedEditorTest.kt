package com.rokid.context.phone.ui.settings.capture

import android.app.Application
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.phone.ui.settings.FakeHudSettings
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** v0.9.7 B item 1: the derived-signal editor on Settings > Capture > Signals: live validation through the SAME validator as PUT /api/signals/derived, Save only when valid. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2400dp")
class DerivedEditorTest {
    @get:Rule val compose = createComposeRule()
    private val walky = """{"steps":[{"name":"s","inputs":[{"name":"k","const":1.2}],"nodes":[{"id":"w","op":"win","agg":"mean","sec":10,"name":"stepHz"},{"id":"c","op":"cmp","f":">"}],"wires":[["w","c.a"],["k","c.b"]],"out":"c"}],"out":"s"}"""
    private fun vm(store: SignalConfigStore = SignalConfigStore()) = SignalSettingsViewModel(FakeHudSettings(), store, MutableStateFlow<String?>(null))

    @Test fun aValidDefinitionCanBeSavedAndAppearsInTheList() {
        val store = SignalConfigStore(); val v = vm(store)
        v.newDerived(); v.setEditorName("walky"); v.setEditorText(walky)
        assertNull(v.editor.value.problem); assertTrue(v.editor.value.canSave)
        v.saveEditor()
        assertEquals(listOf("walky"), store.config.value.derived.map { it.name })
        assertFalse("the editor closes after a save", v.editor.value.open)
    }

    @Test fun anInvalidDefinitionShowsItsFirstProblemAndCannotBeSaved() {
        val store = SignalConfigStore(); val v = vm(store)
        v.newDerived(); v.setEditorName("walky"); v.setEditorText("""{"steps":[{"name":"s","nodes":[{"id":"w","op":"sig","name":"nope"}],"out":"w"}],"out":"s"}""")
        assertNotNull(v.editor.value.problem); assertFalse(v.editor.value.canSave)
        v.saveEditor()
        assertTrue("an invalid save stores nothing", store.config.value.derived.isEmpty())
    }

    @Test fun theEditorUsesTheSameValidatorAsThePutRoute() {
        val store = SignalConfigStore(); val v = vm(store)
        for ((name, text) in listOf("Bad" to walky, "walky" to "[]", "walky" to """{"steps":[],"out":"x"}""", "w2" to walky, "w3" to walky.replace("stepHz", "nope"))) {
            v.newDerived(); v.setEditorName(name); v.setEditorText(text)
            val viaRoute = com.rokid.context.ai.canvas.plan.DerivedSignals.validate(name, kotlinx.serialization.json.Json.parseToJsonElement(text), store.config.value)
            assertEquals("$name / $text", viaRoute.firstOrNull(), v.editor.value.problem)
            assertEquals(viaRoute.isEmpty(), v.editor.value.canSave)
        }
        v.newDerived(); v.setEditorName("walky"); v.setEditorText("not json")
        assertNotNull(v.editor.value.problem); assertFalse(v.editor.value.canSave)
    }

    @Test fun anEmptyEditorShowsNoProblemButCannotBeSaved() {
        val v = vm(); v.newDerived()
        assertNull(v.editor.value.problem); assertFalse(v.editor.value.canSave)
    }

    @Test fun aNameThatBreaksThePatternIsRefused() {
        val v = vm(); v.newDerived(); v.setEditorName("9lives"); v.setEditorText(walky)
        assertTrue(v.editor.value.problem.toString(), v.editor.value.problem!!.contains("name"))
        assertFalse(v.editor.value.canSave)
    }

    @Test fun editingAnExistingSignalLoadsItAndASaveKeepsTheOldVersionInTheHistory() {
        val store = SignalConfigStore(); val v = vm(store)
        assertTrue(store.saveDerived("walky", kotlinx.serialization.json.Json.parseToJsonElement(walky)).isEmpty())
        v.editDerived("walky")
        assertEquals("walky", v.editor.value.name); assertTrue(v.editor.value.text.contains("stepHz")); assertTrue(v.editor.value.editing)
        v.setEditorText(walky.replace("1.2", "1.9")); v.saveEditor()
        assertTrue(store.config.value.derived.single().graph.toString().contains("1.9"))
        assertTrue("the version before the edit is in the bounded history", store.history.any { h -> h.derived.any { it.graph.toString().contains("1.2") } })
        assertTrue(store.history.size <= SignalConfigStore.MAX_HISTORY)
    }

    @Test fun theNinthSignalIsRefusedLive() {
        val store = SignalConfigStore(); val v = vm(store)
        repeat(8) { assertTrue(store.saveDerived("s$it", kotlinx.serialization.json.Json.parseToJsonElement(walky)).isEmpty()) }
        v.newDerived(); v.setEditorName("s9"); v.setEditorText(walky)
        assertFalse(v.editor.value.canSave); assertTrue(v.editor.value.problem!!.contains("at most"))
    }

    @Test fun theScreenSaveButtonFollowsValidity() {
        val v = vm()
        compose.setContent { PcTheme { SignalSettingsScreen(v.state.collectAsState().value, onBack = {}, editor = v.editor.collectAsState().value, editorActions = v.editorActions()) } }
        compose.onNodeWithTag("signal_settings").performScrollToNode(hasTestTag("derived_new"))
        compose.onNodeWithTag("derived_new").performClick()
        compose.onNodeWithTag("signal_settings").performScrollToNode(hasTestTag("derived_save"))
        compose.onNodeWithTag("derived_save").assertIsNotEnabled()
        compose.onNodeWithTag("derived_name").performTextInput("walky")
        compose.onNodeWithTag("derived_text").performTextInput("not json")
        compose.waitForIdle()
        compose.onNodeWithTag("derived_problem").assertExists()
        compose.onNodeWithTag("derived_save").assertIsNotEnabled()
    }
}
