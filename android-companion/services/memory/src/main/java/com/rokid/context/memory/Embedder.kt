package com.rokid.context.memory

enum class Modality { TEXT, IMAGE }

/** Implemented in `:services:ai` (Phase 5) so this module never depends on it. */
interface EmbeddingService {
    /** Canonical fingerprint of the embedding space this embedder writes (spec §4.6). */
    val fingerprint: String
    val modalities: Set<Modality> get() = setOf(Modality.TEXT)
    suspend fun embedDocuments(texts: List<String>): List<FloatArray>
    suspend fun embedQuery(text: String): FloatArray
    /** The un-shortened query vector (768 for Nomic), for shortening per index; the same as [embedQuery] when there's nothing to shorten. */
    suspend fun embedQueryFull(text: String): FloatArray = embedQuery(text)
    suspend fun embedImages(jpegs: List<ByteArray>): List<FloatArray> = throw UnsupportedOperationException("text-only space")
}

/** Every existing call site keeps saying `Embedder`. */
typealias Embedder = EmbeddingService

/** Builds the embedder for a stored space from *its* config (spec §4.6: query with the space's own config). */
fun interface SpaceEmbedderProvider {
    suspend fun forSpace(space: com.rokid.context.memory.db.EmbeddingSpaceEntity): Embedder?
}
