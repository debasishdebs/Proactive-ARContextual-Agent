package com.rokid.context.ai

import android.util.Log
import com.rokid.context.model.ModelImageInput
import kotlin.coroutines.cancellation.CancellationException

/**
 * Routes a role's call to its bound endpoint. Vision is used only when the endpoint has VISION and images are given;
 * a vision failure retries as text on the same endpoint; a primary failure retries on the fallback binding.
 * Failures surface as [ModelCallException] (see the error contract in the plan); cancellation is rethrown untouched.
 */
class ModelRouter(
    private val bindings: RoleBindingRepository,
    private val endpoints: EndpointRepository,
    private val providers: ProviderBuilder,
) {
    suspend fun generate(role: Role, prompt: String, images: List<ModelImageInput> = emptyList()): String {
        val b = bindings.get(role) ?: throw ModelCallException("No model is set for ${role.name}", retryable = true, kind = FailureKind.NOT_SET)
        return generateWith(b, prompt, images, skipPrimary = false)
    }

    /**
     * Frame summarisation, matching the legacy worker: vision → text on the vision endpoint, then SUMMARIZE's chain.
     * When SUMMARIZE shares the vision endpoint+model, only its fallback is tried (the primary already failed as text).
     */
    suspend fun generateFromFrames(prompt: String, images: List<ModelImageInput>): String {
        val vision = bindings.get(Role.VISION_EXTRACT) ?: return generate(Role.SUMMARIZE, prompt, images)
        return try {
            generateWith(vision, prompt, images, skipPrimary = false)
        } catch (first: ModelCallException) {
            val summarize = bindings.get(Role.SUMMARIZE) ?: throw first
            val samePrimary = summarize.endpointId == vision.endpointId && summarize.model == vision.model
            if (samePrimary && summarize.fallbackEndpointId == null) throw first
            try {
                generateWith(summarize, prompt, images, skipPrimary = samePrimary)
            } catch (second: ModelCallException) {
                throw ModelCallException("Frame summary failed: ${first.message}; ${second.message}", first.retryable || second.retryable, second, ProviderErrors.worst(first.kind, second.kind))
            }
        }
    }

    private suspend fun generateWith(b: RoleBinding, prompt: String, images: List<ModelImageInput>, skipPrimary: Boolean): String {
        val primaryEndpoint = if (skipPrimary) null else
            endpoints.byId(b.endpointId) ?: throw ModelCallException("Endpoint for ${b.role.name} was deleted", retryable = true, kind = FailureKind.NOT_SET)
        val first: Throwable? = primaryEndpoint?.let { primary ->
            try { return call(primary, b.model, prompt, images) } catch (c: CancellationException) { throw c } catch (t: Throwable) { t }
        }
        val primaryKey = primaryEndpoint?.let { endpoints.keyFor(it) }
        val fallback = b.fallbackEndpointId?.let { endpoints.byId(it) }
            ?: throw ModelCallException("Primary provider failed: ${safe(first, primaryKey)}", retryable = first == null || !ProviderErrors.isPermanent(first), first, kind = first?.let(ProviderErrors::kindOf) ?: FailureKind.NOT_SET)
        val fallbackKey = endpoints.keyFor(fallback)
        return try {
            call(fallback, b.fallbackModel ?: b.model, prompt, images)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            val retryable = !ProviderErrors.isPermanent(t) || (first != null && !ProviderErrors.isPermanent(first))
            throw ModelCallException("Primary+fallback generation failed: ${safe(first, primaryKey, fallbackKey)}; ${safe(t, primaryKey, fallbackKey)}", retryable, t, ProviderErrors.worst(first?.let(ProviderErrors::kindOf), ProviderErrors.kindOf(t)))
        }
    }

    /** Type-based config check (permanent): never depends on a provider's error wording. */
    private fun requireConfigured(e: Endpoint, model: String, key: String?) {
        val problem = when {
            e.baseUrl.isBlank() -> if (e.kind == EndpointKind.LLAMACPP_LOCAL) "No model file set for ${e.name}" else "No address set for ${e.name}"
            e.kind != EndpointKind.LLAMACPP_LOCAL && model.isBlank() -> "No model set for ${e.name}"
            e.kind == EndpointKind.AZURE_OPENAI && key.isNullOrBlank() -> "No API key saved for ${e.name}"
            else -> null
        }
        if (problem != null) throw ModelCallException(problem, retryable = false, kind = FailureKind.NOT_SET)
    }

    private suspend fun call(e: Endpoint, model: String, prompt: String, images: List<ModelImageInput>): String {
        val key = endpoints.keyFor(e)
        requireConfigured(e, model, key)
        val p = providers.create(e, model, key)
        if (images.isNotEmpty() && Capability.VISION in e.capabilities) {
            try { return p.generateWithImages(prompt, images) } catch (c: CancellationException) { throw c } catch (t: Throwable) {
                Log.w(TAG, "vision failed on ${e.name}, retrying as text: ${safe(t, key)}")
            }
        }
        return p.generate(prompt)
    }

    private companion object {
        const val TAG = "ModelRouter"
        const val MAX_PROVIDER_TEXT = 200

        /**
         * Provider text for messages/logs: some servers echo the API key in error bodies, so known keys become "***";
         * then truncated, keeping the leading "…request failed: <code>" for classification. Retry decisions use the original.
         */
        fun safe(t: Throwable?, vararg keys: String?): String {
            var m = t?.message.orEmpty()
            keys.filterNotNull().map { it.trim() }.filter { it.isNotEmpty() }.forEach { m = m.replace(it, "***") }
            return if (m.length <= MAX_PROVIDER_TEXT) m else m.take(MAX_PROVIDER_TEXT) + "…"
        }
    }
}
