package com.rokid.context.prompt

import com.rokid.context.ai.contract.PromptKey
import org.junit.Assert.assertEquals
import org.junit.Test

class TemplateVariablesTest {
    @Test fun typoAndSpacedVariables() {
        val t = "Q: {{ question }}\nMem: {{questoin}} {{retrieved_memories}}"
        assertEquals(setOf("question", "questoin", "retrieved_memories"), TemplateVariables.used(t))
        assertEquals(listOf("questoin"), TemplateVariables.unknown(t))
        assertEquals(emptySet<String>(), TemplateVariables.missing(PromptKey.ANSWER, t))
        assertEquals(setOf("retrieved_memories"), TemplateVariables.missing(PromptKey.ANSWER, "Q: {{question}}"))
        assertEquals(setOf("recent_context"), TemplateVariables.missing(PromptKey.VISION_EXTRACT, "Look at the frames."))
        assertEquals(emptySet<String>(), TemplateVariables.missing(PromptKey.SYSTEM, "You are helpful."))
    }
}
