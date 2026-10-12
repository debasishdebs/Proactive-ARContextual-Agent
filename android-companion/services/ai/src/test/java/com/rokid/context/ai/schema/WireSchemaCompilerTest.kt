package com.rokid.context.ai.schema

import com.rokid.context.ai.EndpointKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WireSchemaCompilerTest {
    private fun j(t: String) = Json.parseToJsonElement(t).jsonObject
    private fun compile(t: String, kind: EndpointKind = EndpointKind.AZURE_OPENAI) = WireSchemaCompiler.compile(j(t), kind)

    private val reminder = """{"type":"object","properties":{"title":{"type":"string","minLength":1,"maxLength":80},
        "note":{"type":"string","maxLength":200,"description":"Extra detail"}},"required":["title"]}"""

    @Test fun goldenStrictWireSchema() = assertEquals(
        j("""{"type":"object","properties":{"title":{"type":"string","description":"1–80 characters"},
            "note":{"type":["string","null"],"description":"Extra detail (at most 200 characters)"}},
            "required":["title","note"],"additionalProperties":false}"""),
        compile(reminder).getOrThrow(),
    )

    @Test fun nullToAbsentRoundTripValidatesAgainstTheCanonicalSchema() {
        val canonical = j(reminder)
        val wireReply = Json.parseToJsonElement("""{"title":"Buy milk","note":null}""")
        assertEquals(emptyList<String>(), JsonSchemaValidator.validate(compile(reminder).getOrThrow(), wireReply))
        assertEquals(emptyList<String>(), JsonSchemaValidator.validate(canonical, NullToAbsent.apply(canonical, wireReply)))
    }

    @Test fun oneOfBecomesAnyOfAndOptionalRefsBecomeNullableAnyOf() {
        val w = compile("""{"type":"object","properties":{"a":{"${'$'}ref":"#/${'$'}defs/U"}},"${'$'}defs":{"U":{"oneOf":[
            {"type":"object","properties":{"type":{"type":"string","enum":["X"]}},"required":["type"]},
            {"type":"object","properties":{"type":{"type":"string","enum":["Y"]},"n":{"type":"integer","minimum":1}},"required":["type"]}]}}}""").getOrThrow()
        val a = w["properties"]!!.jsonObject["a"]!!.jsonObject
        assertEquals(j("""{"type":"null"}"""), a["anyOf"]!!.jsonArray[1])
        val u = w["\$defs"]!!.jsonObject["U"]!!.jsonObject
        assertFalse("oneOf" in u); assertEquals(2, u["anyOf"]!!.jsonArray.size)
        val n = u["anyOf"]!!.jsonArray[1].jsonObject["properties"]!!.jsonObject["n"]!!.jsonObject
        assertEquals(JsonArray(listOf(Json.parseToJsonElement("\"integer\""), Json.parseToJsonElement("\"null\""))), n["type"])
        assertEquals("at least 1", n["description"]!!.jsonPrimitive.content)
    }

    @Test fun ollamaAndLlamaCppInlineRefs() {
        val w = compile("""{"type":"object","properties":{"a":{"${'$'}ref":"#/${'$'}defs/A"}},"required":["a"],"${'$'}defs":{"A":{"type":"string","pattern":"^x"}}}""", EndpointKind.OLLAMA).getOrThrow()
        assertFalse("\$defs" in w)
        assertEquals(j("""{"type":"string","description":"must match ^x"}"""), w["properties"]!!.jsonObject["a"])
    }

    @Test fun openAiCompatInlinesRefsToo() {
        val w = compile("""{"type":"object","properties":{"a":{"${'$'}ref":"#/${'$'}defs/A"}},"required":["a"],"${'$'}defs":{"A":{"type":"string"}}}""", EndpointKind.OPENAI_COMPAT).getOrThrow()
        assertFalse("\$defs" in w)
        assertEquals(j("""{"type":"string"}"""), w["properties"]!!.jsonObject["a"])
    }

    @Test fun openAiCompatStillEnforcesLimits() {
        var deep = """{"type":"string"}"""
        repeat(6) { deep = """{"type":"object","properties":{"x":$deep},"required":["x"]}""" }
        assertEquals("Too deeply nested (max 5 levels)", compile(deep, EndpointKind.OPENAI_COMPAT).exceptionOrNull()!!.message)
    }

    @Test fun nullableEnumGetsNullInTheEnum() {
        val w = compile("""{"type":"object","properties":{"p":{"type":"string","enum":["low","high"]}}}""").getOrThrow()
        assertEquals(j("""{"type":["string","null"],"enum":["low","high",null]}"""), w["properties"]!!.jsonObject["p"])
    }

    @Test fun limitsAndUnsupportedRulesFailWithAShortReason() {
        assertEquals("The top level must be an object", compile("""{"type":"array","items":{"type":"string"}}""").exceptionOrNull()!!.message)
        var deep = """{"type":"string"}"""
        repeat(6) { deep = """{"type":"object","properties":{"x":$deep},"required":["x"]}""" }
        assertEquals("Too deeply nested (max 5 levels)", compile(deep).exceptionOrNull()!!.message)
        val many = (1..101).joinToString(",") { "\"p$it\":{\"type\":\"string\"}" }
        assertEquals("Too many properties (max 100)", compile("""{"type":"object","properties":{$many}}""").exceptionOrNull()!!.message)
        assertEquals("'allOf' isn't supported", compile("""{"type":"object","properties":{"a":{"allOf":[{"type":"string"}]}}}""").exceptionOrNull()!!.message)
        assertTrue(compile(deep, EndpointKind.OLLAMA).isSuccess) // limits are Azure/OpenAI's
    }

    private val cycle = """{"type":"object","properties":{"t":{"${'$'}ref":"#/${'$'}defs/Node"}},"required":["t"],"${'$'}defs":{
        "Node":{"type":"object","properties":{"child":{"${'$'}ref":"#/${'$'}defs/Node"}},"required":["child"]}}}"""

    @Test fun aRefCycleIsRefusedForEveryKind() = EndpointKind.entries.forEach { kind ->
        assertEquals("$kind", "Recursive \$ref isn't supported", compile(cycle, kind).exceptionOrNull()!!.message)
    }

    @Test fun anIndirectRefCycleIsRefused() {
        val t = """{"type":"object","properties":{"a":{"${'$'}ref":"#/${'$'}defs/A"}},"required":["a"],"${'$'}defs":{
            "A":{"type":"object","properties":{"b":{"${'$'}ref":"#/${'$'}defs/B"}},"required":["b"]},
            "B":{"type":"array","items":{"${'$'}ref":"#/${'$'}defs/A"}}}}"""
        EndpointKind.entries.forEach { assertEquals("Recursive \$ref isn't supported", compile(t, it).exceptionOrNull()!!.message) }
    }

    @Test fun aRefReusedWithoutACycleIsFine() {
        val t = """{"type":"object","properties":{"a":{"${'$'}ref":"#/${'$'}defs/S"},"b":{"${'$'}ref":"#/${'$'}defs/S"}},"required":["a","b"],"${'$'}defs":{"S":{"type":"string"}}}"""
        EndpointKind.entries.forEach { assertTrue("$it", compile(t, it).isSuccess) }
    }
}
