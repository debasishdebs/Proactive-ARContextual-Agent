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

    @Test fun anyMatchQueryOrsDistinctQuotedPrefixTermsCappedAt16() {
        assertEquals("\"milk*\" OR \"carton*\"", SearchNormalizer.toAnyMatchQuery("Milk, milk CARTON milk"))
        assertEquals(16, SearchNormalizer.toAnyMatchQuery((1..40).joinToString(" ") { "w$it" })!!.split(" OR ").size)
        assertNull(SearchNormalizer.toAnyMatchQuery("  ?! \"\" *"))
    }

    @Test fun anyMatchQueryCannotInjectFtsSyntax() {
        // Quotes, wildcards, column filters, grouping and operators all become inert quoted terms.
        assertEquals("\"a*\" OR \"or*\" OR \"b*\" OR \"near*\" OR \"c*\"", SearchNormalizer.toAnyMatchQuery("a\" OR \"b NEAR(c*)"))
        assertEquals("\"searchtext*\" OR \"milk*\"", SearchNormalizer.toAnyMatchQuery("searchText:-milk"))
    }
}
