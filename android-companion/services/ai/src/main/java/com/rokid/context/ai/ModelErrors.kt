package com.rokid.context.ai

import java.util.Locale

/** Every failure leaving [ModelRouter] (except cancellation) is one of these, with an explicit retry decision. */
class ModelCallException(message: String, val retryable: Boolean, cause: Throwable? = null) : RuntimeException(message, cause)

object ProviderErrors {
    private val PERMANENT_STATUS = Regex("\\b(401|403|404)\\b")
    /** Exact (lower-cased) messages thrown by the legacy providers' `require` checks, plus provider API errors. */
    private val PERMANENT_TEXT = listOf(
        "azure_kg_api_key is missing", "azure_kg_endpoint is missing", "ollama_base_url is missing",
        "local model path is required", "native runtime not available",
        "resource not found", "deployment not found", "unsupported parameter", "invalid_request_error",
    )

    /** Auth/config errors that will not succeed on retry. HTTP 5xx, timeouts and network errors are transient. */
    fun isPermanent(t: Throwable): Boolean {
        if (t is ModelCallException) return !t.retryable
        val m = t.message.orEmpty().lowercase(Locale.US)
        return PERMANENT_STATUS.containsMatchIn(m) || PERMANENT_TEXT.any { it in m }
    }
}
