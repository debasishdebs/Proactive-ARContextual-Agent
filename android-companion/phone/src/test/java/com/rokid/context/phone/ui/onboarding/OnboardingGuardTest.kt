package com.rokid.context.phone.ui.onboarding

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.Capability
import com.rokid.context.ai.Endpoint
import com.rokid.context.ai.EndpointKind
import com.rokid.context.ai.InMemoryKeyVault
import com.rokid.context.ai.InMemorySeedState
import com.rokid.context.ai.ProviderBuilder
import com.rokid.context.ai.ProviderSettings
import com.rokid.context.ai.Role
import com.rokid.context.ai.RoleBinding
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.model.ModelProvider
import com.rokid.context.phone.ui.TestGraph
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * BUG-R3-1 startup defence: Auto Backup restored `onboarding.xml` (complete) onto a fresh install whose keys and DB
 * were not restored, so the first launch landed on Home with no AI. Onboarding runs again only when all three hold:
 * the flag says complete, no chat endpoint is usable (address + model + key where the kind needs one), and the DB
 * holds no frames or memories.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class OnboardingGuardTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val vault = InMemoryKeyVault()
    private lateinit var ai: AiGraph
    private lateinit var memory: MemoryGraph

    @Before fun setUp() {
        memory = TestGraph.fresh(ctx)
        ai = AiGraph(memory, vault, InMemorySeedState(), ProviderBuilder { _, _, _ -> object : ModelProvider { override suspend fun generate(prompt: String) = "OK" } })
    }
    @After fun tearDown() = TestGraph.close(ctx)

    private fun guard() = OnboardingGuard.of(ai, memory)

    @Test fun theDecisionNeedsAllThree() = runBlocking {
        for (complete in listOf(true, false)) for (usable in listOf(true, false)) for (fresh in listOf(true, false)) {
            val g = OnboardingGuard({ usable }, { fresh })
            assertTrue("$complete/$usable/$fresh", g.mustRerun(complete) == (complete && !usable && fresh))
        }
    }

    @Test fun anIncompleteFlagNeverAsksTheDb() = runBlocking {
        assertFalse(OnboardingGuard({ error("not asked") }, { error("not asked") }).mustRerun(complete = false))
    }

    /** The round-3 state: the seeder recreated the Azure endpoint from the build defaults, with no key, in a new DB. */
    @Test fun aRestoredFlagOverAKeylessSeedAndAnEmptyDbRerunsOnboarding() = runBlocking {
        ai.seeder.seedIfNeeded(ProviderSettings("azure", "https://seed.openai.azure.com", "", "dep", "", false)).getOrThrow()
        assertTrue(guard().mustRerun(complete = true))
    }

    @Test fun nothingBoundAtAllAlsoReruns() = runBlocking {
        assertTrue(guard().mustRerun(complete = true))
    }

    @Test fun aSeedWithAKeyIsUsable() = runBlocking {
        ai.seeder.seedIfNeeded(ProviderSettings("azure", "https://seed.openai.azure.com", "sk-seed-000", "dep", "", false)).getOrThrow()
        assertFalse(guard().mustRerun(complete = true))
    }

    @Test fun aKeylessKindIsUsableWithoutAKey() = runBlocking {
        ai.endpoints.save(Endpoint("ollama", "Ollama", EndpointKind.OLLAMA, "http://box.local:11434", null, listOf("llama3.2"), setOf(Capability.CHAT), 1), null).getOrThrow()
        ai.bindings.set(RoleBinding(Role.ANSWER, "ollama", "llama3.2")).getOrThrow()
        assertFalse(guard().mustRerun(complete = true))
    }

    @Test fun aUsableFallbackCounts() = runBlocking {
        ai.seeder.seedIfNeeded(ProviderSettings("azure", "https://seed.openai.azure.com", "", "dep", "", false)).getOrThrow()
        ai.endpoints.save(Endpoint("ollama", "Ollama", EndpointKind.OLLAMA, "http://box.local:11434", null, listOf("llama3.2"), setOf(Capability.CHAT), 1), null).getOrThrow()
        ai.bindings.set(RoleBinding(Role.ANSWER, "azure", "dep", fallbackEndpointId = "ollama", fallbackModel = "llama3.2")).getOrThrow()
        assertFalse(guard().mustRerun(complete = true))
    }

    /** A user who set up, captured and later lost the key keeps Home (Settings shows the missing key). */
    @Test fun aDbWithMemoriesIsNotFresh() = runBlocking {
        memory.memories.add(MemoryEntity("m1", null, "milk", "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, 1, false, ""))
        assertFalse(guard().mustRerun(complete = true))
    }
}
