package com.rokid.context.orchestration

import android.content.Context
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.data.runtime.QueuePayloads
import com.rokid.context.data.runtime.QueueRuntimeStore
import com.rokid.context.domain.queue.QueuePriority
import com.rokid.context.work.OrchestrationScheduler
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.core.log.PcLog
import com.rokid.context.ai.SafeLog
import com.rokid.context.memory.db.ReviewState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray

sealed interface AskOutcome {
    /**
     * [proposalId]: the stored proposal for [proposedAction] (Phase 7), null when none was stored.
     * [photoFrameId] / [photoAtMs] (wave 3): the first cited photo that is still stored, and its capture time; the HUD label "Photo HH:mm". Null for a redacted ask.
     */
    data class Answered(
        val askId: String, val proposedAction: com.rokid.context.ai.contract.ProposedAction? = null, val proposalId: String? = null,
        val photoFrameId: String? = null, val photoAtMs: Long? = null,
        /** v0.9.8 slice C: where the published Query flow sends the answer; null = the surface that asked (the built-in flow). */
        val route: com.rokid.context.core.model.DeliveryRoute? = null,
        /** 2026-11-08: a status line shown with the answer on the phone (never sent to the model, never stored): "Search used keyword matching: <reason>" (codex r9 P2-5: the search, never the answer's provenance). */
        val notice: String? = null,
    ) : AskOutcome
    data class Failed(val message: String) : AskOutcome
}

/** `ask.sourcesJson` = JSON array of cited memory ids (spec §4.4 ANSWER `sources`). Anything else decodes to none. */
object AskSources {
    fun encode(ids: List<String>): String = JsonArray(ids.distinct().map { JsonPrimitive(it) }).toString()

    /**
     * [cited] as the array, plus (when there are any) a trailing `{"dependsOn":[...]}` object: ids the text was built from without citing them (a
     * nudge written from earlier nudges). Readers of the cited ids ([parse]) skip the object; Forget matches both ([parseAll]).
     */
    fun encodeWithDeps(cited: List<String>, deps: Collection<String>): String {
        val extra = deps.filter { it !in cited }.distinct()
        if (extra.isEmpty()) return encode(cited)
        return JsonArray(cited.distinct().map { JsonPrimitive(it) } + kotlinx.serialization.json.buildJsonObject { put("dependsOn", JsonArray(extra.map { JsonPrimitive(it) })) }).toString()
    }

