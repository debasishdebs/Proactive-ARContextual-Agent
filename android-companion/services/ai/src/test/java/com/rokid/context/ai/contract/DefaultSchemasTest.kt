package com.rokid.context.ai.contract

import com.rokid.context.ai.EndpointKind
import com.rokid.context.ai.schema.JsonSchemaValidator
import com.rokid.context.ai.schema.WireSchemaCompiler
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultSchemasTest {
    @Test fun everyRoleButSystemHasADefaultThatCompilesForEveryKind() {
        assertNull(DefaultSchemas.forKey(PromptKey.SYSTEM))
        PromptKey.entries.filter { it != PromptKey.SYSTEM }.forEach { k ->
            EndpointKind.entries.forEach { kind -> assertTrue("$k/$kind", WireSchemaCompiler.compile(DefaultSchemas.forKey(k)!!, kind).isSuccess) }
        }
    }

    @Test fun answerAndNudgeShareTheProposedActionUnion() {
        val reply = Json.parseToJsonElement("""{"answer":"Added","sources":[],"proposedAction":{"type":"SHOPPING_ADD","items":[{"name":"milk","qty":2}]}}""")
        assertEquals(emptyList<String>(), JsonSchemaValidator.validate(DefaultSchemas.forKey(PromptKey.ANSWER)!!, reply))
        val nudge = Json.parseToJsonElement("""{"shouldNudge":true,"text":"Buy milk","why":"Seen empty","sourceIds":["m1"],"priority":"high",
            "proposedAction":{"type":"REMINDER","title":"Buy milk","dueAt":"2026-10-12T18:30"}}""")
        assertEquals(emptyList<String>(), JsonSchemaValidator.validate(DefaultSchemas.forKey(PromptKey.NUDGE)!!, nudge))
        val note = Json.parseToJsonElement("""{"answer":"Noted","sources":[],"proposedAction":{"type":"NOTE_ADD","text":"car on level 3"}}""")
        assertEquals(emptyList<String>(), JsonSchemaValidator.validate(DefaultSchemas.forKey(PromptKey.ANSWER)!!, note))
        val longNote = Json.parseToJsonElement("""{"answer":"Noted","sources":[],"proposedAction":{"type":"NOTE_ADD","text":"${"x".repeat(501)}"}}""")
        assertTrue(JsonSchemaValidator.validate(DefaultSchemas.forKey(PromptKey.ANSWER)!!, longNote).isNotEmpty())
        val bad = Json.parseToJsonElement("""{"answer":"x","sources":[],"proposedAction":{"type":"REMINDER","title":"","dueAt":"tomorrow"}}""")
        assertTrue(JsonSchemaValidator.validate(DefaultSchemas.forKey(PromptKey.ANSWER)!!, bad).isNotEmpty())
    }
}
