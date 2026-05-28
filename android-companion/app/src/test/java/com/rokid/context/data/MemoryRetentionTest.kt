package com.rokid.context.data

import com.rokid.context.domain.queue.QueuePriority
import com.rokid.context.domain.summary.CriticWorker
import com.rokid.context.domain.summary.SummaryStatus
import com.rokid.context.domain.summary.SummarizerWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRetentionTest {
    private val criticWorker = CriticWorker(confidenceThreshold = 0.7)
    private val summarizerWorker = SummarizerWorker()

    @Test
    fun criticMarksLowConfidenceSummaryAsNeedsReviewAndExcludesFromReadContext() {
        val result = criticWorker.validate(summaryId = "s-1", confidence = 0.42)

        assertEquals(SummaryStatus.NEEDS_REVIEW, result.status)
        assertFalse(summarizerWorker.shouldIncludeInReadContext(result.status))
    }

    @Test
    fun priorityReprocessIsTriggeredForNeedsReviewSummaries() {
        val result = criticWorker.validate(summaryId = "s-2", confidence = 0.5)

        assertEquals(
            QueuePriority.PRIORITY_REPROCESS,
            summarizerWorker.reprocessQueuePriorityFor(result.status),
        )
    }

    @Test
    fun trustedSummaryDoesNotTriggerPriorityReprocess() {
        val result = criticWorker.validate(summaryId = "s-3", confidence = 0.91)

        assertTrue(summarizerWorker.shouldIncludeInReadContext(result.status))
        assertNull(summarizerWorker.reprocessQueuePriorityFor(result.status))
    }

    @Test
    fun rawFramesAreDeletedAt24HourTtlWhenCriticNotValidated() {
        val createdAt = 1_000L
        val now = createdAt + (24L * 60L * 60L * 1000L)

        assertTrue(
            summarizerWorker.shouldDeleteRawFrame(
                rawFrameCreatedAtMs = createdAt,
                nowMs = now,
                criticValidated = false,
            ),
        )
    }
}
