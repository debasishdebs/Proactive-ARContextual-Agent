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
    NUDGE(Role.NUDGE, "Nudge"),
    /** Wave 2: one call summarises up to 3 closed scene clusters. Appended so [of] still returns VISION_EXTRACT for the role. */
    CLUSTER_SUMMARY(Role.VISION_EXTRACT, "Scene summaries"),
    /** Wave B: one photo in, the wearer's activity out ([IntentOutput]). Appended after [CLUSTER_SUMMARY]; [of] still returns VISION_EXTRACT for the role. */
    INTENT_CLASSIFY(Role.VISION_EXTRACT, "Activity from a photo");

    companion object {
        fun of(role: Role): PromptKey? = entries.firstOrNull { it.role == role }
        fun parse(id: String): PromptKey? = entries.firstOrNull { it.name == id }
    }
}
