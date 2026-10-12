package com.rokid.context.ai.run

import com.rokid.context.ai.contract.AnswerOutput
import com.rokid.context.ai.contract.DefaultSchemas
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.ai.contract.SemanticContext
import com.rokid.context.ai.schema.SchemaRefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class OutputCheckTest {
    private val schema = DefaultSchemas.forKey(PromptKey.ANSWER)!!
    private val ctx = SemanticContext(setOf("m1"), LocalDateTime.of(2026, 10, 12, 10, 0))
    private fun eval(raw: String) = OutputCheck.evaluate(PromptKey.ANSWER, schema, raw, ctx)

    @Test fun eachStageIsReported() {
        assertFalse(eval("no json here").parsed)
        val bad = eval("""{"answer":"","sources":[]}""")
        assertTrue(bad.parsed); assertFalse(bad.schemaValid); assertEquals(listOf("$.answer: at least 1 characters"), bad.problems())
        val sem = eval("""{"answer":"hi","sources":["x"]}""")
        assertTrue(sem.schemaValid); assertTrue(sem.decoded); assertFalse(sem.semanticOk)
        val ok = eval("```json\n{\"answer\":\"hi\",\"sources\":[\"m1\"],\"proposedAction\":null}\n```")
        assertTrue(ok.ok); assertEquals(listOf("m1"), (ok.output as AnswerOutput).sources)
    }

    @Test fun storedSchemaOrTheDefault() {
        assertEquals(schema, OutputCheck.schemaFor(PromptKey.ANSWER, null))
        assertEquals(schema, OutputCheck.schemaFor(PromptKey.ANSWER, "  "))
        assertEquals(schema, OutputCheck.schemaFor(PromptKey.ANSWER, "{broken")) // a stored schema that no longer parses
    }

    /** Task 3 carry: a schema that parses but can't be applied (bad ref, non-string type, non-object branch) never throws. */
    @Test fun aMalformedSchemaIsATypedFailureNotACrash() {
        listOf(
            """{"${'$'}ref":"#/nowhere/x"}""",
            """{"type":"object","properties":{"answer":{"type":[{}]}}}""",
            """{"anyOf":[1]}""",
        ).forEach { text ->
            val e = OutputCheck.evaluate(PromptKey.ANSWER, SchemaRefs.parse(text), """{"answer":"hi","sources":["m1"]}""", ctx)
            assertTrue(text, e.schemaUnusable); assertFalse(text, e.ok)
            assertEquals(text, listOf(OutputCheck.SCHEMA_UNUSABLE), e.problems())
        }
    }
}
