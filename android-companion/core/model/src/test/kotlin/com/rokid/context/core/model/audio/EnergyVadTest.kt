package com.rokid.context.core.model.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals

class EnergyVadTest {
    private val noise = Random(42)

    /**
     * One 100 ms `pc_audio` chunk (3 200 bytes): a sine with [amp] (RMS ~ amp / sqrt 2) plus Gaussian noise with
     * standard deviation [noiseRms] (its RMS). amp = 0 and noiseRms = 0 is digital silence.
     */
    private fun chunk(amp: Int, noiseRms: Double = 0.0): ByteArray {
        val b = ByteBuffer.allocate(Pcm.CHUNK_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until Pcm.CHUNK_BYTES / 2) {
            val s = amp * sin(i / 5.0) + noise.nextGaussian() * noiseRms
            b.putShort(s.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
        }
        return b.array()
    }

    private fun EnergyVad.feedAll(n: Int, c: () -> ByteArray) =
        repeat(n) { assertEquals(VadDecision.CONTINUE, feed(c()), "chunk ${it + 1} of $n") }

    @Test fun endsAfterTwoPointTwoSecondsOfSilenceFollowingSpeech() {
        val vad = EnergyVad()
        vad.feedAll(3) { chunk(0) } // the 300 ms noise-floor window
        vad.feedAll(5) { chunk(4_000) }
        vad.feedAll(21) { chunk(0) }
        assertEquals(VadDecision.END_OF_SPEECH, vad.feed(chunk(0)))
    }

    /** BUG-R3-3: a 1.5 s cut-off ended asks after "What is". A 2 s thinking pause mid-question no longer ends it. */
    @Test fun aTwoSecondPauseInsideAQuestionDoesNotEndIt() {
        val vad = EnergyVad()
        vad.feedAll(3) { chunk(0, 20.0) }
        vad.feedAll(8) { chunk(1_500, 20.0) } // "What is"
        vad.feedAll(20) { chunk(0, 20.0) } // a 2.0 s pause
        vad.feedAll(10) { chunk(1_500, 20.0) } // "on my shopping list"
        vad.feedAll(21) { chunk(0, 20.0) }
        assertEquals(VadDecision.END_OF_SPEECH, vad.feed(chunk(0, 20.0)))
    }

    /**
     * BUG-R3-3: soft speech never reached the old fixed RMS 300. Amp 200 is RMS ~141 (-47 dBFS) over a quiet-room floor
     * of RMS 20 (-64 dBFS): the threshold is max(80, 3 x 20) = 80, so it is speech, and its end is found.
     */
    @Test fun softSpeechOverAQuietFloorIsHeard() {
        val vad = EnergyVad()
        vad.feedAll(3) { chunk(0, 20.0) }
        vad.feedAll(15) { chunk(200, 20.0) }
        vad.feedAll(21) { chunk(0, 20.0) }
        assertEquals(VadDecision.END_OF_SPEECH, vad.feed(chunk(0, 20.0)))
    }

    /** Below the absolute floor (RMS 80) nothing is speech, however quiet the room: RMS ~71 over digital silence. */
    @Test fun aWhisperUnderTheAbsoluteFloorIsNotSpeech() {
        val vad = EnergyVad()
        vad.feedAll(3) { chunk(0) }
        vad.feedAll(46) { chunk(100) }
        assertEquals(VadDecision.NO_SPEECH, vad.feed(chunk(100)))
    }

    /** A noisy floor (RMS 150, a busy room): the noise itself is never speech, so nothing said ends as NO_SPEECH at 5 s. */
    @Test fun aNoisyFloorIsNotSpeech() {
        val vad = EnergyVad()
        vad.feedAll(49) { chunk(0, 150.0) }
        assertEquals(VadDecision.NO_SPEECH, vad.feed(chunk(0, 150.0)))
    }

    /** Over that noisy floor (threshold ~450) speech at RMS ~1 060 is heard, and the return to the floor ends it. */
    @Test fun speechOverANoisyFloorEndsWhenTheRoomIsBackToItsFloor() {
        val vad = EnergyVad()
        vad.feedAll(3) { chunk(0, 150.0) }
        vad.feedAll(10) { chunk(1_500, 150.0) }
        vad.feedAll(21) { chunk(0, 150.0) }
        assertEquals(VadDecision.END_OF_SPEECH, vad.feed(chunk(0, 150.0)))
    }

    /** Speaking right at the tap: the floor estimate is capped (RMS 400), so loud speech in its window still counts. */
    @Test fun speechDuringTheFloorWindowStillCounts() {
        val vad = EnergyVad()
        vad.feedAll(8) { chunk(4_000) }
        vad.feedAll(21) { chunk(0) }
        assertEquals(VadDecision.END_OF_SPEECH, vad.feed(chunk(0)))
    }

    /** Soft speech right at the tap sets a high floor; the first quiet gap lowers it, and the speech before it counts. */
    @Test fun softSpeechAtTheTapCountsOnceAQuietGapRevealsTheFloor() {
        val vad = EnergyVad()
        vad.feedAll(6) { chunk(200, 20.0) } // the floor window is inside the speech: floor ~141, threshold ~424
        vad.feedAll(21) { chunk(0, 20.0) } // the gap lowers the floor to ~20: the speech before it was speech
        assertEquals(VadDecision.END_OF_SPEECH, vad.feed(chunk(0, 20.0)))
    }

    /** No auto-end before any speech: only the no-speech timeout (5 s) ends a silent listen. */
    @Test fun nothingSaidEndsAsNoSpeechAfterFiveSecondsAndNeverEarlier() {
        val vad = EnergyVad()
        vad.feedAll(49) { chunk(0) }
        assertEquals(VadDecision.NO_SPEECH, vad.feed(chunk(0)))
    }

    @Test fun capsAtFifteenSeconds() {
        val vad = EnergyVad()
        vad.feedAll(149) { chunk(4_000) }
        assertEquals(VadDecision.MAX_REACHED, vad.feed(chunk(4_000)))
    }

    @Test fun resetStartsOverAndAnEmptyChunkChangesNothing() {
        val vad = EnergyVad()
        vad.feedAll(5) { chunk(4_000) }
        vad.feedAll(14) { chunk(0) }
        vad.reset()
        assertEquals(VadDecision.CONTINUE, vad.feed(chunk(0)))
        assertEquals(VadDecision.CONTINUE, vad.feed(ByteArray(0)))
        assertEquals(100L, Pcm.millis(Pcm.CHUNK_BYTES.toLong()))
        vad.feedAll(48) { chunk(0) }
        assertEquals(VadDecision.NO_SPEECH, vad.feed(chunk(0)), "reset also restarted the clock and the speech seen")
    }

    /** A trailing odd byte is not a sample: it is dropped, never read past, and adds no time. */
    @Test fun anOddByteCountIgnoresTheTrailingByte() {
        val vad = EnergyVad()
        assertEquals(VadDecision.CONTINUE, vad.feed(ByteArray(1)))
        vad.feedAll(5) { chunk(4_000) + 0x7F }
        vad.feedAll(21) { chunk(0) + 0x7F }
        assertEquals(VadDecision.END_OF_SPEECH, vad.feed(chunk(0) + 0x7F))
    }

    /** RMS of a sine is about amp / sqrt(2): over digital silence, amp 112 is RMS ~79 (silence), amp 115 ~81 (speech). */
    @Test fun aSineJustUnderTheAbsoluteFloorIsSilence() {
        val under = EnergyVad()
        under.feedAll(3) { chunk(0) }
        under.feedAll(46) { chunk(112) }
        assertEquals(VadDecision.NO_SPEECH, under.feed(chunk(112)))
        val at = EnergyVad()
        at.feedAll(3) { chunk(0) }
        at.feedAll(47) { chunk(115) }
    }
}
