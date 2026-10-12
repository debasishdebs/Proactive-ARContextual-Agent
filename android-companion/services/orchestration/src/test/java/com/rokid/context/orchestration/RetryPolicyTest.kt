package com.rokid.context.orchestration

import com.rokid.context.orchestration.RetryClassifier.Outcome
import com.rokid.context.orchestration.RetryPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class RetryPolicyTest {
    @Test fun retryIsDeferredBelowTheCap() {
        assertEquals(Decision.DEFER, RetryPolicy.decide(Outcome.RETRY, attemptCount = 0))
        assertEquals(Decision.DEFER, RetryPolicy.decide(Outcome.RETRY, attemptCount = RetryPolicy.MAX_ATTEMPTS - 2))
    }

    @Test fun theLastAllowedAttemptFails() =
        assertEquals(Decision.FAIL, RetryPolicy.decide(Outcome.RETRY, attemptCount = RetryPolicy.MAX_ATTEMPTS - 1))

    @Test fun aPermanentFailureFailsAtOnce() = assertEquals(Decision.FAIL, RetryPolicy.decide(Outcome.FAIL, attemptCount = 0))
}
