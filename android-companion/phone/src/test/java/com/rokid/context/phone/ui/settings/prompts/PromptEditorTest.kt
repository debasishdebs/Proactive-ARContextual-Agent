package com.rokid.context.phone.ui.settings.prompts

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.Capability
import com.rokid.context.ai.Endpoint
import com.rokid.context.ai.EndpointKind
import com.rokid.context.ai.ProviderHttpException
import com.rokid.context.ai.Role
import com.rokid.context.ai.RoleBinding
import com.rokid.context.ai.contract.DefaultSchemas
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.ai.run.SaveGates
import com.rokid.context.ai.run.TestBedResult
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
import com.rokid.context.prompt.PromptDraft
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class PromptEditorTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val compose = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private var reply: () -> String = { """{"answer":"In the fridge","sources":["m1"]}""" }
    private lateinit var ai: AiGraph
    private lateinit var vm: PromptEditorViewModel

    @Before fun setUp() = runBlocking {
        TestGraph.fresh(ctx)
        ai = TestSettings.ai(ctx) { reply() }
        ai.endpoints.save(Endpoint("az", "Azure", EndpointKind.AZURE_OPENAI, "https://az.example", null, listOf("gpt"), setOf(Capability.CHAT, Capability.JSON_SCHEMA), 1), "k").getOrThrow()
        ai.bindings.set(RoleBinding(Role.ANSWER, "az", "gpt")).getOrThrow()
        MemoryGraph.get(ctx).memories.add(LegacyImporter.toMemory("m1", "short_term", 0.9, 10, "Milk carton in the fridge"))
        val mem = MemoryGraph.get(ctx)
        vm = PromptEditorViewModel("ANSWER", ai, TestBedInputs(mem, AnswerService(mem, ai.runner, ai.retriever, { RetrievalSettings() })))
        withTimeout(5_000) { vm.state.first { !it.loading && it.endpointId == "az" } }
        vm.onInputText("where is the milk")
    }

    @After fun tearDown() { vm.cancelScope(); TestGraph.close(ctx) }

    private suspend fun ran() = withTimeout(5_000) { vm.state.first { !it.running && it.result != null } }

    @Test fun saveIsEnabledOnlyAfterAPassingRunOnTheUnmodifiedDraft() = runBlocking {
        vm.onTemplate(vm.state.value.template + "\nBe brief.")
        assertFalse(vm.state.value.canSave)
        vm.run()
        val s = ran()
        assertTrue(s.gates.toString(), s.gates.all); assertTrue(s.canSave)
        assertEquals(listOf("✓ Schema compatible", "✓ Variables", "✓ Ran", "✓ Matches schema", "✓ Fits the contract", "✓ Checks passed"), s.gateLines)
        vm.onTemplate(s.template + " ")
        assertFalse(vm.state.value.canSave)
        assertFalse(vm.state.value.gates.ran)
    }

    @Test fun saveDisabledWhileARequiredVariableIsMissing() = runBlocking {
        vm.onTemplate("Answer: {{questoin}} {{retrieved_memories}}")
        val s = vm.state.value
        assertEquals(listOf("questoin"), s.unknownVariables); assertEquals(setOf("question"), s.missingVariables)
        vm.run(); assertFalse(ran().canSave)
    }

    @Test fun anIncompatibleSchemaBlocksSaveWithANamedProblem() = runBlocking<Unit> {
        vm.onSchema(DefaultSchemas.text(PromptKey.ANSWER)!!.replace("\"maxLength\":600", "\"maxLength\":900"))
        assertEquals(listOf("Loosened maxLength of answer"), withTimeout(5_000) { vm.state.first { it.compatProblems.isNotEmpty() } }.compatProblems)
        vm.run(); assertFalse(ran().canSave)
        vm.resetSchema()
        assertEquals(DefaultSchemas.text(PromptKey.ANSWER), vm.state.value.schema)
        withTimeout(5_000) { vm.state.first { it.compatProblems.isEmpty() } }
    }

    @Test fun savingCreatesVersionTwoThenOverwritesWhenVersioningIsOff() = runBlocking {
        vm.onTemplate(vm.state.value.template + "\nBe brief."); vm.run(); ran(); vm.save()
        assertEquals("Saved as v2", withTimeout(5_000) { vm.state.first { it.notice != null } }.notice)
        assertEquals(2, ai.prompts.active(PromptKey.ANSWER).version); assertTrue(ai.prompts.active(PromptKey.ANSWER).testedOk)
        ai.prompts.settings.setKeepVersions(false)
        vm.onTemplate(vm.state.value.template + "\nEven briefer."); vm.run(); ran(); vm.save()
        assertEquals("Saved over v2", withTimeout(5_000) { vm.state.first { it.notice == "Saved over v2" } }.notice)
        assertEquals(2, ai.prompts.active(PromptKey.ANSWER).version)
    }

    @Test fun aFailedRunShowsShortTextAndDiscardRestoresTheActiveVersion() = runBlocking {
        val original = vm.state.value.template
        reply = { throw ProviderHttpException(401, "Azure OpenAI", "key sk-live-1 rejected") }
        vm.onTemplate("$original!"); vm.run()
        val s = ran()
        assertEquals("AI key rejected", s.result!!.failure); assertFalse(s.gates.ran)
        vm.discard()
        assertEquals(original, vm.state.value.template); assertEquals(null, vm.state.value.result)
    }

    @Test fun screenShowsGatesAndADisabledSave() {
        compose.setContent { PcTheme { PromptEditorScreen(PromptEditorUiState(loading = false, label = "Answer", template = "x", gates = SaveGates(true, false, false, false, false, false)),
            onBack = {}, onTemplate = {}, onSchema = {}, onResetSchema = {}, onInputKind = {}, onInputText = {}, onFrame = {}, onEndpoint = {}, onModel = {},
            onRun = {}, onSave = {}, onDiscard = {}, onToggleWire = {}, onOpenHistory = {}) } }
        compose.onNodeWithTag("prompt_save").assertIsNotEnabled()
        compose.onNodeWithText("✕ Variables").assertExists()
        compose.onNodeWithTag("prompt_template").assertExists()
    }

    /** F19: right after a schema edit the old check no longer counts (no stale ✓), and the new check decides. */
    @Test fun anEditedSchemaIsNotCompatibleUntilItsOwnCheckFinishes() = runBlocking {
        withTimeout(5_000) { vm.state.first { it.gates.compatible } }
        vm.onSchema(DefaultSchemas.text(PromptKey.ANSWER)!!.replace("\"maxLength\":600", "\"maxLength\":900"))
        assertFalse(vm.state.value.gates.compatible)
        withTimeout(5_000) { vm.state.first { it.compatProblems.isNotEmpty() } }
        assertFalse(vm.state.value.gates.compatible)
        vm.resetSchema()
        assertTrue(withTimeout(5_000) { vm.state.first { it.gates.compatible } }.gates.compatible)
    }

    /** F20: a version stored without a schema opens on the default schema and is not an unsaved change. */
    @Test fun aVersionStoredWithoutASchemaOpensUnchanged() = runBlocking {
        val seed = ai.prompts.active(PromptKey.ANSWER).template
        ai.prompts.save(PromptKey.ANSWER, PromptDraft("$seed\nNo schema.", null), "", true)
        assertEquals(null, ai.prompts.active(PromptKey.ANSWER).outputSchema)
        val other = PromptEditorViewModel("ANSWER", ai, TestBedInputs(MemoryGraph.get(ctx), AnswerService(MemoryGraph.get(ctx), ai.runner, ai.retriever, { RetrievalSettings() })))
        val s = withTimeout(5_000) { other.state.first { !it.loading } }
        assertEquals(DefaultSchemas.text(PromptKey.ANSWER), s.schema)
        assertFalse(s.dirty)
        other.cancelScope()
    }

    /** Carried from Tasks 3/5/7: a recursive schema can't be saved, and the run result shows the compatibility verdict beside it. */
    @Test fun aRecursiveSchemaBlocksSaveAndTheRunShowsItIsNotCompatible() = runBlocking {
        val base = DefaultSchemas.forKey(PromptKey.ANSWER)!!
        val looped = JsonObject(base + ("\$defs" to JsonObject(base["\$defs"]!!.jsonObject + ("Node" to Json.parseToJsonElement("""{"${'$'}ref":"#/${'$'}defs/Node"}""")))))
        vm.onSchema(looped.toString())
        val c = withTimeout(5_000) { vm.state.first { it.compatProblems.isNotEmpty() } }
        assertTrue(c.compatProblems.toString(), c.compatProblems.single().startsWith("Recursive schemas aren't supported"))
        vm.run()
        val s = ran()
        assertEquals(null, s.result!!.failure)
        assertFalse(s.canSave)
        assertEquals("✕ Schema not compatible — fix it before saving", s.runCompatLine)
        vm.save()
        assertEquals(1, ai.prompts.active(PromptKey.ANSWER).version)
    }

    @Test fun theRunResultShowsTheCompatibilityVerdict() {
        val ok = TestBedResult("{}", 5, 1, 1, null, null, null)
        compose.setContent { PcTheme { PromptEditorScreen(PromptEditorUiState(loading = false, key = PromptKey.ANSWER, label = "Answer", template = "x", result = ok,
            compatFor = PromptDraft("x", null), compatProblems = listOf("Removed sources")),
            onBack = {}, onTemplate = {}, onSchema = {}, onResetSchema = {}, onInputKind = {}, onInputText = {}, onFrame = {}, onEndpoint = {}, onModel = {},
            onRun = {}, onSave = {}, onDiscard = {}, onToggleWire = {}, onOpenHistory = {}) } }
        compose.onNodeWithTag("prompt_editor").performScrollToNode(hasTestTag("prompt_run_compat"))
        compose.onNodeWithTag("prompt_run_compat").assertTextEquals("✕ Schema not compatible — fix it before saving")
    }

    /** Carried from Task 7: SYSTEM has no schema, so no Test is offered and its preamble saves without a run. */
    @Test fun systemOffersNoTestAndSavesWithoutARun() = runBlocking {
        val sys = PromptEditorViewModel("SYSTEM", ai, TestBedInputs(MemoryGraph.get(ctx), AnswerService(MemoryGraph.get(ctx), ai.runner, ai.retriever, { RetrievalSettings() })))
        val s0 = withTimeout(5_000) { sys.state.first { !it.loading } }
        assertFalse(s0.hasSchema)
        sys.onTemplate(s0.template + "\nBe terse.")
        assertTrue(sys.state.value.canSave)
        sys.run()
        assertEquals(null, sys.state.value.result); assertFalse(sys.state.value.running)
        sys.save()
        assertEquals("Saved as v2", withTimeout(5_000) { sys.state.first { it.notice != null } }.notice)
        assertEquals(2, ai.prompts.active(PromptKey.SYSTEM).version)
        sys.cancelScope()
    }

    @Test fun systemScreenHasNoTestBed() {
        compose.setContent { PcTheme { PromptEditorScreen(PromptEditorUiState(loading = false, key = PromptKey.SYSTEM, label = "System", template = "x"),
            onBack = {}, onTemplate = {}, onSchema = {}, onResetSchema = {}, onInputKind = {}, onInputText = {}, onFrame = {}, onEndpoint = {}, onModel = {},
            onRun = {}, onSave = {}, onDiscard = {}, onToggleWire = {}, onOpenHistory = {}) } }
        compose.onNodeWithTag("prompt_editor").performScrollToNode(hasTestTag("prompt_save"))
        compose.onNodeWithTag("prompt_run").assertDoesNotExist()
        compose.onNodeWithTag("prompt_schema").assertDoesNotExist()
    }
}
