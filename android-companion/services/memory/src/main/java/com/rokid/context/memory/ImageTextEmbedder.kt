package com.rokid.context.memory

import com.rokid.context.memory.retrieval.VectorMath

/**
 * A shared image-text vector space (CLIP family): a photo and a text land in the SAME space, so their cosine means "this text describes that photo". Shaped like the identity of an
 * `EmbeddingSpec` (a space id that changes when anything that decides the vectors changes, and one fixed width) so a SigLIP 2 implementation can plug in later (plan 2026-11-20 image-embedder
 * comparison). Vectors are L2-normalised, [dim] wide. Implementations may throw; callers treat any failure as "no score" and never let it change an outcome.
 */
interface ImageTextEmbedder {
    /** The fingerprint of the space: scores from two different ids are never compared. */
    val spaceId: String
    val dim: Int
    suspend fun embedImage(bytes: ByteArray): FloatArray
    suspend fun embedText(text: String): FloatArray
}

/** Today's only image-text space in production: the on-device Nomic bundle, seen through the existing [EmbeddingService]. */
class EmbeddingServiceImageText private constructor(private val inner: EmbeddingService, override val dim: Int) : ImageTextEmbedder {
    override val spaceId: String get() = inner.fingerprint

    override suspend fun embedImage(bytes: ByteArray): FloatArray = inner.embedImages(listOf(bytes)).single().also { check(it.size == dim) { "image vector width changed" } }

    /** The PhotoRecall math: the full query vector, cut to the image width, L2. */
    override suspend fun embedText(text: String): FloatArray {
        val full = inner.embedQueryFull(text)
        check(full.size >= dim) { "text vector narrower than the image space" }
        return VectorMath.l2(full.copyOf(dim))
    }

    companion object {
        /** Null when [e] has no IMAGE modality or does not declare its image width (a text-only space, or the vision file is missing). */
        fun of(e: EmbeddingService): ImageTextEmbedder? {
            if (Modality.IMAGE !in e.modalities) return null
            val d = e.imageDims ?: return null
            return EmbeddingServiceImageText(e, d)
        }
    }
}
