package com.rokid.context.ai.schema

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonSchemaValidatorTest {
    private fun s(t: String) = SchemaRefs.parse(t)
    private fun v(t: String) = Json.parseToJsonElement(t)
    private fun errs(schema: String, value: String) = JsonSchemaValidator.validate(s(schema), v(value))

    private val fact = """{"type":"object","properties":{"text":{"type":"string","minLength":1,"maxLength":5},
        "confidence":{"type":"number","minimum":0,"maximum":1},"tags":{"type":"array","items":{"type":"string"},"maxItems":2,"uniqueItems":true}},
        "required":["text","confidence"],"additionalProperties":false}"""

    @Test fun validValuePasses() = assertEquals(emptyList<String>(), errs(fact, """{"text":"milk","confidence":0.5,"tags":["a"]}"""))

    @Test fun everyBrokenRuleIsReportedWithItsPath() {
        val e = errs(fact, """{"text":"toolong","confidence":1.5,"tags":["a","a","b"],"extra":1}""")
        assertTrue(e.toString(), e.contains("$.text: at most 5 characters"))
        assertTrue(e.toString(), e.contains("$.confidence: must be ≤ 1"))
        assertTrue(e.toString(), e.contains("$.tags: at most 2 items"))
        assertTrue(e.toString(), e.contains("$.tags: items must be unique"))
        assertTrue(e.toString(), e.contains("$: unexpected property 'extra'"))
    }

    @Test fun missingRequiredAndWrongTypes() {
        assertEquals(listOf("$: missing 'confidence'"), errs(fact, """{"text":"a"}"""))
        assertEquals(listOf("$.confidence: must be number"), errs(fact, """{"text":"a","confidence":"high"}"""))
        assertEquals(listOf("$: must be object"), errs(fact, "[]"))
    }

    @Test fun integerEnumConstAndNullableTypes() {
        assertEquals(emptyList<String>(), errs("""{"type":"integer"}""", "3"))
        assertEquals(listOf("$: must be integer"), errs("""{"type":"integer"}""", "3.5"))
        assertEquals(listOf("$: must be one of \"low\", \"high\""), errs("""{"enum":["low","high"]}""", "\"mid\""))
        assertEquals(emptyList<String>(), errs("""{"type":["string","null"]}""", "null"))
        assertEquals(listOf("$: must be \"REMINDER\""), errs("""{"const":"REMINDER"}""", "\"NOTE\""))
    }

    @Test fun patternIsASearchAndCountsCodePoints() {
        val ldt = """{"type":"string","pattern":"^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2})?$"}"""
        assertEquals(emptyList<String>(), errs(ldt, "\"2026-10-12T18:30\""))
        assertEquals(1, errs(ldt, "\"tomorrow 6pm\"").size)
        assertEquals(emptyList<String>(), errs("""{"type":"string","maxLength":2}""", "\"दू\"")) // 2 code points
    }

    @Test fun refsAndOneOfBranches() {
        val schema = """{"type":"object","properties":{"a":{"${'$'}ref":"#/${'$'}defs/A"}},"${'$'}defs":{"A":{"oneOf":[
            {"type":"object","properties":{"type":{"enum":["X"]},"n":{"type":"integer"}},"required":["type","n"]},
            {"type":"object","properties":{"type":{"enum":["Y"]}},"required":["type"]}]}}}"""
        assertEquals(emptyList<String>(), errs(schema, """{"a":{"type":"X","n":1}}"""))
        assertEquals(listOf("$.a: matches none of the allowed shapes"), errs(schema, """{"a":{"type":"X"}}"""))
    }

    @Test fun exclusiveBoundsAndMultipleOf() {
        assertEquals(listOf("$: must be > 0"), errs("""{"type":"number","exclusiveMinimum":0}""", "0"))
        assertEquals(listOf("$: must be a multiple of 0.5"), errs("""{"type":"number","multipleOf":0.5}""", "0.3"))
    }

    private fun deep(n: Int): kotlinx.serialization.json.JsonElement { var e: kotlinx.serialization.json.JsonElement = kotlinx.serialization.json.JsonArray(emptyList()); repeat(n) { e = kotlinx.serialization.json.JsonArray(listOf(e)) }; return e }

    @Test fun tooDeepValueIsAnErrorNotAStackOverflow() {
        val e = JsonSchemaValidator.validate(s("""{"type":"array","uniqueItems":true}"""), deep(100_000))
        assertEquals(listOf("$: nested too deeply"), e)
    }

    @Test fun recursiveOneOfSchemaTerminates() {
        val schema = s("""{"${'$'}ref":"#/${'$'}defs/A","${'$'}defs":{"A":{"oneOf":[{"${'$'}ref":"#/${'$'}defs/A"}]}}}""")
        assertTrue(JsonSchemaValidator.validate(schema, v("1")).isNotEmpty())
    }

    @Test fun longKeysAreTruncatedInMessages() {
        val key = "k".repeat(100)
        val e = errs("""{"type":"object","additionalProperties":false}""", "{\"$key\":1}")
        assertEquals(listOf("$: unexpected property '${"k".repeat(32)}…'"), e)
    }

    @Test fun errorListIsCappedAtTwenty() {
        val e = errs("""{"type":"array","items":{"type":"string"}}""", "[" + (1..500).joinToString(",") + "]")
        assertEquals(20, e.size)
    }

    @Test fun badPatternStringsAreNotMatchedAfterLengthFailed() =
        assertEquals(listOf("$: at most 2 characters"), errs("""{"type":"string","maxLength":2,"pattern":"^z+$"}""", "\"abcd\""))

    @Test fun numbersMustBeFiniteAndIntegersExact() {
        assertEquals(listOf("$: must be number"), errs("""{"type":"number"}""", "1e999"))
        assertEquals(listOf("$: must be integer"), errs("""{"type":"integer"}""", "1e300"))
        assertEquals(listOf("$: must be integer"), errs("""{"type":"integer"}""", "9007199254740993"))
        assertEquals(emptyList<String>(), errs("""{"type":"integer"}""", "9007199254740992"))
    }
}
