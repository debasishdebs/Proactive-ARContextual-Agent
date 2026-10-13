package com.rokid.context.phone.ui.settings.ai

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.Capability
import com.rokid.context.ai.EndpointKind
import com.rokid.context.ai.ProviderHttpException
import com.rokid.context.ai.Role
import com.rokid.context.ai.RoleBinding
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.TestGraph
import com.rokid.context.phone.ui.cancelScope
import com.rokid.context.phone.ui.settings.TestSettings
import com.rokid.context.phone.ui.shell.Routes
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

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class EndpointEditorTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val compose = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private var fail = false
    private lateinit var ai: AiGraph
    private val vms = mutableListOf<EndpointEditorViewModel>()

    @Before fun setUp() {
        TestGraph.fresh(ctx)
        ai = TestSettings.ai(ctx) { if (fail) throw ProviderHttpException(401, "Azure OpenAI", "Incorrect key sk-live-999") else "OK" }
    }

    @After fun tearDown() { vms.forEach { it.cancelScope() }; TestGraph.close(ctx) }

    private fun vm(id: String = Routes.NEW_ENDPOINT) = EndpointEditorViewModel(id, ai) { "ep_test" }.also { vms += it }
    private suspend fun idle(vm: EndpointEditorViewModel) = withTimeout(5_000) { vm.state.first { !it.busy } }

    private fun fillAzure(vm: EndpointEditorViewModel) {
        vm.onKind(EndpointKind.AZURE_OPENAI); vm.onName("  Work Azure "); vm.onBaseUrl(" My-Res.OpenAI.Azure.com/ "); vm.onModels("gpt-5.4, gpt-4o-mini")
        vm.onKey(" sk-live-123 ")
    }

    @Test fun savingNormalisesStoresTheKeyAndClearsTheBuffer() = runBlocking {
        val vm = vm(); fillAzure(vm); vm.save()
        val s = idle(vm)
        assertNull(s.error); assertTrue(s.keySaved); assertEquals("", s.keyInput)
        assertFalse(s.toString().contains("sk-live-123"))
        val e = ai.endpoints.byId("ep_test")!!
        assertEquals("https://my-res.openai.azure.com", e.baseUrl); assertEquals("Work Azure", e.name)
        assertEquals(listOf("gpt-5.4", "gpt-4o-mini"), e.models); assertEquals("sk-live-123", ai.endpoints.keyFor(e))
    }

    @Test fun validatorMessagesAreShownAndNothingIsStored() = runBlocking {
        val vm = vm(); vm.onKind(EndpointKind.OPENAI_COMPAT); vm.onBaseUrl("http://api.example.com"); vm.onModels("m"); vm.onKey("k")
        vm.save()
        assertEquals("Use https:// when sending a key", idle(vm).error)
        assertNull(ai.endpoints.byId("ep_test"))
    }

    @Test fun testSavesPendingEditsThenShowsShortTextOnly() = runBlocking {
        val vm = vm(); fillAzure(vm)
        fail = true; vm.test()
        val s = withTimeout(5_000) { vm.state.first { it.testResult.contains(" ms") && !it.busy } }
        assertTrue(s.testResult, s.testResult.startsWith("Key rejected (401) · "))
        assertFalse(s.testResult.contains("sk-live") || s.testResult.contains("Incorrect"))
        assertEquals("https://my-res.openai.azure.com", ai.endpoints.byId("ep_test")!!.baseUrl) // Test used what it saved
        fail = false; vm.test()
        assertTrue(withTimeout(5_000) { vm.state.first { it.testResult.startsWith("OK") && !it.busy } }.testResult.endsWith(" ms"))
    }

    @Test fun keylessKindsHideTheKeyAndDropAStoredOne() = runBlocking {
        val vm = vm(); fillAzure(vm); vm.save(); idle(vm)
        vm.onKind(EndpointKind.OLLAMA); vm.onBaseUrl("localhost:11434"); vm.onModels("llama3.1:8b")
        assertTrue(vm.state.value.keyless)
        vm.save(); val s = idle(vm)
        assertNull(s.error); assertFalse(s.keySaved)
        assertNull(ai.endpoints.keyFor(ai.endpoints.byId("ep_test")!!))
    }

    @Test fun deleteIsBlockedWhileARoleUsesTheEndpoint() = runBlocking {
        val vm = vm(); fillAzure(vm); vm.onCapability(Capability.CHAT, true); vm.save(); idle(vm)
        ai.bindings.set(RoleBinding(Role.ANSWER, "ep_test", "gpt-5.4")).getOrThrow()
        vm.delete()
        assertEquals("Used by Answer — choose another model there first", idle(vm).error)
        ai.bindings.clear(Role.ANSWER)
        vm.delete()
        assertTrue(withTimeout(5_000) { vm.state.first { it.deleted } }.deleted)
        assertNull(ai.endpoints.byId("ep_test"))
    }

    @Test fun unknownIdShowsNotFound() = runBlocking {
        assertTrue(withTimeout(5_000) { vm("gone").state.first { !it.loading } }.notFound)
    }

    @Test fun screenHasTheFieldsTestAndAWriteOnlyKey() {
        compose.setContent { PcTheme { EndpointEditorScreen(EndpointEditorUiState(loading = false, keySaved = true, kind = EndpointKind.AZURE_OPENAI), onBack = {}, onName = {}, onKind = {},
            onBaseUrl = {}, onApiVersion = {}, onModels = {}, onCapability = { _, _ -> }, onKey = {}, onClearKey = {}, onTestModel = {}, onSave = {}, onTest = {}, onDelete = {}) } }
        listOf("endpoint_name", "endpoint_url", "endpoint_models", "endpoint_key", "endpoint_save", "endpoint_test").forEach { compose.onNodeWithTag(it).assertExists() }
        compose.onNodeWithText("Saved — type to replace").assertExists()
    }

    /** Keys are write-only: reopening an endpoint with a stored key says so, but the key never enters UI state. */
    @Test fun reopeningAnEndpointNeverLoadsTheStoredKey() = runBlocking {
        val first = vm(); fillAzure(first); first.save(); idle(first)
        val s = withTimeout(5_000) { vm("ep_test").state.first { !it.loading } }
        assertTrue(s.keySaved); assertEquals("", s.keyInput)
        assertFalse(s.toString().contains("sk-live-123"))
    }

    @Test fun aSavedKeyShowsAKeySavedPlaceholder() {
        compose.setContent { PcTheme { EndpointEditorScreen(EndpointEditorUiState(loading = false, keySaved = true, kind = EndpointKind.AZURE_OPENAI), onBack = {}, onName = {}, onKind = {},
            onBaseUrl = {}, onApiVersion = {}, onModels = {}, onCapability = { _, _ -> }, onKey = {}, onClearKey = {}, onTestModel = {}, onSave = {}, onTest = {}, onDelete = {}) } }
        compose.onNodeWithTag("endpoint_key").requestFocus()
        compose.onNodeWithText("Key saved").assertExists()
    }
}
