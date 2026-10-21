package com.rokid.context.memory.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Keyframe capture wave 1 (Room v2, additive). Per-frame capture metadata; [wireFrameId] is the glasses-side id, unique. */
@Entity(tableName = "frame_meta", indices = [Index(value = ["wireFrameId"], unique = true), Index("clusterId")])
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
    /** Room v4 (wave 2): null = the scene router has not seen this frame yet. */
    val routedAt: Long? = null,
)

enum class FrameState { PENDING, EXTRACTING, EXTRACTED, CLUSTERED, NEAR_DUP, FAILED, FAILED_FINAL, IN_CLUSTER, CAPTION_QUEUED }

enum class ClusterState { OPEN, CLOSED, SUMMARIZING, SUMMARIZED, FAILED, FAILED_FINAL }

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

/** A scene cluster. Room v4 (wave 2) added the claim/state columns (the summary call's claim lives here, not on the members). */
@Entity(tableName = "frame_cluster", indices = [Index("state")])
data class FrameClusterEntity(
    @PrimaryKey val clusterId: String,
    val openedAt: Long,
    val closedAt: Long?,
    val closeReason: String?,
    val repFrameIds: String,
    val memoryId: String?,
    @ColumnInfo(defaultValue = "'OPEN'") val state: String = ClusterState.OPEN.name,
    @ColumnInfo(defaultValue = "0") val attempts: Int = 0,
    val claimId: String? = null,
    val claimedAt: Long? = null,
    val lastError: String? = null,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
    /** STILL | MOVING */
    val motionClass: String? = null,
    @ColumnInfo(defaultValue = "0") val memberCount: Int = 0,
    val lastTs: Long? = null,
)

@Entity(tableName = "frame_vector", primaryKeys = ["frameId", "spaceId"], indices = [Index("spaceId")])
class FrameVectorEntity(val frameId: String, val spaceId: String, val blob: ByteArray) {
    override fun equals(other: Any?) = other is FrameVectorEntity && frameId == other.frameId && spaceId == other.spaceId && blob.contentEquals(other.blob)
    override fun hashCode() = 31 * (31 * frameId.hashCode() + spaceId.hashCode()) + blob.contentHashCode()
}
