package com.rokid.context.prompt

class PromptComposer(
    private val repository: PromptTemplateSource,
) {
    fun compose(templateId: PromptTemplateId, variables: Map<String, String>): String {
        val system = repository.load(PromptTemplateId.SYSTEM_GLOBAL).trim()
        val bodyTemplate = repository.load(templateId)
        val body = render(bodyTemplate, variables).trim()
        return "$system\n\n$body"
    }

    /** Single pass over the template: substituted values are never re-scanned, so a `{{name}}` inside a value stays literal. */
    internal fun render(template: String, variables: Map<String, String>): String =
        PLACEHOLDER.replace(template) { m -> variables[m.groupValues[1]] ?: m.value }

    private companion object {
        val PLACEHOLDER = Regex("""\{\{([^\{\}]+)\}\}""")
    }
}
