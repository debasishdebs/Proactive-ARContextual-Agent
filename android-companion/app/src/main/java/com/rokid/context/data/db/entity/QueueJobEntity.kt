package com.rokid.context.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "queue_jobs")
data class QueueJobEntity(
    @PrimaryKey val id: String,
    val jobType: String,
    val payload: String,
    val enqueuedAtEpochMs: Long,
    val originalEnqueuedAtEpochMs: Long,
    val attemptCount: Int,
) {
    fun requeue(requeueAtEpochMs: Long): QueueJobEntity = copy(
        enqueuedAtEpochMs = requeueAtEpochMs,
        attemptCount = attemptCount + 1,
    )
}
