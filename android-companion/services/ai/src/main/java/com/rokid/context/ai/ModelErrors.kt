package com.rokid.context.ai

import java.io.IOException

/**
 * What went wrong with a model call. Decides what people see ([ModelErrorMapper]) and is the only input to
 * classification: no provider wording is ever matched (Phase 2 carry). Declared most actionable first ([ProviderErrors.worst]).
 */
enum class FailureKind { NOT_SET, AUTH, REJECTED, INVALID_OUTPUT, TRANSIENT }

/** Every failure leaving [ModelRouter] (except cancellation) is one of these, with an explicit retry decision. */
class ModelCallException(
    message: String,
    val retryable: Boolean,
    cause: Throwable? = null,
    val kind: FailureKind = if (retryable) FailureKind.TRANSIENT else FailureKind.REJECTED,
    /** The provider replies the failed call consumed (every rejected attempt), for usage accounting. Empty when unknown. Never read for text. */
    val replies: List<RoutedReply> = emptyList(),
) : RuntimeException(message, cause)

/** A non-2xx reply. Never user-facing (UI maps by [code]); the message keeps "<provider> request failed: <code> <body>" for Diagnostics logs only (the router redacts it). */
class ProviderHttpException(val code: Int, provider: String, body: String) :
    IllegalStateException("$provider request failed: $code ${body.take(200)}")

/** Missing address, model, key, model file or native runtime: only fixing the settings helps. */
class ProviderConfigException(message: String) : IllegalArgumentException(message)

/** A 2xx reply without the expected content. */
class ProviderResponseException(message: String) : IllegalStateException(message)

object ProviderErrors {
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
