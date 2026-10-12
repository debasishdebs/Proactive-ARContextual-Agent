package com.rokid.context.memory

import com.rokid.context.memory.db.EmbedInput
import com.rokid.context.memory.db.EmbeddingDao
import com.rokid.context.memory.db.EmbeddingSpaceEntity
import com.rokid.context.memory.db.MemoryVectorEntity
import com.rokid.context.memory.db.SpaceState
import com.rokid.context.memory.retrieval.VectorCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.UUID

data class Coverage(val have: Int, val eligible: Int) { val complete: Boolean get() = have >= eligible }

/** spec §4.6 embedding generations. Semantic search reads only [queryable] (ACTIVE); BUILDING is never read. */
class EmbeddingSpaces(private val dao: EmbeddingDao) {
    suspend fun current(): EmbeddingSpaceEntity? = dao.current()
    suspend fun queryable(): EmbeddingSpaceEntity? = dao.current()?.takeIf { it.state == SpaceState.ACTIVE }
    suspend fun building(): EmbeddingSpaceEntity? = dao.building()

    suspend fun startBuilding(fingerprint: String, configJson: String, dim: Int, canaryJson: String, now: Long): EmbeddingSpaceEntity =
        dao.startBuilding(EmbeddingSpaceEntity("space_${UUID.randomUUID()}", fingerprint, configJson, dim, SpaceState.BUILDING, canaryJson, now))

    /** False when the space is gone or no longer writable (RETIRED/STALE); throws on a dimension mismatch. */
    suspend fun put(memoryId: String, spaceId: String, vector: FloatArray): Boolean =
        dao.putChecked(MemoryVectorEntity(memoryId, spaceId, VectorCodec.encode(vector)), vector.size)
    suspend fun missing(spaceId: String, limit: Int): List<EmbedInput> = dao.missing(spaceId, limit)
    suspend fun coverage(spaceId: String): Coverage = Coverage(dao.coveredCount(spaceId), dao.eligibleCount())
    suspend fun swapIfComplete(buildingId: String): Boolean = dao.swap(buildingId)
    suspend fun markStale(id: String) = dao.setState(id, SpaceState.STALE.name)
    suspend fun discard(id: String) = dao.discard(id)
    fun health(): Flow<IndexHealth> = dao.observeSpaces().map(IndexHealth::of).distinctUntilChanged()
}
