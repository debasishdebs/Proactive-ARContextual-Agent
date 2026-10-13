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
import com.rokid.context.ai.ProviderBuilder
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
}
