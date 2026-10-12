package com.rokid.context.prompt

import com.rokid.context.ai.contract.PromptKey
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

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

    @Test fun composerAndVariablesShareOnePlaceholderRegex() {
        val dir = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
            .resolve("services/ai/src/main/java/com/rokid/context/prompt")
        val literals = dir.listFiles()!!.filter { it.extension == "kt" }.sumOf { f -> Regex("Regex\\(\"\"\"\\\\\\{\\\\\\{").findAll(f.readText()).count() }
        assertEquals(1, literals)
        assertEquals("a milk b {{nope}}", PromptComposer.render("a {{ question }} b {{nope}}", mapOf("question" to "milk")))
        assertEquals(setOf("question", "nope"), TemplateVariables.used("a {{ question }} b {{nope}}"))
    }
}
