package com.rokid.context.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExternalAudioProbeTest {
    private val feed = 134_142L + ExternalAudioProbe.SILENCE_TAIL_BYTES
    private val cap = PipeLimits.PIPE_CAPACITY_BYTES

    @Test fun aReaderThatTookTheWholeFeedIsSupported() =
        assertEquals(ProbeVerdict.SUPPORTED, ExternalAudioProbe.verdict(ProbeOutcome(feed, feed, cap)))

    /** Preflight F7: the half-consumed boundary. 166 142 / 2 = 83 071 bytes certainly consumed is just enough. */
    @Test fun exactlyHalfTheFeedConsumedIsSupportedAndOneByteLessIsNot() {
        assertEquals(ProbeVerdict.SUPPORTED, ExternalAudioProbe.verdict(ProbeOutcome(feed, cap + 83_071, cap)))
        assertEquals(ProbeVerdict.UNSUPPORTED, ExternalAudioProbe.verdict(ProbeOutcome(feed, cap + 83_070, cap)))
    }

    /** Fix round 1: the pipe's capacity is measured on the device; the verdict subtracts that, not 64 KiB. */
    @Test fun theMeasuredCapacityIsSubtracted() {
        assertEquals(ProbeVerdict.SUPPORTED, ExternalAudioProbe.verdict(ProbeOutcome(feed, 51_200L + 83_071, capacityBytes = 51_200)))
        assertEquals(ProbeVerdict.UNSUPPORTED, ExternalAudioProbe.verdict(ProbeOutcome(feed, 51_200L + 83_071, capacityBytes = 65_536)))
        assertEquals(ProbeVerdict.UNSUPPORTED, ExternalAudioProbe.verdict(ProbeOutcome(feed, feed, capacityBytes = 256_000)), "a 256 000-byte pipe swallows the whole feed")
    }

    /** Writes stall at the capacity when nobody reads: < 50 % of the feed can have been consumed. */
    @Test fun aNonReaderIsUnsupported() {
        assertEquals(ProbeVerdict.UNSUPPORTED, ExternalAudioProbe.verdict(ProbeOutcome(feed, 65_536, cap)))
        assertEquals(ProbeVerdict.UNSUPPORTED, ExternalAudioProbe.verdict(ProbeOutcome(feed, 64_000, cap)))
    }

    @Test fun aReaderOfLessThanHalfIsUnsupported() =
        assertEquals(ProbeVerdict.UNSUPPORTED, ExternalAudioProbe.verdict(ProbeOutcome(feed, 65_536 + 80_000, cap)))

    @Test fun aFeedBelowNinetySixKBDecidesNothing() =
        assertNull(ExternalAudioProbe.verdict(ProbeOutcome(64_000, 64_000, cap)))

    /** Review Minor 1: EPIPE before the feed ends is a recognizer that dropped our audio: a verdict, not "undecided". */
    @Test fun aPeerThatClosedBeforeTheFeedEndedIsUnsupported() =
        assertEquals(ProbeVerdict.UNSUPPORTED, ExternalAudioProbe.verdict(ProbeOutcome(20_000, 20_000, cap, peerClosed = true)))
}
