package com.rokid.context.ai.local

import com.rokid.context.memory.retrieval.VectorMath
import kotlin.math.sqrt

/**
 * The Nomic Embed v1.5 text transform (spec F.1): mean-pooled 768 (no L2 inside native) → LayerNorm over all 768
 * (no affine, eps 1e-5) → first `dims` → L2. Documents and queries carry the model's task prefixes.
 */
object NomicText {
    const val DOC = "search_document: "
    const val QUERY = "search_query: "
    /** The model's native width. */
    const val FULL_DIMS = 768
    /** The text index width. */
    const val TEXT_DIMS = 256
    /** The catalog id of the bundle (`LocalModels`); the on-device endpoint's bound model carries it. */
    const val CATALOG_ID = "nomic-embed-v1.5"

    fun layerNorm(v: FloatArray, eps: Float = 1e-5f): FloatArray {
        if (v.isEmpty()) return v
        var mean = 0.0
        for (x in v) mean += x
        mean /= v.size
        var variance = 0.0
        for (x in v) { val d = x - mean; variance += d * d }
        variance /= v.size
        val inv = 1.0 / sqrt(variance + eps)
        return FloatArray(v.size) { ((v[it] - mean) * inv).toFloat() }
    }

    /** The first [dims] values, re-normalised to unit length. */
    fun shorten(v: FloatArray, dims: Int): FloatArray = VectorMath.l2(v.copyOf(dims.coerceAtMost(v.size)))

    fun fingerprint(dims: Int): String = "nomic-embed-text-v1.5|mean|ln|d=$dims|l2"

    /** Whether a model (the binding's model on an on-device endpoint) is this bundle, so it must take the Nomic path. */
    fun isNomic(model: String): Boolean = model == CATALOG_ID
}
