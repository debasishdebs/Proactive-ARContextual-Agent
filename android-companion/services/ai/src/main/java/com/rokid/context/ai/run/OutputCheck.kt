package com.rokid.context.ai.run

import com.rokid.context.ai.contract.ContractOutput
import com.rokid.context.ai.contract.DefaultSchemas
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.ai.contract.RoleContracts
import com.rokid.context.ai.contract.SemanticContext
import com.rokid.context.ai.schema.JsonSchemaValidator
import com.rokid.context.ai.schema.ModelJson
import com.rokid.context.ai.schema.NullToAbsent
import com.rokid.context.ai.schema.SchemaRefs
import kotlinx.serialization.json.JsonObject

/** The decode path (spec §4.4): parse → null→absent → canonical validation → contract decoder → semantic checks. */
data class OutputEvaluation(
    /** null = the reply was not JSON at all. */
    val schemaErrors: List<String>?,
    val decodeError: String?,
    val semanticProblems: List<String>,
    val output: ContractOutput?,
    /** The schema itself couldn't be applied (a bad ref or keyword the validator can't read): fix the prompt, not the reply. */
    val schemaUnusable: Boolean = false,
) {
    val parsed: Boolean get() = schemaErrors != null
    val schemaValid: Boolean get() = schemaErrors?.isEmpty() == true
    val decoded: Boolean get() = output != null
    val semanticOk: Boolean get() = decoded && semanticProblems.isEmpty()
    val ok: Boolean get() = schemaValid && semanticOk

    fun problems(): List<String> = when {
        schemaUnusable -> listOf(OutputCheck.SCHEMA_UNUSABLE)
        !parsed -> listOf("Reply wasn't JSON")
        !schemaValid -> schemaErrors.orEmpty()
        !decoded -> listOf(decodeError ?: "Couldn't read the reply")
        else -> semanticProblems
    }
}

object OutputCheck {
    /** Fixed text: the validator's exception message never reaches the UI or the logs. */
    const val SCHEMA_UNUSABLE = "The output schema can't be used to check replies"
    private val UNUSABLE = OutputEvaluation(listOf(SCHEMA_UNUSABLE), null, emptyList(), null, schemaUnusable = true)

    fun evaluate(key: PromptKey, schema: JsonObject, raw: String, ctx: SemanticContext): OutputEvaluation {
        val json = ModelJson.parse(raw) ?: return OutputEvaluation(null, null, emptyList(), null)
        // NullToAbsent/validator resolve refs and read keywords lazily, so a schema that parses can still throw here.
        val (normal, errors) = try {
            NullToAbsent.apply(schema, json).let { it to JsonSchemaValidator.validate(schema, it) }
        } catch (_: IllegalArgumentException) {
            return UNUSABLE
        } catch (_: ClassCastException) {
            return UNUSABLE
        }
        if (errors.isNotEmpty()) return OutputEvaluation(errors, null, emptyList(), null)
        val out = RoleContracts.decode(key, normal).getOrElse { return OutputEvaluation(emptyList(), "Reply doesn't fit the ${key.name} contract", emptyList(), null) }
        return OutputEvaluation(emptyList(), null, RoleContracts.semanticProblems(out, ctx), out)
    }

    /** The version's stored schema, or the role default when it is blank or no longer parses (Save normally prevents that). */
    fun schemaFor(key: PromptKey, stored: String?): JsonObject =
        stored?.takeIf { it.isNotBlank() }?.let { runCatching { SchemaRefs.parse(it) }.getOrNull() }
            ?: checkNotNull(DefaultSchemas.forKey(key)) { "${key.name} has no output schema" }
}
