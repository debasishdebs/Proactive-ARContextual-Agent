package com.rokid.context.ai

import com.rokid.context.core.model.UserFacingErrors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.net.SocketTimeoutException

class ModelErrorMapperTest {
    private fun map(t: Throwable) = ModelErrorMapper.toUserFacing(t)
    private fun mce(kind: FailureKind, msg: String = "x") = ModelCallException(msg, retryable = kind == FailureKind.TRANSIENT, kind = kind)

    @Test fun eachKindHasItsMessage() {
        assertEquals(UserFacingErrors.AiNotSet, map(mce(FailureKind.NOT_SET)))
        assertEquals(UserFacingErrors.AiKeyRejected, map(mce(FailureKind.AUTH)))
        assertEquals(UserFacingErrors.AiRequestRejected, map(mce(FailureKind.REJECTED)))
        assertEquals(UserFacingErrors.AiUnavailable, map(mce(FailureKind.TRANSIENT)))
        assertEquals(UserFacingErrors.AiUnavailable, map(mce(FailureKind.INVALID_OUTPUT)))
    }

    @Test fun wordingNeverDecides() {
        assertEquals(UserFacingErrors.AiUnavailable, map(mce(FailureKind.TRANSIENT, "No model is set for ANSWER; request failed: 401")))
        assertEquals(UserFacingErrors.AiKeyRejected, map(mce(FailureKind.AUTH, "server said 500")))
    }

    @Test fun defaultKindFollowsTheRetryFlag() {
        assertEquals(UserFacingErrors.AiUnavailable, map(ModelCallException("a", retryable = true)))
        assertEquals(UserFacingErrors.AiRequestRejected, map(ModelCallException("b", retryable = false)))
    }

    @Test fun networkErrorIsUnavailable() = assertEquals(UserFacingErrors.AiUnavailable, map(SocketTimeoutException("timeout")))

    @Test fun anythingElseIsGeneric() = assertEquals(UserFacingErrors.Unknown, map(IllegalStateException("request failed: 401")))

    @Test fun providerTextNeverReachesTheLine() {
        val line = map(mce(FailureKind.TRANSIENT, "Primary provider failed: 500 secret-body")).line
        assertFalse(line, "secret" in line || "500" in line)
    }
}
