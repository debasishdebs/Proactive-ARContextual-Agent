package com.rokid.context.voice.commands

// Adapted from Live Fit services/voice/src/main/kotlin/com/debasish/livefit/services/voice/CompositeCommands.kt@4529735
// (Apache-2.0, same owner). Pure Kotlin, no Android.

/**
 * Splits "mark that and then I'm driving" into clauses.
 *
 * Differences from Live Fit, on purpose:
 * - Sentence punctuation also separates clauses ("Mark that! I'm driving."). A full stop, bang, question mark or
 *   semicolon counts only before whitespace, so "3.5 km" stays one clause.
 * - The separators carry no leading `\s*`. Live Fit's pattern rescans long whitespace runs from every position
 *   (quadratic, 5 s at 16k spaces); this one is linear and the pieces are trimmed afterwards.
 */
object EnglishClauses {
    private val separators = Regex(""",|\band then\b|\bthen\b|\band\b|[.!?;]++(?=\s)""", RegexOption.IGNORE_CASE)
    private val nonWord = Regex("[^a-z']+")

    /** Clauses made only of these are dropped ("hey, mark that please" is one command). */
    private val filler = setOf("hey", "hi", "ok", "okay", "please", "um", "uh", "so", "now", "alright", "rokid", "also")

    fun split(utterance: String): List<String> = utterance.split(separators)
        .map { it.trim().trim('.', '!', '?', ';', ':').trim() }
        .filter { clause ->
            clause.isNotEmpty() && !clause.lowercase().split(nonWord).filter { it.isNotEmpty() }.all { it in filler }
        }
}
