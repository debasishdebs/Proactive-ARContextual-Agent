package com.rokid.context.ai

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.DatabaseFactory
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EndpointSeederTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val vault = InMemoryKeyVault()
    private val endpoints = EndpointRepository(db.endpointDao(), vault)
    private val bindings = RoleBindingRepository(db.roleBindingDao(), endpoints)
    private val state = InMemorySeedState()
    private val seeder = EndpointSeeder(endpoints, bindings, state)
    @After fun close() = db.close()

    private val azure = ProviderSettings("openai", "https://az.example", "k", "gpt-5.4", nativeModelPath = "", localModeEnabled = false)
    private val textRoles = listOf(Role.EXPLICIT_REMEMBER, Role.SUMMARIZE, Role.CRITIC, Role.ANSWER, Role.NUDGE)

    @Test fun seedsAzureFromCurrentConfigAndBindsRoles() = runTest {
        seeder.seedIfNeeded(azure).getOrThrow()
        val e = endpoints.all().single()
        assertEquals(EndpointKind.AZURE_OPENAI, e.kind); assertEquals("k", endpoints.keyFor(e))
        (textRoles + Role.VISION_EXTRACT).forEach { assertEquals(e.id, bindings.get(it)!!.endpointId) }
        assertNull(bindings.get(Role.EMBED)); assertTrue(state.isDone())
    }

    @Test fun localModeAddsLlamaCppFallbackToTextRolesOnly() = runTest {
        seeder.seedIfNeeded(azure.copy(nativeModelPath = "/m.gguf", localModeEnabled = true)).getOrThrow()
        val local = endpoints.all().single { it.kind == EndpointKind.LLAMACPP_LOCAL }
        textRoles.forEach { assertEquals(local.id, bindings.get(it)!!.fallbackEndpointId) }
        assertNull(bindings.get(Role.VISION_EXTRACT)!!.fallbackEndpointId)
    }

    @Test fun resumesAfterCrashBetweenEndpointAndBindings() = runTest {
        // Simulate a crash after the endpoint was saved and before any binding/marker was written.
        endpoints.save(Endpoint(EndpointSeeder.AZURE_ID, "Azure OpenAI", EndpointKind.AZURE_OPENAI, "https://az.example", "2024-10-21", listOf("gpt-5.4"),
            setOf(Capability.CHAT, Capability.VISION, Capability.JSON_SCHEMA), 1), apiKey = "k").getOrThrow()
        assertFalse(state.isDone())
        seeder.seedIfNeeded(azure).getOrThrow()
        assertEquals(1, endpoints.all().size)
        (textRoles + Role.VISION_EXTRACT).forEach { assertEquals(EndpointSeeder.AZURE_ID, bindings.get(it)!!.endpointId) }
        assertTrue(state.isDone())
    }

    @Test fun repairNeverOverwritesAnExistingBinding() = runTest {
        endpoints.save(Endpoint(EndpointSeeder.AZURE_ID, "Azure OpenAI", EndpointKind.AZURE_OPENAI, "https://az.example", "2024-10-21", listOf("gpt-5.4"),
            setOf(Capability.CHAT, Capability.VISION), 1), apiKey = "k").getOrThrow()
        bindings.set(RoleBinding(Role.ANSWER, EndpointSeeder.AZURE_ID, "custom-model")).getOrThrow()
        seeder.seedIfNeeded(azure).getOrThrow()
        assertEquals("custom-model", bindings.get(Role.ANSWER)!!.model)
        assertEquals("gpt-5.4", bindings.get(Role.SUMMARIZE)!!.model)
    }

    @Test fun invalidConfigFailsWithoutMarkingDone() = runTest {
        assertTrue(seeder.seedIfNeeded(azure.copy(endpoint = "")).isFailure) // EnvDefaults.azureEndpoint is "" in tests
        assertFalse(state.isDone()); assertTrue(endpoints.all().isEmpty())
    }

    @Test fun doneMarkerMakesSeedANoOp() = runTest {
        seeder.seedIfNeeded(ProviderSettings("ollama", "http://o.local:11434", "", "llama3.1:8b", "", false)).getOrThrow()
        seeder.seedIfNeeded(azure).getOrThrow()
        assertEquals(EndpointKind.OLLAMA, endpoints.all().single().kind)
    }

    @Test fun seedingWithBlankKeyDoesNotEraseAKeySavedEarlier() = runTest {
        endpoints.save(Endpoint(EndpointSeeder.AZURE_ID, "Azure OpenAI", EndpointKind.AZURE_OPENAI, "https://az.example", "2024-10-21", listOf("gpt-5.4"),
            setOf(Capability.CHAT, Capability.VISION), 1), apiKey = "saved").getOrThrow()
        seeder.seedIfNeeded(azure.copy(apiKey = "")).getOrThrow()
        assertEquals("saved", endpoints.keyFor(endpoints.byId(EndpointSeeder.AZURE_ID)!!))
    }

    @Test fun legacyConfigRejectedByValidationFailsSoftlyAndLeavesRolesUnbound() = runTest {
        // Real legacy config can hold what the new validation rejects, e.g. a cleartext remote endpoint with a key.
        val secret = "sekret-key-123"
        val r = seeder.seedIfNeeded(azure.copy(endpoint = "http://remote.example", apiKey = secret))
        assertTrue(r.isFailure)
        assertFalse(state.isDone()); assertTrue(endpoints.all().isEmpty())
        Role.entries.forEach { assertNull(bindings.get(it)) }
        val e = runCatching { ModelRouter(bindings, endpoints, ProviderFactory).generate(Role.ANSWER, "q") }.exceptionOrNull() as ModelCallException
        assertTrue(e.retryable); assertTrue(e.message!!.contains("No model is set for ANSWER"))
        val logs = ShadowLog.getLogsForTag("EndpointSeeder")
        assertEquals(1, logs.size)
        assertTrue(logs.none { secret in it.msg || secret in (it.throwable?.message ?: "") })
    }

    @Test fun reseedRestoresKeyLostAfterEndpointRowWasWritten() = runTest {
        seeder.seedIfNeeded(azure).getOrThrow()
        vault.remove(EndpointRepository.keyRef(EndpointSeeder.AZURE_ID))
        assertNull(vault.get(EndpointRepository.keyRef(EndpointSeeder.AZURE_ID)))
        state2().seedIfNeeded(azure).getOrThrow()
        assertEquals("k", vault.get(EndpointRepository.keyRef(EndpointSeeder.AZURE_ID)))
    }

    private fun state2() = EndpointSeeder(endpoints, bindings, InMemorySeedState())

    private val ollama = ProviderSettings("ollama", "http://o.local:11434", "k", "llama3.1:8b", "", false)

    /** Fix round 1 (T21 review I2): nothing is retired before the seed is done. */
    @Test fun nothingIsRetiredBeforeTheSeedIsDone() = runTest {
        assertFalse(seeder.retireLegacyKey(azure))
        assertFalse(seeder.retireLegacyKey(ollama))
    }

    /** Keyless kinds can never take the key, so once seeded it is cleared without a vault copy. */
    @Test fun aKeylessSeedRetiresTheKeyWithoutStoringIt() = runTest {
        seeder.seedIfNeeded(ollama).getOrThrow()
        assertTrue(seeder.retireLegacyKey(ollama))
        assertNull(endpoints.keyFor(endpoints.byId(EndpointSeeder.OLLAMA_ID)!!))
    }

    /** A seed that finished earlier without the key (Keystore unavailable then): the key is moved in, then retired. */
    @Test fun anEarlierKeylessSeedOfAKeyedKindGetsTheKeyCopiedIn() = runTest {
        seeder.seedIfNeeded(azure.copy(apiKey = "")).getOrThrow()
        assertNull(endpoints.keyFor(endpoints.byId(EndpointSeeder.AZURE_ID)!!))
        assertTrue(seeder.retireLegacyKey(azure))
        assertEquals("k", endpoints.keyFor(endpoints.byId(EndpointSeeder.AZURE_ID)!!))
    }

    @Test fun aVaultKeyIsNeverOverwrittenAndTheLegacyKeyIsRetired() = runTest {
        seeder.seedIfNeeded(azure.copy(apiKey = "")).getOrThrow()
        endpoints.save(endpoints.byId(EndpointSeeder.AZURE_ID)!!, apiKey = "edited").getOrThrow()
        assertTrue(seeder.retireLegacyKey(azure))
        assertEquals("edited", endpoints.keyFor(endpoints.byId(EndpointSeeder.AZURE_ID)!!))
    }

    /** F23, kept for this one case: a blank read on a keyed kind (Keystore unavailable) never deletes the only copy. */
    @Test fun aBlankReadOnAKeyedKindRetiresNothing() = runTest {
        seeder.seedIfNeeded(azure).getOrThrow()
        assertFalse(seeder.retireLegacyKey(azure.copy(apiKey = " ")))
    }

    /** The user deleted the seeded endpoint: nothing uses the legacy key any more. */
    @Test fun aDeletedSeededEndpointRetiresTheKey() = runTest {
        seeder.seedIfNeeded(azure).getOrThrow()
        endpoints.delete(EndpointSeeder.AZURE_ID)
        assertTrue(seeder.retireLegacyKey(azure))
    }
}
