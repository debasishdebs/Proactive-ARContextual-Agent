package com.rokid.context.canvas

import com.rokid.context.ai.Role
import com.rokid.context.ai.RoleBinding
import com.rokid.context.ai.canvas.PipelineNode
import com.rokid.context.ai.run.RoleRunner
import com.rokid.context.model.CallClass
import com.rokid.context.model.LanguageModel
import com.rokid.context.orchestration.ExtractedBatch
import com.rokid.context.orchestration.ExtractPhoto
import com.rokid.context.prompt.PersonaSettings
import com.rokid.context.prompt.PromptRepository
import java.time.LocalDateTime

/**
 * [sources] (codex r12): the stored content behind a Moment or Scene item beyond its [photo] (its member photos and the memories whose text it carries);
 * every executor model call declares it with the item's own photo and value sources ([com.rokid.context.canvas.sourcesOf]).
 */
data class CanvasItem(val frameId: String, val photo: ExtractPhoto, val value: CanvasValue, val sources: com.rokid.context.model.PayloadSources = com.rokid.context.model.PayloadSources.NONE)

/**
 * [calls] (S32): the provider calls this row OWNS, set only on the one row per provider call (null on its batch-mates and on rows that made no call).
 * [promptTokens] and [completionTokens] are the call's summed tokens on that owner row only.
 */
data class CallMeta(
    val latencyMs: Long, val promptTokens: Int?, val completionTokens: Int?, val model: String?, val promptVersion: Int?, val batchId: String?,
    val calls: Int? = null,
)

/**
 * [error] is a fixed short text (a failure kind or a canvas code), never provider text.
 * [cause] is the throwable an executor caught (never a cancellation); it is not stored. The interpreter reads it to tell a transient failure (rethrown) from the rest (fallback).
 * [extracted] is the whole [ExtractedBatch] of the call that produced this item's value (the vision executor sets it; every item of a chunk shares one instance).
 */
data class NodeResult(
    val frameId: String, val value: CanvasValue?, val branch: String? = null, val error: String? = null, val meta: CallMeta? = null, val calls: Int = 0,
    val cause: Throwable? = null, val extracted: ExtractedBatch? = null,
    /**
     * Codex r17: set only by an executor that knows EXACTLY what the model request(s) behind this output contained (Vision: the attempts that carried
     * this photo, with the sources of the context text they included). Null = the boundary's conservative chunk union ([NodeProvenance]).
     */
    val exactSources: com.rokid.context.model.PayloadSources? = null,
    /** Room v16: this item's photo was PROVED delivered as an image on the call that produced this value (Vision sets it; null = not an image call). */
    val imageDelivered: Boolean? = null,
) {
    /** Never prints [cause] or [value]: a cause message can carry provider-derived text. */
    override fun toString() = "NodeResult(frameId=$frameId, branch=$branch, error=$error, calls=$calls)"
}

class RunContext(
    val runId: String,
    val runner: RoleRunner,
    val model: LanguageModel,
    val prompts: PromptRepository,
    val persona: PersonaSettings,
    val bindings: suspend (Role) -> RoleBinding?,
    val now: () -> LocalDateTime = LocalDateTime::now,
    /** The class of every model call the executors make: EXPERIMENT for runs and Shadow, the live class when the interpreter replaces extraction. */
    val callClass: CallClass = CallClass.EXPERIMENT,
    /** Codex r10: the run's photo liveness, attached to every model call that carries a photo (null = such a call is refused by the router). */
    val liveness: com.rokid.context.model.PhotoLiveness? = null,
    /** Canvas v2.2: the model privacy mode read now. The default is OFFLINE_ONLY (fail closed): a context built without it makes no remote call through a model node. */
    val modelPrivacy: () -> com.rokid.context.core.net.ModelPrivacy = { com.rokid.context.core.net.ModelPrivacy.OFFLINE_ONLY },
    /** Canvas v2.2: the kind of endpoint [Role] is bound to now (null = none or unreadable: fail closed). */
    val boundBackend: suspend (Role) -> com.rokid.context.core.net.Backend? = { null },
    /** Canvas v2.1 SEEDED level: when set, the model nodes that build their own request send `seed = this` and `temperature = 0` (best effort online; the providers that take no seed ignore it). Null (every live caller) = nothing changes. */
    val seed: Int? = null,
) {
    /** The same context with [seed] set (the run asked for the SEEDED level). */
    fun withSeed(seed: Int?): RunContext = if (seed == this.seed) this else RunContext(runId, runner, model, prompts, persona, bindings, now, callClass, liveness, modelPrivacy, boundBackend, seed)

    /** The two request fields of the SEEDED level, or nulls. */
    val requestTemperature: Double? get() = if (seed != null) 0.0 else null
}

interface NodeExecutor {
    val type: String
    /**
     * Codex r14: true only when a run(chunk) depends on nothing but that chunk (no state carried across chunks of one node). False (the default, so a new
     * executor is conservative) makes every output also carry the sources of everything the node consumed before ([NodeProvenance]).
     */
    val chunkLocal: Boolean get() = false
    fun chunkSize(node: PipelineNode): Int = 1
    fun nominalCalls(node: PipelineNode, items: Int): Int
    fun maxCalls(node: PipelineNode, items: Int): Int
    /** One result per input item, in the same order. */
    suspend fun run(node: PipelineNode, items: List<CanvasItem>, ctx: RunContext): List<NodeResult>
}
