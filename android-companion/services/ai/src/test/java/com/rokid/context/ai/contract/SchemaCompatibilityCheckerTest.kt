package com.rokid.context.ai.contract

import com.rokid.context.ai.Capability
import com.rokid.context.ai.Endpoint
import com.rokid.context.ai.EndpointKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SchemaCompatibilityCheckerTest {
    private val base = DefaultSchemas.forKey(PromptKey.ANSWER)!!
    private val azure = Endpoint("az", "Azure", EndpointKind.AZURE_OPENAI, "https://az.example", "2024-10-21", listOf("gpt"), setOf(Capability.CHAT, Capability.JSON_SCHEMA), 1)

    private fun edit(f: (MutableMap<String, JsonElement>) -> Unit): String = JsonObject(base.toMutableMap().also(f)).toString()
    private fun props(m: MutableMap<String, JsonElement>) = m["properties"]!!.jsonObject.toMutableMap()
    private fun check(draft: String) = SchemaCompatibilityChecker.check(PromptKey.ANSWER, draft, listOf(azure))

    @Test fun defaultAndAllowedRefinementsPass() {
        assertEquals(emptyList<String>(), SchemaCompatibilityChecker.check(PromptKey.ANSWER, null, listOf(azure)))
        val refined = edit { m ->
            val p = props(m)
            p["answer"] = JsonObject(p["answer"]!!.jsonObject + mapOf("maxLength" to JsonPrimitive(300), "description" to JsonPrimitive("Short")))
            p["mood"] = Json.parseToJsonElement("""{"type":"string"}""")
            m["properties"] = JsonObject(p)
        }
        assertEquals(emptyList<String>(), check(refined))
    }

    @Test fun forbiddenEditsAreNamed() {
        assertEquals(listOf("Removed sources"), check(edit { m -> m["properties"] = JsonObject(props(m) - "sources"); m["required"] = JsonArray(listOf(JsonPrimitive("answer"))) }))
        assertEquals(listOf("Changed the type of answer"), check(edit { m -> val p = props(m); p["answer"] = Json.parseToJsonElement("""{"type":"number"}"""); m["properties"] = JsonObject(p) }))
        assertEquals(listOf("sources must stay required"), check(edit { m -> m["required"] = JsonArray(listOf(JsonPrimitive("answer"))) }))
        assertEquals(listOf("Loosened maxLength of answer"), check(edit { m -> val p = props(m); p["answer"] = JsonObject(p["answer"]!!.jsonObject + ("maxLength" to JsonPrimitive(900))); m["properties"] = JsonObject(p) }))
        assertEquals(listOf("New property mood must be optional"), check(edit { m ->
            val p = props(m); p["mood"] = Json.parseToJsonElement("""{"type":"string"}"""); m["properties"] = JsonObject(p)
            m["required"] = JsonArray(listOf("answer", "sources", "mood").map { JsonPrimitive(it) })
        }))
        assertEquals(listOf("Schema isn't valid JSON"), check("{not json"))
    }

    @Test fun actionVariantsKeepTheirRequiredFields() {
        val defs = base["\$defs"]!!.jsonObject
        val pa = defs["ProposedAction"]!!.jsonObject
        val branches = (pa["oneOf"] as JsonArray).map { b ->
            val o = b.jsonObject
            if ((o["properties"]!!.jsonObject["type"]!!.jsonObject["enum"] as JsonArray).single() == JsonPrimitive("REMINDER"))
                JsonObject(o + ("required" to JsonArray(listOf(JsonPrimitive("type"), JsonPrimitive("dueAt"))))) else o
        }
        val draft = JsonObject(base + ("\$defs" to JsonObject(defs + ("ProposedAction" to JsonObject(mapOf("oneOf" to JsonArray(branches)))))))
        assertEquals(listOf("proposedAction[REMINDER].title must stay required"), check(draft.toString()))
        val dropped = JsonObject(base + ("\$defs" to JsonObject(defs + ("ProposedAction" to JsonObject(mapOf("oneOf" to JsonArray(branches.drop(1))))))))
        assertEquals(emptyList<String>(), check(dropped.toString())) // dropping a whole variant narrows the union: allowed
    }

    @Test fun everyBoundEndpointMustCompileTheDraft() {
        var deep = """{"type":"string"}"""
        repeat(6) { deep = """{"type":"object","properties":{"x":$deep},"required":["x"]}""" }
        val draft = edit { m -> val p = props(m); p["deep"] = Json.parseToJsonElement(deep); m["properties"] = JsonObject(p) }
        assertEquals(listOf("Azure: Too deeply nested (max 5 levels)"), check(draft))
        val noSchema = azure.copy(capabilities = setOf(Capability.CHAT))
        assertEquals(emptyList<String>(), SchemaCompatibilityChecker.check(PromptKey.ANSWER, draft, listOf(noSchema))) // instructions fallback
    }

    private val cycleReason = "Recursive schemas aren't supported (\$defs/%s refers back to itself)"

    @Test fun directRefCycleIsRejected() {
        val defs = base["\$defs"]!!.jsonObject
        val looped = JsonObject(defs + ("Node" to Json.parseToJsonElement("""{"type":"object","properties":{"next":{"${'$'}ref":"#/${'$'}defs/Node"}}}""")))
        val p = props(base.toMutableMap()); p["tree"] = Json.parseToJsonElement("""{"${'$'}ref":"#/${'$'}defs/Node"}""")
        val draft = JsonObject(base + ("\$defs" to JsonObject(looped)) + ("properties" to JsonObject(p)))
        assertEquals(listOf(cycleReason.format("Node")), check(draft.toString()))
    }

    @Test fun indirectMultiBranchRefCycleIsRejectedWithoutHanging() {
        val defs = base["\$defs"]!!.jsonObject
        val a = Json.parseToJsonElement("""{"oneOf":[{"${'$'}ref":"#/${'$'}defs/B"},{"${'$'}ref":"#/${'$'}defs/B"},{"type":"string"}]}""")
        val b = Json.parseToJsonElement("""{"type":"object","properties":{"x":{"${'$'}ref":"#/${'$'}defs/A"},"y":{"${'$'}ref":"#/${'$'}defs/A"}}}""")
        val p = props(base.toMutableMap()); p["tree"] = Json.parseToJsonElement("""{"${'$'}ref":"#/${'$'}defs/A"}""")
        val draft = JsonObject(base + ("\$defs" to JsonObject(defs + ("A" to a) + ("B" to b))) + ("properties" to JsonObject(p)))
        val problems = check(draft.toString())
        assertEquals(1, problems.size)
        assertEquals(true, problems.single().startsWith("Recursive schemas aren't supported"))
    }

    @Test fun cycleIsRejectedEvenWhenNoBoundEndpointUsesSchemas() {
        val defs = base["\$defs"]!!.jsonObject
        val looped = JsonObject(defs + ("Node" to Json.parseToJsonElement("""{"${'$'}ref":"#/${'$'}defs/Node"}""")))
        val draft = JsonObject(base + ("\$defs" to looped))
        assertEquals(listOf(cycleReason.format("Node")), SchemaCompatibilityChecker.check(PromptKey.ANSWER, draft.toString(), emptyList()))
    }

    private fun withProp(name: String, schema: String): String { val p = props(base.toMutableMap()); p[name] = Json.parseToJsonElement(schema); return JsonObject(base + ("properties" to JsonObject(p))).toString() }

    @Test fun nonDefsRefsAreRejectedExplicitly() {
        val bad = "Unsupported \$ref \"%s\" (use #/\$defs/<name> or #/definitions/<name> of an existing entry)"
        assertEquals(listOf(bad.format("#")), SchemaCompatibilityChecker.check(PromptKey.ANSWER, withProp("t", """{"${'$'}ref":"#"}"""), emptyList()))
        assertEquals(listOf(bad.format("#/properties/answer")), SchemaCompatibilityChecker.check(PromptKey.ANSWER, withProp("t", """{"${'$'}ref":"#/properties/answer"}"""), emptyList()))
        assertEquals(listOf(bad.format("#/${'$'}defs/Nope")), SchemaCompatibilityChecker.check(PromptKey.ANSWER, withProp("t", """{"${'$'}ref":"#/${'$'}defs/Nope"}"""), emptyList()))
    }

    @Test fun defsAndDefinitionsWithTheSameNameAreSeparate() {
        val draft = JsonObject(base + ("definitions" to Json.parseToJsonElement("""{"ProposedAction":{"${'$'}ref":"#/${'$'}defs/ProposedAction"}}""")))
        assertEquals(emptyList<String>(), SchemaCompatibilityChecker.check(PromptKey.ANSWER, draft.toString(), emptyList()))
        val looped = JsonObject(base + ("definitions" to Json.parseToJsonElement("""{"X":{"${'$'}ref":"#/definitions/X"}}""")))
        assertEquals(listOf("Recursive schemas aren't supported (\$defs/X refers back to itself)").map { it.replace("\$defs/X", "definitions/X") }, SchemaCompatibilityChecker.check(PromptKey.ANSWER, looped.toString(), emptyList()))
    }

    @Test fun emptyOneOfIsRejected() {
        val defs = base["\$defs"]!!.jsonObject
        val draft = JsonObject(base + ("\$defs" to JsonObject(defs + ("ProposedAction" to JsonObject(mapOf("oneOf" to JsonArray(emptyList())))))))
        assertEquals(listOf("proposedAction must keep at least one variant"), check(draft.toString()))
    }

    private fun editBranch(tag: String, f: (MutableMap<String, JsonElement>) -> Unit): String {
        val defs = base["\$defs"]!!.jsonObject
        val branches = (defs["ProposedAction"]!!.jsonObject["oneOf"] as JsonArray).map { b ->
            val o = b.jsonObject
            if ((o["properties"]!!.jsonObject["type"]!!.jsonObject["enum"] as JsonArray).single() == JsonPrimitive(tag)) JsonObject(o.toMutableMap().also(f)) else o
        }
        return JsonObject(base + ("\$defs" to JsonObject(defs + ("ProposedAction" to JsonObject(mapOf("oneOf" to JsonArray(branches))))))).toString()
    }

    @Test fun patternAndAdditionalPropertiesCannotBeLoosened() {
        fun withField(f: String, g: (JsonObject) -> JsonObject) = editBranch("REMINDER") { m ->
            val p = m["properties"]!!.jsonObject.toMutableMap(); p[f] = g(p[f]!!.jsonObject); m["properties"] = JsonObject(p) }
        assertEquals(listOf("Changed the pattern of proposedAction[REMINDER].dueAt"), check(withField("dueAt") { JsonObject(it - "pattern") }))
        assertEquals(listOf("Changed the pattern of proposedAction[REMINDER].dueAt"), check(withField("dueAt") { JsonObject(it + ("pattern" to JsonPrimitive(".*"))) }))
        assertEquals(listOf("Allowed extra properties in the top level"), check(edit { m -> m["additionalProperties"] = JsonPrimitive(true) }))
    }

    @Test fun constEqualsASingleValueEnum() {
        val draft = editBranch("REMINDER") { m ->
            val p = m["properties"]!!.jsonObject.toMutableMap()
            p["type"] = Json.parseToJsonElement("""{"type":"string","const":"REMINDER"}"""); m["properties"] = JsonObject(p) }
        assertEquals(emptyList<String>(), check(draft))
    }
}
