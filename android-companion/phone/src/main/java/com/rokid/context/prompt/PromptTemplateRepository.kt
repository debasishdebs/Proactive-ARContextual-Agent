package com.rokid.context.prompt

import android.content.Context

class PromptTemplateRepository(
    private val appContext: Context,
) : PromptTemplateSource {
    override fun load(templateId: PromptTemplateId): String {
        return runCatching {
            appContext.assets.open(templateId.assetPath).bufferedReader().use { it.readText() }
        }.getOrElse { defaultTemplate(templateId) }
    }

    private fun defaultTemplate(templateId: PromptTemplateId): String = when (templateId) {
        PromptTemplateId.SYSTEM_GLOBAL -> "You are Proactive Context Agent."
        PromptTemplateId.WRITE_CONTEXT_EXPLICIT -> "Convert explicit input to durable context."
        PromptTemplateId.WRITE_CONTEXT_BATCHED -> "Summarize batched context window."
        PromptTemplateId.READ_CONTEXT_ANSWER -> "Answer query from retrieved trusted context."
        PromptTemplateId.CRITIC_VALIDATION -> "Validate summary quality against source context."
        PromptTemplateId.RE_SUMMARIZE -> "Re-summarize using critic feedback."
    }
}
