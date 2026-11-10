package com.rokid.context.memory.db

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

enum class MemoryTier { SHORT, MEDIUM, LONG }
enum class ReviewState { OK, NEEDS_REVIEW, INVALIDATED }
enum class SpaceState { BUILDING, ACTIVE, STALE, RETIRED }

/** spec §6.1 `frame`. [payload] = legacy metadata excerpt. JPEG stays on disk at [path]. */
@Entity(tableName = "frame", indices = [Index("ts")])
data class FrameEntity(
    @PrimaryKey val id: String,
    val ts: Long,
    val path: String,
    val source: String,
    val payload: String = "",
    val luma: Int? = null,
    val phash: String? = null,
    val placeLabel: String? = null,
)

/** spec §6.1 `memory`. [searchText] is the normalised text the FTS table indexes (see [SearchNormalizer]). */
@Entity(tableName = "memory", indices = [Index("frameId"), Index("createdAt")])
data class MemoryEntity(
    @PrimaryKey val id: String,
    val frameId: String?,
    val text: String,
    val type: String,
    val tags: String,
    val tier: MemoryTier,
    val confidence: Double,
    val reviewState: ReviewState,
    val trusted: Boolean,
    val createdAt: Long,
    val derived: Boolean,
    val searchText: String,
    /**
     * Room v16 (user ruling 2026-11-10): whether the photo(s) this memory describes provably reached the model on the call that wrote it. null = unknown
     * (every row written before v16), true = the router's delivery proof, false = written without one (text-only writes, unproved canvas facts).
     * A false row never counts as describing its photo.
     */
    val imageDelivered: Boolean? = null,
    /** Room v16: when the user marked this memory for review (Diagnostics > Suspect descriptions); null = not marked. A marked row never describes its photo. */
    val reviewFlaggedAt: Long? = null,
)

/** External-content FTS4 over [MemoryEntity.searchText]; Room keeps it in sync with triggers. Default `simple` tokenizer. */
@Fts4(contentEntity = MemoryEntity::class)
@Entity(tableName = "memory_fts")
data class MemoryFts(val searchText: String)

@Entity(tableName = "memory_source", primaryKeys = ["memoryId", "sourceMemoryId"], indices = [Index("sourceMemoryId")])
data class MemorySourceEntity(val memoryId: String, val sourceMemoryId: String)

/** A frame whose extraction is locked after Forget fact / Forget moment (spec §6.1.1). */
@Entity(tableName = "forget_tombstone")
data class ForgetTombstoneEntity(@PrimaryKey val frameId: String, val createdAt: Long)

@Entity(tableName = "embedding_space", indices = [Index(value = ["fingerprint"], unique = true), Index("state")])
data class EmbeddingSpaceEntity(
    @PrimaryKey val id: String,
    val fingerprint: String,
    val configJson: String,
    val dim: Int,
    val state: SpaceState,
    val canaryJson: String,
    val createdAt: Long,
)

@Entity(tableName = "memory_vector", primaryKeys = ["memoryId", "spaceId"], indices = [Index("spaceId")])
data class MemoryVectorEntity(val memoryId: String, val spaceId: String, val blob: ByteArray) {
    override fun equals(other: Any?) = other is MemoryVectorEntity && memoryId == other.memoryId && spaceId == other.spaceId && blob.contentEquals(other.blob)
    override fun hashCode() = 31 * (31 * memoryId.hashCode() + spaceId.hashCode()) + blob.contentHashCode()
}

@Entity(tableName = "summary_trace", indices = [Index("updatedAt")])
data class SummaryTraceEntity(@PrimaryKey val summaryId: String, val state: String, val updatedAt: Long, val note: String)
