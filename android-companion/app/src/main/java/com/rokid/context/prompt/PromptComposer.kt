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

    internal fun render(template: String, variables: Map<String, String>): String {
        return variables.entries.fold(template) { acc, (key, value) ->
            acc.replace("{{$key}}", value)
        }
    }
}
