package com.rokid.context.ai.schema

import com.rokid.context.ai.Capability
import com.rokid.context.ai.Endpoint
import com.rokid.context.ai.EndpointKind
import com.rokid.context.model.WireFormat
import kotlinx.serialization.json.JsonObject

data class StructuredPlan(val format: WireFormat, val promptSuffix: String, val wireSchema: JsonObject?)

/**
 * The `StructuredOutput` adapter per endpoint kind (spec §4.4): strict json_schema (Azure/OpenAI), `format` (Ollama),
 * GBNF (llama.cpp); without the JSON_SCHEMA capability — or if the schema doesn't compile for that kind — the canonical
 * schema is appended to the prompt as instructions. The reply is validated locally either way.
 */
object StructuredOutput {
    private val NAME = Regex("[^A-Za-z0-9_-]")

    fun plan(endpoint: Endpoint, canonical: JsonObject?, name: String): StructuredPlan {
        if (canonical == null) return StructuredPlan(WireFormat.None, "", null)
        if (Capability.JSON_SCHEMA !in endpoint.capabilities) return instructions(canonical)
        val wire = WireSchemaCompiler.compile(canonical, endpoint.kind).getOrElse { return instructions(canonical) }
        val format = when (endpoint.kind) {
            EndpointKind.AZURE_OPENAI, EndpointKind.OPENAI_COMPAT -> WireFormat.JsonSchema(name.replace(NAME, "_").take(64).ifEmpty { "output" }, wire)
            EndpointKind.OLLAMA -> WireFormat.OllamaSchema(wire)
            EndpointKind.LLAMACPP_LOCAL -> WireFormat.Grammar(runCatching { GbnfConverter.convert(wire) }.getOrElse { return instructions(canonical) })
        }
        return StructuredPlan(format, "\n\nReply with JSON only.", wire)
    }

    private fun instructions(canonical: JsonObject) =
        StructuredPlan(WireFormat.None, "\n\nReply with only one JSON value that matches this JSON Schema:\n$canonical", null)
}
