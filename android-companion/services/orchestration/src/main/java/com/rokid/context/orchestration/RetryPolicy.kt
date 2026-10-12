package com.rokid.context.orchestration

/**
 * Phase 2 carry: a RETRY outcome is retried at most [MAX_ATTEMPTS] times in total, then the job fails. With the
 * 30 s × 2ⁿ backoff (capped at 30 min) the last attempt runs about an hour after the first. Policy deferrals
 * (battery saver, low battery) also count, as they always incremented `attemptCount`; the 24 h TTL still applies.
 */
object RetryPolicy {
    const val MAX_ATTEMPTS = 8

    enum class Decision { DEFER, FAIL }

    /** [attemptCount] = attempts already made before this one (`QueueJobRecord.attemptCount`). */
    fun decide(outcome: RetryClassifier.Outcome, attemptCount: Int): Decision =
        if (outcome == RetryClassifier.Outcome.RETRY && attemptCount + 1 < MAX_ATTEMPTS) Decision.DEFER else Decision.FAIL
}
