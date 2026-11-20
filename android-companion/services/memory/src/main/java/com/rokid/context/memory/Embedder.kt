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
    /** [embedQueryFull] for several texts in ONE call where the embedder supports it (same vectors, same order); the default is one call per text. */
    suspend fun embedQueriesFull(texts: List<String>): List<FloatArray> = texts.map { embedQueryFull(it) }
    /** The width of the image vectors [embedImages] returns (Nomic: `NomicVision.STORED_DIMS` = 768); an IMAGE-capable embedder must declare it. A vector of another width is never stored. */
    val imageDims: Int? get() = null
    /** Throws [ImageDecodeException] for a photo that cannot be decoded; any other exception is an embedder failure. */
    suspend fun embedImages(jpegs: List<ByteArray>): List<FloatArray> = throw UnsupportedOperationException("text-only space")
}

/** The only embedder failure that counts against a photo (it will not decode); everything else (model, runtime, memory) is retryable. */
class ImageDecodeException(message: String? = null, cause: Throwable? = null) : Exception(message, cause)

/** Every existing call site keeps saying `Embedder`. */
typealias Embedder = EmbeddingService

/** Builds the embedder for a stored space from *its* config (spec §4.6: query with the space's own config). */
fun interface SpaceEmbedderProvider {
    suspend fun forSpace(space: com.rokid.context.memory.db.EmbeddingSpaceEntity): Embedder?
}
