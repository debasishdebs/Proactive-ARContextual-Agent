package com.rokid.context.ai.embed

import com.rokid.context.ai.Endpoint
import com.rokid.context.ai.EndpointKind
import com.rokid.context.ai.local.NomicText
import com.rokid.context.ai.ProviderResponseException
import com.rokid.context.memory.Embedder
import com.rokid.context.memory.retrieval.VectorMath

/** Embeds with exactly [spec]'s config; a changed dimension is an error, never a silently mixed space. */
class EndpointEmbedder(
    val spec: EmbeddingSpec,
    private val endpoint: Endpoint,
    private val key: String?,
    private val client: EmbeddingClient,
) : Embedder {
    /** The on-device Nomic bundle: raw pooled 768 from native, then LayerNorm → first [EmbeddingSpec.dim] → L2 (spec F.1). */
    private val nomic = NomicText.isNomic(spec.model) && spec.kind == EndpointKind.LLAMACPP_LOCAL

    override val fingerprint: String = if (nomic) NomicText.fingerprint(spec.dim) else spec.fingerprint

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
