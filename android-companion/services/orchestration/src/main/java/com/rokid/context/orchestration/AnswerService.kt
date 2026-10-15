package com.rokid.context.orchestration

import android.content.Context
import androidx.room.withTransaction
import android.util.Log
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.FailureKind
import com.rokid.context.ai.ModelCallException
import com.rokid.context.ai.contract.AnswerOutput
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.ai.run.RoleCall
import com.rokid.context.ai.run.RoleRunner
import com.rokid.context.core.model.TextHygiene
import com.rokid.context.connectors.ContextSourceProvider
import com.rokid.context.connectors.ProposalIntake
import com.rokid.context.connectors.actions.ActionsGraph
import com.rokid.context.connectors.sources.SourcesGraph
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.ProposalOrigin
import com.rokid.context.memory.db.AskEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.retrieval.MemoryRetriever
import com.rokid.context.memory.retrieval.RetrievalSettings
import com.rokid.context.memory.retrieval.RetrievalSettingsStore
import com.rokid.context.prompt.PromptComposer
import com.rokid.context.prompt.PromptVars
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/** `{{context_sources}}` (spec §5.4). Phase 7 supplies calendar/contacts/notes/location lines; v1 Phase 5 has none. */
fun interface ContextSources {
    suspend fun lines(question: String): List<String>
    /** Phase 7: [nowMs] is the ask's clock. Defaults to [lines] without it (additive). */
    suspend fun lines(question: String, nowMs: Long): List<String> = lines(question)
    companion object { val None = ContextSources { emptyList() } }
}

class EmptyQuestionException : IllegalArgumentException("Empty question")
/** Message kept from the legacy READ path: RetryClassifier retries "temporarily unavailable". */
class NoMemoriesException : IllegalStateException("Context temporarily unavailable")

/** Shared text hygiene for questions and retrieved memories (Phase 4 rules, moved here). */
object AskText {
    /** NEL, LS and PS are listed explicitly: the JVM's `\s` and `\p{Cntrl}` miss them (Android ICU may not). */
    fun collapse(text: String) = text.replace(TextHygiene.SEPARATORS, " ").trim()

    /** Cut at a word boundary (when there is one) and mark the cut with an ellipsis. */
    fun clip(text: String, max: Int): String {
        if (text.length <= max) return text
        val cut = text.take(max)
        val at = cut.lastIndexOf(' ')
        return (if (at > max / 2) cut.take(at) else cut).trimEnd() + "…"
    }

    /** Lines in rank order; whole lowest-ranked lines are dropped until the joined text fits. */
    fun bounded(lines: List<String>, maxChars: Int = 5_000): List<String> {
        val kept = mutableListOf<String>()
        var size = 0
        for (l in lines) {
            val add = l.length + if (kept.isEmpty()) 0 else 1
            if (size + add > maxChars) break
            kept += l; size += add
        }
        return kept
    }
}

/**
 * spec §4.7 answer path: retriever (current mode) → ANSWER prompt with the top-k memories (id, time, confidence,
 * frame ref, text) as delimited data + `{{context_sources}}` → RoleRunner (guided decode, contract, sources ⊆
 * retrieved ids) → one `ask` row. HUD overlay + TTS are Phase 6; `proposedAction` handling is Phase 7.
 */
