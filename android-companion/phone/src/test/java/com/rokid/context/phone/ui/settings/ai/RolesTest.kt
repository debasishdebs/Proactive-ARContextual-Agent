package com.rokid.context.phone.ui.settings.ai

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.Capability
import com.rokid.context.ai.Endpoint
import com.rokid.context.ai.EndpointKind
import com.rokid.context.ai.Role
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.TestGraph
import com.rokid.context.phone.ui.cancelScope
import com.rokid.context.phone.ui.settings.TestSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class RolesTest {
    @get:Rule val main = MainDispatcherRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var ai: AiGraph
    private lateinit var vm: RolesViewModel
    private var reindexed = 0

    @Before fun setUp() = runBlocking {
        TestGraph.fresh(ctx); ai = TestSettings.ai(ctx)
        ai.endpoints.save(Endpoint("az", "Azure", EndpointKind.AZURE_OPENAI, "https://az.example", null, listOf("gpt", "mini"), setOf(Capability.CHAT, Capability.VISION), 1), "k").getOrThrow()
        ai.endpoints.save(Endpoint("ol", "Ollama", EndpointKind.OLLAMA, "http://localhost:11434", null, listOf("nomic-embed-text"), setOf(Capability.EMBEDDINGS), 2), null).getOrThrow()
        vm = RolesViewModel(ai) { reindexed++ }
    }

    @After fun tearDown() { vm.cancelScope(); TestGraph.close(ctx) }

    private suspend fun row(role: Role) = withTimeout(5_000) { vm.state.first { it.rows.isNotEmpty() } }.rows.single { it.role == role }

    @Test fun pickersListOnlyCapableEndpoints() = runBlocking {
        assertEquals(listOf("az"), row(Role.VISION_EXTRACT).choices.map { it.id })
        assertEquals(listOf("ol"), row(Role.EMBED).choices.map { it.id })
        assertEquals(listOf("az"), row(Role.ANSWER).choices.map { it.id })
    }

    @Test fun choosingAnEndpointBindsItsFirstModelAndEmbedTriggersAReindex() = runBlocking {
        vm.setEndpoint(Role.ANSWER, "az")
        assertEquals("gpt", withTimeout(5_000) { vm.state.first { s -> s.rows.any { it.role == Role.ANSWER && it.endpointId == "az" } } }.rows.single { it.role == Role.ANSWER }.model)
        vm.setModel(Role.ANSWER, "mini")
        withTimeout(5_000) { vm.state.first { s -> s.rows.any { it.role == Role.ANSWER && it.model == "mini" } } }
        assertEquals(0, reindexed)
        vm.setEndpoint(Role.EMBED, "ol")
        withTimeout(5_000) { while (reindexed == 0) delay(10) }
        assertEquals(1, reindexed)
        assertEquals("ol", ai.bindings.get(Role.EMBED)!!.endpointId)
    }

    @Test fun temperatureAndMaxTokensAreValidatedBeforeSaving() = runBlocking {
        vm.setEndpoint(Role.ANSWER, "az"); withTimeout(5_000) { vm.state.first { s -> s.rows.any { it.role == Role.ANSWER && it.endpointId == "az" } } }
        vm.setTemperature(Role.ANSWER, "3")
        withTimeout(5_000) { vm.state.first { s -> s.rows.any { it.role == Role.ANSWER && it.error == "Temperature is 0–2" } } }
        assertNull(ai.bindings.get(Role.ANSWER)!!.temperature)
        vm.setTemperature(Role.ANSWER, "0.7"); vm.setMaxTokens(Role.ANSWER, "256") // two quick edits: neither may overwrite the other
        withTimeout(5_000) { while (ai.bindings.get(Role.ANSWER)?.maxTokens != 256) delay(10) }
        assertEquals(0.7, ai.bindings.get(Role.ANSWER)!!.temperature!!, 0.0)
        assertEquals("0.7", withTimeout(5_000) { vm.state.first { s -> s.rows.any { it.role == Role.ANSWER && it.error == null } } }.rows.single { it.role == Role.ANSWER }.temperature)
        vm.setMaxTokens(Role.ANSWER, "")
        withTimeout(5_000) { while (ai.bindings.get(Role.ANSWER)?.maxTokens != null) delay(10) }
    }

    /** Fix round 1 (M-3): making the fallback the primary clears the fallback. */
    @Test fun choosingTheFallbackAsPrimaryClearsTheFallback() = runBlocking {
        ai.endpoints.save(Endpoint("az2", "Azure 2", EndpointKind.AZURE_OPENAI, "https://az2.example", null, listOf("gpt"), setOf(Capability.CHAT), 3), "k").getOrThrow()
        vm.setEndpoint(Role.ANSWER, "az"); withTimeout(5_000) { while (ai.bindings.get(Role.ANSWER) == null) delay(10) }
        vm.setFallback(Role.ANSWER, "az2"); withTimeout(5_000) { while (ai.bindings.get(Role.ANSWER)?.fallbackEndpointId != "az2") delay(10) }
        vm.setEndpoint(Role.ANSWER, "az2"); withTimeout(5_000) { while (ai.bindings.get(Role.ANSWER)?.endpointId != "az2") delay(10) }
        assertNull(ai.bindings.get(Role.ANSWER)!!.fallbackEndpointId)
        assertNull(ai.bindings.get(Role.ANSWER)!!.fallbackModel)
    }

    /** Fix round 1 (M-4): re-selecting the same EMBED endpoint and model does not reindex. */
    @Test fun reselectingTheSameEmbedBindingDoesNotReindex() = runBlocking {
        vm.setEndpoint(Role.EMBED, "ol")
        withTimeout(5_000) { while (reindexed == 0) delay(10) }
        vm.setEndpoint(Role.EMBED, "ol"); vm.setModel(Role.EMBED, "nomic-embed-text")
        delay(300)
        assertEquals(1, reindexed)
    }
}
