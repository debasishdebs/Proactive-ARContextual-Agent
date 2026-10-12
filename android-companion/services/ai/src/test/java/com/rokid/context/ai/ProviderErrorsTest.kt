package com.rokid.context.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderErrorsTest {
    @Test fun httpCodesAreClassifiedByNumberOnly() {
        assertEquals(FailureKind.AUTH, ProviderErrors.kindOf(ProviderHttpException(401, "Azure OpenAI", "")))
        assertEquals(FailureKind.AUTH, ProviderErrors.kindOf(ProviderHttpException(403, "Ollama", "")))
        assertEquals(FailureKind.REJECTED, ProviderErrors.kindOf(ProviderHttpException(404, "Azure OpenAI", "")))
        assertEquals(FailureKind.REJECTED, ProviderErrors.kindOf(ProviderHttpException(400, "Azure OpenAI", "invalid_request_error")))
        assertEquals(FailureKind.TRANSIENT, ProviderErrors.kindOf(ProviderHttpException(500, "Azure OpenAI", "upstream route 404; api key is missing")))
        assertEquals(FailureKind.TRANSIENT, ProviderErrors.kindOf(ProviderHttpException(429, "Azure OpenAI", "slow down")))
    }

    @Test fun textAloneIsNeverPermanent() {
        assertFalse(ProviderErrors.isPermanent(IllegalStateException("Azure OpenAI request failed: 401 deployment not found")))
        assertTrue(ProviderErrors.isPermanent(ProviderConfigException("anything at all")))
        assertFalse(ProviderErrors.isPermanent(java.net.SocketTimeoutException("timeout")))
        assertTrue(ProviderErrors.isPermanent(ModelCallException("x", retryable = false)))
    }

    @Test fun worstPrefersTheSettingsProblem() {
        assertEquals(FailureKind.AUTH, ProviderErrors.worst(FailureKind.AUTH, FailureKind.TRANSIENT))
        assertEquals(FailureKind.NOT_SET, ProviderErrors.worst(FailureKind.TRANSIENT, FailureKind.NOT_SET))
        assertEquals(FailureKind.TRANSIENT, ProviderErrors.worst(null, FailureKind.TRANSIENT))
    }

    @Test fun httpMessageKeepsTheLegacyShapeForLogs() =
        assertEquals("Ollama request failed: 503 busy", ProviderHttpException(503, "Ollama", "busy").message)
}
