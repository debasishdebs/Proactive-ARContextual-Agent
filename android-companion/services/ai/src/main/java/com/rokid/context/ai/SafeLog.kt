package com.rokid.context.ai

/**
 * Log text for a failure (Phase 2 ruling). A [ModelCallException]'s message is already redacted, but its cause keeps
 * the raw provider throwable, which may echo an API key: never pass such a throwable to `Log`, never print its cause.
 */
object SafeLog {
    private const val MAX = 300

    fun describe(t: Throwable): String {
        val text = when (t) {
            is ModelCallException -> "ModelCallException(retryable=${t.retryable}): ${t.message.orEmpty()}"
            else -> "${t.javaClass.simpleName}: ${t.message.orEmpty()}"
        }
        return if (text.length <= MAX) text else text.take(MAX) + "…"
    }
}
