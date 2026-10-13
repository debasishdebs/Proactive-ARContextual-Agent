package com.rokid.context.voice

import com.rokid.context.core.model.audio.Pcm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A pipe with [capacity] bytes of buffer; the test moves [read] forward like a recognizer reading. */
class FakePipe(var capacity: Long = PipeLimits.PIPE_CAPACITY_BYTES) : PipeSink {
    var written = 0L
    var read = 0L
    var closed = false
    var writes = 0
    override fun tryWrite(bytes: ByteArray): Int = when {
        closed -> PipeSink.CLOSED
        written - read + bytes.size > capacity -> PipeSink.FULL
        else -> { written += bytes.size; writes++; PipeSink.WROTE }
    }
    override fun close() { closed = true }
}

class PcmFeederTest {
    private fun chunk() = ByteArray(Pcm.CHUNK_BYTES) { 1 }

    /** Phase 5 T5 ruling: a recognizer that ignores our pipe (it listens to the phone mic) is caught after 2 s. */
    @Test fun aNonReadingRecognizerTripsTheGuardAfterTwoSeconds() {
        val pipe = FakePipe()
        val feeder = PcmFeeder(pipe)
        repeat(20) { feeder.offer(chunk()) } // exactly 2 s: not yet "more than 2 s"
        assertFalse(FallbackGuard.suspects(feeder.meter()))
        repeat(10) { feeder.offer(chunk()) } // 3 s fed; the pipe filled at about 2 s
        val m = feeder.meter()
        assertEquals(96_000L, m.fedBytes)
        assertTrue(m.acceptedBytes <= PipeLimits.PIPE_CAPACITY_BYTES)
        assertTrue(m.lastWriteRefused)
        assertTrue(FallbackGuard.suspects(m))
    }

    /** At exactly 2 s fed the 64 KiB pipe isn't full yet, so the test above can't pin "more than"; the meter can. */
    @Test fun theGuardNeedsMoreThanTwoSecondsFed() {
        assertFalse(FallbackGuard.suspects(PipeMeter(fedBytes = 64_000, acceptedBytes = 0, lastWriteRefused = true)))
        assertTrue(FallbackGuard.suspects(PipeMeter(fedBytes = 64_001, acceptedBytes = 0, lastWriteRefused = true)))
    }

    @Test fun aReaderAtRealTimeNeverTripsIt() {
        val pipe = FakePipe()
        val feeder = PcmFeeder(pipe)
        repeat(100) { feeder.offer(chunk()); pipe.read = pipe.written; feeder.pump() } // 10 s, read as it arrives
        val m = feeder.meter()
        assertEquals(m.fedBytes, m.acceptedBytes)
        assertFalse(m.lastWriteRefused)
        assertFalse(FallbackGuard.suspects(m))
    }

    @Test fun aLaggingReaderThatConsumedSixtyFourKiBIsNotAccused() {
        val pipe = FakePipe()
        val feeder = PcmFeeder(pipe)
        pipe.read = 70_000 // it has read 70 000 bytes and fell behind
        repeat(60) { feeder.offer(chunk()) }
        val m = feeder.meter()
        assertTrue(m.lastWriteRefused)
        assertTrue(m.acceptedBytes - PipeLimits.PIPE_CAPACITY_BYTES >= PipeLimits.GUARD_MIN_CONSUMED_BYTES)
        assertFalse(FallbackGuard.suspects(m))
    }

    @Test fun refusedAudioWaitsAndGoesOutWhenTheReaderCatchesUp() {
        val pipe = FakePipe(capacity = 6_400)
        val feeder = PcmFeeder(pipe)
        repeat(4) { feeder.offer(chunk()) }
        assertEquals(6_400L, pipe.written)
        pipe.read = 6_400; feeder.pump()
        assertEquals(12_800L, pipe.written)
        assertEquals(12_800L, feeder.meter().acceptedBytes)
    }

    @Test fun bigChunksAreSplitAndTheOldestPendingAudioIsDroppedPastTheCap() {
        val reading = FakePipe()
        PcmFeeder(reading).offer(ByteArray(10_000))
        assertEquals(4, reading.writes, "3 200 + 3 200 + 3 200 + 400: every write fits PIPE_BUF")

        val stalled = FakePipe(capacity = 0)
        val feeder = PcmFeeder(stalled, maxPendingBytes = 6_400)
        feeder.offer(ByteArray(10_000))
        assertEquals(10_000L, feeder.meter().fedBytes)
        stalled.capacity = 100_000; feeder.pump()
        assertEquals(3_600L, stalled.written, "only the newest audio under the cap is kept: 3 200 + 400")
    }

    @Test fun finishDrainsThenClosesAndADeadlineClosesAFullPipe() {
        val pipe = FakePipe()
        val feeder = PcmFeeder(pipe)
        feeder.offer(chunk()); feeder.finish()
        assertTrue(pipe.closed); assertTrue(feeder.closed)
        feeder.offer(chunk())
        assertEquals(3_200L, pipe.written, "nothing is written after the end")

        var now = 0L
        val full = FakePipe(capacity = 3_200)
        val stuck = PcmFeeder(full, clock = { now })
        repeat(3) { stuck.offer(chunk()) }
        stuck.finish(drainMs = 1_000)
        assertFalse(full.closed)
        now = 1_000; stuck.pump()
        assertTrue(full.closed, "a recognizer that never reads can't keep the feed open")
    }

    @Test fun aClosedPipeEndsTheFeed() {
        val pipe = FakePipe().apply { closed = true }
        val feeder = PcmFeeder(pipe)
        feeder.offer(chunk())
        assertTrue(feeder.closed)
        assertEquals(0L, feeder.meter().acceptedBytes)
    }
}
