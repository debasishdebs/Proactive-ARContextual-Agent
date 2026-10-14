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
    /** BUG-R3-2: the chosen seed has no key yet, so the step shows an API key field for it ([key]). */
    val seedNeedsKey: Boolean get() = useSeed && seed?.needsKey == true
    /** Test needs something to test: the seed (with a typed key when it has none), or a typed endpoint with a model. */
    val canTest: Boolean get() = !testing && (useSeed && (!seedNeedsKey || key.isNotBlank()) || preset != null && model.isNotBlank())
    val canContinue: Boolean get() = !testing && (useSeed || (preset != null && result?.ok == true))
}
