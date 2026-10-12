package com.rokid.context.memory.db

import java.text.Normalizer
import java.util.Locale

/**
 * Normalises English/Hindi text for SQLite's `simple` FTS tokenizer (Review Focus #3). `simple` treats all
 * code points ≥ 128 as word characters, so Indic combining marks stay inside words; we therefore must turn
 * Unicode punctuation, symbols (incl. emoji), separators and format characters into spaces ourselves.
 */
object SearchNormalizer {
    private const val MAX_QUERY_TOKENS = 8
    private val VARIATION_SELECTORS = 0xFE00..0xFE0F
    private const val COMBINING_KEYCAP = 0x20E3

    fun normalize(text: String): String {
        val folded = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val out = StringBuilder(folded.length)
        var i = 0
        while (i < folded.length) {
            val cp = folded.codePointAt(i)
            if (cp in VARIATION_SELECTORS || cp == COMBINING_KEYCAP) Unit // emoji presentation residue: drop
            else when (Character.getType(cp).toByte()) {
                Character.FORMAT -> Unit // ZWJ/ZWNJ and friends: drop
                Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
                Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
                Character.OTHER_PUNCTUATION, Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL,
                Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL, Character.SPACE_SEPARATOR,
                Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.CONTROL,
                Character.SURROGATE, Character.UNASSIGNED, Character.PRIVATE_USE -> out.append(' ')
                else -> out.appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }
        // A term made only of combining marks (e.g. one left after its base symbol became a space) is not a word.
        return out.toString().split(' ').filter { it.isNotEmpty() && !onlyMarks(it) }.joinToString(" ")
    }

    private fun onlyMarks(term: String): Boolean = term.codePoints().allMatch {
        when (Character.getType(it).toByte()) {
            Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> true
            else -> false
        }
    }

    /** Each token becomes a quoted prefix term, AND-ed by FTS; null when nothing searchable remains. */
    fun toMatchQuery(userText: String): String? =
        normalize(userText).split(' ').filter { it.isNotBlank() }.take(MAX_QUERY_TOKENS)
            .joinToString(" ") { "\"$it*\"" }.ifBlank { null }

    private const val MAX_ANY_TOKENS = 16
    private const val MIN_PREFIX_CODE_POINTS = 2

    /**
     * Ranked retrieval: any term may match (BM25 weighs them); distinct quoted prefix terms joined with OR. One-code-point
     * terms ("i", "a") are dropped: as prefixes they match nearly every memory.
     */
    fun toAnyMatchQuery(userText: String): String? =
        normalize(userText).split(' ').filter { it.codePointCount(0, it.length) >= MIN_PREFIX_CODE_POINTS }.distinct().take(MAX_ANY_TOKENS)
            .joinToString(" OR ") { "\"$it*\"" }.ifBlank { null }
}
