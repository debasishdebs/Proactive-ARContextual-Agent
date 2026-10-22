package com.rokid.context.voice.commands

// Adapted from Live Fit services/voice/src/main/kotlin/com/debasish/livefit/services/voice/LanguageRegistry.kt@4529735
// (Apache-2.0, same owner). Pure Kotlin, no Android.

/**
 * Everything voice needs for one language. Add a language by registering a pack.
 * [parseUtterance] works per clause (like Live Fit's `LanguagePack.parseUtterance`): the matched commands in spoken
 * order plus the clauses that matched nothing, which the caller forwards to Ask.
 */
data class LanguagePack(
    val locale: String,
    val displayName: String,
    val parseUtterance: (String) -> ParsedUtterance,
)

object LanguageRegistry {
    const val DEFAULT_LOCALE = "en-IN"

    private fun english(locale: String, name: String) = LanguagePack(locale, name, MarkCommandParser::parseUtterance)

    val packs: Map<String, LanguagePack> = listOf(
        english("en-IN", "English (India)"),
        english("en-US", "English (US)"),
        english("en-GB", "English (UK)"),
    ).associateBy { it.locale }

    fun forLocale(locale: String): LanguagePack? = packs[locale]
}
