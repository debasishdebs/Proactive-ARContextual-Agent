package com.rokid.context.ai

import android.util.Log
import com.rokid.context.model.ModelImageInput
import com.rokid.context.model.ModelRequest
import kotlin.coroutines.cancellation.CancellationException

/** What a routed call returned, and from where (the test bed shows latency + tokens; RoleRunner logs the endpoint). */
data class RoutedReply(val text: String, val endpoint: Endpoint, val model: String, val latencyMs: Long, val promptTokens: Int?, val completionTokens: Int?)

/**
 * Routes a role's call to its bound endpoint. Vision is used only when the endpoint has VISION and images are given;
 * a vision failure retries as text on the same endpoint; a primary failure retries on the fallback binding. [build] is
 * called once per endpoint tried, so each kind gets its own wire format. The binding's temperature/maxTokens fill
 * whatever the request leaves null. Failures surface as [ModelCallException] with a [FailureKind]; cancellation is
 * rethrown untouched.
 */
class ModelRouter(
    private val bindings: RoleBindingRepository,
    private val endpoints: EndpointRepository,
    private val providers: ProviderBuilder,
) {
    suspend fun generate(role: Role, prompt: String, images: List<ModelImageInput> = emptyList()): String =
        complete(role) { ModelRequest(prompt, images) }.text

    suspend fun generateFromFrames(prompt: String, images: List<ModelImageInput>): String =
        completeFromFrames { ModelRequest(prompt, images) }.text

    suspend fun complete(role: Role, build: (Endpoint) -> ModelRequest): RoutedReply {
        val b = bindings.get(role) ?: throw ModelCallException("No model is set for ${role.name}", retryable = true, kind = FailureKind.NOT_SET)
        return route(b, build, skipPrimary = false)
    }

    /**
     * Frame summarisation, matching the legacy worker: vision → text on the vision endpoint, then SUMMARIZE's chain.
     * When SUMMARIZE shares the vision endpoint+model, only its fallback is tried (the primary already failed as text).
     */
    suspend fun completeFromFrames(build: (Endpoint) -> ModelRequest): RoutedReply {
        val vision = bindings.get(Role.VISION_EXTRACT) ?: return complete(Role.SUMMARIZE, build)
        return try {
            route(vision, build, skipPrimary = false)
        } catch (first: ModelCallException) {
            val summarize = bindings.get(Role.SUMMARIZE) ?: throw first
            val samePrimary = summarize.endpointId == vision.endpointId && summarize.model == vision.model
            if (samePrimary && summarize.fallbackEndpointId == null) throw first
            try {
                route(summarize, build, skipPrimary = samePrimary)
            } catch (second: ModelCallException) {
                throw ModelCallException("Frame summary failed: ${first.message}; ${second.message}", first.retryable || second.retryable, second, ProviderErrors.worst(first.kind, second.kind))
            }
        }
    }

    /** Test bed: exactly this endpoint and model, no binding params and no fallback. */
    suspend fun completeOn(endpointId: String, model: String, build: (Endpoint) -> ModelRequest): RoutedReply {
        val e = endpoints.byId(endpointId) ?: throw ModelCallException("That endpoint was deleted", retryable = false, kind = FailureKind.NOT_SET)
        return try {
            call(e, model, null, build)
        } catch (c: CancellationException) {
            throw c
        } catch (m: ModelCallException) {
            throw m
        } catch (t: Throwable) {
            throw ModelCallException("Test call failed: ${safe(t, endpoints.keyFor(e))}", !ProviderErrors.isPermanent(t), t, ProviderErrors.kindOf(t))
        }
    }

    private suspend fun route(b: RoleBinding, build: (Endpoint) -> ModelRequest, skipPrimary: Boolean): RoutedReply {
        val primaryEndpoint = if (skipPrimary) null else
            endpoints.byId(b.endpointId) ?: throw ModelCallException("Endpoint for ${b.role.name} was deleted", retryable = true, kind = FailureKind.NOT_SET)
        val first: Throwable? = primaryEndpoint?.let { primary ->
            try { return call(primary, b.model, b, build) } catch (c: CancellationException) { throw c } catch (t: Throwable) { t }
        }
        val primaryKey = primaryEndpoint?.let { endpoints.keyFor(it) }
        val fallback = b.fallbackEndpointId?.let { endpoints.byId(it) }
            ?: throw ModelCallException(
                "Primary provider failed: ${safe(first, primaryKey)}",
                retryable = first == null || !ProviderErrors.isPermanent(first), first,
                kind = first?.let(ProviderErrors::kindOf) ?: FailureKind.NOT_SET,
            )
        val fallbackKey = endpoints.keyFor(fallback)
        return try {
            call(fallback, b.fallbackModel ?: b.model, b, build)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            val retryable = !ProviderErrors.isPermanent(t) || (first != null && !ProviderErrors.isPermanent(first))
            throw ModelCallException(
                "Primary+fallback generation failed: ${safe(first, primaryKey, fallbackKey)}; ${safe(t, primaryKey, fallbackKey)}",
                retryable, t, ProviderErrors.worst(first?.let(ProviderErrors::kindOf), ProviderErrors.kindOf(t)),
            )
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

    private suspend fun call(e: Endpoint, model: String, b: RoleBinding?, build: (Endpoint) -> ModelRequest): RoutedReply {
        val key = endpoints.keyFor(e)
        requireConfigured(e, model, key)
        val p = providers.create(e, model, key)
        val request = build(e).let { r -> r.copy(temperature = r.temperature ?: b?.temperature, maxTokens = r.maxTokens ?: b?.maxTokens) }
        val start = System.nanoTime()
        val reply = if (request.images.isNotEmpty() && Capability.VISION in e.capabilities) {
            try { p.complete(request) } catch (c: CancellationException) { throw c } catch (t: Throwable) {
                Log.w(TAG, "vision failed on ${e.name}, retrying as text: ${SafeLog.describe(t)}")
                p.complete(request.copy(images = emptyList()))
            }
        } else {
            p.complete(request.copy(images = emptyList()))
        }
        return RoutedReply(reply.text, e, model, (System.nanoTime() - start) / 1_000_000, reply.promptTokens, reply.completionTokens)
    }

    internal companion object {
        const val TAG = "ModelRouter"
        const val MAX_PROVIDER_TEXT = 200

        /**
         * Failure text for exception messages. Only our own fixed texts are kept ([ModelCallException], whose message is
         * built here from these parts, and [ProviderConfigException]); a provider throwable becomes class, kind and HTTP
         * code ([SafeLog.describe]), so no provider body (echoed request text, memory, keys) ever enters a message.
         * Known [keys] are still masked, then the text is truncated.
         */
        fun safe(t: Throwable?, vararg keys: String?): String {
            var m = when (t) {
                null -> ""
                is ModelCallException, is ProviderConfigException -> t.message.orEmpty()
                else -> SafeLog.describe(t)
            }
            keys.filterNotNull().map { it.trim() }.filter { it.isNotEmpty() }.forEach { m = m.replace(it, "***") }
            return if (m.length <= MAX_PROVIDER_TEXT) m else m.take(MAX_PROVIDER_TEXT) + "…"
        }
    }
}
