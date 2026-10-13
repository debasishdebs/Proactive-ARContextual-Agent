package com.rokid.context.ai

/**
 * Log text for a failure. Log lines reach Diagnostics › Logs and the shared export (Task 21), and a provider's message
 * can echo request text (memory) or the key, so only the class, [FailureKind] and HTTP code are logged: never
 * `message`, never the cause's message (fix round 1, T21 review I1).
 */
object SafeLog {
    fun describe(t: Throwable): String {
        val parts = buildList {
            add("kind=${ProviderErrors.kindOf(t)}")
            if (t is ModelCallException) add("retryable=${t.retryable}")
            httpCode(t)?.let { add("http=$it") }
        }
        return "${t.javaClass.simpleName}(${parts.joinToString(", ")})"
    }

    /** The first HTTP status in the cause chain, if any. */
    fun httpCode(t: Throwable): Int? = generateSequence(t) { it.cause }.take(8).filterIsInstance<ProviderHttpException>().firstOrNull()?.code
}
