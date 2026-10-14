package com.rokid.context.orchestration

/**
 * Utterances that ask for an action rather than about memories ("note that…", user notes). With no memory to answer
 * from, AnswerService still calls the model for these, so the action can be proposed; any other ask keeps "nothing yet".
 * Small on purpose: a miss only means the old no-match answer.
 */
object ActionTriggers {
    private val PATTERNS = listOf(
        Regex("""\bnote that\b"""),
        Regex("""\bmake a note\b"""),
        Regex("""\bremember that\b"""),
        Regex("""\badd\b.+\bto (my |the )?shopping list\b"""),
        Regex("""\bremind me\b"""),
    ).map { Regex(it.pattern, RegexOption.IGNORE_CASE) }

    fun matches(utterance: String): Boolean = PATTERNS.any { it.containsMatchIn(utterance) }
}
