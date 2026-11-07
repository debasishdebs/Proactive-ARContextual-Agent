package com.rokid.context.memory.retrieval

import com.rokid.context.core.log.PcLog
import com.rokid.context.memory.EmbeddingSpaces
import com.rokid.context.memory.SpaceEmbedderProvider
import com.rokid.context.memory.db.SpaceState
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
    suspend fun search(query: String, limit: Int, minConfidence: Double = 0.0, notAfterMs: Long? = null): List<Hit>? =
        searchOrOff(query, limit, minConfidence, notAfterMs).hits

    /** As [search], with the reason when it is unavailable (2026-11-08 quality fix: the reason is shown, never a silent degrade). */
    suspend fun searchOrOff(query: String, limit: Int, minConfidence: Double = 0.0, notAfterMs: Long? = null): SemanticSearch = try {
        val current = spaces.current()
        val space = current?.takeIf { it.state == SpaceState.ACTIVE }
        if (space == null) {
            SemanticSearch(null, when {
                current?.state == SpaceState.BUILDING || spaces.building() != null -> SemanticOff.INDEX_BUILDING // a first build or a rebuild
                current == null -> SemanticOff.NO_INDEX
                else -> SemanticOff.INDEX_STALE
            })
        } else {
            // forSpace reads the endpoint key from the vault (Keystore decryption): IO, never the caller's thread.
            val embedder = withContext(Dispatchers.IO) { embedders.forSpace(space) }
            if (embedder == null) SemanticSearch(null, SemanticOff.MODEL_CHANGED)
            else {
                val q = embedder.embedQuery(query)
                if (q.size != space.dim || !VectorMath.usable(q)) SemanticSearch(null, SemanticOff.QUERY_FAILED)
                else SemanticSearch(if (notAfterMs == null) index.search(space.id, q, limit, minConfidence) else index.searchUntil(space.id, q, limit, minConfidence, notAfterMs), null)
            }
        }
    } catch (c: CancellationException) {
        throw c
    } catch (e: Exception) {
        PcLog.i(TAG, "semantic search unavailable (${e.javaClass.simpleName}); using lexical")
        SemanticSearch(null, SemanticOff.QUERY_FAILED)
    }

    private companion object { const val TAG = "SemanticRetriever" }
}

/** [hits] null = unavailable, and then [off] says why. */
data class SemanticSearch(val hits: List<Hit>?, val off: SemanticOff?)

/**
 * Why semantic search could not run (fixed words only: no exception text, no endpoint names). [text] is what the user reads after
 * "Semantic search is off: ".
 */
enum class SemanticOff(val text: String) {
    /** No embedding space was ever built: in practice no Embeddings model is set (the index job is idle without one). */
    NO_INDEX("no Embeddings model is set. Download Nomic Embed in Settings > AI > On-device models and use it for Embeddings, or bind an embeddings endpoint in Settings > Model endpoints > Embeddings"),
    INDEX_BUILDING("the search index is still being built (on-device: only while charging or above 70 % battery)"),
    INDEX_STALE("the search index needs a rebuild (Settings > Retrieval > Rebuild)"),
    MODEL_CHANGED("the Embeddings endpoint changed since the index was built; it rebuilds in the background"),
    QUERY_FAILED("the Embeddings model or the search index failed for this question"),
}
