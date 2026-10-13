package com.rokid.context.phone.ui.onboarding

data class TestOutcome(val ok: Boolean, val text: String)

/** The AI step. [seed] is an existing endpoint; with no [preset] chosen the seed (if any) is what Continue uses. */
data class AiStepState(
    val seed: SeedEndpoint? = null,
    val preset: AiPreset? = null,
    val url: String = "",
    val apiVersion: String = "",
    val model: String = "",
    val key: String = "",
    val testing: Boolean = false,
    val result: TestOutcome? = null,
    val urlError: String? = null,
) {
    val useSeed: Boolean get() = seed != null && preset == null
    val canContinue: Boolean get() = !testing && (useSeed || (preset != null && result?.ok == true))
}
