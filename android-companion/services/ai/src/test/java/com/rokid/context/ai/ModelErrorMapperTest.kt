package com.rokid.context.ai

import com.rokid.context.core.model.UserFacingErrors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.net.SocketTimeoutException

class ModelErrorMapperTest {
    private fun map(t: Throwable) = ModelErrorMapper.toUserFacing(t)

    @Test fun missingBindingIsNotSetUp() =
        assertEquals(UserFacingErrors.AiNotSet, map(ModelCallException("No model is set for ANSWER", retryable = true)))

    @Test fun deletedEndpointIsNotSetUp() =
        assertEquals(UserFacingErrors.AiNotSet, map(ModelCallException("Endpoint for ANSWER was deleted", retryable = true)))

    @Test fun rejectedKeyIsKeyRejected() = assertEquals(
        UserFacingErrors.AiKeyRejected,
        map(ModelCallException("Primary provider failed: Azure OpenAI request failed: 401 invalid ***", retryable = false)),
    )

    @Test fun otherPermanentFailureIsRequestRejected() = assertEquals(
        UserFacingErrors.AiRequestRejected,
        map(ModelCallException("Primary provider failed: Azure OpenAI request failed: 404 deployment not found", retryable = false)),
    )

    @Test fun transientFailureIsUnavailable() = assertEquals(
        UserFacingErrors.AiUnavailable,
        map(ModelCallException("Primary provider failed: Azure OpenAI request failed: 500 upstream", retryable = true)),
    )

    @Test fun networkErrorIsUnavailable() = assertEquals(UserFacingErrors.AiUnavailable, map(SocketTimeoutException("timeout")))

    @Test fun anythingElseIsGeneric() =
        assertEquals(UserFacingErrors.Unknown, map(IllegalStateException("No Rokid frames available for summarization")))

    @Test fun providerTextNeverReachesTheLine() {
        val line = map(ModelCallException("Primary provider failed: 500 secret-body", retryable = true)).line
        assertFalse(line, "secret" in line || "500" in line)
    }
}
