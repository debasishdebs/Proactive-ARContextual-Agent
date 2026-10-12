package com.rokid.context.prompt

interface PromptTemplateSource {
    fun load(templateId: PromptTemplateId): String
}
