package com.rokid.context.ai.run

import com.rokid.context.core.log.PcLog
import com.rokid.context.ai.FailureKind
import com.rokid.context.ai.ImagesUnguardedException
import com.rokid.context.ai.ImagesUnsupportedException
import com.rokid.context.ai.contract.ImageUse
import com.rokid.context.ai.ModelCallException
import com.rokid.context.ai.RoutedReply
import com.rokid.context.ai.contract.ContractOutput
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.ai.contract.SemanticContext
import com.rokid.context.ai.schema.StructuredOutput
import com.rokid.context.model.CallClass
import com.rokid.context.model.LanguageModel
import com.rokid.context.model.ModelImageInput
import com.rokid.context.model.ModelRequest
import com.rokid.context.prompt.NowText
import com.rokid.context.prompt.PersonaSettings
import com.rokid.context.prompt.PromptDraft
import com.rokid.context.prompt.PromptComposer
import com.rokid.context.prompt.PromptRepository
import com.rokid.context.prompt.PromptVars
import java.time.LocalDateTime

data class RoleCall(
    val key: PromptKey,
    val vars: PromptVars,
    val images: List<ModelImageInput> = emptyList(),
    /** Ids the output may cite (ANSWER `sources`, NUDGE `sourceIds`). */
    val inputIds: Set<String> = emptySet(),
    /** VISION_EXTRACT/SUMMARIZE over frames: the vision → summarize chain (ModelRouter.completeFromFrames). */
    val fromFrames: Boolean = false,
    /** Who the call is for (budget class). Null = the key's default: VISION_EXTRACT -> EXTRACT, NUDGE -> NUDGE, ANSWER -> USER, else OTHER. Ask-triggered extraction passes USER. */
    val callClass: CallClass? = null,
    /** Canvas seam: an in-memory draft used in place of the active template and schema of [key] (never stored). The result's promptVersion is 0. */
    val override: PromptDraft? = null,
    /** When set, [images] go only to this endpoint: a fallback endpoint gets the text without them (the user's consent names one endpoint). */
    val imagesOnlyOnEndpoint: String? = null,
    /** Codex r10: required with [images] (every image carries its photo ref); the router re-checks the photos with it inside each call's permit. */
    val liveness: com.rokid.context.model.PhotoLiveness? = null,
    /**
     * Codex r11: the stored photos this call's TEXT is derived from (their facts, captions or frame metadata), with no image attached. The router
     * re-checks them with [liveness] inside the permit and under the upload slot exactly like an attached photo; a forgotten one drops the call unsent.
     */
    val derivedFromFrames: Set<String> = emptySet(),
) {
    val effectiveClass: CallClass
        get() = callClass ?: when (key) {
            PromptKey.VISION_EXTRACT, PromptKey.CLUSTER_SUMMARY, PromptKey.INTENT_CLASSIFY -> CallClass.EXTRACT
            PromptKey.NUDGE -> CallClass.NUDGE
            PromptKey.ANSWER -> CallClass.USER
            else -> CallClass.OTHER
        }
}

/** [replies]: every provider reply the call consumed, the rejected attempts included ([reply] is the last one), for token accounting. */
data class RoleResult(val output: ContractOutput, val raw: String, val reply: RoutedReply, val promptVersion: Int, val replies: List<RoutedReply> = listOf(reply)) {
    /** Codex r10 delivery proof: the accepted reply came from the provider call that carried the photos ([RoutedReply.imagesDelivered]). */
    val imagesDelivered: Boolean get() = reply.imagesDelivered
}

/**
 * Every production model call with an output contract (spec §4.4, §4.4.1): the active prompt version + SYSTEM
 * preamble, the endpoint's structured format, then [OutputCheck]. One retry carries the problems back to the model;
 * a second failure is a retryable [FailureKind.INVALID_OUTPUT] (→ the existing retry queue).
 * OBS-R3-1: when the rejected reply stopped at the token cap, the retry also gets twice that cap (at most
 * [ModelRequest.MAX_TOKENS_LIMIT]): the same cap would cut it again.
 */
