package com.rokid.context.ai.schema

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NullToAbsentTest {
    private val schema = SchemaRefs.parse("""{"type":"object","properties":{"answer":{"type":"string"},"sources":{"type":"array","items":{"type":"string"}},
        "proposedAction":{"${'$'}ref":"#/${'$'}defs/PA"}},"required":["answer","sources"],"${'$'}defs":{"PA":{"oneOf":[
        {"type":"object","properties":{"type":{"enum":["REMINDER"]},"title":{"type":"string"},"note":{"type":"string"}},"required":["type","title"]},
        {"type":"object","properties":{"type":{"enum":["SHOPPING_ADD"]},"items":{"type":"array","items":{"type":"object",
          "properties":{"name":{"type":"string"},"qty":{"type":"number"}},"required":["name"]}}},"required":["type","items"]}]}}}""")
    private fun j(t: String) = Json.parseToJsonElement(t)

    @Test fun nullForAnOptionalFieldBecomesAbsent() =
        assertEquals(j("""{"answer":"a","sources":[]}"""), NullToAbsent.apply(schema, j("""{"answer":"a","sources":[],"proposedAction":null}""")))

    @Test fun requiredNullsStaySoValidationReportsThem() =
        assertEquals(j("""{"answer":null,"sources":[]}"""), NullToAbsent.apply(schema, j("""{"answer":null,"sources":[]}""")))

    @Test fun nestedBranchAndArrayItemsAreNormalised() {
        val wire = j("""{"answer":"a","sources":[],"proposedAction":{"type":"SHOPPING_ADD","items":[{"name":"milk","qty":null}]}}""")
        assertEquals(j("""{"answer":"a","sources":[],"proposedAction":{"type":"SHOPPING_ADD","items":[{"name":"milk"}]}}"""), NullToAbsent.apply(schema, wire))
        val reminder = j("""{"answer":"a","sources":[],"proposedAction":{"type":"REMINDER","title":"t","note":null}}""")
        assertEquals(emptyList<String>(), JsonSchemaValidator.validate(schema, NullToAbsent.apply(schema, reminder)))
    }

    @Test fun tooDeepInputAndRecursiveSchemasDoNotOverflow() {
        var deep: kotlinx.serialization.json.JsonElement = kotlinx.serialization.json.JsonArray(emptyList()); repeat(100_000) { deep = kotlinx.serialization.json.JsonArray(listOf(deep)) }
        assertTrue(deep === NullToAbsent.apply(schema, deep))
        val rec = SchemaRefs.parse("""{"${'$'}ref":"#/${'$'}defs/A","${'$'}defs":{"A":{"oneOf":[{"${'$'}ref":"#/${'$'}defs/A"}]}}}""")
        assertEquals(j("""{"a":null}"""), NullToAbsent.apply(rec, j("""{"a":null}""")))
    }

    /** Phase 5 T3 carry: properties beside a union apply too. */
    @Test fun propertiesBesideAUnionAreNormalisedToo() {
        val s = SchemaRefs.parse("""{"type":"object","properties":{"a":{"type":"string"},"b":{"type":"string"}},"required":["a"],"anyOf":[{"required":["a"]}]}""")
        assertEquals(j("""{"a":"x"}"""), NullToAbsent.apply(s, j("""{"a":"x","b":null}""")))
    }
}
