package com.rokid.context.work.frames.place

import com.rokid.context.memory.ImageTextEmbedder
import com.rokid.context.memory.retrieval.VectorMath
import com.rokid.context.work.frames.scene.ImageSpaceTarget

/** The ACTIVE image space seen as an [ImageTextEmbedder]: the same photo and text vectors PhotoRecall already uses (text cut to the image width, then L2). */
internal class ImageSpaceTargetImageText(private val t: ImageSpaceTarget) : ImageTextEmbedder {
    override val spaceId: String get() = t.fingerprint
    override val dim: Int get() = t.imageDims
    override suspend fun embedImage(bytes: ByteArray): FloatArray = t.embedImages(listOf(bytes)).single().also { check(it.size == dim) { "image vector width changed" } }
    override suspend fun embedText(text: String): FloatArray = cut(t.embedQueryFull(text))
    override suspend fun embedTexts(texts: List<String>): List<FloatArray> = t.embedQueriesFull(texts).map(::cut)
    private fun cut(full: FloatArray): FloatArray {
        check(full.size >= dim) { "text vector narrower than the image space" }
        return VectorMath.l2(full.copyOf(dim))
    }
}
