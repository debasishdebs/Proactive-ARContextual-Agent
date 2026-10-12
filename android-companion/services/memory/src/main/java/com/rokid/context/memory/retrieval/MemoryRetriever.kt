package com.rokid.context.memory.retrieval

import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryViewDao
import com.rokid.context.memory.db.ReviewState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.pow

data class ScoredMemory(val memory: MemoryEntity, val lexical: Double?, val semantic: Double?, val fused: Double, val score: Double)

/** [modeUsed] is what actually ran; [semanticUnavailable] = semantic was asked for but could not run (shown in Try it). */
data class RetrievalResult(val hits: List<ScoredMemory>, val modeUsed: RetrievalMode, val semanticUnavailable: Boolean)

/** spec §4.6: the current mode → candidates (min confidence applied in SQL, before the pool cut) → fusion (Hybrid) → recency → top-k. */
class MemoryRetriever(
    private val lexical: LexicalRetriever,
    private val semantic: SemanticRetriever,
    private val memories: MemoryViewDao,
) {
    /** All work (BM25, the embedder lookup and query, the vector scan, fusion) runs off the caller's thread. */
    suspend fun retrieve(query: String, settings: RetrievalSettings, nowMs: Long): RetrievalResult =
        withContext(Dispatchers.Default) { retrieveNow(query, settings.clamped(), nowMs) }

    private suspend fun retrieveNow(query: String, s: RetrievalSettings, nowMs: Long): RetrievalResult {
        val pool = maxOf(s.topK * 5, 40)
        val lex = if (s.mode == RetrievalMode.SEMANTIC) emptyList() else lexical.search(query, pool, s.minConfidence)
        // Semantic cosine is in [-1, 1]; shift to [0, 1] so recency and fusion only ever see non-negative scores.
        val sem = if (s.mode == RetrievalMode.LEXICAL) null else semantic.search(query, pool, s.minConfidence)?.map { Hit(it.id, (it.score + 1) / 2) }
        val unavailable = s.mode != RetrievalMode.LEXICAL && sem == null
        val (scores, used) = when {
            s.mode == RetrievalMode.LEXICAL -> lex.associate { it.id to it.score } to RetrievalMode.LEXICAL
            sem == null -> (if (s.mode == RetrievalMode.SEMANTIC) lexical.search(query, pool, s.minConfidence) else lex).associate { it.id to it.score } to RetrievalMode.LEXICAL
            s.mode == RetrievalMode.SEMANTIC -> sem.associate { it.id to it.score } to RetrievalMode.SEMANTIC
            else -> s.fusion.fuse(lex, sem) to RetrievalMode.HYBRID
        }
        if (scores.isEmpty()) return RetrievalResult(emptyList(), used, unavailable)
        val lexById = lex.associate { it.id to it.score }
        val semById = sem?.associate { it.id to it.score }.orEmpty()
        val rows = scores.keys.toList().chunked(CHUNK).flatMap { memories.byIds(it) }
        val hits = rows.filter { it.reviewState == ReviewState.OK && it.confidence >= s.minConfidence }
            .map { m ->
                val fused = scores.getValue(m.id)
                ScoredMemory(m, lexById[m.id], semById[m.id], fused, fused * recency(m.createdAt, nowMs, s))
            }
            .sortedWith(compareByDescending<ScoredMemory> { it.score }.thenByDescending { it.memory.createdAt })
            .take(s.topK)
        return RetrievalResult(hits, used, unavailable)
    }

    companion object {
        private const val CHUNK = 900 // below SQLite's 999 bound-parameter limit

        /** Recency boost: weight 1.0 now, 0.75 after one half-life, tending to 0.5 — it reorders, never drops. */
        fun recency(createdAt: Long, nowMs: Long, s: RetrievalSettings): Double {
            if (!s.recencyBoost) return 1.0
            val ageDays = (nowMs - createdAt).coerceAtLeast(0) / 86_400_000.0
            return 0.5 + 0.5 * 2.0.pow(-ageDays / s.halfLifeDays)
        }
    }
}
