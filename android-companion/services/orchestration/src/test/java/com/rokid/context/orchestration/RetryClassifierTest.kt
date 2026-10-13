package com.rokid.context.orchestration

import com.rokid.context.ai.ModelCallException
import com.rokid.context.orchestration.RetryClassifier.Outcome
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Test

class RetryClassifierTest {
    @Test fun modelErrorsFollowTheirFlag() {
        assertEquals(Outcome.RETRY, RetryClassifier.classify(ModelCallException("No model is set for ANSWER", retryable = true)))
        assertEquals(Outcome.RETRY, RetryClassifier.classify(ModelCallException("Primary provider failed: Azure OpenAI request failed: 500", retryable = true)))
        assertEquals(Outcome.FAIL, RetryClassifier.classify(ModelCallException("Primary provider failed: Azure OpenAI request failed: 401", retryable = false)))
    }

    @Test fun ioErrorsRetry() {
        assertEquals(Outcome.RETRY, RetryClassifier.classify(java.net.SocketTimeoutException("read timed out")))
        assertEquals(Outcome.RETRY, RetryClassifier.classify(java.net.UnknownHostException("no host")))
    }

    @Test fun cancellationIsItsOwnOutcome() {
        assertEquals(Outcome.CANCELLED, RetryClassifier.classify(CancellationException("worker stopped")))
    }

    @Test fun nonModelErrorsKeepLegacyRules() {
        assertEquals(Outcome.FAIL, RetryClassifier.classify(IllegalStateException("No Rokid frames available for summarization")))
        assertEquals(Outcome.FAIL, RetryClassifier.classify(IllegalArgumentException("Empty explicit write input")))
        assertEquals(Outcome.RETRY, RetryClassifier.classify(IllegalStateException("connection reset")))
    }

    @Test fun serverErrorBodyMentioning404IsStillRetry() {
        val raw = IllegalStateException("Azure OpenAI request failed: 500 upstream said route 404 missing; connection reset")
        assertEquals(Outcome.RETRY, RetryClassifier.classify(raw))
        assertEquals(Outcome.FAIL, RetryClassifier.classify(IllegalStateException("Azure OpenAI request failed: 404 not here; connection")))
    }

    /** Carry: typed pipeline failures are FAIL by type, before (and whatever) the legacy wording says. */
    @Test fun typedFailuresBeatWording() {
        assertEquals(Outcome.FAIL, RetryClassifier.classify(NoMemoriesException()))
        assertEquals(Outcome.FAIL, RetryClassifier.classify(EmptyQuestionException()))
        assertEquals(Outcome.FAIL, RetryClassifier.classify(com.rokid.context.data.runtime.StalePayloadException()))
        // Subclasses whose message would match the legacy "retry" wording are still FAIL.
        assertEquals(Outcome.FAIL, RetryClassifier.classify(object : com.rokid.context.data.runtime.StalePayloadException() { override val message: String = "connection timeout" }))
    }
}
