package com.rokid.context.orchestration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.EndpointKind
import com.rokid.context.ai.EndpointSeeder
import com.rokid.context.ai.InMemoryKeyVault
import com.rokid.context.ai.InMemorySeedState
import com.rokid.context.ai.ProviderBuilder
import com.rokid.context.ai.Role
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.model.ModelProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LegacyConfigBridgeTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val usedKeys = mutableListOf<String?>()
    private val usedEndpoints = mutableListOf<String>()
    private lateinit var ai: AiGraph

    @Before fun setUp() {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        ai = AiGraph(MemoryGraph.get(ctx), InMemoryKeyVault(), InMemorySeedState(), ProviderBuilder { e, model, key ->
            object : ModelProvider { override suspend fun generate(prompt: String): String { usedKeys += key; usedEndpoints += e.id; return "${e.id}/$model" } }
        })
        AiGraph.resetForTests(ai)
    }

    @After fun tearDown() { AiGraph.resetForTests(null); MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    @Test fun savingTheConfigScreenChangesWhatWorkersUse() = runBlocking {
        AppConfigStore.setProvider(ctx, "openai", "https://az.example", "key-1", "gpt-5.4", "")
        assertEquals("azure/gpt-5.4", ai.router.generate(Role.ANSWER, "q"))
        AppConfigStore.setProvider(ctx, "openai", "https://az.example", "key-2", "gpt-6", "")
        assertEquals("azure/gpt-6", ai.router.generate(Role.ANSWER, "q"))
        assertEquals("key-2", usedKeys.last())
    }

    @Test fun clearingTheKeyOnTheConfigScreenStopsWorkersUsingTheOldKey() = runBlocking {
        AppConfigStore.setProvider(ctx, "openai", "https://az.example", "key-1", "gpt-5.4", "")
        assertEquals("azure/gpt-5.4", ai.router.generate(Role.ANSWER, "q"))
        AppConfigStore.setProvider(ctx, "openai", "https://az.example", "", "gpt-5.4", "")
        assertEquals(null, ai.endpoints.keyFor(ai.endpoints.byId(EndpointSeeder.AZURE_ID)!!))
        val e = runCatching { ai.router.generate(Role.ANSWER, "q") }.exceptionOrNull() as com.rokid.context.ai.ModelCallException
        assertEquals(false, e.retryable)
        assertTrue(e.message!!.contains("No API key saved"))
        assertEquals(listOf<String?>("key-1"), usedKeys) // the old key was never sent after it was cleared
    }

    @Test fun switchingProviderRebinds() = runBlocking {
        AppConfigStore.setProvider(ctx, "openai", "https://az.example", "k", "gpt-5.4", "")
        AppConfigStore.setProvider(ctx, "ollama", "box.local:11434", "", "llama3.1:8b", "")
        assertEquals(EndpointSeeder.OLLAMA_ID, ai.bindings.get(Role.ANSWER)!!.endpointId)
        assertEquals(null, ai.bindings.get(Role.VISION_EXTRACT))
        usedEndpoints.clear()
        ai.router.generateFromFrames("q", listOf(com.rokid.context.model.ModelImageInput("image/jpeg", "AAAA")))
        assertEquals(listOf(EndpointSeeder.OLLAMA_ID), usedEndpoints) // zero Azure calls for frames
        assertEquals(EndpointKind.OLLAMA, ai.endpoints.byId(EndpointSeeder.OLLAMA_ID)!!.kind)
        assertEquals("http://box.local:11434", ai.endpoints.byId(EndpointSeeder.OLLAMA_ID)!!.baseUrl)
    }

    @Test fun localModeToggleAddsAndRemovesFallback() = runBlocking {
        AppConfigStore.setProvider(ctx, "openai", "https://az.example", "k", "gpt-5.4", "/m.gguf")
        AppConfigStore.setLocalModeEnabled(ctx, true)
        assertEquals(EndpointSeeder.LOCAL_ID, ai.bindings.get(Role.ANSWER)!!.fallbackEndpointId)
        AppConfigStore.setLocalModeEnabled(ctx, false)
        assertEquals(null, ai.bindings.get(Role.ANSWER)!!.fallbackEndpointId)
    }

    @Test fun halfTypedAddressLeavesLastGoodBindingInPlace() = runBlocking {
        AppConfigStore.setProvider(ctx, "openai", "https://az.example", "k", "gpt-5.4", "")
        AppConfigStore.setProvider(ctx, "openai", "https://az exa", "k", "gpt-5.4", "") // user mid-typing a space
        assertEquals("https://az.example", ai.endpoints.byId(EndpointSeeder.AZURE_ID)!!.baseUrl)
        assertTrue(LegacyConfigBridge.sync(ctx).isFailure)
    }

    @Test fun rejectedInputReturnsShortMessageWithoutTheKeyAndWritesNothing() = runBlocking {
        val r = AppConfigStore.setProvider(ctx, "openai", "http://az.example", "sekret-key-9", "gpt-5.4", "")
        assertTrue(r.isFailure)
        assertEquals("Azure needs https://", r.exceptionOrNull()!!.message)
        assertNull(ai.endpoints.byId(EndpointSeeder.AZURE_ID)) // no endpoint row without its key
        assertNull(ai.bindings.get(Role.ANSWER))
    }

    @Test fun nonAsciiKeyFailsWithoutLeakingItOrChangingTheRegistry() = runBlocking {
        AppConfigStore.setProvider(ctx, "openai", "https://az.example", "good-key", "gpt-5.4", "")
        val r = AppConfigStore.setProvider(ctx, "openai", "https://other.example", "kéy-secret", "gpt-6", "")
        assertTrue(r.isFailure)
        val msg = r.exceptionOrNull()!!.message!!
        assertFalse(msg.contains("secret"))
        val e = ai.endpoints.byId(EndpointSeeder.AZURE_ID)!!
        assertEquals("https://az.example", e.baseUrl)
        assertEquals("good-key", ai.endpoints.keyFor(e))
        assertEquals("gpt-5.4", ai.bindings.get(Role.ANSWER)!!.model)
    }

    @Test fun validInputReturnsSuccess() = runBlocking {
        assertTrue(AppConfigStore.setProvider(ctx, "openai", "https://az.example", "k", "gpt-5.4", "").isSuccess)
    }

    @Test fun textEditsWithoutRegistrySyncLeaveRegistryUntilExplicitSync() = runBlocking {
        AppConfigStore.setProvider(ctx, "openai", "https://az.example", "k", "gpt-5.4", "")
        val r = AppConfigStore.setProvider(ctx, "openai", "https://az.exampl", "k", "gpt-5.4", "", syncRegistry = false)
        assertTrue(r.isSuccess)
        assertEquals("https://az.example", ai.endpoints.byId(EndpointSeeder.AZURE_ID)!!.baseUrl)
        assertTrue(LegacyConfigBridge.sync(ctx).isSuccess)
        assertEquals("https://az.exampl", ai.endpoints.byId(EndpointSeeder.AZURE_ID)!!.baseUrl)
    }

    @Test fun localModeToggleWithoutRegistrySyncLeavesBindingsUntilExplicitSync() = runBlocking {
        AppConfigStore.setProvider(ctx, "openai", "https://az.example", "k", "gpt-5.4", "/m.gguf")
        AppConfigStore.setLocalModeEnabled(ctx, false)
        AppConfigStore.setLocalModeEnabled(ctx, true, syncRegistry = false)
        assertEquals(null, ai.bindings.get(Role.ANSWER)!!.fallbackEndpointId)
        LegacyConfigBridge.sync(ctx)
        assertEquals(EndpointSeeder.LOCAL_ID, ai.bindings.get(Role.ANSWER)!!.fallbackEndpointId)
    }
}
