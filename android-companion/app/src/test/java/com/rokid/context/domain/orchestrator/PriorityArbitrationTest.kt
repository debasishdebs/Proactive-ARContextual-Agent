package com.rokid.context.domain.orchestrator

import com.rokid.context.domain.queue.JobEnvelope
import com.rokid.context.domain.queue.QueuePriority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.security.MessageDigest

class PriorityArbitrationTest {

    @Test
    fun contentionOrderHonorsSpecPriorities() {
        val arbitrated = QueuePriority.arbitrate(
            QueuePriority.RETRY,
            QueuePriority.READ,
            QueuePriority.SUMMARIZER,
            QueuePriority.EXPLICIT_WRITE,
            QueuePriority.FRAME_INGEST,
            QueuePriority.PRIORITY_REPROCESS,
        )

        assertEquals(
            listOf(
                QueuePriority.PRIORITY_REPROCESS,
                QueuePriority.EXPLICIT_WRITE,
                QueuePriority.READ,
                QueuePriority.SUMMARIZER,
                QueuePriority.FRAME_INGEST,
                QueuePriority.RETRY,
            ),
            arbitrated,
        )
    }

    @Test
    fun idempotencyKeyUsesQueuePayloadHashAndSixtyMinuteBucket() {
        val queue = "summarizer"
        val payload = "{\"frameId\":42,\"intent\":\"summary\"}"
        val inBucketA = 1704067200000L
        val stillBucketA = inBucketA + 59 * 60 * 1000
        val inBucketB = inBucketA + 60 * 60 * 1000

        val expectedHash = sha256Hex(payload)
        val expectedBucketA = inBucketA / (60 * 60 * 1000)

        val keyA1 = JobEnvelope.idempotencyKey(queue, payload, inBucketA)
        val keyA2 = JobEnvelope.idempotencyKey(queue, payload, stillBucketA)
        val keyB = JobEnvelope.idempotencyKey(queue, payload, inBucketB)

        assertEquals("$queue:$expectedHash:$expectedBucketA", keyA1)
        assertEquals(keyA1, keyA2)
        assertNotEquals(keyA1, keyB)
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
