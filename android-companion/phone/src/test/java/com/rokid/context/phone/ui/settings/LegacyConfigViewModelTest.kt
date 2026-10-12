package com.rokid.context.phone.ui.settings

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.InMemoryKeyVault
import com.rokid.context.ai.InMemorySeedState
import com.rokid.context.ai.ProviderBuilder
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.model.ModelProvider
import com.rokid.context.phone.ui.FakePipelineControl
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.cancelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class) // TestCoroutineScheduler.advanceTimeBy
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class LegacyConfigViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val models = mutableListOf<String>()
    private var fail = false
    private val pipeline = FakePipelineControl()
    private lateinit var vm: LegacyConfigViewModel

    @Before fun setUp() {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        AiGraph.resetForTests(AiGraph(MemoryGraph.get(ctx), InMemoryKeyVault(), InMemorySeedState(), ProviderBuilder { _, model, _ ->
            object : ModelProvider {
                override suspend fun generate(prompt: String): String {
                    models += model
                    if (fail) throw RuntimeException("request failed: 401 {\"error\":{\"message\":\"Incorrect API key provided: sk-live-999\"}}")
                    return "OK"
                }
            }
        }))
        AppConfigStore.setProvider(ctx, "openai", "https://az.example", "key-1", "gpt-5.4", "")
        vm = LegacyConfigViewModel(ctx, pipeline)
    }

    @After fun tearDown() { vm.cancelScope(); AiGraph.resetForTests(null); MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    private suspend fun testResult() = withTimeout(10_000) { vm.state.first { it.config.testResult.contains(" ms") || it.config.testResult == ConfigTestText.NOT_SET_UP } }.config.testResult

    @Test fun loadsSavedSettings() {
        val c = vm.state.value.config
        assertEquals("https://az.example", c.endpoint); assertEquals("gpt-5.4", c.deploymentOrModel); assertEquals("Not tested", c.testResult)
    }

    @Test fun testButtonShowsShortMessageOnly() = runBlocking {
        fail = true
        vm.testConnectivity()
        val text = testResult()
        assertTrue(text, text.startsWith("Key rejected (401) · "))
        assertFalse(text.contains("sk-live") || text.contains("Incorrect"))
    }

    @Test fun testUsesTheModelJustTypedWithoutWaitingForTheDebounce() = runBlocking {
        vm.updateModel("gpt-6")
        vm.testConnectivity()
        assertTrue(testResult().startsWith("OK · "))
        assertEquals("gpt-6", models.last())
    }

    @Test fun halfTypedAddressShowsTheNotAppliedNotice() = runBlocking {
        vm.updateEndpoint("https://az exa")
        main.dispatcher.scheduler.advanceTimeBy(LegacyConfigViewModel.TYPING_DEBOUNCE_MS + 100)
        assertEquals(LegacyConfigViewModel.NOT_APPLIED, withTimeout(10_000) { vm.state.first { it.notice != null } }.notice)
    }

    @Test fun pipelineToggleUsesTheSharedControl() {
        vm.togglePipeline(false)
        assertFalse(pipeline.enabled.value)
        assertFalse(vm.state.value.config.pipelineEnabled)
    }

    private suspend fun storedKeyBecomes(expected: String) = withTimeout(10_000) { while (AppConfigStore.get(ctx).apiKey != expected) kotlinx.coroutines.delay(20) }
    private fun assertKeyNotInState(key: String) = assertFalse(vm.state.value.toString().contains(key))

    @Test fun openingWithAStoredKeyShowsAnEmptyFieldAndTheSavedHint() {
        assertEquals("", vm.state.value.config.apiKeyMasked)
        assertTrue(vm.state.value.config.apiKeySaved)
        assertKeyNotInState("key-1")
    }

    @Test fun firstKeyTypedOrPastedIsSaved() = runBlocking {
        AppConfigStore.setProvider(ctx, "openai", "https://az.example", "", "gpt-5.4", "")
        vm = LegacyConfigViewModel(ctx, pipeline)
        assertFalse(vm.state.value.config.apiKeySaved)
        vm.updateApiKey("sk-first-123")
        storedKeyBecomes("sk-first-123")
    }

    @Test fun editingOtherFieldsKeepsTheStoredKey() = runBlocking {
        vm.updateEndpoint("https://other.example")
        main.dispatcher.scheduler.advanceTimeBy(LegacyConfigViewModel.TYPING_DEBOUNCE_MS + 100)
        withTimeout(10_000) { while (AppConfigStore.get(ctx).endpoint != "https://other.example") kotlinx.coroutines.delay(20) }
        assertEquals("key-1", AppConfigStore.get(ctx).apiKey)
    }

    @Test fun typingANewKeyReplacesItAndClearsTheBufferAfterSync() = runBlocking {
        vm.updateApiKey("new-key")
        storedKeyBecomes("new-key")
        main.dispatcher.scheduler.advanceTimeBy(LegacyConfigViewModel.TYPING_DEBOUNCE_MS + 100)
        withTimeout(10_000) { vm.state.first { it.config.apiKeyMasked.isEmpty() && it.config.apiKeySaved } }
        assertEquals("new-key", AppConfigStore.get(ctx).apiKey)
        assertKeyNotInState("key-1")
    }

    @Test fun clearKeyRemovesIt() = runBlocking {
        vm.clearApiKey()
        storedKeyBecomes("")
        assertFalse(vm.state.value.config.apiKeySaved)
    }

    @Test fun testNeverReportsOkWhenTheSyncFailed() = runBlocking {
        vm.updateEndpoint("https://az exa")
        vm.testConnectivity()
        val s = withTimeout(10_000) { vm.state.first { it.config.testResult != "Testing…" && it.config.testResult != "Not tested" } }
        assertEquals(ConfigTestText.NOT_APPLIED, s.config.testResult)
        assertEquals(LegacyConfigViewModel.NOT_APPLIED, s.notice)
        assertEquals(emptyList<String>(), models)
    }
}
