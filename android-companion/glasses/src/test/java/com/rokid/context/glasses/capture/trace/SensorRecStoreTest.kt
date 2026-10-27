package com.rokid.context.glasses.capture.trace

import com.rokid.context.capture.trace.ChunkCodec
import com.rokid.context.capture.trace.TraceEvent
import com.rokid.context.capture.trace.TraceFiles
import java.io.File
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** v0.9.7 B item 3: the sensor-recording batches on the glasses: same chunk codec as a trace, own folder, size cap with oldest-first prune, wiped and forgotten with the traces. */
class SensorRecStoreTest {
    private fun events(n: Int, t0: Long = 1_000) = (0 until n).map { TraceEvent.Gyro(t0 + it * 100L, t0 + it * 100L, 0.1f * it, 0.2f, 0.3f) }
    private fun store(root: File = TraceTestKit.tempDir(), cap: Long = 16L shl 20, free: Long = Long.MAX_VALUE) = SensorRecStore(File(root, "sensor_rec"), capBytes = cap, freeBytes = { free })
    private fun files(s: SensorRecStore) = s.dir.listFiles()?.map { it.name }?.sorted() ?: emptyList()

    @Test fun aBatchIsAFileWithTheTraceChunkCodecAndNeverLandsInTraces() {
        val root = TraceTestKit.tempDir(); val s = store(root)
        val ev = events(50)
        assertEquals(SensorRecStore.Result.WRITTEN, s.write(startWallMs = 5_000_000, endWallMs = 5_004_900, seq = 0, events = ev))
        val f = s.dir.listFiles()!!.single()
        assertEquals("s-5000000-5004900-0000.json", f.name)
        val chunk = TraceFiles.decodeFile(f.readText()).getOrThrow()
        assertEquals("sensor", chunk.kind)
        assertEquals(ev, ChunkCodec.decode(chunk.traceId, chunk.seq, chunk.sha256, chunk.b64).getOrThrow().events)
        assertTrue("importable traces are untouched", TraceTestKit.readAll(root).isEmpty() && !File(root, "traces").exists())
    }

    @Test fun theStoreStaysUnderItsCapAndPrunesTheOldestFirst() {
        val s = store(cap = 6_000)
        var seq = 0
        for (i in 0 until 12) assertTrue(s.write(1_000L * (i + 1), 1_000L * (i + 1) + 500, seq++, events(40, 1_000L * (i + 1))) != SensorRecStore.Result.REFUSED)
        val names = files(s)
        assertTrue("under the cap: ${s.totalBytes()}", s.totalBytes() <= 6_000)
        assertTrue(names.isNotEmpty() && names.size < 12)
        assertTrue("the newest batch is kept", names.any { it.startsWith("s-12000-") })
        assertFalse("the oldest is gone", names.any { it.startsWith("s-1000-") })
        assertEquals("oldest first: what is left is the newest run", names.sorted(), names)
    }

    @Test fun aBatchBiggerThanTheCapIsRefusedAndWritesNothing() {
        val s = store(cap = 100)
        assertEquals(SensorRecStore.Result.REFUSED, s.write(1, 2, 0, events(40)))
        assertTrue(files(s).isEmpty())
    }

    @Test fun noRoomOnTheVolumeWritesNothing() {
        val s = store(free = 1L shl 20) // under the 64 MiB floor
        assertEquals(SensorRecStore.Result.NO_ROOM, s.write(1, 2, 0, events(10)))
        assertTrue(files(s).isEmpty())
    }

    @Test fun wipeRemovesEveryBatch() {
        val s = store(); s.write(1, 2, 0, events(5)); s.write(3, 4, 1, events(5))
        assertTrue(s.wipe()); assertFalse(s.dir.exists()); assertEquals(0L, s.totalBytes())
    }

    @Test fun aForgottenRangeRemovesOverlappingBatchesAndAnUnreadableNameButKeepsTheRest() {
        val s = store()
        s.write(1_000, 2_000, 0, events(5)); s.write(3_000, 4_000, 1, events(5)); s.write(9_000, 9_500, 2, events(5))
        File(s.dir, "junk.json").writeText("x")
        assertTrue(s.forget(listOf(1_500L..3_200L)))
        assertEquals(listOf("s-9000-9500-0002.json"), files(s))
    }

    @Test fun anEpochWipeOfTheTraceStorageTakesTheBatchesToo() {
        val root = TraceTestKit.tempDir(); val ts = TraceStorage(root)
        ts.sensor.write(1_000, 2_000, 0, events(5))
        assertTrue(ts.wipe()); assertFalse(File(root, "sensor_rec").exists())
    }

    @Test fun aRangeForgetOfTheTraceStorageTakesTheOverlappingBatches() {
        val root = TraceTestKit.tempDir(); val ts = TraceStorage(root)
        ts.sensor.write(1_000, 2_000, 0, events(5)); ts.sensor.write(50_000, 51_000, 1, events(5))
        ts.addForget(listOf("R 1500 1800"))
        assertTrue(ts.runForget())
        assertEquals(listOf("s-50000-51000-0001.json"), files(ts.sensor))
    }

    @Test fun aFailedBatchDeleteKeepsTheForgetRequest() {
        val root = TraceTestKit.tempDir()
        val ts = TraceStorage(root, deleteTree = { false })
        ts.sensor.write(1_000, 2_000, 0, events(5))
        ts.addForget(listOf("R 1500 1800"))
        assertFalse("the request stays until the batch is really gone", ts.runForget())
        assertTrue(ts.hasPendingForget())
    }
}
