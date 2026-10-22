package com.rokid.context.voice.commands

/**
 * Splits "mark that and then I'm driving" into clauses (adapted from Live Fit's EnglishClauses, Apache-2.0,
 * same owner; pure Kotlin).
 */
object EnglishClauses {
    private val separators = Regex("""\s*,\s*|\s*\band then\b\s*|\s*\bthen\b\s*|\s*\band\b\s*|\s*[.!?;]+\s+""", RegexOption.IGNORE_CASE)

    /** Clauses made only of these are dropped ("hey, mark that please" is one command). */
    private val filler = setOf("hey", "hi", "ok", "okay", "please", "um", "uh", "so", "now", "alright", "rokid", "also")

    fun split(utterance: String): List<String> = utterance.split(separators)
        .map { it.trim().trim('.', '!', '?', ';', ':').trim() }
        .filter { clause ->
            clause.isNotEmpty() && !clause.lowercase().split(Regex("[^a-z']+")).filter { it.isNotEmpty() }.all { it in filler }
        }
}
