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
    data class Answered(val askId: String, val proposedAction: com.rokid.context.ai.contract.ProposedAction? = null) : AskOutcome
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
        withContext(Dispatchers.IO) { AnswerService.forApp(context).answer(question, "phone_typed", nowMs) }

    /**
     * 👍 = 1, 👎 = −1 (spec §2.4 Ask detail). Changing to 👎 enqueues one critic re-summarise over the cited memories
     * (the legacy PRIORITY_REPROCESS queue). Repeating the same rating is a no-op. Redacted or unknown asks → false.
     */
    suspend fun rate(context: Context, askId: String, up: Boolean): Boolean {
        val graph = MemoryGraph.get(context)
        val ask = graph.asks.byId(askId) ?: return false
        if (ask.redacted) return false
        val rating = if (up) 1 else -1
        if (ask.rating == rating) return true
        graph.asks.setRating(askId, rating) // field-level UPDATE: never a whole-row upsert
        if (!up) {
            val cited = graph.timeline.memoriesByIds(AskSources.parse(ask.sourcesJson))
            if (cited.isNotEmpty()) withContext(Dispatchers.IO) {
                QueueRuntimeStore.enqueue(
                    context = context,
                    queue = QueuePriority.PRIORITY_REPROCESS,
                    payload = QueuePayloads.reprocess(reason = REVIEW_REASON, sourceContext = cited.joinToString("\n") { it.text }),
                    personaSnapshot = AppConfigStore.get(context).persona,
                    ttlHours = 24,
                )
                // Paused: the review waits in the queue; resuming drains it.
                if (AppConfigStore.get(context).pipelineEnabled) OrchestrationScheduler(context).enqueueQueueDrainNow()
            }
        }
        return true
    }
}
