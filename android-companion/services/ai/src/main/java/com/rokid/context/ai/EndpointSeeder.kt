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
 * is repaired on the next start. [retireLegacyKey] decides when the legacy key file may go.
 */
class EndpointSeeder(private val endpoints: EndpointRepository, private val bindings: RoleBindingRepository, private val state: SeedState) {
    /**
     * Never throws: legacy config the new validation rejects (e.g. an http remote endpoint with a key) returns the
     * failure, logs one short line without the key, and leaves the roles unbound (the router reports "No model is set").
     */
    suspend fun seedIfNeeded(settings: ProviderSettings): Result<Unit> = runCatching {
        if (state.isDone()) return@runCatching
        apply(settings)
        state.markDone()
    }.onFailure { Log.w(TAG, "AI seed not finished: ${SafeLog.describe(it)}") }

    /**
     * Whether the legacy key may be deleted (T21 F23 + fix round 1). Only once the seed is done:
     * - keyless seeded kind: always (that endpoint can never take a key);
     * - keyed kind, blank read (Keystore unavailable): never, it may be the only copy (F23);
     * - keyed kind with a key: the seeded endpoint keeps a key it already has; one without a key gets this key first
     *   (a seed that finished earlier without it); a deleted endpoint needs none. A vault write failure throws.
     */
    suspend fun retireLegacyKey(settings: ProviderSettings): Boolean {
        if (!state.isDone()) return false
        val desired = desired(settings, 0L)
        if (desired.kind in EndpointRules.KEYLESS) return true
        if (settings.apiKey.isBlank()) return false
        val e = endpoints.byId(desired.id) ?: return true
        if (endpoints.keyFor(e).isNullOrBlank()) endpoints.save(e, apiKey = settings.apiKey).getOrThrow()
        return true
    }

    /** The endpoint the legacy provider type maps to (one place: [apply] and [retireLegacyKey]). */
    private fun desired(settings: ProviderSettings, now: Long): Endpoint = when (settings.providerType.lowercase()) {
        "ollama" -> Endpoint(OLLAMA_ID, "Ollama", EndpointKind.OLLAMA, settings.endpoint.ifBlank { EnvDefaults.ollamaBaseUrl }, null,
            listOf(settings.deploymentOrModel.ifBlank { EnvDefaults.ollamaModel }), setOf(Capability.CHAT), now)
        "llama_cpp" -> Endpoint(LOCAL_ID, "On-device (llama.cpp)", EndpointKind.LLAMACPP_LOCAL, settings.nativeModelPath, null,
            listOf("local"), setOf(Capability.CHAT), now)
        else -> Endpoint(AZURE_ID, "Azure OpenAI", EndpointKind.AZURE_OPENAI, settings.endpoint.ifBlank { EnvDefaults.azureEndpoint }, "2024-10-21",
            listOf(settings.deploymentOrModel.ifBlank { EnvDefaults.azureDeployment }), setOf(Capability.CHAT, Capability.VISION, Capability.JSON_SCHEMA), now)
    }

    private suspend fun apply(settings: ProviderSettings) {
        val now = System.currentTimeMillis()
        val desired = desired(settings, now)
        // A blank key means "none known yet" (null = keep). Keyless kinds never store a key (EndpointRules).
        val apiKey = if (desired.kind in EndpointRules.KEYLESS) null else settings.apiKey.ifBlank { null }
        val primary = endpoints.byId(desired.id)
            ?.also { existing ->
                // Repair: a crash after the row was written but before the key was stored leaves a keyless endpoint.
                if (apiKey != null && endpoints.keyFor(existing) == null) endpoints.save(existing, apiKey = apiKey).getOrThrow()
            }
            ?: endpoints.save(desired, apiKey = apiKey).getOrThrow()
        val local = if (settings.localModeEnabled && settings.nativeModelPath.isNotBlank() && primary.id != LOCAL_ID) {
            endpoints.byId(LOCAL_ID)
                ?: endpoints.save(Endpoint(LOCAL_ID, "On-device (llama.cpp)", EndpointKind.LLAMACPP_LOCAL, settings.nativeModelPath, null, listOf("local"), setOf(Capability.CHAT), now), apiKey = null).getOrThrow()
        } else null
        val model = primary.models.firstOrNull().orEmpty()
        for (role in Role.entries) {
            if (role == Role.EMBED || role.requiredCapability !in primary.capabilities) continue
            if (bindings.get(role) != null) continue
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
    }
}
