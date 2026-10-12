package com.rokid.context.prompt

import java.time.LocalDateTime
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Values for one call. Builders put user/model-derived text through [PromptComposer.dataBlock] before it lands here. */
data class PromptVars(
    val recentContext: String = "",
    val retrievedMemories: String = "",
    val contextSources: String = "",
    val question: String = "",
) {
    fun toMap(persona: Persona, now: String): Map<String, String> = mapOf(
        "persona" to persona.text, "language" to persona.language.promptText, "recent_context" to recentContext,
        "retrieved_memories" to retrievedMemories, "context_sources" to contextSources, "question" to question, "now" to now,
    )
}

object NowText {
    /** "2026-10-12T10:00 (Monday)": the local time the model should treat as now. */
    fun of(now: LocalDateTime): String =
        "${now.truncatedTo(ChronoUnit.MINUTES)} (${now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)})"
}
