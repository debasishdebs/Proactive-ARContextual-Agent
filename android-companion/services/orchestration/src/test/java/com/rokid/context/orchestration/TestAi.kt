package com.rokid.context.orchestration

import android.content.Context
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.Capability
import com.rokid.context.ai.Endpoint
import com.rokid.context.ai.EndpointKind
import com.rokid.context.ai.InMemoryKeyVault
import com.rokid.context.ai.InMemorySeedState
import com.rokid.context.ai.ProviderBuilder
import com.rokid.context.ai.Role
import com.rokid.context.ai.RoleBinding
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.model.ModelProvider
import kotlinx.coroutines.runBlocking

/** An AiGraph whose every model call is [reply]; [bindAll] binds every role but EMBED to one https Azure endpoint. */
object TestAi {
    fun graph(ctx: Context, reply: suspend (String) -> String): AiGraph =
        AiGraph(MemoryGraph.get(ctx), InMemoryKeyVault(), InMemorySeedState(), ProviderBuilder { _, _, _ ->
            object : ModelProvider { override suspend fun generate(prompt: String): String = reply(prompt) }
        }).also { AiGraph.resetForTests(it) }

    fun bindAll(ai: AiGraph, caps: Set<Capability> = setOf(Capability.CHAT, Capability.VISION)) = runBlocking {
        ai.endpoints.save(Endpoint("az", "Azure", EndpointKind.AZURE_OPENAI, "https://az.example", "2024-10-21", listOf("gpt"), caps, 1), apiKey = "k").getOrThrow()
        Role.entries.filter { it != Role.EMBED && it.requiredCapability in caps }.forEach { ai.bindings.set(RoleBinding(it, "az", "gpt")).getOrThrow() }
    }
}
