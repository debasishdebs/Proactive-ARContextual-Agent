package com.rokid.context.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExternalAudioProbeTest {
    private val feed = 134_142L + ExternalAudioProbe.SILENCE_TAIL_BYTES
    private val cap = TestPipes.CAPACITY

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
        assertNull(ExternalAudioProbe.verdict(ProbeOutcome(feed, feed, capacityBytes = 256_000)), "a 256 000-byte pipe swallows the whole feed: undecidable")
    }

    /** Fix round 2: a pipe bigger than half the feed can't tell a reader from a non-reader, so the probe decides nothing. */
    @Test fun aCapacityAboveHalfTheFeedDecidesNothing() {
        val half = feed / 2 // 83 071
        assertEquals(ProbeVerdict.SUPPORTED, ExternalAudioProbe.verdict(ProbeOutcome(feed, feed, capacityBytes = half)))
        assertEquals(ProbeVerdict.UNSUPPORTED, ExternalAudioProbe.verdict(ProbeOutcome(feed, half, capacityBytes = half)))
        assertNull(ExternalAudioProbe.verdict(ProbeOutcome(feed, feed, capacityBytes = half + 1)))
        assertNull(ExternalAudioProbe.verdict(ProbeOutcome(feed, half + 1, capacityBytes = half + 1)), "a non-reader is never UNSUPPORTED here")
    }

    /** A recognizer that stops at the end of speech must still read past the capacity: it can't exceed the clip. */
    @Test fun aCapacityAboveTheClipDecidesNothing() {
        assertEquals(ProbeVerdict.SUPPORTED, ExternalAudioProbe.verdict(ProbeOutcome(400_000, 400_000, capacityBytes = 150_000, clipBytes = 150_000)))
        assertNull(ExternalAudioProbe.verdict(ProbeOutcome(400_000, 400_000, capacityBytes = 150_000, clipBytes = 149_999)))
        assertNull(ExternalAudioProbe.verdict(ProbeOutcome(400_000, 150_000, capacityBytes = 150_000, clipBytes = 149_999)))
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
