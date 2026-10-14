package com.rokid.context.ai.run

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.Capability
import com.rokid.context.ai.Endpoint
import com.rokid.context.ai.EndpointKind
import com.rokid.context.ai.InMemoryKeyVault
import com.rokid.context.ai.InMemorySeedState
import com.rokid.context.ai.ModelCallException
import com.rokid.context.ai.ProviderBuilder
import com.rokid.context.ai.Role
import com.rokid.context.ai.RoleBinding
import com.rokid.context.ai.contract.DefaultSchemas
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.model.CallClass
import com.rokid.context.model.CallGate
import com.rokid.context.model.ModelProvider
import com.rokid.context.model.ModelReply
import com.rokid.context.model.ModelRequest
import com.rokid.context.prompt.PromptDraft
import com.rokid.context.prompt.PromptVars
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CallClassPropagationTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val permits = mutableListOf<CallClass>()
    private var providerRuns = 0
    private var failFirst = false
    /** A budget with zero EXTRACT tokens: only USER passes. */
    private val gate = object : CallGate {
        override suspend fun <T> withPermit(callClass: CallClass, block: suspend () -> T): T {
            permits += callClass
            if (callClass == CallClass.EXTRACT) throw ModelCallException("no tokens", retryable = true)
            return block()
        }
    }
    private lateinit var ai: AiGraph

    @Before fun setUp() {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        ai = AiGraph(MemoryGraph.get(ctx), InMemoryKeyVault(), InMemorySeedState(), ProviderBuilder { _, _, _ ->
            object : ModelProvider {
                override suspend fun generate(prompt: String) = error("unused")
                override suspend fun complete(request: ModelRequest): ModelReply {
                    providerRuns++
                    if (failFirst && providerRuns == 1) error("primary down")
                    return ModelReply("""{"facts":[],"captions":[]}""", 1, 1)
                }
            }
        }, gate = gate)
        runBlocking {
            val caps = setOf(Capability.CHAT, Capability.VISION, Capability.JSON_SCHEMA)
            ai.endpoints.save(Endpoint("a", "A", EndpointKind.OLLAMA, "http://localhost:1", null, listOf("m"), caps, 1), apiKey = null).getOrThrow()
            ai.endpoints.save(Endpoint("b", "B", EndpointKind.OLLAMA, "http://localhost:2", null, listOf("m"), caps, 1), apiKey = null).getOrThrow()
            ai.bindings.set(RoleBinding(Role.VISION_EXTRACT, "a", "m", fallbackEndpointId = "b", fallbackModel = "m")).getOrThrow()
            ai.bindings.set(RoleBinding(Role.ANSWER, "a", "m")).getOrThrow()
        }
    }

    @After fun tearDown() { MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    private fun extract(callClass: CallClass? = null) = RoleCall(PromptKey.VISION_EXTRACT, PromptVars(), fromFrames = true, callClass = callClass)

    @Test fun askTriggeredExtractionIsUser() = runBlocking {
        ai.runner.run(extract(CallClass.USER))
        assertEquals(listOf(CallClass.USER), permits)
        assertEquals(CallClass.EXTRACT, extract().effectiveClass)
        assertEquals(CallClass.NUDGE, RoleCall(PromptKey.NUDGE, PromptVars()).effectiveClass)
        assertEquals(CallClass.USER, RoleCall(PromptKey.ANSWER, PromptVars()).effectiveClass)
    }

    @Test fun testBedIsUser() = runBlocking {
        ai.testBed.run(TestBedRequest(PromptKey.VISION_EXTRACT, PromptDraft("{{recent_context}}", DefaultSchemas.text(PromptKey.VISION_EXTRACT)), "a", "m", PromptVars()))
        assertEquals(listOf(CallClass.USER), permits)
    }

    /** Still the gate-level check: the fallback attempt carries the caller's class too (the real-budget bypass is in UserBypassRealBudgetTest). */
    @Test fun userFallbackKeepsClass() = runBlocking {
        failFirst = true
        ai.runner.run(extract(CallClass.USER)) // primary fails, the fallback still runs
        assertEquals(listOf(CallClass.USER, CallClass.USER), permits)
        assertEquals(2, providerRuns)
        permits.clear()
        val err = runCatching { ai.runner.run(extract()) }.exceptionOrNull()
        assertTrue(err is ModelCallException)
        assertEquals(CallClass.EXTRACT, permits.first())
    }
}
