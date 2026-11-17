package com.rokid.context.memory.retrieval

import com.rokid.context.memory.db.EmbeddingDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.PriorityQueue

data class Hit(val id: String, val score: Double)

/** An event-time window `[fromMs, toMs)` for retrieval: the photo's capture time, else when the memory was written (retrieval-quality spec section 4). */
data class EventWindow(val fromMs: Long, val toMs: Long)

/** spec §4.6 `VectorIndex`: swappable; v1 is brute force (fine below ~50k memories, spec §7). Reads one space only. */
interface VectorIndex {
    /** Only memories with confidence ≥ [minConfidence] are candidates, so a threshold never empties the top-[limit]. */
    suspend fun search(spaceId: String, query: FloatArray, limit: Int, minConfidence: Double = 0.0): List<Hit>

    /**
     * [search] over memories created at or before [notAfterMs], applied BEFORE the top-[limit] cut (S34). An index that cannot bound by time must
     * say so: the default throws, and [SemanticRetriever] then falls back to lexical like any index failure.
     */
    suspend fun searchUntil(spaceId: String, query: FloatArray, limit: Int, minConfidence: Double, notAfterMs: Long): List<Hit> =
        throw UnsupportedOperationException("index cannot bound by time")

    /** [search] over memories whose event time is in [window] (and created at or before [notAfterMs]), applied BEFORE the top-[limit] cut. Same default as [searchUntil]. */
    suspend fun searchWindow(spaceId: String, query: FloatArray, limit: Int, minConfidence: Double, notAfterMs: Long?, window: EventWindow): List<Hit> =
        throw UnsupportedOperationException("index cannot bound by event time")

    /** Which of [ids] have a vector in [spaceId]; null = this index cannot say (callers then treat every id as embedded, today's behaviour). */
    suspend fun embeddedAmong(spaceId: String, ids: Collection<String>): Set<String>? = null
}

/**
 * Streams the space in rowid keyset pages of [pageSize] rows (an index range seek per page, no sort) into a bounded
 * [TopHits] heap, decoding each blob into one reused buffer, so peak memory is O(page + k) whatever the space size.
 * Only live OK memories are scored
 * ([EmbeddingDao.livePage]). The result equals a full sort by score descending, then id ascending. Runs on
 * [Dispatchers.Default], never on Main.
 */
class BruteForceVectorIndex(private val dao: EmbeddingDao, private val pageSize: Int = 500) : VectorIndex {
    override suspend fun search(spaceId: String, query: FloatArray, limit: Int, minConfidence: Double): List<Hit> = scan(spaceId, query, limit, minConfidence, null)

    override suspend fun searchUntil(spaceId: String, query: FloatArray, limit: Int, minConfidence: Double, notAfterMs: Long): List<Hit> =
        scan(spaceId, query, limit, minConfidence, notAfterMs)

    override suspend fun searchWindow(spaceId: String, query: FloatArray, limit: Int, minConfidence: Double, notAfterMs: Long?, window: EventWindow): List<Hit> =
        scan(spaceId, query, limit, minConfidence, notAfterMs, window)

    override suspend fun embeddedAmong(spaceId: String, ids: Collection<String>): Set<String>? =
        withContext(Dispatchers.Default) { ids.toList().chunked(CHUNK).flatMap { dao.embeddedAmong(spaceId, it) }.toSet() }

    private suspend fun scan(spaceId: String, query: FloatArray, limit: Int, minConfidence: Double, notAfterMs: Long?, window: EventWindow? = null): List<Hit> {
        if (limit <= 0 || !VectorMath.usable(query)) return emptyList()
        val page = pageSize.coerceAtLeast(1)
        return withContext(Dispatchers.Default) {
            val top = TopHits(limit)
            val v = FloatArray(query.size) // reused for every row: no per-vector allocation
            var after = 0L
            while (true) {
                val rows = when {
                    window != null -> dao.livePageWindow(spaceId, after, minConfidence, notAfterMs ?: Long.MAX_VALUE, window.fromMs, window.toMs, page)
                    notAfterMs == null -> dao.livePage(spaceId, after, minConfidence, page)
                    else -> dao.livePageUntil(spaceId, after, minConfidence, notAfterMs, page)
                }
                for (r in rows) {
                    // A corrupt blob or a vector of another dimension is skipped, never scored.
                    if (!VectorCodec.decodeInto(r.blob, v)) continue
                    top.offer(Hit(r.memoryId, VectorMath.cosine(query, v)))
                }
                if (rows.size < page) break
                after = rows.last().rowid
            }
            top.sorted()
        }
    }
}

private const val CHUNK = 900 // below SQLite's 999 bound-parameter limit

/** Bounded top-[k]: keeps the best hits by score descending, then id ascending, in O(k) memory. */
internal class TopHits(private val k: Int) {
    // Head = the worst kept hit: lowest score, and among equal scores the largest id.
    private val heap = PriorityQueue(minOf(k.coerceAtLeast(0), 1_024) + 1, WORST_FIRST)

    fun offer(hit: Hit) {
        if (k <= 0) return
        if (heap.size < k) heap.add(hit) else if (WORST_FIRST.compare(hit, heap.peek()) > 0) { heap.poll(); heap.add(hit) }
    }

    fun sorted(): List<Hit> = heap.sortedWith(WORST_FIRST.reversed())

    private companion object {
        val WORST_FIRST: Comparator<Hit> = compareBy<Hit> { it.score }.thenByDescending { it.id }
    }
}
