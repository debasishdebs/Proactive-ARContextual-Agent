package com.rokid.context.ai.run

import com.rokid.context.ai.contract.AnswerOutput
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.prompt.PromptDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveGatesTest {
    private val draft = PromptDraft("Q {{question}} M {{retrieved_memories}}", null)
    private val good = TestBedResult("{}", 10, 1, 1, null, OutputEvaluation(emptyList(), null, emptyList(), AnswerOutput("a", emptyList(), null)), null)

    @Test fun allFiveGatesAndTheVariablesMustHoldForTheSameDraft() {
        assertTrue(SaveGates.of(PromptKey.ANSWER, draft, emptyList(), draft to good).all)
        assertFalse(SaveGates.of(PromptKey.ANSWER, draft.copy(template = draft.template + "!"), emptyList(), draft to good).ran) // edited after the run
        assertFalse(SaveGates.of(PromptKey.ANSWER, draft, listOf("Removed sources"), draft to good).all)
        assertFalse(SaveGates.of(PromptKey.ANSWER, draft, emptyList(), null).ran)
        val failed = good.copy(evaluation = null, failure = "AI key rejected")
        assertFalse(SaveGates.of(PromptKey.ANSWER, draft, emptyList(), draft to failed).ran)
        val semantic = good.copy(evaluation = good.evaluation!!.copy(semanticProblems = listOf("sources not in the given memories: x")))
        val g = SaveGates.of(PromptKey.ANSWER, draft, emptyList(), draft to semantic)
        assertEquals(listOf(true, true, true, true, true, false), listOf(g.compatible, g.variables, g.ran, g.validated, g.decoded, g.semantic))
    }

    @Test fun missingOrUnknownVariablesBlockSave() {
        assertFalse(SaveGates.of(PromptKey.ANSWER, PromptDraft("Q {{question}}", null), emptyList(), null).variables)
        assertFalse(SaveGates.of(PromptKey.ANSWER, PromptDraft("{{question}} {{retrieved_memories}} {{questoin}}", null), emptyList(), null).variables)
    }

    /** F19: gate 1 belongs to the draft the checker saw — never ✓ before a check, stale as soon as the draft changes. */
    @Test fun theCompatibilityGateIsTiedToTheCheckedDraft() {
        assertFalse(SaveGates.of(PromptKey.ANSWER, draft, null, draft to good).compatible) // not checked yet
        assertTrue(SaveGates.of(PromptKey.ANSWER, draft, draft to emptyList(), draft to good).all)
        val edited = draft.copy(outputSchema = "{}")
        assertFalse(SaveGates.of(PromptKey.ANSWER, edited, draft to emptyList(), edited to good).compatible) // checked an older draft
        assertFalse(SaveGates.of(PromptKey.ANSWER, draft, draft to listOf("Removed sources"), draft to good).compatible)
        assertTrue(SaveGates.of(PromptKey.SYSTEM, PromptDraft("You are terse.", null), null, null).all) // SYSTEM has no schema to check
    }

    @Test fun systemNeedsNoTestRun() {
        assertTrue(SaveGates.of(PromptKey.SYSTEM, PromptDraft("You are terse. {{persona}}", null), emptyList(), null).all)
        assertFalse(SaveGates.of(PromptKey.SYSTEM, PromptDraft("  ", null), emptyList(), null).all)
    }
}
