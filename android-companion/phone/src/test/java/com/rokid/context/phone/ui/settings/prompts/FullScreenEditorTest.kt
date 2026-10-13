package com.rokid.context.phone.ui.settings.prompts

import android.app.Application
import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.Capability
import com.rokid.context.ai.Endpoint
import com.rokid.context.ai.EndpointKind
import com.rokid.context.ai.Role
import com.rokid.context.ai.RoleBinding
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.legacy.LegacyImporter
import com.rokid.context.memory.retrieval.RetrievalSettings
import com.rokid.context.orchestration.AnswerService
import com.rokid.context.orchestration.TestBedInputs
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.TestGraph
import com.rokid.context.phone.ui.cancelScope
import com.rokid.context.phone.ui.settings.TestSettings
import com.rokid.context.prompt.TemplateVariables
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** Agent flows C1: the full-screen template/schema editor writes back through the same VM path as an inline edit. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class FullScreenEditorTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val compose = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var ai: AiGraph
    private val vms = mutableListOf<PromptEditorViewModel>()

    @Before fun setUp() = runBlocking {
        TestGraph.fresh(ctx)
        ai = TestSettings.ai(ctx) { """{"answer":"In the fridge","sources":["m1"]}""" }
        ai.endpoints.save(Endpoint("az", "Azure", EndpointKind.AZURE_OPENAI, "https://az.example", null, listOf("gpt"), setOf(Capability.CHAT, Capability.JSON_SCHEMA), 1), "k").getOrThrow()
        ai.bindings.set(RoleBinding(Role.ANSWER, "az", "gpt")).getOrThrow()
        MemoryGraph.get(ctx).memories.add(LegacyImporter.toMemory("m1", "short_term", 0.9, 10, "Milk carton in the fridge"))
    }

    @After fun tearDown() { try { vms.forEach { it.cancelScope() } } finally { TestGraph.close(ctx) } }

    private fun editor(id: String): PromptEditorViewModel {
        val mem = MemoryGraph.get(ctx)
        return PromptEditorViewModel(id, ai, TestBedInputs(mem, AnswerService(mem, ai.runner, ai.retriever, { RetrievalSettings() })), work = main.dispatcher).also { vms += it }
    }

    private fun show(vm: PromptEditorViewModel) = compose.setContent {
        PcTheme {
            val s by vm.state.collectAsState()
            PromptEditorScreen(s, onBack = {}, onTemplate = vm::onTemplate, onSchema = vm::onSchema, onResetSchema = vm::resetSchema, onInputKind = vm::onInputKind,
                onInputText = vm::onInputText, onFrame = vm::onFrame, onEndpoint = vm::onEndpoint, onModel = vm::onModel, onRun = vm::run, onSave = vm::save,
                onDiscard = vm::discard, onToggleWire = vm::toggleWire, onOpenHistory = {})
        }
    }

    private fun save() = compose.onNodeWithTag("prompt_editor").performScrollToNode(hasTestTag("prompt_save")).let { compose.onNodeWithTag("prompt_save") }

    /** A tested, saveable ANSWER draft. */
    private fun testedAnswer(): PromptEditorViewModel = runBlocking {
        val vm = editor("ANSWER")
        withTimeout(5_000) { vm.state.first { !it.loading && it.endpointId == "az" && it.gates.compatible } }
        vm.onInputText("where is the milk")
        vm.onTemplate(vm.state.value.template + "\nBe brief.")
        vm.run()
        val s = withTimeout(5_000) { vm.state.first { !it.running && it.result != null } }
        assertTrue(s.gates.toString(), s.canSave)
        vm
    }

    @Test fun roleTemplateFullScreenDoneUpdatesDraftAndRelocksSave() {
        val vm = testedAnswer()
        show(vm)
        save().assertIsEnabled()
        val edited = vm.state.value.template + "\nEven briefer."
        compose.onNodeWithTag("prompt_editor").performScrollToNode(hasTestTag("prompt_template_expand"))
        compose.onNodeWithTag("prompt_template_expand").performClick()
        compose.onNodeWithTag("prompt_template_full_field").performTextReplacement(edited)
        compose.onNodeWithTag("prompt_template_full_done").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("prompt_template_full_field").assertDoesNotExist()
        assertEquals(edited, vm.state.value.template)
        compose.onNodeWithTag("prompt_editor").performScrollToNode(hasTestTag("prompt_template"))
        compose.onNodeWithTag("prompt_template").assertTextEquals(edited)
        assertFalse(vm.state.value.gates.ran)
        save().assertIsNotEnabled()
    }

    @Test fun cancelLeavesDraftUntouched() {
        val vm = testedAnswer()
        show(vm)
        val before = vm.state.value.draft
        compose.onNodeWithTag("prompt_editor").performScrollToNode(hasTestTag("prompt_template_expand"))
        compose.onNodeWithTag("prompt_template_expand").performClick()
        compose.onNodeWithTag("prompt_template_full_field").performTextReplacement("something else")
        compose.onNodeWithTag("prompt_template_full_cancel").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("prompt_template_full_field").assertDoesNotExist()
        assertEquals(before, vm.state.value.draft)
        assertTrue(vm.state.value.gates.ran)
        save().assertIsEnabled()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun chipInsertsVariableAtCursor() {
        var done: String? = null
        compose.setContent { PcTheme { FullScreenTextEditor("Template", "ab", TemplateVariables.KNOWN, onDone = { done = it }, onCancel = {}, tag = "fs") } }
        compose.onNodeWithTag("fs_field").performTextInputSelection(TextRange(1))
        compose.onNodeWithTag("fs_chip_now").performScrollTo().performClick()
        compose.onNodeWithTag("fs_field").assertTextEquals("a{{now}}b")
        compose.onNodeWithTag("fs_done").performClick()
        assertEquals("a{{now}}b", done)
    }

    @Test fun systemFullScreenDoneReevaluatesOnlyVariablesAndAllowsSaveWithoutRun() {
        val sys = editor("SYSTEM")
        val s0 = runBlocking { withTimeout(5_000) { sys.state.first { !it.loading } } }
        show(sys)
        compose.onNodeWithTag("prompt_template_expand").performClick()
        compose.onNodeWithTag("prompt_template_full_field").performTextReplacement(s0.template + "\nBe terse. {{language}}")
        compose.onNodeWithTag("prompt_template_full_done").performClick()
        compose.waitForIdle()
        val s = sys.state.value
        assertEquals(s0.template + "\nBe terse. {{language}}", s.template)
        assertEquals(listOf("\u2713 Variables"), s.gateLines)
        assertNull(s.result); assertFalse(s.running)
        save().assertIsEnabled()
        compose.onNodeWithTag("prompt_run").assertDoesNotExist()
    }

    @Test fun systemUnknownVariableBlocksSave() {
        val sys = editor("SYSTEM")
        val s0 = runBlocking { withTimeout(5_000) { sys.state.first { !it.loading } } }
        show(sys)
        compose.onNodeWithTag("prompt_template_expand").performClick()
        compose.onNodeWithTag("prompt_template_full_field").performTextReplacement(s0.template + " {{nope}}")
        compose.onNodeWithTag("prompt_template_full_done").performClick()
        compose.waitForIdle()
        assertEquals(listOf("nope"), sys.state.value.unknownVariables)
        assertFalse(sys.state.value.canSave)
        save().assertIsNotEnabled()
    }

    @Test fun schemaExpandHiddenForSystem() {
        val sys = editor("SYSTEM")
        runBlocking { withTimeout(5_000) { sys.state.first { !it.loading } } }
        show(sys)
        compose.onNodeWithTag("prompt_template_expand").assertExists()
        compose.onNodeWithTag("prompt_schema_expand").assertDoesNotExist()
    }

    @Test fun schemaFullScreenDoneGoesThroughOnSchema() {
        val vm = testedAnswer()
        show(vm)
        val edited = vm.state.value.schema.replace("\"maxLength\":600", "\"maxLength\":500")
        compose.onNodeWithTag("prompt_editor").performScrollToNode(hasTestTag("prompt_schema_expand"))
        compose.onNodeWithTag("prompt_schema_expand").performClick()
        compose.onNodeWithTag("prompt_schema_full_field").performTextReplacement(edited)
        compose.onNodeWithTag("prompt_schema_full_done").performClick()
        compose.waitForIdle()
        assertEquals(edited, vm.state.value.schema)
        assertFalse(vm.state.value.canSave)
    }

    @Test fun expandIsDisabledWhileSaving() {
        compose.setContent { PcTheme { PromptEditorScreen(PromptEditorUiState(loading = false, key = com.rokid.context.ai.contract.PromptKey.ANSWER, label = "Answer", template = "x", saving = true),
            onBack = {}, onTemplate = {}, onSchema = {}, onResetSchema = {}, onInputKind = {}, onInputText = {}, onFrame = {}, onEndpoint = {}, onModel = {},
            onRun = {}, onSave = {}, onDiscard = {}, onToggleWire = {}, onOpenHistory = {}) } }
        compose.onNodeWithTag("prompt_template_expand").assertIsNotEnabled()
        compose.onNodeWithTag("prompt_editor").performScrollToNode(hasTestTag("prompt_schema_expand"))
        compose.onNodeWithTag("prompt_schema_expand").assertIsNotEnabled()
    }

    /** Review Focus 4: text typed in full screen survives a configuration change. */
    @Test fun fullScreenTextSurvivesRecreation() {
        val restore = StateRestorationTester(compose)
        restore.setContent { PcTheme { FullScreenTextEditor("Template", "start", TemplateVariables.KNOWN, onDone = {}, onCancel = {}, tag = "fs") } }
        compose.onNodeWithTag("fs_field").performTextReplacement("typed in full screen")
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("fs_field").assertTextEquals("typed in full screen")
    }

    /** System Back on the topmost showing dialog window (the full-screen editor is a Dialog). */
    private fun pressBack() = compose.runOnIdle {
        (ShadowDialog.getShownDialogs().last { it.isShowing } as androidx.activity.ComponentDialog).onBackPressedDispatcher.onBackPressed()
    }

    /** Back with edited text asks first: Keep editing returns to the text, Discard closes without handing it back. */
    @Test fun backWithChangesAsksToDiscard() {
        var cancelled = 0
        var done: String? = null
        compose.setContent { PcTheme { FullScreenTextEditor("Template", "start", emptyList(), onDone = { done = it }, onCancel = { cancelled++ }, tag = "fs") } }
        compose.onNodeWithTag("fs_field").performTextReplacement("changed")
        pressBack()
        compose.onNodeWithText("Discard changes?").assertExists()
        assertEquals(0, cancelled)
        compose.onNodeWithTag("fs_keep_editing").performClick()
        compose.onNodeWithText("Discard changes?").assertDoesNotExist()
        compose.onNodeWithTag("fs_field").assertTextEquals("changed")
        assertEquals(0, cancelled)
        pressBack()
        compose.onNodeWithTag("fs_discard").performClick()
        compose.waitForIdle()
        assertEquals(1, cancelled)
        assertNull(done)
    }

    /** Back with the text as it opened just closes, with no dialog. */
    @Test fun backWithoutChangesJustCloses() {
        var cancelled = 0
        compose.setContent { PcTheme { FullScreenTextEditor("Template", "start", emptyList(), onDone = {}, onCancel = { cancelled++ }, tag = "fs") } }
        compose.onNodeWithTag("fs_field").assertTextEquals("start")
        pressBack()
        compose.waitForIdle()
        compose.onNodeWithText("Discard changes?").assertDoesNotExist()
        assertEquals(1, cancelled)
    }
}
