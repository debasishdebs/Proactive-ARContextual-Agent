package com.rokid.context.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class TextHygieneTest {
    /** Phase 6 (F14): the one separator set shared by voice, ask and prompt text. */
    @Test fun separatorsCoverWhitespaceControlsAndUnicodeLineBreaks() {
        val messy = "a \t\n\r\u000B\u000C\u0000\u0007b\u0085c\u2028d\u2029e"
        assertEquals("a b c d e", messy.replace(TextHygiene.SEPARATORS, " "))
    }

    @Test fun separatorsLeaveWordsAndJoinersAlone() {
        val kept = "\u0915\u094D\u200D\u0937 milk"
        assertEquals(kept, kept.replace(TextHygiene.SEPARATORS, " "))
    }
}
