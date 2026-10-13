package com.rokid.context.voice

import com.rokid.context.core.model.audio.Pcm
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A Linux pipe as the kernel counts it (review Important 1): [slots] page buffers of [pageSize] bytes. A write merges
 * into the newest page when it fits there entirely (anonymous pipes, `PIPE_BUF_FLAG_CAN_MERGE`); otherwise it takes
 * ceil(len / pageSize) fresh slots, and is refused (EAGAIN) when that many aren't free. A page frees when fully read.
 */
class SlotPipe(private val pageSize: Int, private val slots: Int = 16) : PipeSink {
    private class Page(var filled: Int, var unread: Int)
    private val pages = ArrayDeque<Page>()
    var closed = false
    var read = 0L
        private set

    override fun tryWrite(bytes: ByteArray): Int {
        if (closed) return PipeSink.CLOSED
        val len = bytes.size
        val last = pages.lastOrNull()
        if (last != null && last.filled + len <= pageSize) {
            last.filled += len; last.unread += len
            return PipeSink.WROTE
        }
        val need = (len + pageSize - 1) / pageSize
        if (pages.size + need > slots) return PipeSink.FULL
        var left = len
        repeat(need) { val n = min(left, pageSize); pages.addLast(Page(n, n)); left -= n }
        return PipeSink.WROTE
    }

    /** The recognizer reads until it has consumed [upTo] bytes in total (or the pipe is empty). */
    fun readUpTo(upTo: Long) {
        while (read < upTo && pages.isNotEmpty()) {
            val p = pages.first()
            val n = min(p.unread.toLong(), upTo - read).toInt()
            p.unread -= n; read += n
            if (p.unread == 0) pages.removeFirst()
        }
    }

    override fun close() { closed = true }
}

class PageSlotPipeTest {
    private val clip = 134_142
    private val feed = clip + ExternalAudioProbe.SILENCE_TAIL_BYTES
    private val everything = Long.MAX_VALUE

    /** The two configurations Task 6 can meet: 4 KiB pages (default 16 slots) and 16 KiB pages pinned to 64 KiB (4 slots). */
    private val pipes = listOf(4_096 to 16, 16_384 to 4)

    private fun measured(page: Int, slots: Int) = PipeCapacity.measure(SlotPipe(page, slots))

    @Test fun capacityIsCountedInSlotsAndMeasuredByFilling() {
        assertEquals(51_200L, measured(4_096, 16), "16 slots x 3 200: 3 200-byte writes never share a 4 KiB page")
        assertEquals(64_000L, measured(16_384, 4), "4 slots x 5 writes")
        assertEquals(256_000L, measured(16_384, 16), "16 KiB pages at the default 16 slots")
    }

    /** Feeds the probe clip + silence chunk by chunk; the recognizer reads after each chunk until it has [readUpTo] bytes. */
    private fun probe(page: Int, slots: Int, readUpTo: Long): ProbeVerdict? {
        val pipe = SlotPipe(page, slots)
        val feeder = PcmFeeder(pipe, maxPendingBytes = feed, capacityBytes = measured(page, slots))
        val audio = ByteArray(feed)
        var i = 0
        while (i < feed) {
            val n = min(Pcm.CHUNK_BYTES, feed - i)
            feeder.offer(audio.copyOfRange(i, i + n)); i += n
            pipe.readUpTo(readUpTo); feeder.pump()
        }
        val m = feeder.meter()
        return ExternalAudioProbe.verdict(ProbeOutcome(m.fedBytes, m.acceptedBytes, m.capacityBytes, m.peerClosed))
    }

    /** Feeds [seconds] of audio in chunks; the recognizer reads after each chunk until it has [readUpTo] bytes. */
    private fun guard(page: Int, slots: Int, seconds: Int, readUpTo: Long): PipeMeter {
        val pipe = SlotPipe(page, slots)
        val feeder = PcmFeeder(pipe, capacityBytes = measured(page, slots))
        repeat(seconds * 10) { feeder.offer(ByteArray(Pcm.CHUNK_BYTES)); pipe.readUpTo(readUpTo); feeder.pump() }
        return feeder.meter()
    }

    @Test fun aNonReaderIsUnsupportedAndTripsTheGuard() = pipes.forEach { (page, slots) ->
        assertEquals(ProbeVerdict.UNSUPPORTED, probe(page, slots, readUpTo = 0), "page $page")
        assertTrue(FallbackGuard.suspects(guard(page, slots, seconds = 4, readUpTo = 0)), "page $page")
    }

    @Test fun aReaderIsSupportedAndNeverTripsTheGuard() = pipes.forEach { (page, slots) ->
        assertEquals(ProbeVerdict.SUPPORTED, probe(page, slots, readUpTo = everything), "page $page")
        assertEquals(ProbeVerdict.SUPPORTED, probe(page, slots, readUpTo = clip.toLong()), "page $page: it stops at the end of speech")
        assertFalse(FallbackGuard.suspects(guard(page, slots, seconds = 10, readUpTo = everything)), "page $page")
    }

    @Test fun aSlowReaderThatConsumedMoreThanTheCapacityIsNotAccused() = pipes.forEach { (page, slots) ->
        val cap = measured(page, slots)
        val m = guard(page, slots, seconds = 10, readUpTo = cap + 20_000)
        assertTrue(m.lastWriteRefused && m.flatFedBytes >= Pcm.BYTES_PER_SECOND, "page $page: it stalled")
        assertFalse(FallbackGuard.suspects(m), "page $page")
    }

    /** The review's case: 16 KiB pages at 16 slots take the whole probe feed unread. Measured, it is never verified. */
    @Test fun anUnpinnedSixteenKiBPipeNeverPassesANonReader() {
        assertNull(probe(16_384, 16, readUpTo = 0))
        assertNull(probe(16_384, 16, readUpTo = everything), "nor a reader: 256 000 > half the feed")
        assertFalse(FallbackGuard.suspects(guard(16_384, 16, seconds = 8, readUpTo = 0)), "the pipe holds 8 s")
        assertTrue(FallbackGuard.suspects(guard(16_384, 16, seconds = 10, readUpTo = 0)))
    }
}
