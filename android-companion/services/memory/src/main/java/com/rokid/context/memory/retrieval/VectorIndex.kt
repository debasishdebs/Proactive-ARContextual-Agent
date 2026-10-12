package com.rokid.context.memory.retrieval

import com.rokid.context.memory.db.EmbeddingDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class Hit(val id: String, val score: Double)

/** spec §4.6 `VectorIndex`: swappable; v1 is brute force (fine below ~50k memories, spec §7). Reads one space only. */
interface VectorIndex {
    suspend fun search(spaceId: String, query: FloatArray, limit: Int): List<Hit>
}

class BruteForceVectorIndex(private val dao: EmbeddingDao) : VectorIndex {
    override suspend fun search(spaceId: String, query: FloatArray, limit: Int): List<Hit> {
        if (!VectorMath.usable(query)) return emptyList()
        val rows = dao.vectors(spaceId)
        return withContext(Dispatchers.Default) {
            rows.mapNotNull { r ->
                // A corrupt blob or a vector of another dimension is skipped, never scored.
                val v = runCatching { VectorCodec.decode(r.blob) }.getOrNull()
                if (v == null || v.size != query.size) null else Hit(r.memoryId, VectorMath.cosine(query, v))
            }.sortedByDescending { it.score }.take(limit.coerceAtLeast(0))
        }
    }
}
