package com.rokid.context.ai.schema

import com.rokid.context.ai.Capability
import com.rokid.context.ai.Endpoint
import com.rokid.context.ai.EndpointKind
import com.rokid.context.model.WireFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredOutputTest {
    private val schema = Json.parseToJsonElement("""{"type":"object","properties":{"a":{"type":"string"}},"required":["a"]}""").jsonObject
    private fun e(kind: EndpointKind, vararg caps: Capability) = Endpoint("e", "E", kind, "https://x.example", null, listOf("m"), caps.toSet(), 1)

    @Test fun eachKindGetsItsFormat() {
        val az = StructuredOutput.plan(e(EndpointKind.AZURE_OPENAI, Capability.CHAT, Capability.JSON_SCHEMA), schema, "ANSWER v2!")
        assertTrue(az.format is WireFormat.JsonSchema); assertEquals("ANSWER_v2_", (az.format as WireFormat.JsonSchema).name)
        assertTrue(StructuredOutput.plan(e(EndpointKind.OPENAI_COMPAT, Capability.CHAT, Capability.JSON_SCHEMA), schema, "x").format is WireFormat.JsonSchema)
        assertTrue(StructuredOutput.plan(e(EndpointKind.OLLAMA, Capability.CHAT, Capability.JSON_SCHEMA), schema, "x").format is WireFormat.OllamaSchema)
        assertTrue(StructuredOutput.plan(e(EndpointKind.LLAMACPP_LOCAL, Capability.CHAT, Capability.JSON_SCHEMA), schema, "x").format is WireFormat.Grammar)
    }

    @Test fun withoutJsonSchemaCapabilityTheSchemaGoesIntoThePrompt() {
        val p = StructuredOutput.plan(e(EndpointKind.OLLAMA, Capability.CHAT), schema, "x")
        assertEquals(WireFormat.None, p.format); assertNull(p.wireSchema)
        assertTrue(p.promptSuffix.contains("JSON Schema")); assertTrue(p.promptSuffix.contains("\"required\":[\"a\"]"))
    }

    @Test fun noSchemaMeansFreeText() = assertEquals(StructuredPlan(WireFormat.None, "", null), StructuredOutput.plan(e(EndpointKind.OLLAMA), null, "x"))

    @Test fun aSchemaThatDoesNotCompileFallsBackToPromptInstructions() {
        val cyc = Json.parseToJsonElement("""{"type":"object","properties":{"t":{"${'$'}ref":"#/${'$'}defs/N"}},"required":["t"],"${'$'}defs":{"N":{"type":"object","properties":{"c":{"${'$'}ref":"#/${'$'}defs/N"}},"required":["c"]}}}""").jsonObject
        val p = StructuredOutput.plan(e(EndpointKind.AZURE_OPENAI, Capability.CHAT, Capability.JSON_SCHEMA), cyc, "x")
        assertEquals(WireFormat.None, p.format); assertNull(p.wireSchema); assertTrue(p.promptSuffix.contains("JSON Schema"))
    }

    @Test fun anEmptyEnumFallsBackForLlamaCpp() {
        val s = Json.parseToJsonElement("""{"type":"object","properties":{"p":{"type":"string","enum":[]}},"required":["p"]}""").jsonObject
        val p = StructuredOutput.plan(e(EndpointKind.LLAMACPP_LOCAL, Capability.CHAT, Capability.JSON_SCHEMA), s, "x")
        assertEquals(WireFormat.None, p.format); assertTrue(p.promptSuffix.contains("JSON Schema"))
    }
}
