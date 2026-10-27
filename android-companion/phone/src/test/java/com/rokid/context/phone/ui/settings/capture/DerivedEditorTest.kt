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
import kotlinx.coroutines.asCoroutineDispatcher
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
    private fun vm(store: SignalConfigStore = SignalConfigStore()) = SignalSettingsViewModel(FakeHudSettings(), store, MutableStateFlow<String?>(null), editorDispatcher = kotlinx.coroutines.Dispatchers.Unconfined, editorDebounceMs = 0)

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

    // ---- review fix: the size limit before parsing, and validation off the main thread and debounced ----
    private val limit = SignalConfigStore.MAX_DEFINITION_BYTES

    @Test fun theLimitIsTheApisBodyLimit() = assertEquals(com.rokid.context.phone.canvas.server.CanvasServer.MAX_BODY, limit)

    @Test fun aTextOverTheLimitIsRefusedWithASizeProblemBeforeAnythingIsParsed() {
        val v = vm(); v.newDerived(); v.setEditorName("big")
        v.setEditorText(" ".repeat(limit) + walky)
        assertTrue(v.editor.value.problem.toString(), v.editor.value.problem!!.contains("256 KiB")); assertFalse(v.editor.value.canSave)
        v.saveEditor()
        assertFalse("nothing is saved", v.editor.value.canSave)
    }

    @Test fun theLimitCountsUtf8BytesNotCharacters() {
        val v = vm(); v.newDerived(); v.setEditorName("big")
        v.setEditorText("\u00e9".repeat(limit / 2 + 10)) // 2 bytes each: under the limit in characters, over it in bytes
        assertTrue(v.editor.value.problem!!.contains("KiB"))
        v.setEditorText(walky + " ".repeat(limit - walky.length)) // exactly the limit in bytes: not a size problem
        assertFalse(v.editor.value.problem.toString(), v.editor.value.problem?.contains("KiB") == true)
        assertTrue(v.editor.value.canSave)
    }

    @Test fun theStoreRefusesAnOversizedDefinitionToo() {
        val store = SignalConfigStore()
        val pad = "x".repeat(limit)
        val big = kotlinx.serialization.json.Json.parseToJsonElement("""{"steps":[],"out":"s","pad":"$pad"}""")
        val problems = store.saveDerived("big", big)
        assertTrue(problems.toString(), problems.single().contains("256 KiB")); assertTrue(store.config.value.derived.isEmpty())
    }

    @Test fun theCheckRunsOffTheCallingThreadAndTypingIsDebounced() {
        val exec = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "editor-check-test") }
        try {
            val v = SignalSettingsViewModel(FakeHudSettings(), SignalConfigStore(), MutableStateFlow<String?>(null), editorDispatcher = exec.asCoroutineDispatcher(), editorDebounceMs = 150)
            v.newDerived(); v.setEditorName("walky")
            repeat(6) { v.setEditorText(walky.take(5 + it)); Thread.sleep(30) } // six quick edits, each within the pause
            v.setEditorText(walky)
            assertTrue("pending while the check has not run", v.editor.value.checking && !v.editor.value.canSave)
            val end = System.currentTimeMillis() + 5_000
            while (v.editor.value.checking && System.currentTimeMillis() < end) Thread.sleep(20)
            assertTrue(v.editor.value.canSave)
            assertTrue("the check ran on its own thread: ${v.lastCheckThread}", v.lastCheckThread!!.startsWith("editor-check-test"))
            assertTrue("one check for the burst, not one per key (${v.checks.get()})", v.checks.get() <= 2)
        } finally { exec.shutdownNow() }
    }

    @Test fun aSlowOlderCheckNeverDecidesForANewerText() {
        val exec = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val v = SignalSettingsViewModel(FakeHudSettings(), SignalConfigStore(), MutableStateFlow<String?>(null), editorDispatcher = exec.asCoroutineDispatcher(), editorDebounceMs = 0)
            val gate1 = java.util.concurrent.CountDownLatch(1); val gate2 = java.util.concurrent.CountDownLatch(1); val started1 = java.util.concurrent.CountDownLatch(1); val started2 = java.util.concurrent.CountDownLatch(1)
            v.checkHook = { when (v.checks.get()) { 1 -> { started1.countDown(); gate1.await(5, java.util.concurrent.TimeUnit.SECONDS) }; 2 -> { started2.countDown(); gate2.await(5, java.util.concurrent.TimeUnit.SECONDS) } } }
            v.newDerived(); v.setEditorName("walky"); v.setEditorText("not json")   // the first check blocks inside
            assertTrue(started1.await(5, java.util.concurrent.TimeUnit.SECONDS))
            v.setEditorText(walky)                                                  // the newer text replaces it
            gate1.countDown()                                                       // the old check finishes; the newer one starts and is held
            assertTrue(started2.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("the old verdict did not land on the newer text: ${v.editor.value}", v.editor.value.text == walky && v.editor.value.problem == null && v.editor.value.checking)
            gate2.countDown()
            val end = System.currentTimeMillis() + 5_000
            while (v.editor.value.checking && System.currentTimeMillis() < end) Thread.sleep(10)
            assertTrue("the newer text gets its own verdict", v.editor.value.canSave && v.editor.value.text == walky)
        } finally { exec.shutdownNow() }
    }

    @Test fun aCheckThatFinishesAfterTheEditorWasClosedNeverReopensIt() {
        val exec = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val v = SignalSettingsViewModel(FakeHudSettings(), SignalConfigStore(), MutableStateFlow<String?>(null), editorDispatcher = exec.asCoroutineDispatcher(), editorDebounceMs = 0)
            val gate = java.util.concurrent.CountDownLatch(1); val started = java.util.concurrent.CountDownLatch(1)
            v.checkHook = { started.countDown(); gate.await(5, java.util.concurrent.TimeUnit.SECONDS) }
            v.newDerived(); v.setEditorName("walky"); v.setEditorText(walky)
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            v.closeEditor()
            gate.countDown(); Thread.sleep(300)
            assertFalse("closed stays closed: ${v.editor.value}", v.editor.value.open)
        } finally { exec.shutdownNow() }
    }

    @Test fun aSaveThatFinishesAfterTheTextChangedDoesNotCloseTheEditor() {
        val exec = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val store = SignalConfigStore()
            val v = SignalSettingsViewModel(FakeHudSettings(), store, MutableStateFlow<String?>(null), editorDispatcher = exec.asCoroutineDispatcher(), editorDebounceMs = 0)
            v.newDerived(); v.setEditorName("walky"); v.setEditorText(walky)
            val end = System.currentTimeMillis() + 5_000
            while (!v.editor.value.canSave && System.currentTimeMillis() < end) Thread.sleep(10)
            val gate = java.util.concurrent.CountDownLatch(1)
            exec.execute { gate.await(5, java.util.concurrent.TimeUnit.SECONDS) }   // the one worker thread is busy: the save waits behind this
            v.saveEditor(); v.setEditorText(walky + " ")                             // typed over before the save ran
            gate.countDown()
            Thread.sleep(500)
            assertTrue("the save itself happened", store.config.value.derived.isNotEmpty())
            assertTrue("typing during the save keeps the editor open: ${v.editor.value}", v.editor.value.open && v.editor.value.text == walky + " ")
        } finally { exec.shutdownNow() }
    }
}

