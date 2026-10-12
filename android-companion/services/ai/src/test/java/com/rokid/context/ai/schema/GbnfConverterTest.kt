package com.rokid.context.ai.schema

import com.rokid.context.ai.EndpointKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
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
}
