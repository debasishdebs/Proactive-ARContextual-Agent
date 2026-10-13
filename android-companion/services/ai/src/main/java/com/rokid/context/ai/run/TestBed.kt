package com.rokid.context.ai.run

import com.rokid.context.ai.Endpoint
import com.rokid.context.ai.EndpointRepository
import com.rokid.context.ai.ModelCallException
import com.rokid.context.ai.ModelErrorMapper
import com.rokid.context.ai.ModelRouter
import com.rokid.context.ai.RoleBindingRepository
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.ai.contract.SchemaCompatibilityChecker
import com.rokid.context.ai.contract.SemanticContext
import com.rokid.context.ai.schema.StructuredOutput
import com.rokid.context.model.ModelImageInput
import com.rokid.context.model.ModelRequest
import com.rokid.context.prompt.NowText
import com.rokid.context.prompt.PersonaSettings
import com.rokid.context.prompt.PromptComposer
import com.rokid.context.prompt.PromptDraft
import com.rokid.context.prompt.PromptRepository
import com.rokid.context.prompt.PromptVars
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.time.LocalDateTime
import kotlin.coroutines.cancellation.CancellationException

data class TestBedRequest(
    val key: PromptKey,
    val draft: PromptDraft,
    val endpointId: String,
    val model: String,
    val vars: PromptVars,
    val images: List<ModelImageInput> = emptyList(),
    val inputIds: Set<String> = emptySet(),
)

data class TestBedResult(
    val raw: String,
    val latencyMs: Long,
    val promptTokens: Int?,
    val completionTokens: Int?,
    /** Compiled wire schema (read-only tab); null when the endpoint gets the instructions fallback. */
    val wireSchema: String?,
    /** null when the call itself failed. */
    val evaluation: OutputEvaluation?,
    /** Short human text (UserFacingError title); never provider text. */
    val failure: String?,
)

/** spec §4.5: runs the *draft* template + schema on the chosen endpoint/model through the production checks. */
/** Gate (1)'s checker; injectable so tests can observe where it runs. */
fun interface SchemaCheck { fun check(key: PromptKey, draftSchema: String?, bound: List<Endpoint>): List<String> }

class TestBed(
    private val router: ModelRouter,
    private val prompts: PromptRepository,
    private val persona: PersonaSettings,
    private val bindings: RoleBindingRepository,
    private val endpoints: EndpointRepository,
    private val now: () -> LocalDateTime = LocalDateTime::now,
    private val checker: SchemaCheck = SchemaCheck(SchemaCompatibilityChecker::check),
    /** CPU work (schema check, compose, evaluate) runs here, off the caller's thread. */
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) {
    /** Gate (1): the draft refines the contract and compiles for every endpoint bound to the role (primary + fallback). */
    suspend fun compatibility(key: PromptKey, draft: PromptDraft): List<String> {
        val role = key.role ?: return emptyList()
        val b = bindings.get(role)
        val bound = listOfNotNull(b?.endpointId, b?.fallbackEndpointId).mapNotNull { endpoints.byId(it) }
        return withContext(compute) { checker.check(key, draft.outputSchema, bound) } // parse + walks + compiles: never on Main
    }

    suspend fun run(request: TestBedRequest): TestBedResult {
        val at = now()
        val system = prompts.active(PromptKey.SYSTEM).template
        val (schema, prompt) = withContext(compute) {
            OutputCheck.schemaFor(request.key, request.draft.outputSchema) to
                PromptComposer.compose(system, request.draft.template, request.vars.toMap(persona.persona.value, NowText.of(at)))
        }
        var wire: JsonObject? = null
        val reply = try {
            router.completeOn(request.endpointId, request.model) { e ->
                val plan = StructuredOutput.plan(e, schema, request.key.name)
                wire = plan.wireSchema
                ModelRequest(prompt + plan.promptSuffix, request.images, format = plan.format)
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: ModelCallException) {
            return TestBedResult("", 0, null, null, null, null, ModelErrorMapper.toUserFacing(e).title)
        }
        val eval = withContext(compute) { OutputCheck.evaluate(request.key, schema, reply.text, SemanticContext(request.inputIds, at)) }
        return TestBedResult(reply.text.take(MAX_RAW), reply.latencyMs, reply.promptTokens, reply.completionTokens, wire?.toString(), eval, null)
    }

    private companion object { const val MAX_RAW = 4_000 }
}
