package com.rokid.context.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TranscriptsTest {
    /** Phase 0: on-device onResults gave RESULTS_RECOGNITION = null after complete partials. */
    @Test fun aNullOrEmptyFinalFallsBackToTheLastNonEmptyPartial() {
        assertEquals("What do I need from the store", FinalTranscript.pick(null, "What do I need from the store"))
        assertEquals("milk", FinalTranscript.pick(listOf(null, "  "), "milk"))
        assertEquals("eggs", FinalTranscript.pick(listOf("", "eggs"), "milk"))
        assertNull(FinalTranscript.pick(emptyList(), " "))
    }

    /** Literals are escaped (preflight F5): NEL U+0085, LS U+2028, PS U+2029, ZWJ U+200D, Devanagari U+0900..U+097F. */
    @Test fun separatorsCollapseAndDevanagariSurvives() {
        assertEquals("milk is low", VoiceText.clean("milk is\u0085low "))
        assertEquals("milk is low", VoiceText.clean("milk\u2028is\u2029low"))
        assertEquals(
            "\u0926\u0942\u0927 \u0915\u0939\u093E\u0901 \u0939\u0948",
            VoiceText.clean("  \u0926\u0942\u0927\t\u0915\u0939\u093E\u0901\n \u0939\u0948 "),
        )
        assertEquals("\u0915\u094D\u200D\u0937", VoiceText.clean("\u0915\u094D\u200D\u0937"), "ZWJ inside a Devanagari word is kept")
    }

    /** U+1F600 is the surrogate pair \uD83D\uDE00. */
    @Test fun clippingNeverSplitsASurrogatePair() {
        assertEquals("ab", VoiceText.clip("ab\uD83D\uDE00c", 3))
        assertEquals("ab\uD83D\uDE00", VoiceText.clip("ab\uD83D\uDE00c", 4))
        assertEquals("x".repeat(500), VoiceText.clean("x".repeat(900)))
    }
}
