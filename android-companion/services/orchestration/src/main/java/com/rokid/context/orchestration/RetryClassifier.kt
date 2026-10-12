package com.rokid.context.orchestration

import com.rokid.context.ai.ModelCallException
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

object RetryClassifier {
    enum class Outcome { RETRY, FAIL, CANCELLED }

    // Anchored to the provider prefix so a 5xx body that merely contains "404" is not classed permanent.
    private val PERMANENT_STATUS = Regex("request failed: (401|403|404)\\b")

    fun classify(t: Throwable): Outcome = when (t) {
        is CancellationException -> Outcome.CANCELLED
        is ModelCallException -> if (t.retryable) Outcome.RETRY else Outcome.FAIL
        is java.io.IOException -> Outcome.RETRY
        else -> if (legacyRetryable(t)) Outcome.RETRY else Outcome.FAIL
    }

    /** The legacy `QueueDrainWorker.isRetryable` rules, kept for non-model errors. */
    private fun legacyRetryable(error: Throwable): Boolean {
        val msg = error.message?.lowercase(Locale.US).orEmpty()
        val permanent = listOf("resource not found", "deployment not found", "unsupported parameter",
            "invalid_request_error", "api key is missing", "endpoint is missing")
        if (PERMANENT_STATUS.containsMatchIn(msg) || permanent.any { it in msg }) return false
        return listOf("timeout", "network", "connection", "temporarily unavailable", "provider failed").any { it in msg }
    }
}