    /** Cited ids and dependency ids. */
    fun parseAll(json: String): List<String> = parse(json) + runCatching {
        Json.parseToJsonElement(json).jsonArray.filterIsInstance<kotlinx.serialization.json.JsonObject>()
            .flatMap { (it["dependsOn"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { e -> (e as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } }
    }.getOrDefault(emptyList())

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
    /** G10 (MVP section 9.5): no match says so, with when it will look again (the ask queued a priority reprocess). */
    const val NO_MEMORIES = "I don't have anything on that yet \u2014 I'll look again in your next photos"
    const val AI_UNAVAILABLE = "AI unavailable — check Settings › Model endpoints"
    const val FAILED = "Couldn't answer that — try again"
    const val REVIEW_REASON = "User marked an answer unhelpful"
    /** G7 ruling (2026-10-15): Battery Saver pauses user asks too. Home shows it, and so does the HUD toast. */
    const val BATTERY_SAVER = "Battery Saver is on \u2014 asks are paused"
    const val SEARCH_FALLBACK_PREFIX = "Search used keyword matching: "

    /**
     * The visible status of an ask whose memory SEARCH fell back to keyword matching; null when semantic search ran (or lexical was the user's choice).
     * Codex r9 P2-5: it describes the search only. The answer can also draw on the newest memories, recent context or photos (or need no memory), so it
     * never claims where the answer came from.
     */
    fun semanticNotice(off: com.rokid.context.memory.retrieval.SemanticOff?): String? =
        off?.let { SEARCH_FALLBACK_PREFIX + it.text }

    /** Built on IO: the first ask reads SharedPreferences (RetrievalSettingsStore) and the key store, never on Main. */
    suspend fun ask(context: Context, question: String, nowMs: Long = System.currentTimeMillis()): AskOutcome =
        askFrom(context, question, TYPED_SOURCE, nowMs)

    /** The `ask.source` of a typed (Home chat) ask. Its answer reaches the glasses HUD through the phone's mirror ([DeliveryRoute.mirrorsChatToHud]). */
    const val TYPED_SOURCE = "phone_typed"

    /**
     * Voice asks (Phase 6): the same answer path and messages, recorded with their [source] (`glasses_voice`,
     * `phone_voice`). The transcript gets the question hygiene of [AnswerService] (collapse + data-block folding).
     * Cancelled before the reply is stored, it saves nothing: the ask row is written in one transaction after the model
     * answered, so only a cancel that lands after that commit leaves one (a complete, answered ask in the feed).
     */
    suspend fun askFrom(context: Context, question: String, source: String, nowMs: Long = System.currentTimeMillis()): AskOutcome {
        // G7: every direct ask (typed, phone mic, glasses tap) passes here; with Battery Saver on, nothing reaches the model.
        if (BatterySaver.isOn(context)) return AskOutcome.Failed(BATTERY_SAVER)
        return withContext(Dispatchers.IO) { AnswerService.forApp(context).answer(question, source, nowMs) }
    }

    /**
     * 👍 = 1, 👎 = −1 (spec §2.4 Ask detail). Repeating the same rating is a no-op. Redacted or unknown asks → false.
     * 👎 (Phase 7 ruling): the cited, still-OK memories move to NEEDS_REVIEW and **one** `critic_review` job (ids only,
     * dedupe key = the ask id) is queued **before** the rating is written, so a failed enqueue leaves the rating as it
     * was and returns false. The drain runs CRITIC over them (pass → OK); it never writes a summary.
     * [enqueue] queues the payload (null = not queued: Delete all ran since [MemoryWipe.generation] was read).
     */
    suspend fun rate(
        context: Context, askId: String, up: Boolean,
        enqueue: (Context, Long, String) -> Any? = ::enqueueReview,
    ): Boolean = rateLock.withLock { // two taps racing: one check-and-enqueue at a time (P6 T6 carry)
        val generation = MemoryWipe.generation.get() // before the memory read (fix round 2 N2)
        val graph = MemoryGraph.get(context)
        val ask = graph.asks.byId(askId) ?: return@withLock false
        if (ask.redacted) return@withLock false
        val rating = if (up) 1 else -1
        if (ask.rating == rating) return@withLock true
        if (!up) {
            val cited = graph.timeline.memoriesByIds(AskSources.parse(ask.sourcesJson)).filter { it.reviewState != ReviewState.INVALIDATED }
            if (cited.isNotEmpty()) {
                // Fix round 1: review state (and the 👎 mark, I7) before the enqueue, so a drain it starts finds NEEDS_REVIEW.
                val moved = cited.filter { it.reviewState == ReviewState.OK }
                    .filter { graph.db.memoryViewDao().moveReviewState(it.id, ReviewState.OK.name, ReviewState.NEEDS_REVIEW.name) > 0 }
                UserReviews.mark(context, cited.map { it.id })
                val queued = withContext(Dispatchers.IO) {
                    val already = QueueRuntimeStore.live(context, QueuePriority.PRIORITY_REPROCESS)
                        .any { QueuePayloads.string(QueuePayloads.parse(it.payload), "dedupe") == askId }
                    already || try {
                        enqueue(context, generation, QueuePayloads.criticReview(cited.map { it.id }, dedupeKey = askId)) != null
                    } catch (c: CancellationException) {
                        throw c
                    } catch (e: Exception) {
                        PcLog.w(TAG, "review not queued: ${SafeLog.describe(e)}")
                        false
                    }
                }
                if (!queued) {
                    moved.forEach { graph.db.memoryViewDao().moveReviewState(it.id, ReviewState.NEEDS_REVIEW.name, ReviewState.OK.name) }
                    UserReviews.clear(context, cited.map { it.id })
                    return@withLock false
                }
            }
        }
        graph.asks.setRating(askId, rating) // field-level UPDATE: never a whole-row upsert
        // Paused: the review waits in the queue; resuming drains it.
        if (!up && AppConfigStore.isPipelineEnabled(context)) withContext(Dispatchers.IO) { OrchestrationScheduler(context).enqueueQueueDrainNow() }
        true
    }

    private val rateLock = Mutex()
    private const val TAG = "AskActions"

    private fun enqueueReview(context: Context, generation: Long, payload: String): Any? = QueueRuntimeStore.enqueueIfCurrent(
        context = context, generation = generation, queue = QueuePriority.PRIORITY_REPROCESS, payload = payload,
        personaSnapshot = AppConfigStore.getWithoutSecret(context).persona, ttlHours = 24,
    )
}
