package com.rokid.context.ai.run

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.Capability
import com.rokid.context.ai.Endpoint
import com.rokid.context.ai.EndpointKind
import com.rokid.context.ai.FailureKind
import com.rokid.context.ai.InMemoryKeyVault
import com.rokid.context.ai.InMemorySeedState
import com.rokid.context.ai.ModelCallException
import com.rokid.context.ai.ProviderBuilder
import com.rokid.context.ai.Role
import com.rokid.context.ai.RoleBinding
import com.rokid.context.ai.contract.AnswerOutput
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Canvas seam: [RoleCall.override] replaces the active template and schema of the called key, in memory only. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RoleRunnerOverrideTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val replies = ArrayDeque<String>()
    private val requests = mutableListOf<ModelRequest>()
    private lateinit var ai: AiGraph

    @Before fun setUp() {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        ai = AiGraph(MemoryGraph.get(ctx), InMemoryKeyVault(), InMemorySeedState(), ProviderBuilder { _, _, _ ->
            object : ModelProvider {
                override suspend fun generate(prompt: String) = error("unused")
                override suspend fun complete(request: ModelRequest): ModelReply { requests += request; return ModelReply(replies.removeFirst(), 10, 2) }
            }
        })
        runBlocking {
            ai.endpoints.save(Endpoint("az", "Azure", EndpointKind.AZURE_OPENAI, "https://az.example", "2024-10-21", listOf("gpt"), setOf(Capability.CHAT, Capability.JSON_SCHEMA), 1), apiKey = "k").getOrThrow()
            ai.bindings.set(RoleBinding(Role.ANSWER, "az", "gpt")).getOrThrow()
        }
    }

    @After fun tearDown() { MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    private fun call(override: PromptDraft? = null) =
        RoleCall(PromptKey.ANSWER, PromptVars(question = "Where is the milk?"), inputIds = setOf("m1"), override = override)

    private val ok = """{"answer":"In the fridge","sources":["m1"],"proposedAction":null}"""

    @Test fun overrideTemplateIsSentInsteadOfActive() = runBlocking {
        val active = ai.prompts.active(PromptKey.ANSWER).template
        replies += ok
        ai.runner.run(call(PromptDraft("OVERRIDE-MARKER {{question}}", null)))
        val sent = requests.single().prompt
        assertTrue(sent.contains("OVERRIDE-MARKER Where is the milk?"))
        assertFalse(sent.contains(active.trim()))
    }

    @Test fun overrideSchemaValidatesReply() = runBlocking {
        val tight = DefaultSchemas.text(PromptKey.ANSWER)!!.replace("\"maxLength\":600", "\"maxLength\":5")
        replies += """{"answer":"far too long","sources":[]}"""; replies += """{"answer":"far too long","sources":[]}"""
        val e = runCatching { ai.runner.run(call(PromptDraft("T {{question}}", tight))) }.exceptionOrNull() as ModelCallException
        assertEquals(FailureKind.INVALID_OUTPUT, e.kind)
        assertEquals(2, requests.size)
    }

    @Test fun nullOverrideUsesActivePromptAndVersion() = runBlocking {
        replies += ok
        val r = ai.runner.run(call())
        assertEquals(1, r.promptVersion)
        assertTrue(requests.single().prompt.contains(ai.prompts.active(PromptKey.ANSWER).template.replace("{{question}}", "Where is the milk?").lines().first()))
        assertEquals("In the fridge", (r.output as AnswerOutput).answer)
    }

    @Test fun overrideReportsVersionZero() = runBlocking {
        replies += ok
        assertEquals(0, ai.runner.run(call(PromptDraft("T {{question}}", null))).promptVersion)
    }

    @Test fun systemPreambleStillActive() = runBlocking {
        val system = ai.prompts.active(PromptKey.SYSTEM).template.lines().first { it.isNotBlank() }.trim()
        replies += ok
        ai.runner.run(call(PromptDraft("T {{question}}", null)))
        assertTrue(requests.single().prompt.contains(system.take(40)))
    }
}
