package com.rokid.context.memory.retrieval

import com.rokid.context.memory.db.EmbeddingDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.PriorityQueue

data class Hit(val id: String, val score: Double)

/** spec §4.6 `VectorIndex`: swappable; v1 is brute force (fine below ~50k memories, spec §7). Reads one space only. */
interface VectorIndex {
    suspend fun search(spaceId: String, query: FloatArray, limit: Int): List<Hit>
}

/**
 * Streams the space in keyset pages of [pageSize] rows and keeps a bounded top-[limit] heap, so peak memory is
 * O(page + k) whatever the space size. Only live OK memories are scored ([EmbeddingDao.livePage]). The result equals a
 * full sort by score descending, then id ascending. Runs on [Dispatchers.Default], never on Main.
 */
class BruteForceVectorIndex(private val dao: EmbeddingDao, private val pageSize: Int = 500) : VectorIndex {
    override suspend fun search(spaceId: String, query: FloatArray, limit: Int): List<Hit> {
        if (limit <= 0 || !VectorMath.usable(query)) return emptyList()
        val page = pageSize.coerceAtLeast(1)
        return withContext(Dispatchers.Default) {
            // Head = the worst kept hit: lowest score, and among equal scores the largest id.
            val heap = PriorityQueue(minOf(limit, 1_024) + 1, WORST_FIRST)
            var after = ""
            while (true) {
                val rows = dao.livePage(spaceId, after, page)
                for (r in rows) {
                    // A corrupt blob or a vector of another dimension is skipped, never scored.
                    val v = runCatching { VectorCodec.decode(r.blob) }.getOrNull()
                    if (v == null || v.size != query.size) continue
                    val hit = Hit(r.memoryId, VectorMath.cosine(query, v))
                    if (heap.size < limit) heap.add(hit)
                    else if (WORST_FIRST.compare(hit, heap.peek()) > 0) { heap.poll(); heap.add(hit) }
                }
                if (rows.size < page) break
                after = rows.last().memoryId
            }
            heap.sortedWith(WORST_FIRST.reversed())
        }
    }

    private companion object {
        val WORST_FIRST: Comparator<Hit> = compareBy<Hit> { it.score }.thenByDescending { it.id }
    }
}
