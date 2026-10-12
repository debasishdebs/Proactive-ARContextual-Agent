package com.rokid.context.domain.queue

import java.security.MessageDigest
import java.util.Locale

data class JobEnvelope(
    val queue: QueuePriority,
    val payload: String,
    val enqueuedAtEpochMillis: Long,
) {
    val idempotencyKey: String
        get() = idempotencyKey(queue, payload, enqueuedAtEpochMillis)

    companion object {
        private const val BUCKET_MILLIS = 60 * 60 * 1000L

        fun idempotencyKey(queue: QueuePriority, payload: String, enqueuedAtEpochMillis: Long): String {
            val canonicalQueue = queue.name.lowercase(Locale.ROOT)
            val payloadHash = sha256Hex(payload)
            val bucket = enqueuedAtEpochMillis / BUCKET_MILLIS
            return "$canonicalQueue:$payloadHash:$bucket"
        }

        private fun sha256Hex(payload: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray())
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
