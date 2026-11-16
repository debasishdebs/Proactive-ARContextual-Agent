package com.rokid.context.prompt

import com.rokid.context.ai.contract.PromptKey

/** spec §4.3 template variables. Unknown ones are flagged in the editor; required ones gate Save (spec §4.5). */
object TemplateVariables {
    val KNOWN = listOf("persona", "language", "recent_context", "retrieved_memories", "context_sources", "question", "now")
    /** `{{ name }}`, shared with [PromptComposer.render]. Every brace is escaped: Android's ICU rejects a bare `}`. */
    internal val PLACEHOLDER = Regex("""\{\{\s*([^\{\}]*?)\s*\}\}""")

    fun used(template: String): Set<String> = PLACEHOLDER.findAll(template).map { it.groupValues[1] }.toCollection(LinkedHashSet())
    fun unknown(template: String): List<String> = used(template).filter { it !in KNOWN }
    fun required(key: PromptKey): Set<String> = when (key) {
        // The INTENT_CLASSIFY seed is a fixed instruction over one attached photo: it takes no variables.
        PromptKey.SYSTEM, PromptKey.INTENT_CLASSIFY -> emptySet()
        // PLACE_MATCH carries its numbered candidate list in {{recent_context}} (the else branch).
        PromptKey.ANSWER -> setOf("question", "retrieved_memories")
        else -> setOf("recent_context")
    }
    fun missing(key: PromptKey, template: String): Set<String> = required(key) - used(template)
}
