package com.rokid.context.memory.retrieval

import android.util.Log
import com.rokid.context.memory.EmbeddingSpaces
import com.rokid.context.memory.SpaceEmbedderProvider
import kotlin.coroutines.cancellation.CancellationException

/** spec §4.6 `SemanticRetriever`: embeds the query with the ACTIVE space's own config; never reads BUILDING vectors. */
class SemanticRetriever(
    private val spaces: EmbeddingSpaces,
    private val index: VectorIndex,
    private val embedders: SpaceEmbedderProvider,
) {
    /** null = unavailable (no ACTIVE space, endpoint gone, query embed failed or wrong size): callers use lexical. */
    suspend fun search(query: String, limit: Int): List<Hit>? {
        val space = spaces.queryable() ?: return null
        val embedder = embedders.forSpace(space) ?: return null
        val q = try {
            embedder.embedQuery(query)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Log.i(TAG, "query embedding failed (${e.javaClass.simpleName}); using lexical")
            return null
        }
        if (q.size != space.dim) return null
        return index.search(space.id, q, limit)
    }

    private companion object { const val TAG = "SemanticRetriever" }
}
