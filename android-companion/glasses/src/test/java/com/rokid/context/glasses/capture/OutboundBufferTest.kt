package com.rokid.context.glasses.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class OutboundBufferTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = 1_793_000_000_000L
    private val dir get() = File(tmp.root, "outbound")
    private fun buffer(capacity: Int = 200, writer: (File, ByteArray) -> Unit = ::atomicWrite) =
        OutboundBuffer(dir, capacity, clock = { now }, writer = writer)

    /** Spec §1.7: persisted in app storage, so a process restart keeps the frames, oldest first. */
    @Test fun framesSurviveARestartInCaptureOrder() {
        val b = buffer()
        b.add("g2", byteArrayOf(2), now - 10, null, null, null)
        b.add("g1", byteArrayOf(1), now - 20, 5, "ab", 80)
        val again = buffer()
        assertEquals(2, again.size)
        val first = again.oldest()!!
        assertEquals(BufferedMeta("g1", now - 20, 5, "ab", 80), first.meta)
        assertArrayEquals(byteArrayOf(1), again.readJpeg(first))
    }

    @Test fun aFullBufferDropsTheOldest() {
        val b = buffer(capacity = 3)
        (1..5).forEach { b.add("g$it", byteArrayOf(it.toByte()), now - 10 + it, null, null, null) }
        assertEquals(3, b.size)
        assertEquals("g3", b.oldest()!!.meta.frameId)
        assertEquals(6, dir.list()!!.size) // jpg + json per frame
    }

    @Test fun framesOlderThan72HoursAreDropped() {
        val b = buffer()
        b.add("old", byteArrayOf(1), now - 72 * 3_600_000L - 1, null, null, null)
        b.add("new", byteArrayOf(2), now - 1, null, null, null)
        assertEquals("new", b.oldest()!!.meta.frameId)
        assertEquals(1, b.size)
    }

    /** Review Focus 2 (glasses side): storage full → nothing half-written, the buffer is unchanged. */
    @Test fun diskFullLeavesNothingBehind() {
        var calls = 0
        val b = buffer(writer = { f, bytes -> if (++calls == 2) throw IOException("ENOSPC") else atomicWrite(f, bytes) })
        assertFalse(b.add("g1", byteArrayOf(1), now, null, null, null))
        assertEquals(0, b.size)
        assertTrue(dir.list().orEmpty().isEmpty())
    }

    /** Review Focus 3: a crash between the two writes, or mid-write, leaves files that load as nothing. */
    @Test fun crashLeftoversAreDiscardedOnLoad() {
        dir.mkdirs()
        File(dir, "000000000000001_g1.jpg").writeBytes(byteArrayOf(1))
        File(dir, "000000000000002_g2.json").writeText("{}")
        File(dir, "000000000000003_g3.jpg.tmp").writeText("x")
        assertEquals(0, buffer().size)
        assertTrue(dir.list()!!.isEmpty())
    }

    @Test fun removeAndClear() {
        val b = buffer()
        b.add("g1", byteArrayOf(1), now - 2, null, null, null)
        b.add("g2", byteArrayOf(2), now - 1, null, null, null)
        b.remove("g1")
        assertEquals("g2", b.oldest()!!.meta.frameId)
        b.clear()
        assertNull(b.oldest())
        assertTrue(dir.list()!!.isEmpty())
    }
}
