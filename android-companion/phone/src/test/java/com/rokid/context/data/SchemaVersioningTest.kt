package com.rokid.context.data

import com.rokid.context.data.db.entity.MemoryEntity
import com.rokid.context.data.db.entity.QueueJobEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SchemaVersioningTest {

    @Test
    fun memoryRecordsIncludeSchemaVersion() {
        val hasSchemaVersion = MemoryEntity::class.java.declaredFields.any { it.name == "schemaVersion" }

        assertTrue("MemoryEntity should expose schemaVersion", hasSchemaVersion)
    }

    @Test
    fun queueEntitiesPreserveOriginalTimestampOnRequeue() {
        val originalTimestamp = 1_700_000_000_000L
        val requeueTimestamp = 1_700_000_000_500L
        val queueJob = QueueJobEntity(
            id = "job-1",
            jobType = "summary",
            payload = "{}",
            enqueuedAtEpochMs = originalTimestamp,
            originalEnqueuedAtEpochMs = originalTimestamp,
            attemptCount = 0,
        )

        val requeued = queueJob.requeue(requeueTimestamp)

        assertEquals(originalTimestamp, requeued.originalEnqueuedAtEpochMs)
        assertEquals(requeueTimestamp, requeued.enqueuedAtEpochMs)
        assertEquals(1, requeued.attemptCount)
    }
}
