package com.rokid.context.memory.retrieval

import com.rokid.context.memory.db.RetrievalDao
import com.rokid.context.memory.db.SearchNormalizer

/** spec §4.6 `LexicalRetriever`: FTS4 candidates, BM25-ranked in Kotlin. */
class LexicalRetriever(private val dao: RetrievalDao) {
    suspend fun search(query: String, limit: Int): List<Hit> {
        val match = SearchNormalizer.toAnyMatchQuery(query) ?: return emptyList()
        return dao.lexical(match, CANDIDATES).map { Hit(it.id, Bm25.score(Bm25.ints(it.mi))) }
            .sortedByDescending { it.score }.take(limit)
    }

    private companion object { const val CANDIDATES = 1_000 }
}
