package com.rokid.context.memory.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Keyframe capture wave 1 (Room v2, additive). Per-frame capture metadata; [wireFrameId] is the glasses-side id, unique. */
@Entity(tableName = "frame_meta", indices = [Index(value = ["wireFrameId"], unique = true)])
data class FrameMetaEntity(
    @PrimaryKey val frameId: String,
    val wireFrameId: String?,
    val sessionId: String?,
    val motion: String,
    val dwell: Boolean,
    val blur: Float?,
    val dhash: String?,
    val phash: String?,
    val width: Int?,
    val salience: Float?,
    val salienceTerms: String?,
    val clusterId: String?,
    val nearDupOf: String?,
    val imageVectorSpace: String?,
    val createdAt: Long,
)

enum class FrameState { PENDING, EXTRACTING, EXTRACTED, CLUSTERED, NEAR_DUP, FAILED, FAILED_FINAL }

/** Durable per-frame processing state: the backlog's source of truth. A claim is [claimedAt] + random [claimId]. */
@Entity(tableName = "frame_state", indices = [Index("state")])
data class FrameStateEntity(
    @PrimaryKey val frameId: String,
    val state: String,
    val attempts: Int,
    val lastError: String?,
    val claimedAt: Long?,
    val claimId: String?,
    val updatedAt: Long,
)

/** memory → frame provenance (the memory→memory edges stay in `memory_source`). [role]: FACT | CLUSTER_REP | CLUSTER_MEMBER. */
@Entity(tableName = "frame_source", primaryKeys = ["memoryId", "frameId"], indices = [Index("frameId")])
data class FrameSourceEntity(val memoryId: String, val frameId: String, val clusterId: String?, val role: String)

@Entity(tableName = "frame_cluster")
data class FrameClusterEntity(
    @PrimaryKey val clusterId: String,
    val openedAt: Long,
    val closedAt: Long?,
    val closeReason: String?,
    val repFrameIds: String,
    val memoryId: String?,
)

@Entity(tableName = "frame_vector", primaryKeys = ["frameId", "spaceId"], indices = [Index("spaceId")])
class FrameVectorEntity(val frameId: String, val spaceId: String, val blob: ByteArray) {
    override fun equals(other: Any?) = other is FrameVectorEntity && frameId == other.frameId && spaceId == other.spaceId && blob.contentEquals(other.blob)
    override fun hashCode() = 31 * (31 * frameId.hashCode() + spaceId.hashCode()) + blob.contentHashCode()
}
