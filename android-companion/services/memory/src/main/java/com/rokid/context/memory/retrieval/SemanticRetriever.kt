package com.rokid.context.memory.retrieval

import android.util.Log
import com.rokid.context.memory.EmbeddingSpaces
import com.rokid.context.memory.SpaceEmbedderProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** spec §4.6 `SemanticRetriever`: embeds the query with the ACTIVE space's own config; never reads BUILDING vectors. */
class SemanticRetriever(
    private val spaces: EmbeddingSpaces,
    private val index: VectorIndex,
    private val embedders: SpaceEmbedderProvider,
) {
    /**
     * null = unavailable, so callers use lexical (R-51): no ACTIVE space, no embedder, a vault/Keystore failure, a
     * failed or wrong-size or unusable (zero / non-finite) query vector, or an index failure. Cancellation propagates.
     */
    suspend fun search(query: String, limit: Int, minConfidence: Double = 0.0): List<Hit>? = try {
        val space = spaces.queryable()
        // forSpace reads the endpoint key from the vault (Keystore decryption): IO, never the caller's thread.
        val embedder = space?.let { withContext(Dispatchers.IO) { embedders.forSpace(it) } }
        val q = embedder?.embedQuery(query)
        if (space == null || q == null || q.size != space.dim || !VectorMath.usable(q)) null
        else index.search(space.id, q, limit, minConfidence)
    } catch (c: CancellationException) {
        throw c
    } catch (e: Exception) {
        Log.i(TAG, "semantic search unavailable (${e.javaClass.simpleName}); using lexical")
        null
    }

    private companion object { const val TAG = "SemanticRetriever" }
}
