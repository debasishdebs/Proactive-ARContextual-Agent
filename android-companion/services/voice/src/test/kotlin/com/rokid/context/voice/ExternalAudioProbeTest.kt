package com.rokid.context.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExternalAudioProbeTest {
    private val feed = 134_142L + ExternalAudioProbe.SILENCE_TAIL_BYTES

    @Test fun aReaderThatTookTheWholeFeedIsSupported() =
        assertEquals(ProbeVerdict.SUPPORTED, ExternalAudioProbe.verdict(ProbeOutcome(feed, feed, gotOutput = true)))

    /** Preflight F7: the half-consumed boundary. 166 142 / 2 = 83 071 bytes certainly consumed is just enough. */
    @Test fun exactlyHalfTheFeedConsumedIsSupportedAndOneByteLessIsNot() {
        assertEquals(ProbeVerdict.SUPPORTED, ExternalAudioProbe.verdict(ProbeOutcome(feed, 65_536L + 83_071, gotOutput = true)))
        assertEquals(ProbeVerdict.UNSUPPORTED, ExternalAudioProbe.verdict(ProbeOutcome(feed, 65_536L + 83_070, gotOutput = true)))
    }

    /** Writes stall near 64 KiB when nobody reads: < 50 % of the feed can have been consumed. */
    @Test fun aNonReaderIsUnsupportedEvenWhenItProducedText() {
        assertEquals(ProbeVerdict.UNSUPPORTED, ExternalAudioProbe.verdict(ProbeOutcome(feed, 65_536, gotOutput = true)))
        assertEquals(ProbeVerdict.UNSUPPORTED, ExternalAudioProbe.verdict(ProbeOutcome(feed, 64_000, gotOutput = false)))
    }

    @Test fun aReaderOfLessThanHalfIsUnsupported() =
        assertEquals(ProbeVerdict.UNSUPPORTED, ExternalAudioProbe.verdict(ProbeOutcome(feed, 65_536 + 80_000, gotOutput = true)))

    @Test fun aFeedBelowNinetySixKBDecidesNothing() =
        assertNull(ExternalAudioProbe.verdict(ProbeOutcome(64_000, 64_000, gotOutput = true)))
}
