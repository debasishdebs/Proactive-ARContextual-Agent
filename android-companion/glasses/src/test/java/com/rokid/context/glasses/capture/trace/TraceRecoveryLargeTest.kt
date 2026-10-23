package com.rokid.context.glasses.capture.trace

import com.rokid.context.capture.trace.ChunkCodec
import com.rokid.context.capture.trace.CloseReason
import com.rokid.context.capture.trace.Manifest
import com.rokid.context.capture.trace.ManifestChunk
import com.rokid.context.capture.trace.TraceEvent
import com.rokid.context.capture.trace.TraceFiles
import com.rokid.context.capture.trace.TraceStatus
import com.rokid.context.core.model.TraceChunk
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The ANR of 2026-10-23: a killed trace of ~400 chunks / ~450k events was sealed by fully decoding every event, inside the recorder's constructor, which ran
 * under the `lazy` lock on the main thread. Recovery now runs on the recorder's own io thread and starts no trace before it is done.
 */
class TraceRecoveryLargeTest {
    private val root = TraceTestKit.tempDir()
    private val executors = ArrayList<java.util.concurrent.ExecutorService>()
    @AfterTest fun cleanup() { executors.forEach { it.shutdownNow() }; root.deleteRecursively() }

    private val traceId = "trace-large-0001"
    private val chunks = 400
    private val perChunk = 1_130

    /** A killed trace on disk: start record plus [chunks] chunk files, written exactly as the recorder writes them (count still the placeholder 0). */
    private fun killedLargeTrace(): Long {
        val tmp = File(root, "imu_trace_tmp/$traceId"); tmp.mkdirs()
        File(root, "trace_epoch").writeText("inst-1\n1")
        val t0 = 100_000L
        TraceStorage(root).writeStart(traceId, Manifest(
            traceId = traceId, installId = "inst-1", deletionCounter = 1, revision = 1, parentTraceId = null, bootId = Manifest.bootIdOf(7),
            startElapsedMs = t0, endElapsedMs = t0, startWallMs = 1_700_000_000_000, endWallMs = 1_700_000_000_000,
            startPhoneWallMs = null, endPhoneWallMs = null, startPhoneAgeMs = null, endPhoneAgeMs = null, phoneOffsetMs = null, offsetUncertaintyMs = null,
            chunkCount = 1, eventCount = 1, rawBytes = 1, traceSha256 = "0".repeat(64), recorded = TraceStatus.PARTIAL, closeReason = CloseReason.STOP, snapshot = TraceTestKit.snapshot(),
        ))
        var t = t0; var n = 0L
        for (seq in 0 until chunks) {
            val ev = ArrayList<TraceEvent>(perChunk)
            repeat(perChunk) { i ->
                t += 2
                val v = (Math.sin((n + i) / 37.0)).toFloat()
                ev += if (i % 2 == 0) TraceEvent.Gyro(t, t - 3, v, -v, v * 0.5f) else TraceEvent.Accel(t, t - 3, v * 9.8f, 0.1f, -v)
            }
            n += perChunk
            val e = ChunkCodec.encode(traceId, seq, ev)
            File(tmp, TraceFiles.chunkFile(seq)).writeText(
                TraceFiles.encodeFile(TraceChunk(traceId = traceId, kind = ChunkCodec.KIND, seq = seq, count = 0, installId = "inst-1", deletionCounter = 1, sha256 = e.sha256, b64 = e.b64)),
                Charsets.UTF_8,
            )
        }
        return n
    }

    /** An executor whose tasks all wait for [release]: the recovery runnable is held, so the test controls exactly when it runs. */
    private val release = CountDownLatch(1)
    private fun gatedIo() = Executors.newSingleThreadExecutor { r -> Thread(r, "imu-trace-test").also { it.isDaemon = true } }.also { executors += it }

    private fun construct(io: java.util.concurrent.ExecutorService, gate: CountDownLatch? = null) =
        TraceRecorder(root, { System.nanoTime() / 1_000_000 }, { 1_700_000_000_000 }, { "7" }, 1, io = { r -> io.execute { gate?.await(); r.run() } })

    private fun sealedDone() = File(root, "traces").listFiles()?.size == 1 && File(root, "imu_trace_tmp").listFiles().isNullOrEmpty()

    @Test fun aLargeKilledTraceIsSealedOffTheCallingThreadByAScanNotADecode() {
        val total = killedLargeTrace()
        val io = gatedIo()
        val callerThread = Thread.currentThread()

        // construction: the recovery is held behind the latch, so nothing of it can have run on this thread or at all
        val c0 = System.nanoTime()
        val r = construct(io, release)
        println("LARGE-FIXTURE constructionMs=${(System.nanoTime() - c0) / 1_000_000} events=$total chunks=$chunks")
        assertTrue(File(root, "imu_trace_tmp/$traceId").isDirectory && File(root, "traces").listFiles().isNullOrEmpty(), "construction recovered nothing")
        r.onControl(TraceTestKit.collect(counter = 1)) // same epoch as the persisted one: no wipe
        assertFalse(r.wantsStart, "no trace may start before the recovery finished")
        assertFalse(r.start(TraceTestKit.start()), "start refuses before the recovery finished")

        val decodes0 = com.rokid.context.capture.trace.EventCodec.decodeCount.get()
        val r0 = System.nanoTime()
        release.countDown()
        val done = CountDownLatch(1); io.execute { done.countDown() } // runs after the recovery on the same single thread
        assertTrue(done.await(180, TimeUnit.SECONDS), "recovery did not finish")
        println("LARGE-FIXTURE recoveryMs=${(System.nanoTime() - r0) / 1_000_000}")
        assertTrue(sealedDone())
        assertEquals(decodes0, com.rokid.context.capture.trace.EventCodec.decodeCount.get(), "sealing scans the chunks: it must not strict-decode a single event (452,000 would be the full decode)")
        assertTrue(Thread.currentThread() === callerThread)

        val t = TraceTestKit.readAll(root).single()
        assertEquals(chunks, t.manifest.chunkCount)
        assertEquals(total, t.manifest.eventCount)
        assertEquals(TraceStatus.PARTIAL, t.manifest.recorded)
        assertEquals(CloseReason.STOP, t.manifest.closeReason)
        assertEquals(TraceStatus.PARTIAL, t.verification.status)
        assertEquals(t.events.last().tMs, t.manifest.endElapsedMs)
        assertTrue(r.wantsStart, "recovery finished: the recorder may start traces")

        // a second construction finds nothing to recover: no chunk is read or decoded again
        val before = File(root, "traces").listFiles()!!.map { it.name to it.lastModified() }
        val decodes1 = com.rokid.context.capture.trace.EventCodec.decodeCount.get()
        val io2 = gatedIo()
        construct(io2)
        val l2 = CountDownLatch(1); io2.execute { l2.countDown() }
        assertTrue(l2.await(60, TimeUnit.SECONDS))
        assertEquals(decodes1, com.rokid.context.capture.trace.EventCodec.decodeCount.get())
        assertEquals(before, File(root, "traces").listFiles()!!.map { it.name to it.lastModified() })
    }
}
