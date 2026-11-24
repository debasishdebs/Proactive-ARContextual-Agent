package com.rokid.context.memory.retrieval

import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryViewDao
import com.rokid.context.memory.db.ReviewState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.pow

data class ScoredMemory(val memory: MemoryEntity, val lexical: Double?, val semantic: Double?, val fused: Double, val score: Double)

/**
 * [modeUsed] is what actually ran; [semanticUnavailable] = semantic was asked for but could not run (shown in Try it); [semanticOff] = why
 * (set exactly when [semanticUnavailable]).
 */
data class RetrievalResult(val hits: List<ScoredMemory>, val modeUsed: RetrievalMode, val semanticUnavailable: Boolean, val semanticOff: SemanticOff? = null)

/** spec §4.6: the current mode → candidates (min confidence applied in SQL, before the pool cut) → fusion (Hybrid) → recency → top-k. */
class MemoryRetriever(
    private val lexical: LexicalRetriever,
    private val semantic: SemanticRetriever,
    private val memories: MemoryViewDao,
    /** The Quick recall window (retention v3 WP3); [RecallWindow.NONE] = nothing is demoted, today's behaviour. */
    private val recall: RecallWindow = RecallWindow.NONE,
) {
    /**
     * [poolOverride] (recall only, <= [MAX_POOL_OVERRIDE]) replaces the clamped `topK`; null keeps today's behaviour.
     * All work (BM25, the embedder lookup and query, the vector scan, fusion) runs off the caller's thread. */
    suspend fun retrieve(query: String, settings: RetrievalSettings, nowMs: Long, poolOverride: Int? = null, notAfterMs: Long? = null): RetrievalResult =
        retrieveWindowed(query, settings, nowMs, poolOverride, notAfterMs, null)

    /** [retrieve] over memories whose event time is in [window] (null = [retrieve]). A separate name keeps [retrieve]'s signature, and so every compiled caller, unchanged. */
    suspend fun retrieveWindowed(query: String, settings: RetrievalSettings, nowMs: Long, poolOverride: Int?, notAfterMs: Long?, window: EventWindow?): RetrievalResult =
        withContext(Dispatchers.Default) { retrieveNow(query, settings.clamped().let { if (poolOverride == null) it else it.copy(topK = poolOverride.coerceIn(1, MAX_POOL_OVERRIDE)) }, nowMs, notAfterMs, window) }

    /**
     * [notAfterMs] (S34, the Ask debug view): only memories created at or before it exist for this call. It is applied before every limit: the lexical
     * candidate query, the semantic candidate scan, the top-k. Null = today's behaviour, byte for byte.
     * [window] (retrieval-quality spec section 4): only memories whose EVENT time (capture time, else createdAt) is in it are candidates, in the same
     * places and before the same limits, and the candidate pool is at least [WINDOW_POOL]. Null = today's behaviour.
     */
    private suspend fun retrieveNow(query: String, s: RetrievalSettings, nowMs: Long, notAfterMs: Long?, window: EventWindow?): RetrievalResult {
        val pool = if (window == null) maxOf(s.topK * 5, 40) else maxOf(s.topK * 5, 40, WINDOW_POOL)
        val since = if (window == null) recall.sinceMs(nowMs) else null // a time-window question is not narrowed: it reaches demoted facts
        // Codex v3 r1 P2-11: a time-window question also runs the windowed lexical search in SEMANTIC mode (a demoted fact has no vector and is reachable only that way)
        val lex = if (s.mode == RetrievalMode.SEMANTIC && window == null) emptyList() else lexical.search(query, pool, s.minConfidence, notAfterMs, window, since)
        // Semantic cosine is in [-1, 1]; shift to [0, 1] so recency and fusion only ever see non-negative scores.
        val semSearch = if (s.mode == RetrievalMode.LEXICAL) null else semantic.searchOrOff(query, pool, s.minConfidence, notAfterMs, window)
        val sem = semSearch?.hits?.map { Hit(it.id, (it.score + 1) / 2) }
        val unavailable = s.mode != RetrievalMode.LEXICAL && sem == null
        val off = if (unavailable) semSearch?.off ?: SemanticOff.QUERY_FAILED else null
        val (scores, used) = when {
            s.mode == RetrievalMode.LEXICAL -> lex.associate { it.id to it.score } to RetrievalMode.LEXICAL
            sem == null -> (if (s.mode == RetrievalMode.SEMANTIC) lexical.search(query, pool, s.minConfidence, notAfterMs, window, since) else lex).associate { it.id to it.score } to RetrievalMode.LEXICAL
            s.mode == RetrievalMode.SEMANTIC -> {
                val base = sem.associate { it.id to it.score }
                // inside a window the facts that have NO vector in the active space (demoted or not embedded yet) join by their lexical score: a vector that merely scored low is not one of them
                val noVector = if (window != null) notEmbedded(semSearch?.spaceId, lex, sem) else emptySet()
                (base + lex.filter { it.id in noVector }.associate { it.id to it.score }) to RetrievalMode.SEMANTIC
            }
            else -> s.fusion.fuse(lex, sem, notEmbedded(semSearch?.spaceId, lex, sem)) to RetrievalMode.HYBRID
        }
        if (scores.isEmpty()) return RetrievalResult(emptyList(), used, unavailable, off)
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
        return RetrievalResult(hits, used, unavailable, off)
    }

    /**
     * R4 (retrieval-quality spec section 7): the lexical candidates the semantic list does not hold because they have NO vector in the active space (a memory
     * written after the index paused, or not embedded yet), as opposed to a vector that merely scored low. Those are fused at their full lexical weight,
     * so a fresh memory is not capped at 0.4. An index that cannot say, or a failed lookup, gives none (today's scoring).
     */
    private suspend fun notEmbedded(spaceId: String?, lex: List<Hit>, sem: List<Hit>): Set<String> {
        if (spaceId == null) return emptySet()
        val inSem = sem.mapTo(HashSet()) { it.id }
        val candidates = lex.map { it.id }.filter { it !in inSem }
        if (candidates.isEmpty()) return emptySet()
        val embedded = semantic.embeddedAmong(spaceId, candidates) ?: return emptySet()
        return candidates.filterTo(HashSet()) { it !in embedded }
    }

    companion object {
        /** Retrieval-quality spec section 4: the candidate pool inside a time window. */
        const val WINDOW_POOL = 200
        /** Wave 3 (S8): the cap of `poolOverride`, which bypasses the `topK <= 20` clamp for the photo-recall caller only. UI settings are unchanged. */
        const val MAX_POOL_OVERRIDE = 200
        private const val CHUNK = 900 // below SQLite's 999 bound-parameter limit

        /** Recency boost: weight 1.0 now, 0.75 after one half-life, tending to 0.5 — it reorders, never drops. */
        fun recency(createdAt: Long, nowMs: Long, s: RetrievalSettings): Double {
            if (!s.recencyBoost) return 1.0
            val ageDays = (nowMs - createdAt).coerceAtLeast(0) / 86_400_000.0
            return 0.5 + 0.5 * 2.0.pow(-ageDays / s.halfLifeDays)
        }
    }
}
