package com.rokid.context.ai.run

import android.util.Log
import com.rokid.context.ai.FailureKind
import com.rokid.context.ai.ModelCallException
import com.rokid.context.ai.ModelRouter
import com.rokid.context.ai.RoutedReply
import com.rokid.context.ai.contract.ContractOutput
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.ai.contract.SemanticContext
import com.rokid.context.ai.schema.StructuredOutput
import com.rokid.context.model.ModelImageInput
import com.rokid.context.model.ModelRequest
import com.rokid.context.prompt.NowText
import com.rokid.context.prompt.PersonaSettings
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
)

data class RoleResult(val output: ContractOutput, val raw: String, val reply: RoutedReply, val promptVersion: Int)

/**
 * Every production model call with an output contract (spec §4.4, §4.4.1): the active prompt version + SYSTEM
 * preamble, the endpoint's structured format, then [OutputCheck]. One retry carries the problems back to the model;
 * a second failure is a retryable [FailureKind.INVALID_OUTPUT] (→ the existing retry queue).
 */
class RoleRunner(
    private val router: ModelRouter,
    private val prompts: PromptRepository,
    private val persona: PersonaSettings,
    private val now: () -> LocalDateTime = LocalDateTime::now,
) {
    suspend fun run(call: RoleCall): RoleResult {
        val role = requireNotNull(call.key.role) { "SYSTEM has no model call" }
        val version = prompts.active(call.key)
        val system = prompts.active(PromptKey.SYSTEM)
        val schema = OutputCheck.schemaFor(call.key, version.outputSchema)
        val at = now()
        val prompt = PromptComposer.compose(system.template, version.template, call.vars.toMap(persona.persona.value, NowText.of(at)))
        val ctx = SemanticContext(call.inputIds, at)
        var rejected: OutputEvaluation? = null
        for (attempt in 1..2) {
            val text = rejected?.let { prompt + retryNote(it) } ?: prompt
            val build: (com.rokid.context.ai.Endpoint) -> ModelRequest = { e ->
                val plan = StructuredOutput.plan(e, schema, call.key.name)
                ModelRequest(text + plan.promptSuffix, call.images, format = plan.format)
            }
            val reply = if (call.fromFrames) router.completeFromFrames(build) else router.complete(role, build)
            val eval = OutputCheck.evaluate(call.key, schema, reply.text, ctx)
            if (eval.ok) return RoleResult(checkNotNull(eval.output), reply.text, reply, version.version)
            if (eval.schemaUnusable) {
                // A corrected reply can't help; the job stays retryable so it succeeds once the prompt's schema is fixed.
                Log.w(TAG, "${call.key.name} v${version.version} output schema can't be applied")
                throw ModelCallException("${call.key.name} v${version.version} output schema can't be applied", retryable = true, kind = FailureKind.INVALID_OUTPUT)
            }
            rejected = eval
            Log.i(TAG, "${call.key.name} v${version.version} reply rejected (attempt $attempt, ${eval.problems().size} problem(s)) from ${reply.endpoint.name}")
        }
        val last = checkNotNull(rejected)
        throw ModelCallException("${call.key.name} reply didn't match its schema: ${failureSummary(last)}", retryable = true, kind = FailureKind.INVALID_OUTPUT)
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
        fun retryNote(e: OutputEvaluation): String {
            val problems = e.problems()
            val detail = if (fixedText(e)) problems.joinToString("; ")
            else "${problems.size} problem(s), listed in the validation_errors data below.\n" + PromptComposer.dataBlock("validation_errors", bounded(problems))
            return "\n\nYour previous reply was rejected: $detail\nReply again with corrected JSON only."
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
