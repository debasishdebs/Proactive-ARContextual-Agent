package com.rokid.context.memory.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchNormalizerTest {
    @Test fun lowercasesAndStripsPunctuationAndSymbols() {
        assertEquals("milk carton is empty", SearchNormalizer.normalize("Milk-carton, is EMPTY!! 🥛"))
    }

    @Test fun keepsIndicCombiningMarksInsideWords() {
        // दूध = द + ू (U+0942, a combining vowel sign) + ध; the danda (।) is punctuation.
        assertEquals("आज दूध खत्म", SearchNormalizer.normalize("आज दूध खत्म।"))
    }

    @Test fun removesZeroWidthFormatCharacters() {
        assertEquals("क्षेत्र", SearchNormalizer.normalize("क्‍षेत्र"))
    }

    @Test fun matchQueryQuotesAndPrefixesEachToken() {
        assertEquals("\"दूध*\" \"milk*\"", SearchNormalizer.toMatchQuery("दूध, milk"))
        assertNull(SearchNormalizer.toMatchQuery("  !!! 🥛 "))
    }

    @Test fun matchQueryCannotInjectFtsSyntax() {
        assertEquals("\"a*\" \"or*\" \"b*\"", SearchNormalizer.toMatchQuery("a\" OR b*"))
    }

    @Test fun matchQueryCapsTokenCount() {
        assertEquals(8, SearchNormalizer.toMatchQuery((1..20).joinToString(" ") { "w$it" })!!.split(' ').size)
    }
}
