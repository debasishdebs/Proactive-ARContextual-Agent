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
    /** Never prints the key. */
    override fun toString() = "AiStepState(preset=$preset, url=$url, model=$model, key=${if (key.isEmpty()) "" else "***"}, testing=$testing, result=$result)"

    val useSeed: Boolean get() = seed != null && preset == null
    /** Test needs something to test: the seed, or a typed endpoint with a model. */
    val canTest: Boolean get() = !testing && (preset == null && seed != null || preset != null && model.isNotBlank())
    val canContinue: Boolean get() = !testing && (useSeed || (preset != null && result?.ok == true))
}
