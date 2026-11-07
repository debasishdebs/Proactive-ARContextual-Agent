package com.rokid.context.canvas

import com.rokid.context.orchestration.AnswerService
import com.rokid.context.orchestration.AskText
import com.rokid.context.orchestration.DataOpGate
import com.rokid.context.orchestration.MemoryWipe
import com.rokid.context.orchestration.RagParams
import com.rokid.context.memory.retrieval.RetrievalMode

/**
 * The phone's REAL memory retrieval for an arbitrary query, read-only (the quality-eval tool's route). It calls [AnswerService.retrieveScored] (the
 * Ask retriever) and nothing else: no role, no model, no write, no photo search, no other context source. The query is never logged (this class logs nothing).
 * Fails closed ([Result.Busy]) while an export, delete or save runs, and when a wipe finished while the retrieval ran.
 * Review fix 2026-11-07: a fact or photo Forget that completes during the retrieval changes neither the gate nor the wipe generation, so every hit (and its
 * snippet) also passes the live-memory fence ([live], [com.rokid.context.memory.forget.ForgetFence.liveIds]) after the retrieval; a dead id is dropped.
 */
class RetrieveDebug(
    private val answers: AnswerService,
    private val gate: () -> DataOpGate = { DataOpGate.shared },
    private val generation: () -> Long = MemoryWipe.generation::get,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Those of the ids that are live now (ForgetFence.liveIds on the memory database in production). */
    private val live: suspend (Collection<String>) -> Set<String>,
) {
    data class Hit(val memoryId: String, val score: Double, val rank: Int, val reviewState: String, val createdAt: Long, val snippet: String?)

    sealed interface Result {
        /** [semanticOff]: the reason token (`SemanticOff` name) when [semanticUnavailable], else null. */
        data class Ok(val mode: String, val semanticUnavailable: Boolean, val fallback: Boolean, val hits: List<Hit>, val semanticOff: String? = null) : Result
        data object Busy : Result
    }

    suspend fun run(query: String, mode: RetrievalMode?, topK: Int?, notAfterMs: Long?, snippets: Boolean): Result {
        if (gate().busy()) return Result.Busy
        val gen = generation()
        val sr = answers.retrieveScored(query, clock(), notAfterMs, RagParams(mode = mode, topK = topK), withSources = false)
        val hits = if (sr.fallback) {
            sr.retrieved.memories.mapIndexed { i, m -> Hit(m.id, 0.0, i + 1, m.reviewState.name, m.createdAt, snip(m.text, snippets)) }
        } else {
            sr.hits.mapIndexed { i, h -> Hit(h.memory.id, h.score, i + 1, h.memory.reviewState.name, h.memory.createdAt, snip(h.memory.text, snippets)) }
        }
        if (gate().busy() || generation() != gen) return Result.Busy
        // The fence runs after the retrieval: a Forget that completed meanwhile drops its ids (and snippets); ranks stay contiguous.
        val alive = live(hits.map { it.memoryId })
        val fenced = hits.filter { it.memoryId in alive }.mapIndexed { i, h -> h.copy(rank = i + 1) }
        if (gate().busy() || generation() != gen) return Result.Busy
        return Result.Ok(sr.mode.name, sr.semanticUnavailable, sr.fallback, fenced, sr.semanticOff?.name)
    }

    private fun snip(text: String, on: Boolean): String? = if (on) AskText.collapse(text).take(SNIPPET) else null

    companion object { const val SNIPPET = 80 }
}
