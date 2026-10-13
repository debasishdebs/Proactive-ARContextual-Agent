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
    private var up = 5_000_000L
    private var boot = "boot-a"
    private val fsyncs = mutableListOf<File>()
    private val dir get() = File(tmp.root, "outbound")
    private fun buffer(capacity: Int = 200, maxBytes: Long = 8L * 1024 * 1024, writer: (File, ByteArray) -> Unit = ::atomicWrite) =
        OutboundBuffer(
            dir, capacity, maxBytes, clock = { now }, elapsed = { up }, bootId = { boot }, writer = writer,
            fsyncDir = { fsyncs += it; true },
        )
    private val hour = 3_600_000L

    /** Spec §1.7: persisted in app storage, so a process restart keeps the frames, oldest first. */
    @Test fun framesSurviveARestartInCaptureOrder() {
        val b = buffer()
        b.add("g2", byteArrayOf(2), now - 10, null, null, null)
        b.add("g1", byteArrayOf(1), now - 20, 5, "ab", 80)
        val again = buffer()
        assertEquals(2, again.size)
        val first = again.oldest()!!
        assertEquals(BufferedMeta("g1", now - 20, 5, "ab", 80, up, "boot-a"), first.meta)
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
        b.add("old", byteArrayOf(1), now - 72 * 3_600_000L - 1, null, null, null, elapsedAtCaptureMs = up - 72 * 3_600_000L - 1)
        b.add("new", byteArrayOf(2), now - 1, null, null, null, elapsedAtCaptureMs = up - 1)
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

    /** Review Focus 1: an NTP jump forward does not age out frames captured in this boot. */
    @Test fun aClockJumpForwardDoesNotAgeOutFramesFromThisBoot() {
        val b = buffer()
        b.add("g1", byteArrayOf(1), now, null, null, null)
        now += 100 * hour; up += hour
        assertEquals("g1", b.oldest()?.meta?.frameId)
    }

    @Test fun aClockJumpBackDoesNotKeepAFrameBeyond72hOfUptime() {
        val b = buffer()
        b.add("g1", byteArrayOf(1), now, null, null, null)
        now -= 10 * hour; up += 73 * hour
        assertNull(b.oldest())
        assertEquals(0, b.size)
    }

    @Test fun aFrameFromAnEarlierBootUsesWallClockAge() {
        buffer().add("old", byteArrayOf(1), now - 73 * hour, null, null, null)
        buffer().add("young", byteArrayOf(2), now - hour, null, null, null)
        boot = "boot-b"; up = 1_000L
        val b = buffer()
        assertEquals("young", b.oldest()?.meta?.frameId)
        assertEquals(1, b.size)
    }

    @Test fun aFutureDatedFrameFromAnEarlierBootIsDropped() {
        buffer().add("g1", byteArrayOf(1), now + 2 * hour, null, null, null)
        boot = "boot-b"
        val b = buffer()
        assertNull(b.oldest())
        assertEquals(0, b.size)
    }

    @Test fun reAddingAnExistingKeyDoesNotDoubleCountBytes() {
        val b = buffer()
        b.add("g1", ByteArray(100), now, null, null, null)
        b.add("g1", ByteArray(100), now, null, null, null)
        assertEquals(1, b.size)
        assertEquals(100L, b.bytes)
    }

    @Test fun sendTimeIsRebasedToTheCurrentWallClock() {
        val b = buffer()
        val captured = now
        b.add("g1", byteArrayOf(1), captured, null, null, null)
        up += 1_000; now += 1_000 + 5 * 60_000L
        assertEquals(captured + 5 * 60_000L, b.sendTimeMs(b.oldest()!!))
    }

    @Test fun byteCapDropsOldestFirst() {
        val b = buffer(maxBytes = 8L * 1024 * 1024)
        val big = 4 * 1024 * 1024 - 1024
        (1..3).forEach { b.add("g$it", ByteArray(big), now + it, null, null, null) }
        assertEquals(2, b.size)
        assertEquals("g2", b.oldest()!!.meta.frameId)
        assertEquals(2L * big, b.bytes)
    }

    /** Review Focus 2: storage full while capturing keeps the newest (FIFO), not the oldest. */
    @Test fun storageFullEvictsTheOldestAndStoresTheNewest() {
        var fail = false
        val b = buffer(writer = { f, bytes -> if (fail) { fail = false; throw IOException("ENOSPC") } else atomicWrite(f, bytes) })
        b.add("g1", byteArrayOf(1), now - 2, null, null, null)
        b.add("g2", byteArrayOf(2), now - 1, null, null, null)
        fail = true
        assertTrue(b.add("g3", byteArrayOf(3), now, null, null, null))
        assertEquals(2, b.size)
        assertFalse(b.contains("g1"))
        assertTrue(b.contains("g3"))
        assertEquals(4, dir.list()!!.size)
    }

    @Test fun storageFullWithAnEmptyBufferStoresNothing() {
        val b = buffer(writer = { _, _ -> throw IOException("ENOSPC") })
        assertFalse(b.add("g1", byteArrayOf(1), now, null, null, null))
        assertEquals(0, b.size)
        assertTrue(dir.list().orEmpty().isEmpty())
    }

    @Test fun oldMetaWithoutBootFieldsStillLoads() {
        dir.mkdirs()
        val key = String.format(java.util.Locale.ROOT, "%015d_%s", now - hour, "g1")
        File(dir, "$key.jpg").writeBytes(byteArrayOf(1))
        File(dir, "$key.json").writeText("""{"frameId":"g1","capturedAtMs":${now - hour}}""")
        val b = buffer()
        assertEquals("g1", b.oldest()?.meta?.frameId)
        now += 72 * hour // wall-clock age 73 h; uptime unchanged
        assertNull(b.oldest())
    }

    @Test fun dirIsFsyncedAfterEachAdd() {
        val b = buffer()
        b.add("g1", byteArrayOf(1), now - 1, null, null, null)
        b.add("g2", byteArrayOf(2), now, null, null, null)
        assertEquals(listOf(dir, dir), fsyncs)
    }
}
