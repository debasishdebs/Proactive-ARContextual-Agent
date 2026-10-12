package com.rokid.context.memory.retrieval

import com.rokid.context.memory.db.RetrievalDao
import com.rokid.context.memory.db.SearchNormalizer

/**
 * spec §4.6 `LexicalRetriever`: every FTS4 match is BM25-scored in Kotlin. Matches stream in docid pages into a bounded
 * top-[limit] heap, so no match is cut before it is ranked (a newest best match is never lost behind older rows).
 */
class LexicalRetriever(private val dao: RetrievalDao) {
    suspend fun search(query: String, limit: Int, minConfidence: Double = 0.0): List<Hit> {
        if (limit <= 0) return emptyList()
        val match = SearchNormalizer.toAnyMatchQuery(query) ?: return emptyList()
        val top = TopHits(limit)
        var after = 0L
        while (true) {
            val rows = dao.lexicalPage(match, after, minConfidence, PAGE)
            for (r in rows) top.offer(Hit(r.id, Bm25.score(Bm25.ints(r.mi))))
            if (rows.size < PAGE) break
            after = rows.last().rowid
        }
        return top.sorted()
    }

    private companion object { const val PAGE = 500 }
}
