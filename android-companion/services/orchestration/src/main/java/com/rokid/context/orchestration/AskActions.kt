package com.rokid.context.orchestration

import android.content.Context
import android.util.Log
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.ModelCallException
import com.rokid.context.ai.Role
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.AskEntity
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.prompt.PromptComposer
import com.rokid.context.prompt.PromptTemplateId
import com.rokid.context.prompt.PromptTemplateRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

sealed interface AskOutcome {
    data class Answered(val askId: String) : AskOutcome
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
 * Typed Ask from the phone (Phase 4 interim; Phase 5 swaps in the R-52 answer path behind the same call): lexical hits,
 * or the most recent trusted memories, → legacy READ_CONTEXT_ANSWER prompt → ANSWER binding → an `ask` row.
 * Failures return fixed short strings (spec §6.3); provider text is never surfaced.
 */
object AskActions {
    const val EMPTY_QUESTION = "Type a question first"
    const val NO_MEMORIES = "Nothing saved yet — ask again once a few moments are captured"
    const val AI_UNAVAILABLE = "AI unavailable — check Settings › Model endpoints"
    const val FAILED = "Couldn't answer that — try again"
    private const val TAG = "AskActions"

    suspend fun ask(context: Context, question: String, nowMs: Long = System.currentTimeMillis()): AskOutcome {
        val q = question.replace('\n', ' ').trim().take(500)
        if (q.isEmpty()) return AskOutcome.Failed(EMPTY_QUESTION)
        val graph = MemoryGraph.get(context)
        val hits = graph.memories.search(q, 8).filter { it.reviewState == ReviewState.OK }
        val pool = hits.ifEmpty { graph.memories.recent(30).filter { it.reviewState == ReviewState.OK } }.take(10)
        if (pool.isEmpty()) return AskOutcome.Failed(NO_MEMORIES)
        val prompt = withContext(Dispatchers.IO) {
            PromptComposer(PromptTemplateRepository(context)).compose(
                templateId = PromptTemplateId.READ_CONTEXT_ANSWER,
                variables = mapOf(
                    "persona" to AppConfigStore.get(context).persona,
                    "input_language" to "auto",
                    "query" to q,
                    "retrieved_context" to pool.joinToString("\n") { "${it.type} | conf=${"%.2f".format(Locale.US, it.confidence)} | ${it.text}" },
                ),
            )
        }
        val answer = try {
            AiGraph.get(context).router.generate(Role.ANSWER, prompt).trim()
        } catch (c: CancellationException) {
            throw c
        } catch (e: ModelCallException) {
            Log.i(TAG, "ask failed: ${e.message}") // message is already key-redacted (Phase 2); never log the throwable
            return AskOutcome.Failed(AI_UNAVAILABLE)
        } catch (e: Exception) {
            Log.i(TAG, "ask failed (${e.javaClass.simpleName})")
            return AskOutcome.Failed(FAILED)
        }
        if (answer.isEmpty()) return AskOutcome.Failed(FAILED)
        val id = "ask_${UUID.randomUUID()}"
        graph.asks.upsert(
            AskEntity(
                id = id, ts = nowMs, question = q, answer = answer.take(2_000), sourcesJson = AskSources.encode(pool.map { it.id }),
                rating = null, source = "phone_typed", redacted = false,
            ),
        )
        return AskOutcome.Answered(id)
    }
}
