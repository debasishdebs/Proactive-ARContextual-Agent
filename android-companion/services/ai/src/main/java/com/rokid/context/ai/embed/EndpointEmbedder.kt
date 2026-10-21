package com.rokid.context.ai.embed

import com.rokid.context.ai.Endpoint
import com.rokid.context.ai.EndpointKind
import com.rokid.context.ai.local.NomicBundle
import com.rokid.context.ai.local.NomicText
import com.rokid.context.ai.local.NomicVision
import com.rokid.context.ai.local.OnnxImageEmbedder
import com.rokid.context.ai.ProviderConfigException
import com.rokid.context.memory.Modality
import java.io.File
import com.rokid.context.ai.ProviderResponseException
import com.rokid.context.memory.Embedder
import com.rokid.context.memory.retrieval.VectorMath

/** Embeds with exactly [spec]'s config; a changed dimension is an error, never a silently mixed space. */
class EndpointEmbedder(
    val spec: EmbeddingSpec,
    private val endpoint: Endpoint,
    private val key: String?,
    private val client: EmbeddingClient,
    /** The process-wide ONNX image embedder for (vision file, width); a test seam. */
    private val imageEmbedderFor: (String, Int) -> OnnxImageEmbedder = NomicBundle::imageEmbedder,
) : Embedder {
    /** The on-device Nomic bundle: raw pooled 768 from native, then LayerNorm → first [EmbeddingSpec.dim] → L2 (spec F.1). */
    private val nomic = NomicText.isNomic(spec.model) && spec.kind == EndpointKind.LLAMACPP_LOCAL

    private val imageWidth = spec.imageDim ?: NomicVision.STORED_DIMS

    /** Always the combined text+image space for Nomic, whether or not the vision file is present, so it never flips at runtime. */
    override val fingerprint: String = if (nomic) NomicBundle.fingerprint(imageWidth) else spec.fingerprint

    private val visionFile: File? get() = if (nomic) NomicBundle.visionPath(endpoint.baseUrl) else null

    private val imageCapable: Boolean get() = visionFile?.isFile == true && !OnnxImageEmbedder.isUnavailable

    override val modalities: Set<Modality> get() = if (imageCapable) setOf(Modality.TEXT, Modality.IMAGE) else setOf(Modality.TEXT)

    override val imageDims: Int? get() = if (imageCapable) imageWidth else null

    /** Decoded and embedded by ONNX; an undecodable photo is an [com.rokid.context.memory.ImageDecodeException], any other failure is retryable. */
    override suspend fun embedImages(jpegs: List<ByteArray>): List<FloatArray> {
        val vision = visionFile?.takeIf { it.isFile } ?: throw ProviderConfigException("On-device image embedding is unavailable")
        return imageEmbedderFor(vision.path, imageWidth).embed(jpegs)
    }

    override suspend fun embedDocuments(texts: List<String>): List<FloatArray> = embed(texts.map { spec.docPrefix + it.take(spec.maxInputChars) })

    override suspend fun embedQuery(text: String): FloatArray = embed(listOf(spec.queryPrefix + text.take(spec.maxInputChars))).single()

    /** Nomic: the full 768 (LayerNormed, unit length) for shortening per index; everything else has nothing to shorten. */
    override suspend fun embedQueryFull(text: String): FloatArray =
        if (!nomic) embedQuery(text) else NomicText.shorten(NomicText.layerNorm(raw(listOf(spec.queryPrefix + text.take(spec.maxInputChars))).single()), NomicText.FULL_DIMS)

    private suspend fun raw(inputs: List<String>): List<FloatArray> = inputs.chunked(BATCH).flatMap { chunk ->
        client.embed(endpoint, spec.model, key, chunk, spec.dimensions, normalize = !nomic).onEach { v ->
            if (nomic && v.size != NomicText.FULL_DIMS) throw ProviderResponseException("Embedding size changed (expected ${NomicText.FULL_DIMS}, got ${v.size})")
        }
    }

    private suspend fun embed(inputs: List<String>): List<FloatArray> = if (nomic) {
        raw(inputs).map { NomicText.shorten(NomicText.layerNorm(it), spec.dim) }
    } else inputs.chunked(BATCH).flatMap { chunk ->
        client.embed(endpoint, spec.model, key, chunk, spec.dimensions).map { v ->
            if (v.size != spec.dim) throw ProviderResponseException("Embedding size changed (expected ${spec.dim}, got ${v.size})")
            if (spec.l2) VectorMath.l2(v) else v
        }
    }

    private companion object { const val BATCH = 64 }
}
