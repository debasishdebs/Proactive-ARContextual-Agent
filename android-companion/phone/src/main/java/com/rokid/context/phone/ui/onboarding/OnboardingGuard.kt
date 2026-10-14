package com.rokid.context.phone.ui.onboarding

import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.Endpoint
import com.rokid.context.ai.EndpointKind
import com.rokid.context.ai.Role
import com.rokid.context.memory.MemoryGraph

/**
 * BUG-R3-1 startup defence. Auto Backup once restored `onboarding.xml` (complete) onto a fresh install whose keys and
 * DB are never restored, so the first launch landed on Home with no AI. The backup rules now exclude it; this is the
 * second line: a "complete" flag is not believed when no chat endpoint is usable AND the DB holds nothing yet.
 * A user with captured data keeps Home (Settings shows what is missing). Runs off Main (DB + Keystore).
 */
class OnboardingGuard(private val hasUsableChat: suspend () -> Boolean, private val dbIsFresh: suspend () -> Boolean) {
    suspend fun mustRerun(complete: Boolean): Boolean = complete && !hasUsableChat() && dbIsFresh()

    companion object {
        fun of(ai: AiGraph, memory: MemoryGraph) = OnboardingGuard(
            hasUsableChat = {
                val b = ai.bindings.get(Role.ANSWER)
                b != null && (usable(ai, b.endpointId, b.model) || b.fallbackEndpointId?.let { usable(ai, it, b.fallbackModel ?: b.model) } == true)
            },
            dbIsFresh = { memory.db.memoryDao().count() == 0 && memory.db.frameDao().count() == 0 },
        )

        /** The router's own configuration rule (ModelRouter.requireConfigured): address, model, and a key for Azure. */
        private suspend fun usable(ai: AiGraph, endpointId: String, model: String): Boolean {
            val e: Endpoint = ai.endpoints.byId(endpointId) ?: return false
            if (e.baseUrl.isBlank()) return false
            if (e.kind != EndpointKind.LLAMACPP_LOCAL && model.isBlank()) return false
            return e.kind != EndpointKind.AZURE_OPENAI || !ai.endpoints.keyFor(e).isNullOrBlank()
        }
    }
}
