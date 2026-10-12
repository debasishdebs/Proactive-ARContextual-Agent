package com.rokid.context.ai

import android.content.Context
import android.util.Log

interface SeedState { fun isDone(): Boolean; fun markDone() }

class PrefsSeedState(context: Context) : SeedState {
    private val prefs = context.getSharedPreferences("ai_seed", Context.MODE_PRIVATE)
    override fun isDone() = prefs.getBoolean("v1_done", false)
    override fun markDone() { prefs.edit().putBoolean("v1_done", true).commit() }
}

class InMemorySeedState : SeedState {
    @Volatile private var done = false
    override fun isDone() = done
    override fun markDone() { done = true }
}

/**
 * Turns the legacy single-provider config into endpoints + role bindings (spec §4.1 seed).
 * [seedIfNeeded] only creates what is missing and marks done after every binding exists, so a crash at any point
 * is repaired on the next start. [syncFromLegacy] overwrites (legacy Config screen edits, Task 9).
 */
class EndpointSeeder(private val endpoints: EndpointRepository, private val bindings: RoleBindingRepository, private val state: SeedState) {
    /**
     * Never throws: legacy config the new validation rejects (e.g. an http remote endpoint with a key) returns the
     * failure, logs one short line without the key, and leaves the roles unbound (the router reports "No model is set").
     */
    suspend fun seedIfNeeded(settings: ProviderSettings): Result<Unit> = runCatching {
        if (state.isDone()) return@runCatching
        apply(settings, overwrite = false)
        state.markDone()
    }.onFailure { Log.w(TAG, "AI seed not finished: ${redact(it.message, settings.apiKey)}") }

    suspend fun syncFromLegacy(settings: ProviderSettings): Result<Unit> = runCatching {
        apply(settings, overwrite = true)
        state.markDone()
    }

    private suspend fun apply(settings: ProviderSettings, overwrite: Boolean) {
        val now = System.currentTimeMillis()
        val desired = when (settings.providerType.lowercase()) {
            "ollama" -> Endpoint(OLLAMA_ID, "Ollama", EndpointKind.OLLAMA, settings.endpoint.ifBlank { EnvDefaults.ollamaBaseUrl }, null,
                listOf(settings.deploymentOrModel.ifBlank { EnvDefaults.ollamaModel }), setOf(Capability.CHAT), now)
            "llama_cpp" -> Endpoint(LOCAL_ID, "On-device (llama.cpp)", EndpointKind.LLAMACPP_LOCAL, settings.nativeModelPath, null,
                listOf("local"), setOf(Capability.CHAT), now)
            else -> Endpoint(AZURE_ID, "Azure OpenAI", EndpointKind.AZURE_OPENAI, settings.endpoint.ifBlank { EnvDefaults.azureEndpoint }, "2024-10-21",
                listOf(settings.deploymentOrModel.ifBlank { EnvDefaults.azureDeployment }), setOf(Capability.CHAT, Capability.VISION, Capability.JSON_SCHEMA), now)
        }
        // Seeding: a blank key means "none known yet" (null = keep). Explicit Config edits: pass it through, so a
        // cleared key ("") removes the stored credential instead of silently keeping the old one.
        val apiKey = if (overwrite) settings.apiKey else settings.apiKey.ifBlank { null }
        val primary = endpoints.byId(desired.id)?.takeUnless { overwrite }
            ?: endpoints.save(desired, apiKey = apiKey).getOrThrow()
        val local = if (settings.localModeEnabled && settings.nativeModelPath.isNotBlank() && primary.id != LOCAL_ID) {
            endpoints.byId(LOCAL_ID)?.takeUnless { overwrite }
                ?: endpoints.save(Endpoint(LOCAL_ID, "On-device (llama.cpp)", EndpointKind.LLAMACPP_LOCAL, settings.nativeModelPath, null, listOf("local"), setOf(Capability.CHAT), now), apiKey = null).getOrThrow()
        } else null
        val model = primary.models.firstOrNull().orEmpty()
        for (role in Role.entries) {
            if (role == Role.EMBED) continue
            if (role.requiredCapability !in primary.capabilities) {
                // Switching the legacy provider to a text-only endpoint must not leave frames on the old vision endpoint.
                if (overwrite) bindings.clear(role)
                continue
            }
            if (!overwrite && bindings.get(role) != null) continue
            // Text roles get the on-device fallback (legacy `localFallback`); VISION_EXTRACT can't, the local endpoint has no VISION.
            val fallback = local?.takeIf { role.requiredCapability in it.capabilities }
            bindings.set(RoleBinding(role, primary.id, model, fallbackEndpointId = fallback?.id, fallbackModel = fallback?.models?.firstOrNull())).getOrThrow()
        }
    }

    companion object {
        private const val TAG = "EndpointSeeder"
        const val AZURE_ID = "azure"
        const val OLLAMA_ID = "ollama"
        const val LOCAL_ID = "llamacpp"

        private fun redact(message: String?, key: String): String {
            val m = message.orEmpty()
            val k = key.trim()
            return if (k.isEmpty()) m else m.replace(k, "***")
        }
    }
}
