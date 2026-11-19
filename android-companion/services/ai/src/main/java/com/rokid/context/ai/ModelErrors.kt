package com.rokid.context.ai

import java.io.IOException

/**
 * What went wrong with a model call. Decides what people see ([ModelErrorMapper]) and is the only input to
 * classification: no provider wording is ever matched (Phase 2 carry). Declared most actionable first ([ProviderErrors.worst]).
 */
enum class FailureKind { NOT_SET, AUTH, REJECTED, INVALID_OUTPUT, TRANSIENT }

/** Every failure leaving [ModelRouter] (except cancellation) is one of these, with an explicit retry decision. */
open class ModelCallException(
    message: String,
    val retryable: Boolean,
    cause: Throwable? = null,
    val kind: FailureKind = if (retryable) FailureKind.TRANSIENT else FailureKind.REJECTED,
    /** The provider replies the failed call consumed (every rejected attempt), for usage accounting. Empty when unknown. Never read for text. */
    val replies: List<RoutedReply> = emptyList(),
) : RuntimeException(message, cause)

/**
 * Codex r10: a photo attached to the request was forgotten (or everything was deleted) before it left: the call was dropped unsent. Never retried (not as
 * text, not on a fallback endpoint, not by the frame chain); the caller treats [frameIds] as gone and writes nothing for them.
 */
class PhotoForgottenException(val frameIds: Set<String>, val wiped: Boolean, val memoryIds: Set<String> = emptySet(), val markIds: Set<String> = emptySet()) :
    ModelCallException("photo forgotten before send", retryable = false, kind = FailureKind.NOT_SET)

/**
 * The roaming data saver refused the call before anything was sent (a photo upload, or text when "Small requests on roaming" is off). [reason] is a fixed token.
 * Retryable: the work waits for Wi-Fi. Callers park without using an attempt ([com.rokid.context.work.frames.ExtractOutcome.classify]); the router never retries it as text.
 */
class DataSaverBlockedException(
    val reason: String,
    /** What was asked and how it is counted; the router counts it ONCE per outer call ([ModelRouter.counting]), whatever number of endpoints were tried. */
    val dataKind: com.rokid.context.core.net.DataKind = com.rokid.context.core.net.DataKind.SMALL,
    val what: com.rokid.context.core.net.Skipped = com.rokid.context.core.net.Skipped.BULK,
) : ModelCallException("blocked by data saver", retryable = true, kind = FailureKind.TRANSIENT) {
    @Volatile var counted = false
}

/** Codex r10: the call needs its photo but the endpoint or provider cannot deliver images (no VISION, or the on-device runtime): nothing was sent, no output. */
class ImagesUnsupportedException : ModelCallException(ModelRouter.NEEDS_VISION, retryable = false, kind = FailureKind.NOT_SET)

/** Codex r10: a request carried a photo without a ref or without a [com.rokid.context.model.PhotoLiveness]: refused unsent (fail closed). */
class ImagesUnguardedException : ModelCallException("photo call without a liveness check", retryable = false, kind = FailureKind.NOT_SET)

/** A non-2xx reply. Never user-facing (UI maps by [code]); the message keeps "<provider> request failed: <code> <body>" for Diagnostics logs only (the router redacts it). */
class ProviderHttpException(val code: Int, provider: String, body: String) :
    IllegalStateException("$provider request failed: $code ${body.take(200)}") {
    /** 2026-11-08: a fixed token for why the provider refused ([ProviderErrors.reasonOf]); never provider text, so a device log may show it. */
    val reason: String? = ProviderErrors.reasonOf(body)
}

/** Missing address, model, key, model file or native runtime: only fixing the settings helps. */
class ProviderConfigException(message: String) : IllegalArgumentException(message)

/** A 2xx reply without the expected content. */
class ProviderResponseException(message: String) : IllegalStateException(message)

object ProviderErrors {
    /**
     * The refusal class of an error body, as one of a fixed set of tokens (null = none recognised). Only these tokens leave this function:
     * the body itself can echo the prompt or the user's memories and is never logged.
     */
    fun reasonOf(body: String): String? {
        val b = body.take(8_192).lowercase()
        return when {
            "content_filter" in b || "responsibleaipolicyviolation" in b || "content management policy" in b || "content_policy" in b -> "content_filter"
            "image" in b && listOf("invalid", "unsupported", "could not", "too large", "decode", "format").any { it in b } -> "image_rejected"
            "context_length" in b || "maximum context" in b || "too many tokens" in b -> "too_long"
            "response_format" in b || "json_schema" in b -> "format_unsupported"
            "deploymentnotfound" in b || "model_not_found" in b || "does not exist" in b -> "model_not_found"
            else -> null
        }
    }

    private val AUTH_CODES = setOf(401, 403)
    private val REJECTED_CODES = setOf(400, 404, 405, 409, 410, 413, 415, 422)

    fun kindOf(t: Throwable): FailureKind = when (t) {
        is ModelCallException -> t.kind
        is ProviderConfigException -> FailureKind.NOT_SET
        is ProviderHttpException -> when (t.code) {
            in AUTH_CODES -> FailureKind.AUTH
            in REJECTED_CODES -> FailureKind.REJECTED
            else -> FailureKind.TRANSIENT // 408, 429, 5xx, 3xx and anything unexpected
        }
        is IOException -> FailureKind.TRANSIENT
        else -> FailureKind.TRANSIENT
    }

    /** Settings/request errors that will not succeed on retry. 5xx, 408, 429, timeouts and network errors are transient. */
    fun isPermanent(t: Throwable): Boolean = if (t is ModelCallException) !t.retryable else kindOf(t) != FailureKind.TRANSIENT

    /** When two attempts failed, the kind people should act on: a settings problem outranks a transient one. */
    fun worst(a: FailureKind?, b: FailureKind): FailureKind = if (a == null || b.ordinal <= a.ordinal) b else a
}
