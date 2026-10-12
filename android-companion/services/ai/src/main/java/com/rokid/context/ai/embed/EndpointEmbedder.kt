package com.rokid.context.ai.embed

import com.rokid.context.ai.Endpoint
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
    override val fingerprint: String = spec.fingerprint

    override suspend fun embedDocuments(texts: List<String>): List<FloatArray> = embed(texts.map { spec.docPrefix + it.take(spec.maxInputChars) })

    override suspend fun embedQuery(text: String): FloatArray = embed(listOf(spec.queryPrefix + text.take(spec.maxInputChars))).single()

    private suspend fun embed(inputs: List<String>): List<FloatArray> =
        client.embed(endpoint, spec.model, key, inputs, spec.dimensions).map { v ->
            if (v.size != spec.dim) throw ProviderResponseException("Embedding size changed (expected ${spec.dim}, got ${v.size})")
            if (spec.l2) VectorMath.l2(v) else v
        }
}
