package com.rokid.context.voice

import com.rokid.context.core.model.audio.Pcm
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The probe's paced feed (Task 5 carries: pump after the drain deadline; skip when the measured pipe can't decide). */
class ProbeFeedTest {
    /** A pipe that takes [capacity] bytes and then nothing (a non-reading recognizer), or everything ([reads]). */
    private class Sink(private val capacity: Long, private val reads: Boolean = false, private val now: () -> Long) : PipeSink {
        var taken = 0L
        var closedAt: Long? = null
        override fun tryWrite(bytes: ByteArray): Int {
            if (closedAt != null) return PipeSink.CLOSED
            if (!reads && taken + bytes.size > capacity) return PipeSink.FULL
            taken += bytes.size
            return PipeSink.WROTE
        }
        override fun close() { if (closedAt == null) closedAt = now() }
    }

    private val clip = ByteArray(134_142)

    @Test fun theBundledClipIsDecidableOnlyThroughAPinnedPipe() {
        // feed = 134 142 + 32 000 = 166 142: decidable up to fed / 2 = 83 071 (and capacity <= clip).
        assertTrue(ProbeFeed.canDecide(clip.size, 51_200), "4 KiB pages x 16 slots")
        assertTrue(ProbeFeed.canDecide(clip.size, 64_000), "16 KiB pages pinned to 65 536 (4 slots)")
        assertTrue(ProbeFeed.canDecide(clip.size, 83_071))
        assertFalse(ProbeFeed.canDecide(clip.size, 83_072))
        assertFalse(ProbeFeed.canDecide(clip.size, 256_000), "unpinned 16 KiB pages: skip, NOT_VERIFIED")
    }

    @Test fun aNonReaderIsStillClosedAfterTheDrainDeadline() = runTest {
        val sink = Sink(64_000) { testScheduler.currentTime }
        val feeder = PcmFeeder(sink, maxPendingBytes = clip.size + ExternalAudioProbe.SILENCE_TAIL_BYTES, clock = { testScheduler.currentTime }, capacityBytes = 64_000)
        val outcome = ProbeFeed.stream(feeder, clip) { kotlinx.coroutines.delay(it) }
        val feedMs = Pcm.millis((clip.size + ExternalAudioProbe.SILENCE_TAIL_BYTES).toLong())
        val closedAt = sink.closedAt
        assertTrue(feeder.closed, "the write end is closed (EOF) although the pipe never drained")
        assertTrue(closedAt != null && closedAt >= feedMs + ProbeFeed.DRAIN_MS && closedAt <= feedMs + ProbeFeed.DRAIN_MS + ProbeFeed.PUMP_MS, "closed at $closedAt")
        assertEquals(ProbeOutcome(166_142, 64_000, 64_000, peerClosed = false, clipBytes = 134_142), outcome)
        assertEquals(ProbeVerdict.UNSUPPORTED, ExternalAudioProbe.verdict(outcome))
    }

    @Test fun aReaderGetsEofAsSoonAsTheFeedEnds() = runTest {
        val sink = Sink(64_000, reads = true) { testScheduler.currentTime }
        val feeder = PcmFeeder(sink, clock = { testScheduler.currentTime }, capacityBytes = 64_000)
        val outcome = ProbeFeed.stream(feeder, clip) { kotlinx.coroutines.delay(it) }
        assertTrue(feeder.closed)
        assertTrue(sink.closedAt!! < Pcm.millis(166_142) + ProbeFeed.DRAIN_MS, "no drain wait when nothing is pending")
        assertEquals(166_142L, outcome.acceptedBytes)
        assertEquals(ProbeVerdict.SUPPORTED, ExternalAudioProbe.verdict(outcome))
    }

    @Test fun theFeedIsPacedInHundredMillisecondChunks() = runTest {
        val sizes = mutableListOf<Int>()
        val sink = object : PipeSink {
            override fun tryWrite(bytes: ByteArray): Int { sizes += bytes.size; return PipeSink.WROTE }
            override fun close() = Unit
        }
        val pauses = mutableListOf<Long>()
        ProbeFeed.stream(PcmFeeder(sink, clock = { testScheduler.currentTime }), ByteArray(6_500)) { pauses += it; kotlinx.coroutines.delay(it) }
        // 6 500 + 32 000 = 38 500 bytes: 12 chunks of 3 200 and one of 100.
        assertEquals(List(12) { Pcm.CHUNK_BYTES } + 100, sizes)
        assertEquals(List(12) { 100L } + 3L, pauses)
    }
}
