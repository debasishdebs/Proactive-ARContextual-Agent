package com.rokid.context.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeLogTest {
    /** Phase 2 ruling: a ModelCallException's cause keeps the raw provider throwable, which may echo the key. */
    @Test fun modelFailureUsesOnlyItsRedactedMessage() {
        val raw = RuntimeException("Azure OpenAI request failed: 500 echoed sk-live-123")
        val e = ModelCallException("Primary provider failed: Azure OpenAI request failed: 500 echoed ***", retryable = true, cause = raw)
        val text = SafeLog.describe(e)
        assertTrue(text, text.startsWith("ModelCallException(retryable=true): Primary provider failed"))
        assertFalse(text, "sk-live-123" in text)
    }

    @Test fun longTextIsTruncated() = assertEquals(301, SafeLog.describe(IllegalStateException("x".repeat(1_000))).length)
}
