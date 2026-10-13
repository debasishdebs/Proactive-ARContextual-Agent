package com.rokid.context.orchestration

import android.content.Context
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.data.runtime.QueuePayloads
import com.rokid.context.data.runtime.QueueRuntimeStore
import com.rokid.context.domain.queue.QueuePriority
import com.rokid.context.work.OrchestrationScheduler
import com.rokid.context.memory.MemoryGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray

sealed interface AskOutcome {
    /** [proposalId]: the stored proposal for [proposedAction] (Phase 7), null when none was stored. */
    data class Answered(
        val askId: String, val proposedAction: com.rokid.context.ai.contract.ProposedAction? = null, val proposalId: String? = null,
    ) : AskOutcome
    data class Failed(val message: String) : AskOutcome
}

/** `ask.sourcesJson` = JSON array of cited memory ids (spec §4.4 ANSWER `sources`). Anything else decodes to none. */
object AskSources {
    fun encode(ids: List<String>): String = JsonArray(ids.distinct().map { JsonPrimitive(it) }).toString()

    fun parse(json: String): List<String> = runCatching {
        Json.parseToJsonElement(json).jsonArray
            .mapNotNull { e -> (e as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() } }
            .distinct()
    }.getOrDefault(emptyList())
}

/**
 * Typed Ask from the phone: the R-52 answer path ([AnswerService]) behind the Phase 4 call. Failures return fixed
 * short strings (spec §6.3); provider text is never surfaced. Ask is a user action, so it runs while the pipeline is paused.
 */
object AskActions {
    const val EMPTY_QUESTION = "Type a question first"
    const val NO_MEMORIES = "Nothing saved yet — ask again once a few moments are captured"
    const val AI_UNAVAILABLE = "AI unavailable — check Settings › Model endpoints"
    const val FAILED = "Couldn't answer that — try again"
    const val REVIEW_REASON = "User marked an answer unhelpful"

    /** Built on IO: the first ask reads SharedPreferences (RetrievalSettingsStore) and the key store, never on Main. */
    suspend fun ask(context: Context, question: String, nowMs: Long = System.currentTimeMillis()): AskOutcome =
        askFrom(context, question, "phone_typed", nowMs)

    /**
     * Voice asks (Phase 6): the same answer path and messages, recorded with their [source] (`glasses_voice`,
     * `phone_voice`). The transcript gets the question hygiene of [AnswerService] (collapse + data-block folding).
     * Cancelled before the reply is stored, it saves nothing: the ask row is written in one transaction after the model
     * answered, so only a cancel that lands after that commit leaves one (a complete, answered ask in the feed).
     */
    suspend fun askFrom(context: Context, question: String, source: String, nowMs: Long = System.currentTimeMillis()): AskOutcome =
        withContext(Dispatchers.IO) { AnswerService.forApp(context).answer(question, source, nowMs) }

    /**
     * 👍 = 1, 👎 = −1 (spec §2.4 Ask detail). Changing to 👎 enqueues one critic re-summarise over the cited memories
     * (the legacy PRIORITY_REPROCESS queue). Repeating the same rating is a no-op. Redacted or unknown asks → false.
     */
    suspend fun rate(context: Context, askId: String, up: Boolean): Boolean {
        val generation = MemoryWipe.generation.get() // before the memory read (fix round 2 N2)
        val graph = MemoryGraph.get(context)
        val ask = graph.asks.byId(askId) ?: return false
        if (ask.redacted) return false
        val rating = if (up) 1 else -1
        if (ask.rating == rating) return true
        graph.asks.setRating(askId, rating) // field-level UPDATE: never a whole-row upsert
        if (!up) {
            val cited = graph.timeline.memoriesByIds(AskSources.parse(ask.sourcesJson))
            if (cited.isNotEmpty()) withContext(Dispatchers.IO) {
                QueueRuntimeStore.enqueueIfCurrent(
                    context = context,
                    generation = generation,
                    queue = QueuePriority.PRIORITY_REPROCESS,
                    payload = QueuePayloads.reprocess(reason = REVIEW_REASON, sourceContext = cited.joinToString("\n") { it.text }),
                    personaSnapshot = AppConfigStore.getWithoutSecret(context).persona,
                    ttlHours = 24,
                )
                // Paused: the review waits in the queue; resuming drains it.
                if (AppConfigStore.isPipelineEnabled(context)) OrchestrationScheduler(context).enqueueQueueDrainNow()
            }
        }
        return true
    }
}
