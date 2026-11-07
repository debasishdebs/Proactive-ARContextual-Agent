package com.rokid.context.work.budget

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
import com.rokid.context.ai.Role
import com.rokid.context.ai.RoleBinding
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.ai.run.RoleCall
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.model.CallClass
import com.rokid.context.model.ModelProvider
import com.rokid.context.model.ModelReply
import com.rokid.context.model.ModelRequest
import com.rokid.context.prompt.PromptVars
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The REAL [CallBudget] with every background token spent: a USER call still runs at once, a background one waits. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class UserBypassRealBudgetTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val budget = CallBudget({ 0L }, { 10_000L }) // frozen clock: nothing is ever minted
    private var providerRuns = 0
    private lateinit var ai: AiGraph

    @Before fun setUp() {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        ai = AiGraph(MemoryGraph.get(ctx), InMemoryKeyVault(), InMemorySeedState(), ProviderBuilder { _, _, _ ->
            object : ModelProvider {
                override suspend fun generate(prompt: String) = error("unused")
                override suspend fun complete(request: ModelRequest): ModelReply { providerRuns++; return ModelReply("""{"facts":[],"captions":[]}""", 1, 1) }
            }
        }, gate = budget)
        runBlocking {
            val caps = setOf(Capability.CHAT, Capability.VISION, Capability.JSON_SCHEMA)
            ai.endpoints.save(Endpoint("a", "A", EndpointKind.OLLAMA, "http://localhost:1", null, listOf("m"), caps, 1), apiKey = null).getOrThrow()
            ai.bindings.set(RoleBinding(Role.VISION_EXTRACT, "a", "m")).getOrThrow()
        }
    }

    @After fun tearDown() { MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    private fun extract(c: CallClass) = RoleCall(PromptKey.VISION_EXTRACT, PromptVars(), images = listOf(com.rokid.context.model.ModelImageInput("image/jpeg", "AAAA")), fromFrames = true, callClass = c) // codex r9 P1-2: always with its photo

    @Test fun userBypassesWhenBackgroundTokensExhausted() = runBlocking {
        repeat(2) { budget.withPermit(CallClass.EXTRACT) {} } // the burst of 2 is spent
        assertNull("a background call now waits for a token", withTimeoutOrNull(300) { ai.runner.run(extract(CallClass.EXTRACT)) })
        assertEquals(0, providerRuns)
        assertNotNull(withTimeoutOrNull(5_000) { ai.runner.run(extract(CallClass.USER)) })
        assertEquals(1, providerRuns)
        assertEquals(1, budget.stats.value.callsLastHour[CallClass.USER])
    }
}