class RoleRunner(
    private val router: LanguageModel,
    private val prompts: PromptRepository,
    private val persona: PersonaSettings,
    private val now: () -> LocalDateTime = LocalDateTime::now,
) {
    /** The active output schema text of [key] (what a call would be validated against). */
    suspend fun activeSchema(key: PromptKey): String? = prompts.active(key).outputSchema

    suspend fun run(call: RoleCall): RoleResult {
        val role = requireNotNull(call.key.role) { "SYSTEM has no model call" }
        // Codex r10: photos only where the key declares them, and a photo-requiring key never runs without its photos.
        if (call.images.isNotEmpty() && call.key.imageUse == ImageUse.NONE) throw ImagesUnguardedException()
        if (call.key.requiresImage && call.images.isEmpty()) throw ImagesUnsupportedException()
        val photoIds = call.images.mapNotNull { it.ref }.toSet() + call.derivedFromFrames
        val draft = call.override
        val active = if (draft == null) prompts.active(call.key) else null
        val template = draft?.template ?: active!!.template
        val outputSchema = if (draft != null) draft.outputSchema else active!!.outputSchema
        val versionNo = active?.version ?: 0
        val system = prompts.active(PromptKey.SYSTEM)
        val schema = OutputCheck.schemaFor(call.key, outputSchema)
        val at = now()
        val prompt = PromptComposer.compose(system.template, template, call.vars.toMap(persona.persona.value, NowText.of(at)))
        val ctx = SemanticContext(call.inputIds, at)
        var rejected: OutputEvaluation? = null
        var cutCap: Int? = null
        val attempts = mutableListOf<com.rokid.context.ai.RoutedReply>()
        for (attempt in 1..2) {
            val text = rejected?.let { prompt + retryNote(it, cut = cutCap != null) } ?: prompt
            val cap = cutCap
            val build: (com.rokid.context.ai.Endpoint) -> ModelRequest = { e ->
                val plan = StructuredOutput.plan(e, schema, call.key.name)
                ModelRequest(text + plan.promptSuffix, call.images.takeIf { call.imagesOnlyOnEndpoint == null || call.imagesOnlyOnEndpoint == e.id }.orEmpty(), maxTokens = cap, format = plan.format,
                    requireImages = call.key.requiresImage, photoIds = photoIds, liveness = call.liveness)
            }
            val reply = if (call.fromFrames) router.completeFromFrames(call.effectiveClass, build) else router.complete(role, call.effectiveClass, build)
            attempts += reply
            val eval = OutputCheck.evaluate(call.key, schema, reply.text, ctx)
            if (eval.ok) return RoleResult(checkNotNull(eval.output), reply.text, reply, versionNo, attempts.toList())
            if (eval.schemaUnusable) {
                // A corrected reply can't help; the job stays retryable so it succeeds once the prompt's schema is fixed.
                PcLog.w(TAG, "${call.key.name} v$versionNo output schema can't be applied")
                throw ModelCallException("${call.key.name} v$versionNo output schema can't be applied", retryable = true, kind = FailureKind.INVALID_OUTPUT, replies = attempts.toList())
            }
            rejected = eval
            cutCap = if (reply.truncated) ((reply.maxTokens ?: ModelRequest.DEFAULT_MAX_TOKENS) * 2).coerceAtMost(ModelRequest.MAX_TOKENS_LIMIT) else null
            // Fixed words only: the stage and whether the provider cut the reply tell the failure classes apart in a device log.
            PcLog.i(TAG, "${call.key.name} v$versionNo reply rejected (attempt $attempt, stage=${stage(eval)}, ${eval.problems().size} problem(s), cut=${reply.truncated})")
        }
        val last = checkNotNull(rejected)
        throw ModelCallException("${call.key.name} reply didn't match its schema: ${failureSummary(last)}", retryable = true, kind = FailureKind.INVALID_OUTPUT, replies = attempts.toList())
    }

    private companion object {
        const val TAG = "RoleRunner"
        const val MAX_ECHO = 400
        private val SAFE_PATH = Regex("^\\$[A-Za-z0-9_.\\[\\]]*$")

        /** No JSON, or JSON the decoder couldn't read: the problem line is fixed text. */
        fun fixedText(e: OutputEvaluation) = !e.parsed || (e.schemaValid && !e.decoded)

        /**
         * The corrective note. Its instruction text is fixed; problem lines can carry model-controlled text (keys, cited
         * ids), so they go in a data block, at most [MAX_ECHO] characters in all.
         */
        fun retryNote(e: OutputEvaluation, cut: Boolean = false): String {
            val problems = e.problems()
            val detail = if (fixedText(e)) problems.joinToString("; ")
            else "${problems.size} problem(s), listed in the validation_errors data below.\n" + PromptComposer.dataBlock("validation_errors", bounded(problems))
            val why = if (cut) "\nIt was cut off at the length limit: keep the reply short and complete." else ""
            return "\n\nYour previous reply was rejected: $detail$why\nReply again with corrected JSON only."
        }

        /** Where the decode path stopped (spec 4.4 order), as a fixed word for the log. */
        fun stage(e: OutputEvaluation): String = when {
            !e.parsed -> "NOT_JSON"
            !e.schemaValid -> "SCHEMA"
            !e.decoded -> "CONTRACT"
            else -> "SEMANTIC"
        }

        fun bounded(lines: List<String>): List<String> {
            var left = MAX_ECHO
            return buildList {
                for (l in lines) { if (left <= 0) break; add(l.take(left)); left -= l.length }
            }
        }

        /** For the exception (and so the logs): the count and where the first problem is, never reply text. */
        fun failureSummary(e: OutputEvaluation): String {
            val problems = e.problems()
            val first = problems.firstOrNull().orEmpty()
            val where = when {
                fixedText(e) -> first
                !e.schemaValid -> first.substringBefore(": ").takeIf { it.length <= 60 && SAFE_PATH.matches(it) } ?: "$"
                else -> first.substringBefore(": ") // semantic lines: fixed text, then ": <cited ids>"
            }
            return "${problems.size} problem(s), first: $where"
        }
    }
}
