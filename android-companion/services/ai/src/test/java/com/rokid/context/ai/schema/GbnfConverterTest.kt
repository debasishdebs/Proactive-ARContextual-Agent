package com.rokid.context.ai.schema

import com.rokid.context.ai.EndpointKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GbnfConverterTest {
    @Test fun objectWithEnumAndNullableFieldBecomesAGrammar() {
        val canonical = Json.parseToJsonElement("""{"type":"object","properties":{"verdict":{"type":"string","enum":["pass","fail"]},
            "reasons":{"type":"array","items":{"type":"string"}},"score":{"type":"number"}},"required":["verdict","reasons"]}""").jsonObject
        val g = GbnfConverter.convert(WireSchemaCompiler.compile(canonical, EndpointKind.LLAMACPP_LOCAL).getOrThrow())
        assertTrue(g, g.startsWith("root ::= "))
        assertTrue(g, g.contains("\"\\\"verdict\\\"\""))
        assertTrue(g, g.contains("\"\\\"pass\\\"\" | \"\\\"fail\\\"\""))
        assertTrue(g, g.contains("number | null") || g.contains("null | number"))
        listOf("ws ::=", "string ::=", "number ::=", "null ::=").forEach { assertTrue(it, g.contains(it)) }
    }

    private fun o(t: String) = Json.parseToJsonElement(t).jsonObject

    @Test fun enumLiteralsAreEscapedExactly() {
        val g = GbnfConverter.convert(o("""{"type":"object","properties":{"p":{"type":"string","enum":["a\"b","c\\d","e\nf","é"]}}}"""))
        val expected = "r1 ::= " + "\"\\\"a\\\\\\\"b\\\"\"" + " | " + "\"\\\"c\\\\\\\\d\\\"\"" + " | " + "\"\\\"e\\\\nf\\\"\"" + " | " + "\"\\\"é\\\"\""
        assertTrue(g, g.lines().contains(expected))
        assertEquals("root ::= r2", g.lines().first())
    }

    @Test fun nullableObjectAndAnyOfBecomeAlternatives() {
        val g = GbnfConverter.convert(o("""{"type":"object","properties":{"n":{"type":["object","null"],"properties":{"x":{"type":"integer"}}},
            "u":{"anyOf":[{"type":"string"},{"type":"boolean"}]}}}"""))
        assertTrue(g, g.lines().any { it.matches(Regex("r\\d+ ::= r\\d+ \\| null")) })
        assertTrue(g, g.lines().contains("r3 ::= string | boolean"))
    }

    @Test fun propertiesWithoutATypeIsAnObject() {
        val g = GbnfConverter.convert(o("""{"properties":{"x":{"type":"string"}}}"""))
        assertTrue(g, g.contains("\"{\" ws"))
        assertTrue(g, !g.startsWith("root ::= string"))
    }

    @Test fun anEmptyEnumIsRefused() {
        val e = runCatching { GbnfConverter.convert(o("""{"type":"object","properties":{"p":{"enum":[]}}}""")) }.exceptionOrNull()
        assertEquals("An enum needs at least one value", e!!.message)
    }
}
