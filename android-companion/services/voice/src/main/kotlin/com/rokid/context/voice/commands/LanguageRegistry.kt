package com.rokid.context.voice.commands

/** Everything voice needs for one language (adapted from Live Fit's LanguagePack). Add a language by registering a pack. */
data class LanguagePack(
    val locale: String,
    val displayName: String,
    /** Parses a whole utterance (intents may sit in later clauses), or null when it is not a mark command. */
    val parseCommand: (String) -> MarkCommand?,
    val splitClauses: (String) -> List<String> = EnglishClauses::split,
)

object LanguageRegistry {
    const val DEFAULT_LOCALE = "en-IN"

    private fun english(locale: String, name: String) = LanguagePack(locale, name, MarkCommandParser::parse)

    val packs: Map<String, LanguagePack> = listOf(
        english("en-IN", "English (India)"),
        english("en-US", "English (US)"),
        english("en-GB", "English (UK)"),
    ).associateBy { it.locale }

    fun forLocale(locale: String): LanguagePack? = packs[locale]
}
