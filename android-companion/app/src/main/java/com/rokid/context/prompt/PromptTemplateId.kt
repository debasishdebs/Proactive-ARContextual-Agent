package com.rokid.context.prompt

enum class PromptTemplateId(val assetPath: String) {
    SYSTEM_GLOBAL("prompts/system_global.txt"),
    WRITE_CONTEXT_EXPLICIT("prompts/write_context_explicit.txt"),
    WRITE_CONTEXT_BATCHED("prompts/write_context_batched.txt"),
    READ_CONTEXT_ANSWER("prompts/read_context_answer.txt"),
    CRITIC_VALIDATION("prompts/critic_validation.txt"),
    RE_SUMMARIZE("prompts/re_summarize.txt"),
}
