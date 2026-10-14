package com.rokid.context.phone.ui.onboarding

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.EndpointRepository
import com.rokid.context.ai.InMemoryKeyVault
import com.rokid.context.ai.InMemorySeedState
import com.rokid.context.ai.EndpointSeeder
import com.rokid.context.ai.ProviderBuilder
import com.rokid.context.ai.ProviderSettings
import com.rokid.context.ai.RoleBinding
import com.rokid.context.ai.Role
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.model.ModelProvider
import com.rokid.context.phone.ui.TestGraph
import com.rokid.context.phone.ui.shell.Routes
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class OnboardingKeyAndStoreTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val vault = InMemoryKeyVault()
    private lateinit var ai: AiGraph

    @Before fun setUp() {
        TestGraph.fresh(ctx)
        ai = AiGraph(MemoryGraph.get(ctx), vault, InMemorySeedState(), ProviderBuilder { _, _, _ -> object : ModelProvider { override suspend fun generate(prompt: String) = "OK" } })
    }
    @After fun tearDown() = TestGraph.close(ctx)

    @Test fun keyNeverInLogsOrRoom() = runBlocking {
        val secret = "sk-SECRET-0123456789"
        val port = AiGraphOnboardingAi { ai }
        val draft = EndpointDraft(AiPreset.OPENAI, "https://api.example.com/v1", null, "gpt-4o-mini", secret)
        val r = port.testDraft(draft).getOrThrow()
        assertTrue(r.ok)
        port.use(AiPreset.OPENAI.endpointId)
        // The key is in the vault ...
        assertEquals(secret, vault.get(EndpointRepository.keyRef(AiPreset.OPENAI.endpointId)))
        // ... and nowhere in Room, the domain objects or the logs.
        val rows = MemoryGraph.get(ctx).db.endpointDao().all()
        assertTrue(rows.isNotEmpty())
        assertFalse(rows.toString().contains(secret))
        assertFalse(ai.endpoints.all().toString().contains(secret))
        assertFalse(ShadowLog.getLogs().any { it.msg.orEmpty().contains(secret) })
        // The chosen endpoint now serves the chat roles.
        assertNotNull(ai.bindings.get(Role.ANSWER))
    }

    // Final review I1: the seeder has already bound every chat role; a preset the user picked and tested must win.
    private suspend fun seedAzure() {
        ai.seeder.seedIfNeeded(ProviderSettings("azure", "https://seed.openai.azure.com", "sk-seed-000", "dep", "", false)).getOrThrow()
        assertEquals(EndpointSeeder.AZURE_ID, ai.bindings.get(Role.ANSWER)?.endpointId)
    }

    private suspend fun testOllama(port: AiGraphOnboardingAi) =
        assertTrue(port.testDraft(EndpointDraft(AiPreset.OLLAMA, "http://mybox.local:11434", null, "llama3.2", null)).getOrThrow().ok)

    @Test fun aTestedPresetChoiceReplacesTheSeedersBindings() = runBlocking {
        seedAzure()
        val port = AiGraphOnboardingAi { ai }
        testOllama(port)
        port.use(AiPreset.OLLAMA.endpointId, chosen = true)
        for (r in listOf(Role.ANSWER, Role.SUMMARIZE, Role.CRITIC, Role.NUDGE, Role.EXPLICIT_REMEMBER)) {
            assertEquals(r.name, AiPreset.OLLAMA.endpointId, ai.bindings.get(r)?.endpointId)
            assertEquals(r.name, "llama3.2", ai.bindings.get(r)?.model)
        }
        // Ollama has no VISION: that role keeps the seed rather than going unbound.
        assertEquals(EndpointSeeder.AZURE_ID, ai.bindings.get(Role.VISION_EXTRACT)?.endpointId)
    }

    @Test fun aBindingTheUserMadeInSettingsIsKept() = runBlocking {
        seedAzure()
        // Settings: ANSWER tuned (temperature), SUMMARIZE moved to the seed with another model.
        ai.bindings.set(RoleBinding(Role.ANSWER, EndpointSeeder.AZURE_ID, "dep", temperature = 0.2)).getOrThrow()
        ai.bindings.set(RoleBinding(Role.SUMMARIZE, EndpointSeeder.AZURE_ID, "other-dep")).getOrThrow()
        val port = AiGraphOnboardingAi { ai }
        testOllama(port)
        port.use(AiPreset.OLLAMA.endpointId, chosen = true)
        assertEquals(RoleBinding(Role.ANSWER, EndpointSeeder.AZURE_ID, "dep", temperature = 0.2), ai.bindings.get(Role.ANSWER))
        assertEquals(RoleBinding(Role.SUMMARIZE, EndpointSeeder.AZURE_ID, "other-dep"), ai.bindings.get(Role.SUMMARIZE))
        assertEquals(AiPreset.OLLAMA.endpointId, ai.bindings.get(Role.CRITIC)?.endpointId)
    }

    @Test fun useSeedOnlyFillsUnboundRoles() = runBlocking {
        seedAzure()
        val port = AiGraphOnboardingAi { ai }
        testOllama(port)
        port.use(AiPreset.OLLAMA.endpointId, chosen = false)
        assertEquals(EndpointSeeder.AZURE_ID, ai.bindings.get(Role.ANSWER)?.endpointId)
    }

    @Test fun invalidDraftFailsWithoutStoringAKey() = runBlocking {
        val port = AiGraphOnboardingAi { ai }
        val r = port.testDraft(EndpointDraft(AiPreset.OPENAI, "not a url", null, "m", "sk-x"))
        assertTrue(r.isFailure)
        assertEquals(null, vault.get(EndpointRepository.keyRef(AiPreset.OPENAI.endpointId)))
    }

    @Test fun completedPhase4UserStartsAtHome() {
        // Phase 4 wrote exactly this prefs file and flag.
        ctx.getSharedPreferences("onboarding", Context.MODE_PRIVATE).edit().putBoolean("v1_complete", true).commit()
        assertEquals(Routes.HOME, Routes.startRoute(OnboardingStore(ctx).isComplete()))
        ctx.getSharedPreferences("onboarding", Context.MODE_PRIVATE).edit().clear().commit()
        assertEquals(Routes.ONBOARDING, Routes.startRoute(OnboardingStore(ctx).isComplete()))
    }

    @Test fun durableCompletionWritesTheSameFlag() = runBlocking {
        OnboardingStore(ctx).markCompleteDurably()
        assertTrue(ctx.getSharedPreferences("onboarding", Context.MODE_PRIVATE).getBoolean("v1_complete", false))
    }

    /** BUG-R3-2: the build seed (endpoint + deployment, no key) takes its key in the wizard, through the vault only. */
    @Test fun aKeylessSeedTakesItsKeyThroughTheVaultOnly() = runBlocking {
        ai.seeder.seedIfNeeded(ProviderSettings("azure", "https://seed.openai.azure.com", "", "dep", "", false)).getOrThrow()
        val port = AiGraphOnboardingAi { ai }
        val seed = port.seeded()!!
        assertTrue(seed.needsKey)
        val secret = "sk-SEED-0123456789"
        assertTrue(port.testSeed(seed, secret).ok)
        assertEquals(secret, vault.get(EndpointRepository.keyRef(EndpointSeeder.AZURE_ID)))
        assertFalse(port.seeded()!!.needsKey)
        assertFalse(MemoryGraph.get(ctx).db.endpointDao().all().toString().contains(secret))
        assertFalse(ShadowLog.getLogs().any { it.msg.orEmpty().contains(secret) })
        // A seed Test without a typed key keeps the stored one.
        assertTrue(port.testSeed(seed.copy(needsKey = false), null).ok)
        assertEquals(secret, vault.get(EndpointRepository.keyRef(EndpointSeeder.AZURE_ID)))
    }
}
