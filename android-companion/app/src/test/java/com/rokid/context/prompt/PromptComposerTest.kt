package com.rokid.context.prompt

import org.junit.Assert.assertTrue
import org.junit.Test

class PromptComposerTest {
    @Test
    fun composeInjectsSystemAndVariables() {
        val source = object : PromptTemplateSource {
            override fun load(templateId: PromptTemplateId): String = when (templateId) {
                PromptTemplateId.SYSTEM_GLOBAL -> "SYSTEM"
                PromptTemplateId.WRITE_CONTEXT_EXPLICIT -> "Hello {{persona}} / {{raw_input}}"
                else -> "unused"
            }
        }

        val composer = PromptComposer(source)
        val prompt = composer.compose(
            templateId = PromptTemplateId.WRITE_CONTEXT_EXPLICIT,
            variables = mapOf(
                "persona" to "coach",
                "raw_input" to "I met Alex today",
            ),
        )

        assertTrue(prompt.contains("SYSTEM"))
        assertTrue(prompt.contains("Hello coach / I met Alex today"))
    }

    @Test
    fun hashIsStableForSamePrompt() {
        val hash1 = PromptHash.sha256("abc")
        val hash2 = PromptHash.sha256("abc")
        assertTrue(hash1 == hash2)
    }
}
