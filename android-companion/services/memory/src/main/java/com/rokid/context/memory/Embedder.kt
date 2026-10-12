package com.rokid.context.memory

/** Implemented in `:services:ai` (Phase 5) so this module never depends on it. */
interface Embedder {
    /** Canonical fingerprint of the embedding space this embedder writes (spec §4.6). */
    val fingerprint: String
    suspend fun embedDocuments(texts: List<String>): List<FloatArray>
    suspend fun embedQuery(text: String): FloatArray
}
