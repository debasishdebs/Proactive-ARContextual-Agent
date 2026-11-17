package com.rokid.context.ai.contract

import com.rokid.context.ai.Role

/**
 * How a prompt uses photos (codex r10: the ONE declaration RoleRunner and the router read; PromptKeyImageUseTest fails when a call site passes images
 * to a key declared [NONE]).
 * [NONE]: never receives images (RoleRunner refuses a call that attaches one). [OPTIONAL]: may carry photos as context (ANSWER's photo recall, SUMMARIZE
 * over frames); a failed image call may be retried as text, still under the liveness re-check. [REQUIRED]: the input IS the photo; every attempt carries
 * it, it is never dropped or retried as text, and an endpoint that cannot deliver it fails closed.
 */
enum class ImageUse { NONE, OPTIONAL, REQUIRED }

/** One prompt per role except EMBED, plus the shared SYSTEM preamble (spec §4.3). [name] is the `prompt.id` in Room. */
enum class PromptKey(val role: Role?, val label: String, val imageUse: ImageUse = ImageUse.NONE) {
    SYSTEM(null, "System preamble"),
    VISION_EXTRACT(Role.VISION_EXTRACT, "Facts from frames", ImageUse.REQUIRED),
    EXPLICIT_REMEMBER(Role.EXPLICIT_REMEMBER, "Remember this"),
    SUMMARIZE(Role.SUMMARIZE, "Re-summarise", ImageUse.OPTIONAL),
    CRITIC(Role.CRITIC, "Critic"),
    ANSWER(Role.ANSWER, "Answer", ImageUse.OPTIONAL),
    NUDGE(Role.NUDGE, "Nudge"),
    /** Wave 2: one call summarises up to 3 closed scene clusters. Appended so [of] still returns VISION_EXTRACT for the role. */
    CLUSTER_SUMMARY(Role.VISION_EXTRACT, "Scene summaries", ImageUse.REQUIRED),
    /** Wave B: one photo in, the wearer's activity out ([IntentOutput]). Appended after [CLUSTER_SUMMARY]; [of] still returns VISION_EXTRACT for the role. */
    INTENT_CLASSIFY(Role.VISION_EXTRACT, "Activity from a photo", ImageUse.REQUIRED),
    /** Place Guide: one photo plus numbered candidate names in, a candidate number or NONE out ([PlaceMatchOutput]). Appended after [INTENT_CLASSIFY]. */
    PLACE_MATCH(Role.VISION_EXTRACT, "Place from a photo", ImageUse.REQUIRED),
    /** Retrieval quality (spec section 3): the question text and today's date in, a time window out ([TimeAnchorOutput]); never any memory. Appended after [PLACE_MATCH]. */
    TIME_ANCHOR(Role.ANSWER, "Time words of a question"),
    /** Retrieval quality (spec section 10, T10): the question text and today's date in, a short English search query and the language out ([CondenseOutput]); never any memory. Appended after [TIME_ANCHOR]. */
    CONDENSE_QUERY(Role.ANSWER, "Search words of a question");

    /** The input is the photo: no retry path ever drops it (see [ImageUse.REQUIRED]). */
    val requiresImage: Boolean get() = imageUse == ImageUse.REQUIRED

    companion object {
        fun of(role: Role): PromptKey? = entries.firstOrNull { it.role == role }
        fun parse(id: String): PromptKey? = entries.firstOrNull { it.name == id }
    }
}
