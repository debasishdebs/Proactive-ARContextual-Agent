package com.rokid.context.memory.retrieval

import com.rokid.context.memory.db.RetrievalDao
import com.rokid.context.memory.db.SearchNormalizer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * spec §4.6 `LexicalRetriever`: every FTS4 match is BM25-scored in Kotlin. One cursor per query (the FTS doclists are
 * read once, Phase 8 B2) streams every match into a bounded top-[limit] heap, so no match is cut before it is ranked
 * and peak memory is O(k) plus one CursorWindow. Reads on [io], never on Main; the cursor is closed on every path.
 */
class LexicalRetriever(private val dao: RetrievalDao, private val io: CoroutineDispatcher = Dispatchers.IO) {
    /** [notAfterMs] (S34): only memories created at or before it are candidates, applied in the query, before [limit]. Null = today's behaviour. */
    suspend fun search(query: String, limit: Int, minConfidence: Double = 0.0, notAfterMs: Long? = null, window: EventWindow? = null): List<Hit> {
        if (limit <= 0) return emptyList()
        val match = SearchNormalizer.toAnyMatchQuery(query) ?: return emptyList()
        return withContext(io) {
            val top = TopHits(limit)
            val cursor = when {
                window != null -> dao.lexicalCursorWindow(match, minConfidence, notAfterMs ?: Long.MAX_VALUE, window.fromMs, window.toMs) // [window]: event time, before [limit]
                notAfterMs == null -> dao.lexicalCursor(match, minConfidence)
                else -> dao.lexicalCursorUntil(match, minConfidence, notAfterMs)
            }
            try {
                val idCol = cursor.getColumnIndexOrThrow("id")
                val miCol = cursor.getColumnIndexOrThrow("mi")
                var n = 0
                while (cursor.moveToNext()) {
                    if (++n and CHECK_MASK == 0) coroutineContext.ensureActive()
                    top.offer(Hit(cursor.getString(idCol), Bm25.score(Bm25.ints(cursor.getBlob(miCol)))))
                }
            } finally {
                cursor.close()
            }
            top.sorted()
        }
    }

    /** Cancellation is checked every 256 rows. */
    private companion object { const val CHECK_MASK = 255 }
}
