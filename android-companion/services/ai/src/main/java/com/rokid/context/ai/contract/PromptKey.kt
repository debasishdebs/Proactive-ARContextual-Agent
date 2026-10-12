package com.rokid.context.ai.contract

import com.rokid.context.ai.Role

/** One prompt per role except EMBED, plus the shared SYSTEM preamble (spec §4.3). [name] is the `prompt.id` in Room. */
enum class PromptKey(val role: Role?, val label: String) {
    SYSTEM(null, "System preamble"),
    VISION_EXTRACT(Role.VISION_EXTRACT, "Facts from frames"),
    EXPLICIT_REMEMBER(Role.EXPLICIT_REMEMBER, "Remember this"),
    SUMMARIZE(Role.SUMMARIZE, "Re-summarise"),
    CRITIC(Role.CRITIC, "Critic"),
    ANSWER(Role.ANSWER, "Answer"),
    NUDGE(Role.NUDGE, "Nudge");

    companion object {
        fun of(role: Role): PromptKey? = entries.firstOrNull { it.role == role }
        fun parse(id: String): PromptKey? = entries.firstOrNull { it.name == id }
    }
}
