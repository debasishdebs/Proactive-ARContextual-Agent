package com.rokid.context.ai

import com.rokid.context.core.log.PcLog
import com.rokid.context.model.CallClass
import com.rokid.context.model.CallGate
import com.rokid.context.model.DispatchGuard
import com.rokid.context.model.LanguageModel
import com.rokid.context.model.ModelImageInput
import com.rokid.context.model.ModelRequest
import com.rokid.context.model.PassThroughGate
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * What a routed call returned, and from where (the test bed shows latency + tokens; RoleRunner logs the endpoint).
 * [truncated]: stopped at the token cap; [maxTokens]: the cap that call sent (request, else binding; null = provider default).
 */
data class RoutedReply(
    val text: String, val endpoint: Endpoint, val model: String, val latencyMs: Long, val promptTokens: Int?, val completionTokens: Int?,
    val truncated: Boolean = false, val maxTokens: Int? = null,
)

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
    /** Every individual provider call (fallbacks and retries too) runs inside this, with the caller's [CallClass]. */
    private val gate: CallGate = PassThroughGate,
) : LanguageModel {
    @Deprecated("Pass a CallClass", ReplaceWith("generate(role, CallClass.OTHER, prompt, images)"))
    suspend fun generate(role: Role, prompt: String, images: List<ModelImageInput> = emptyList()): String =
        generate(role, CallClass.OTHER, prompt, images)

    suspend fun generate(role: Role, callClass: CallClass, prompt: String, images: List<ModelImageInput> = emptyList()): String =
        complete(role, callClass) { ModelRequest(prompt, images) }.text

    @Deprecated("Pass a CallClass", ReplaceWith("generateFromFrames(CallClass.OTHER, prompt, images)"))
    suspend fun generateFromFrames(prompt: String, images: List<ModelImageInput>): String =
        generateFromFrames(CallClass.OTHER, prompt, images)

    suspend fun generateFromFrames(callClass: CallClass, prompt: String, images: List<ModelImageInput>): String =
        completeFromFrames(callClass) { ModelRequest(prompt, images) }.text

    @Deprecated("Pass a CallClass", ReplaceWith("complete(role, CallClass.OTHER, build)"))
    suspend fun complete(role: Role, build: (Endpoint) -> ModelRequest): RoutedReply = complete(role, CallClass.OTHER, build)

    override suspend fun complete(role: Role, callClass: CallClass, build: (Endpoint) -> ModelRequest): RoutedReply {
        val b = bindings.get(role) ?: throw ModelCallException("No model is set for ${role.name}", retryable = true, kind = FailureKind.NOT_SET)
        return route(b, build, skipPrimary = false, callClass)
    }

    /**
     * Frame summarisation, matching the legacy worker: vision → text on the vision endpoint, then SUMMARIZE's chain.
     * When SUMMARIZE shares the vision endpoint+model, only its fallback is tried (the primary already failed as text).
     */
    @Deprecated("Pass a CallClass", ReplaceWith("completeFromFrames(CallClass.OTHER, build)"))
    suspend fun completeFromFrames(build: (Endpoint) -> ModelRequest): RoutedReply = completeFromFrames(CallClass.OTHER, build)

    override suspend fun completeFromFrames(callClass: CallClass, build: (Endpoint) -> ModelRequest): RoutedReply {
        val vision = bindings.get(Role.VISION_EXTRACT) ?: return complete(Role.SUMMARIZE, callClass, build)
        return try {
            route(vision, build, skipPrimary = false, callClass)
        } catch (first: ModelCallException) {
            val summarize = bindings.get(Role.SUMMARIZE) ?: throw first
            val samePrimary = summarize.endpointId == vision.endpointId && summarize.model == vision.model
            if (samePrimary && summarize.fallbackEndpointId == null) throw first
            try {
                route(summarize, build, skipPrimary = samePrimary, callClass)
            } catch (second: ModelCallException) {
                // A photo-only call whose SUMMARIZE endpoint cannot take the photo: the vision failure is the real one (a 400 stays a 400, keeps its attempts and parking).
                if (needsVision(second)) throw first
                throw ModelCallException("Frame summary failed: ${first.message}; ${second.message}", first.retryable || second.retryable, second, ProviderErrors.worst(first.kind, second.kind))
            }
        }
    }

    /** Test bed: exactly this endpoint and model, no binding params and no fallback. */
    @Deprecated("Pass a CallClass", ReplaceWith("completeOn(endpointId, model, CallClass.OTHER, build)"))
    suspend fun completeOn(endpointId: String, model: String, build: (Endpoint) -> ModelRequest): RoutedReply =
        completeOn(endpointId, model, CallClass.OTHER, build)

    override suspend fun completeOn(endpointId: String, model: String, callClass: CallClass, build: (Endpoint) -> ModelRequest): RoutedReply {
        val e = endpoints.byId(endpointId) ?: throw ModelCallException("That endpoint was deleted", retryable = false, kind = FailureKind.NOT_SET)
        // P8 D3: the key is read once, before the call; the failure path never reads it again, so a Keystore failure
        // there can't replace the provider's error.
        var key: String? = null
        return try {
            key = endpoints.keyFor(e)
            call(e, model, null, build, key, callClass)
        } catch (c: CancellationException) {
            throw c
        } catch (m: ModelCallException) {
            throw m
        } catch (t: Throwable) {
            throw ModelCallException("Test call failed: ${safe(t, key)}", !ProviderErrors.isPermanent(t), t, ProviderErrors.kindOf(t))
        }
    }

    private suspend fun route(b: RoleBinding, build: (Endpoint) -> ModelRequest, skipPrimary: Boolean, callClass: CallClass): RoutedReply {
        val primaryEndpoint = if (skipPrimary) null else
            endpoints.byId(b.endpointId) ?: throw ModelCallException("Endpoint for ${b.role.name} was deleted", retryable = true, kind = FailureKind.NOT_SET)
        // P8 D3: each key is read once, before its call; a failure path never reads it again (see completeOn).
        var primaryKey: String? = null
        val first: Throwable? = primaryEndpoint?.let { primary ->
            try {
                primaryKey = endpoints.keyFor(primary)
                return call(primary, b.model, b, build, primaryKey, callClass)
            } catch (c: CancellationException) { throw c } catch (t: Throwable) { t }
        }
        val fallback = b.fallbackEndpointId?.let { endpoints.byId(it) }
            ?: throw ModelCallException(
                "Primary provider failed: ${safe(first, primaryKey)}",
                retryable = first == null || !ProviderErrors.isPermanent(first), first,
                kind = first?.let(ProviderErrors::kindOf) ?: FailureKind.NOT_SET,
            )
        var fallbackKey: String? = null
        return try {
            fallbackKey = endpoints.keyFor(fallback)
            call(fallback, b.fallbackModel ?: b.model, b, build, fallbackKey, callClass)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            if (first != null && needsVision(t)) {
                // A photo-only call whose fallback cannot take the photo: report the primary's own failure, not "not set".
                throw ModelCallException("Primary provider failed: ${safe(first, primaryKey, fallbackKey)}", !ProviderErrors.isPermanent(first), first, ProviderErrors.kindOf(first))
            }
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

    private suspend fun call(e: Endpoint, model: String, b: RoleBinding?, build: (Endpoint) -> ModelRequest, key: String?, callClass: CallClass): RoutedReply {
        requireConfigured(e, model, key)
        val p = providers.create(e, model, key)
        val request = build(e).let { r -> r.copy(temperature = r.temperature ?: b?.temperature, maxTokens = r.maxTokens ?: b?.maxTokens) }
        // Each provider call (the vision attempt and its text retry are two) takes its own permit.
        val start = System.nanoTime()
        // Codex r9 P1-1: a call carrying photos passes the caller's DispatchGuard inside its permit (a Forget during the permit wait drops it unsent).
        suspend fun gated(r: ModelRequest) = gate.withPermit(callClass) {
            val guard = if (r.images.isNotEmpty()) currentCoroutineContext()[DispatchGuard] else null
            if (guard == null) p.complete(r) else guard.send { p.complete(r) }
        }
        if (request.requireImages && (request.images.isEmpty() || Capability.VISION !in e.capabilities)) {
            PcLog.i(TAG, "vision_refused_no_text_retry endpoint_without_vision")
            throw ModelCallException(NEEDS_VISION, retryable = false, kind = FailureKind.NOT_SET)
        }
        val reply = if (request.images.isNotEmpty() && Capability.VISION in e.capabilities) {
            try { gated(request) } catch (c: CancellationException) { throw c } catch (t: Throwable) {
                if (request.requireImages) {   // a text-only retry would guess without the photo (codex r9 P1-2)
                    PcLog.w(TAG, "vision_refused_no_text_retry ${SafeLog.describe(t)}")
                    throw t
                }
                PcLog.w(TAG, "vision failed, retrying as text: ${SafeLog.describe(t)}")
                gated(request.copy(images = emptyList()))
            }
        } else {
            gated(request.copy(images = emptyList()))
        }
        return RoutedReply(reply.text, e, model, (System.nanoTime() - start) / 1_000_000, reply.promptTokens, reply.completionTokens, reply.truncated, request.maxTokens)
    }

    internal companion object {
        const val TAG = "ModelRouter"
        const val MAX_PROVIDER_TEXT = 200
        const val NEEDS_VISION = "This call needs a vision endpoint and its photo"
        /** True when [t] failed only because a photo-only request met an endpoint without vision (also when [route] wrapped that one failure). */
        fun needsVision(t: Throwable?): Boolean = t is ModelCallException && t.kind == FailureKind.NOT_SET &&
            generateSequence<Throwable>(t) { it.cause?.takeIf { c -> c !== it } }.take(3).any { it is ModelCallException && it.message == NEEDS_VISION }
        /** Fixed log token: a photo call failed (or had no vision endpoint) and was NOT retried as text. */
        const val VISION_REFUSED_NO_TEXT_RETRY = "vision_refused_no_text_retry"

        /**
         * Failure text for exception messages. Only our own fixed texts are kept ([ModelCallException], whose message is
         * built here from these parts, and [ProviderConfigException]); a provider throwable becomes class, kind and HTTP
         * code ([SafeLog.describe]), so no provider body (echoed request text, memory, keys) ever enters a message.
         * Known [keys] are still masked, each also in its URL-encoded and Base64 forms (P8 D3), then the text is truncated.
         */
        fun safe(t: Throwable?, vararg keys: String?): String {
            var m = when (t) {
                null -> ""
                is ModelCallException, is ProviderConfigException -> t.message.orEmpty()
                else -> SafeLog.describe(t)
            }
            keys.filterNotNull().map { it.trim() }.filter { it.isNotEmpty() }.flatMap(::maskForms).distinct()
                .sortedByDescending { it.length }.forEach { m = m.replace(it, "***") }
            return if (m.length <= MAX_PROVIDER_TEXT) m else m.take(MAX_PROVIDER_TEXT) + "…"
        }

        /** The key, its URL-encoded form and its Base64 form (padded or not, e.g. inside a Basic-auth header). */
        private fun maskForms(key: String): List<String> = listOf(
            key,
            java.net.URLEncoder.encode(key, "UTF-8"),
            java.util.Base64.getEncoder().encodeToString(key.toByteArray(Charsets.UTF_8)).trimEnd('='),
        ).filter { it.isNotEmpty() }
    }
}
