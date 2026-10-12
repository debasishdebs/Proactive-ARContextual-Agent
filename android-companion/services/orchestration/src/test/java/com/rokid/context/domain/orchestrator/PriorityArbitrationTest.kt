package com.rokid.context.domain.orchestrator

import com.rokid.context.domain.queue.JobEnvelope
import com.rokid.context.domain.queue.QueuePriority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.security.MessageDigest
import java.util.Locale

class PriorityArbitrationTest {

    @Test
    fun contentionOrderHonorsSpecPriorities() {
        val arbitrated = QueuePriority.arbitrate(
            QueuePriority.RETRY,
            QueuePriority.READ,
            QueuePriority.SUMMARIZER,
            QueuePriority.EXPLICIT_WRITE,
            QueuePriority.FRAME_INGEST,
            QueuePriority.READ,
            QueuePriority.PRIORITY_REPROCESS,
            QueuePriority.RETRY,
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
        val queue = QueuePriority.SUMMARIZER
        val payload = "{\"frameId\":42,\"intent\":\"summary\"}"
        val inBucketA = 1704067200000L
        val stillBucketA = inBucketA + 59 * 60 * 1000
        val inBucketB = inBucketA + 60 * 60 * 1000

        val expectedHash = sha256Hex(payload)
        val expectedBucketA = inBucketA / (60 * 60 * 1000)

        val keyA1 = JobEnvelope.idempotencyKey(queue, payload, inBucketA)
        val keyA2 = JobEnvelope.idempotencyKey(queue, payload, stillBucketA)
        val keyB = JobEnvelope.idempotencyKey(queue, payload, inBucketB)

        assertEquals("summarizer:$expectedHash:$expectedBucketA", keyA1)
        assertEquals(keyA1, keyA2)
        assertNotEquals(keyA1, keyB)
    }

    @Test
    fun idempotencyKeyQueueCanonicalizationIsLocaleStable() {
        val queue = QueuePriority.PRIORITY_REPROCESS
        val payload = "{\"frameId\":42,\"intent\":\"summary\"}"
        val timestamp = 1704067200000L
        val expectedHash = sha256Hex(payload)
        val expectedBucket = timestamp / (60 * 60 * 1000)

        withDefaultLocale(Locale("tr", "TR")) {
            val key = JobEnvelope.idempotencyKey(queue, payload, timestamp)
            assertEquals("priority_reprocess:$expectedHash:$expectedBucket", key)
        }
    }

    @Test
    fun idempotencyKeyDiffersAcrossPrioritiesForSamePayloadAndBucket() {
        val payload = "{\"frameId\":42,\"intent\":\"summary\"}"
        val timestamp = 1704067200000L

        val summaryEnvelope =
            JobEnvelope(
                queue = QueuePriority.SUMMARIZER,
                payload = payload,
                enqueuedAtEpochMillis = timestamp,
            )
        val readEnvelope =
            JobEnvelope(
                queue = QueuePriority.READ,
                payload = payload,
                enqueuedAtEpochMillis = timestamp,
            )

        assertNotEquals(summaryEnvelope.idempotencyKey, readEnvelope.idempotencyKey)
    }

    @Test
    fun idempotencyKeyInstancePropertyMatchesHelperContract() {
        val queue = QueuePriority.SUMMARIZER
        val payload = "{\"frameId\":99,\"intent\":\"summary\"}"
        val timestamp = 1704067200000L

        val envelope =
            JobEnvelope(
                queue = queue,
                payload = payload,
                enqueuedAtEpochMillis = timestamp,
            )

        assertEquals(
            JobEnvelope.idempotencyKey(queue, payload, timestamp),
            envelope.idempotencyKey,
        )
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private inline fun withDefaultLocale(locale: Locale, block: () -> Unit) {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(locale)
            block()
        } finally {
            Locale.setDefault(original)
        }
    }
}
