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
import com.rokid.context.ai.ProviderBuilder
import com.rokid.context.ai.ProviderHttpException
import com.rokid.context.ai.Role
import com.rokid.context.ai.RoleBinding
import com.rokid.context.ai.contract.DefaultSchemas
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.model.ModelProvider
import com.rokid.context.model.ModelReply
import com.rokid.context.model.ModelRequest
import com.rokid.context.prompt.PromptDraft
import com.rokid.context.prompt.PromptVars
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TestBedTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private var reply: () -> String = { """{"answer":"Fridge","sources":["m1"]}""" }
    private val used = mutableListOf<String>()
    private lateinit var ai: AiGraph

    @Before fun setUp() {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        ai = AiGraph(MemoryGraph.get(ctx), InMemoryKeyVault(), InMemorySeedState(), ProviderBuilder { e, m, _ ->
            object : ModelProvider {
                override suspend fun generate(prompt: String) = error("unused")
                override suspend fun complete(request: ModelRequest): ModelReply { used += "${e.id}/$m:${request.prompt.contains("DRAFT")}"; return ModelReply(reply(), 7, 3) }
            }
        })
        runBlocking {
            ai.endpoints.save(Endpoint("az", "Azure", EndpointKind.AZURE_OPENAI, "https://az.example", "2024-10-21", listOf("gpt"), setOf(Capability.CHAT, Capability.JSON_SCHEMA), 1), apiKey = "k").getOrThrow()
            ai.endpoints.save(Endpoint("ol", "Ollama", EndpointKind.OLLAMA, "http://localhost:11434", null, listOf("llama"), setOf(Capability.CHAT), 1), apiKey = null).getOrThrow()
            ai.bindings.set(RoleBinding(Role.ANSWER, "az", "gpt")).getOrThrow()
        }
    }

    @After fun tearDown() { MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    private fun req(endpoint: String = "ol", model: String = "llama") = TestBedRequest(
        PromptKey.ANSWER, PromptDraft("DRAFT {{question}} {{retrieved_memories}}", DefaultSchemas.text(PromptKey.ANSWER)),
        endpoint, model, PromptVars(question = "milk?"), inputIds = setOf("m1"),
    )

    @Test fun runsTheDraftOnTheSelectedModelAndReportsEveryGate() = runBlocking {
        val r = ai.testBed.run(req())
        assertEquals(listOf("ol/llama:true"), used) // the chosen endpoint, not the ANSWER binding, with the draft template
        assertNull(r.failure); assertEquals(7, r.promptTokens); assertEquals(3, r.completionTokens)
        assertTrue(r.evaluation!!.ok)
        assertNull(r.wireSchema) // Ollama without JSON_SCHEMA: instructions fallback
        assertNotNull(ai.testBed.run(req("az", "gpt")).wireSchema)
    }

    @Test fun failuresAreShortHumanText() = runBlocking {
        reply = { throw ProviderHttpException(401, "Azure OpenAI", "key sk-live-1 rejected") }
        val r = ai.testBed.run(req("az", "gpt"))
        assertEquals("AI key rejected", r.failure); assertNull(r.evaluation)
        reply = { "not json" }
        assertTrue(ai.testBed.run(req()).evaluation!!.problems().contains("Reply wasn't JSON"))
    }

    @Test fun compatibilityChecksTheBoundEndpoints() = runBlocking {
        var deep = """{"type":"string"}"""
        repeat(6) { deep = """{"type":"object","properties":{"x":$deep},"required":["x"]}""" }
        val draft = DefaultSchemas.text(PromptKey.ANSWER)!!.replaceFirst("\"properties\":{", "\"properties\":{\"deep\":$deep,")
        assertEquals(listOf("Azure: Too deeply nested (max 5 levels)"), ai.testBed.compatibility(PromptKey.ANSWER, PromptDraft("t", draft)))
        assertEquals(emptyList<String>(), ai.testBed.compatibility(PromptKey.SYSTEM, PromptDraft("t", null)))
    }

    /** Task 3 carry: a draft schema the validator can't apply shows a fixed problem line, never a crash or exception text. */
    @Test fun aMalformedDraftSchemaIsAProblemNotACrash() = runBlocking {
        listOf("""{"type":"object","properties":{"answer":{"type":[{}]}}}""", """{"anyOf":[1]}""").forEach { schema ->
            val r = ai.testBed.run(req().copy(draft = PromptDraft("DRAFT {{question}}", schema)))
            assertNull(r.failure)
            assertEquals(listOf(OutputCheck.SCHEMA_UNUSABLE), r.evaluation!!.problems())
        }
    }

    /** Fix round 1 (finding 5): cancelling a test-bed run is never turned into a failure result. */
    @Test fun cancellationPropagates() = runBlocking {
        reply = { throw CancellationException("stop") }
        assertTrue(runCatching { ai.testBed.run(req("az", "gpt")) }.exceptionOrNull() is CancellationException)
    }
}