class AnswerService(
    private val memory: MemoryGraph,
    private val runner: RoleRunner,
    private val retriever: MemoryRetriever,
    private val settings: () -> RetrievalSettings,
    private val sources: ContextSources = ContextSources.None,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val proposals: ProposalIntake = ProposalIntake.None,
    /**
     * G10 (MVP section 9.5 "trigger immediate priority reprocessing"): retrieval found nothing to answer from. The app
     * runs the capture pipeline now, so photos not processed yet become memories before the next ask. Never fails the ask.
     */
    private val onNoMatch: () -> Unit = {},
    /** Spec P2.3: with a keyframe backlog, the newest frames are extracted first (user-started, bypassing the budget). Never fails the ask. */
    private val beforeRetrieve: suspend () -> Unit = {},
) {
    data class Retrieved(val vars: PromptVars, val memories: List<MemoryEntity>) { val ids: Set<String> get() = memories.map { it.id }.toSet() }

    suspend fun retrieve(question: String, nowMs: Long): Retrieved {
        val q = AskText.collapse(question).take(MAX_QUESTION)
        val s = settings().clamped()
        val hits = retriever.retrieve(q, s, nowMs).hits.map { it.memory }
        // No hits: the newest trusted memories, under the same minConfidence and top-k the retriever applies.
        val pool = hits.ifEmpty {
            memory.memories.recent(30).filter { it.reviewState == ReviewState.OK && it.confidence >= s.minConfidence }.take(minOf(FALLBACK, s.topK))
        }
        val lines = AskText.bounded(pool.map(::line))
        val kept = pool.take(lines.size)
        return Retrieved(
            PromptVars(
                // The question is user (later ASR) text: a data block folds look-alike markers as for memories.
                question = PromptComposer.dataBlock("question", listOf(q)),
                retrievedMemories = PromptComposer.dataBlock("memories", lines),
                contextSources = PromptComposer.dataBlock("sources", sources.lines(q, nowMs)),
            ),
            kept,
        )
    }

    /** Phone/HUD asks: every failure becomes one of the fixed short messages. */
    suspend fun answer(question: String, source: String, nowMs: Long = System.currentTimeMillis()): AskOutcome = try {
        answerOrThrow(question, source, nowMs)
    } catch (c: CancellationException) {
        throw c
    } catch (e: EmptyQuestionException) {
        AskOutcome.Failed(AskActions.EMPTY_QUESTION)
    } catch (e: NoMemoriesException) {
        AskOutcome.Failed(AskActions.NO_MEMORIES)
    } catch (e: ModelCallException) {
        Log.i(TAG, "answer failed: kind=${e.kind} retryable=${e.retryable}") // never the message: it can carry provider text
        AskOutcome.Failed(if (e.kind == FailureKind.INVALID_OUTPUT) AskActions.FAILED else AskActions.AI_UNAVAILABLE)
    } catch (e: Exception) {
        Log.i(TAG, "answer failed (${e.javaClass.simpleName})")
        AskOutcome.Failed(AskActions.FAILED)
    }

    /** Queue jobs: failures propagate so the queue's retry policy applies (RetryClassifier). */
    /** [askId]: a queue job passes one derived from its id, so a re-run after a cancellation adds no second Ask row. */
    /**
     * [generation]: read before retrieval. If Delete all runs while the model answers, the ask (built from memories that
     * no longer exist) is not saved and the call fails as "no memories" (review I1).
     */
    suspend fun answerOrThrow(
        question: String, source: String, nowMs: Long = System.currentTimeMillis(), askId: String? = null,
        generation: Long = MemoryWipe.generation.get(),
    ): AskOutcome.Answered {
        val q = AskText.collapse(question).take(MAX_QUESTION)
        if (q.isEmpty()) throw EmptyQuestionException()
        try {
            beforeRetrieve()
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Log.i(TAG, "backlog extraction before ask failed (${e.javaClass.simpleName})")
        }
        val r = retrieve(q, nowMs)
        // An action command ("note that…") needs no memory: only other asks stop at "nothing yet" (user notes).
        if (r.memories.isEmpty() && !ActionTriggers.matches(q)) {
            runCatching(onNoMatch).onFailure { Log.w(TAG, "no-match reprocess not queued (${it.javaClass.simpleName})") }
            throw NoMemoriesException()
        }
        val out = runner.run(RoleCall(PromptKey.ANSWER, r.vars, inputIds = r.ids, callClass = com.rokid.context.model.CallClass.USER)).output as AnswerOutput
        val text = AskText.collapse(out.answer).take(MAX_ANSWER)
        if (text.isEmpty()) throw ModelCallException("ANSWER reply was empty", retryable = true, kind = FailureKind.INVALID_OUTPUT)
        val id = askId ?: "ask_${UUID.randomUUID()}"
        var redacted = false
        val saved = memory.db.withTransaction {
            if (MemoryWipe.generation.get() != generation) return@withTransaction false
            // Phase 7 Forget fence (Stream F fix round 1 I1): a memory in the prompt or cited was forgotten while the
            // model answered, so the reply may hold forgotten text: store it redacted, with no sources and no proposal.
            redacted = !com.rokid.context.memory.forget.ForgetFence.allLive(memory.db, r.ids + out.sources)
            memory.asks.add(
                if (redacted) AskEntity(id, nowMs, q, com.rokid.context.memory.forget.ForgetService.REDACTED, "[]", null, source, true)
                else AskEntity(id, nowMs, q, text, AskSources.encode(out.sources), null, source, false),
            )
            true
        }
        if (!saved) throw NoMemoriesException()
        if (redacted) return AskOutcome.Answered(id)
        // Phase 7 (R-62): only after the ask row commits, so a proposal always has its origin row.
        // The ask is saved: a failing intake must not fail it (nor make a queue job retry it). Cancellation still propagates.
        val proposalId = out.proposedAction?.let { action ->
            try {
                proposals.onProposed(ProposalOrigin.ASK, id, action, nowMs)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "proposal intake failed (${t.javaClass.simpleName})")
                null
            }
        }
        return AskOutcome.Answered(id, out.proposedAction, proposalId)
    }

    private fun line(m: MemoryEntity): String =
        "id=${m.id} · ${TIME.withZone(zone()).format(Instant.ofEpochMilli(m.createdAt))} · confidence ${"%.2f".format(Locale.US, m.confidence)} · " +
            "frame=${m.frameId ?: "none"} · ${AskText.clip(AskText.collapse(m.text), MAX_MEMORY_CHARS)}"

    companion object {
        private const val TAG = "AnswerService"
        private const val MAX_QUESTION = 500
        private const val MAX_ANSWER = 2_000
        private const val MAX_MEMORY_CHARS = 500
        private const val FALLBACK = 10
        private const val ASK_EXTRACT_TIMEOUT_MS = 30_000L
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)

        /** Adapts the connectors provider: the ask's nowMs reaches it (the one-argument form falls back to the wall clock). */
        fun sourcesFrom(provider: ContextSourceProvider): ContextSources = object : ContextSources {
            override suspend fun lines(question: String): List<String> = provider.lines(question, System.currentTimeMillis())
            override suspend fun lines(question: String, nowMs: Long): List<String> = provider.lines(question, nowMs)
        }

        /** [extractFirst] false: no backlog extraction before retrieving (a queued ask that is no longer fresh, review M6). */
        fun forApp(context: Context, extractFirst: Boolean = true): AnswerService {
            val ai = AiGraph.get(context)
            val store = RetrievalSettingsStore.get(context)
            val provider = SourcesGraph.get(context).provider
            return AnswerService(
                MemoryGraph.get(context), ai.runner, ai.retriever, { store.settings.value },
                sources = sourcesFrom(provider),
                proposals = OriginFencedIntake(context, MemoryGraph.get(context), ActionsGraph.get(context).intake),
                beforeRetrieve = { if (extractFirst) kotlinx.coroutines.withTimeoutOrNull(ASK_EXTRACT_TIMEOUT_MS) { com.rokid.context.work.frames.KeyframeGraph.get(context).pipeline.extractForAsk() } },
                onNoMatch = { if (com.rokid.context.data.runtime.AppConfigStore.isPipelineEnabled(context)) com.rokid.context.work.OrchestrationScheduler(context).enqueueFrameIngestIfIdle() },
            )
        }
    }
}
