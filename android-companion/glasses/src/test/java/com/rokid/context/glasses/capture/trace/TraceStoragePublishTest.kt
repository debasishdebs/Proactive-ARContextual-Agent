package com.rokid.context.glasses.capture.trace

import com.rokid.context.capture.trace.ChunkCodec
import com.rokid.context.capture.trace.CloseReason
import com.rokid.context.capture.trace.Manifest
import com.rokid.context.capture.trace.TraceEvent
import com.rokid.context.capture.trace.TraceFiles
import com.rokid.context.capture.trace.TraceStatus
import com.rokid.context.core.model.TraceChunk
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A second process death during `publish` must not lose the trace, and recovery keeps the valid prefix when a later chunk is bad (Codex review 1, findings 1 and 2). */
class TraceStoragePublishTest {
    private val root = TraceTestKit.tempDir()
    private val id = "trace-pub-0001"
    @AfterTest fun cleanup() { root.deleteRecursively() }

    private class Died(val at: String) : RuntimeException(at)

    private fun manifest(chunks: Int, events: Long, raw: Long, shas: List<String>, status: TraceStatus = TraceStatus.COMPLETE, reason: String = CloseReason.STOP) = Manifest(
        traceId = id, installId = "inst-1", deletionCounter = 1, revision = 1, parentTraceId = null, bootId = Manifest.bootIdOf(7),
        startElapsedMs = 1_000, endElapsedMs = 1_000 + events * 2, startWallMs = 1_700_000_000_000, endWallMs = 1_700_000_000_000 + events * 2,
        startPhoneWallMs = null, endPhoneWallMs = null, startPhoneAgeMs = null, endPhoneAgeMs = null, phoneOffsetMs = null, offsetUncertaintyMs = null,
        chunkCount = chunks, eventCount = events, rawBytes = raw, traceSha256 = Manifest.traceSha(shas), recorded = status, closeReason = reason, snapshot = TraceTestKit.snapshot(),
    )

    /** A closed-looking trace in tmp written like the recorder does: start record, [n] chunks. Returns the manifest the recorder's finish would publish. */
    private fun written(storage: TraceStorage, n: Int, badChunk: Int? = null): Manifest {
        File(root, "trace_epoch").writeText("inst-1\n1")
        storage.writeStart(id, manifest(1, 1, 1, listOf("0".repeat(64)), TraceStatus.PARTIAL))
        var t = 1_000L; val shas = ArrayList<String>(); var ev = 0L; var raw = 0L
        for (seq in 0 until n) {
            val evs = (0 until 50).map { t += 2; TraceEvent.Gyro(t, t - 1, 0.1f, 0.2f, 0.3f) }
            val e = ChunkCodec.encode(id, seq, evs)
            if (seq == badChunk) {
                // a checksum-valid chunk whose payload has an event line strict decode refuses
                val text = "[\"PCT1\",\"$id\",$seq,2]\n[\"rs\",${t + 1},true]\n[\"rs\",${t + 2},garbage]\n"
                val raw = text.toByteArray(); val sha = java.security.MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
                val bo = java.io.ByteArrayOutputStream(); java.util.zip.GZIPOutputStream(bo).use { it.write(raw) }; val b64 = java.util.Base64.getEncoder().encodeToString(bo.toByteArray())
                storage.writeChunk(id, seq, TraceChunk(traceId = id, kind = ChunkCodec.KIND, seq = seq, count = 0, installId = "inst-1", deletionCounter = 1, sha256 = sha, b64 = b64))
                continue
            }
            storage.writeChunk(id, seq, TraceChunk(traceId = id, kind = ChunkCodec.KIND, seq = seq, count = 0, installId = "inst-1", deletionCounter = 1, sha256 = e.sha256, b64 = e.b64))
            shas += e.sha256; ev += e.eventCount; raw += e.rawBytes
        }
        return manifest(n, ev, raw, shas)
    }

    private fun assertRecovered(chunks: Int) {
        TraceStorage(root).recover()
        assertTrue(File(root, "imu_trace_tmp").listFiles().isNullOrEmpty(), "tmp is empty")
        val t = TraceTestKit.readAll(root).single()
        assertEquals(chunks, t.manifest.chunkCount)
        assertEquals(chunks * 50L, t.manifest.eventCount)
    }

    @Test fun aDeathWhileStampingStillSealsTheTraceFromItsStartRecord() {
        val storage = TraceStorage(root, checkpoint = { if (it == "stamp:1") throw Died(it) })
        val m = written(storage, 4)
        try { storage.publish(id, m); error("should have died") } catch (e: Died) { /* the process is gone */ }
        assertTrue(File(root, "imu_trace_tmp/$id/start.json").isFile, "the start record is kept while stamping")
        assertRecovered(4)
    }

    @Test fun aDeathAfterTheManifestIsWrittenIsFinishedFromTheManifestOnlyDirectory() {
        val storage = TraceStorage(root, checkpoint = { if (it == "beforeRename") throw Died(it) })
        val m = written(storage, 4)
        try { storage.publish(id, m); error("should have died") } catch (e: Died) { /* gone just before the rename */ }
        assertFalse(File(root, "imu_trace_tmp/$id/start.json").exists(), "removed just before the rename")
        assertTrue(File(root, "imu_trace_tmp/$id/manifest.json").isFile)
        TraceStorage(root).recover()
        val t = TraceTestKit.readAll(root).single()
        assertEquals(TraceStatus.COMPLETE, t.manifest.recorded, "the recorder's own manifest is published as it was")
        assertEquals(4, t.manifest.chunkCount)
        assertTrue(File(root, "imu_trace_tmp").listFiles().isNullOrEmpty())
    }

    @Test fun aDeathRightAfterTheManifestRenameKeepsTheStartRecordAndIsStillFinished() {
        val storage = TraceStorage(root, checkpoint = { if (it == "manifest") throw Died(it) })
        val m = written(storage, 3)
        try { storage.publish(id, m); error("should have died") } catch (e: Died) { /* gone */ }
        assertTrue(File(root, "imu_trace_tmp/$id/start.json").isFile && File(root, "imu_trace_tmp/$id/manifest.json").isFile)
        TraceStorage(root).recover()
        assertEquals(3, TraceTestKit.readAll(root).single().manifest.chunkCount)
    }

    @Test fun aValidPrefixIsKeptWhenALaterChecksumValidChunkHasABadEvent() {
        val storage = TraceStorage(root)
        written(storage, 4, badChunk = 2)
        storage.recover()
        val t = TraceTestKit.readAll(root).single()
        assertEquals(2, t.manifest.chunkCount, "the two good chunks before the bad one")
        assertEquals(TraceStatus.PARTIAL, t.manifest.recorded)
        assertEquals(100L, t.manifest.eventCount)
    }

    // ---- retryable I/O errors keep a finishable trace; rejected directories go (Codex review 2, finding 1) ----

    private var failRename = true
    private fun flaky() = TraceStorage(root, renameDir = { a, b -> if (failRename) false else a.renameTo(b) })

    @Test fun aFailedFinalRenameKeepsTheManifestOnlyDirectoryAndTheRetryPublishesIt() {
        val storage = flaky()
        val m = written(storage, 4)
        try { storage.publish(id, m); error("the rename must fail") } catch (e: java.io.IOException) { /* retryable */ }
        flaky().recover() // first recovery: the rename still fails
        assertTrue(File(root, "imu_trace_tmp/$id/manifest.json").isFile, "kept for the next recovery")
        assertTrue(File(root, "traces").listFiles().isNullOrEmpty())
        failRename = false
        flaky().recover(); flaky().recover() // the retry publishes; a second recovery has nothing left to do
        assertEquals(1, TraceTestKit.readAll(root).size)
        assertEquals(4, TraceTestKit.readAll(root).single().manifest.chunkCount)
        assertTrue(File(root, "imu_trace_tmp").listFiles().isNullOrEmpty())
    }

    @Test fun aFailedRenameWhileSealingFromAStartRecordIsRetriedToo() {
        val storage = TraceStorage(root, checkpoint = { if (it == "stamp:0") throw Died(it) })
        val m = written(storage, 3)
        try { storage.publish(id, m) } catch (e: Died) { /* gone while stamping */ }
        flaky().recover() // seals, the final rename fails: the directory (now with a manifest) stays
        assertTrue(File(root, "imu_trace_tmp/$id").isDirectory)
        failRename = false
        flaky().recover()
        assertEquals(3, TraceTestKit.readAll(root).single().manifest.chunkCount)
    }

    private fun manifestOnly(chunks: Int = 3): File {
        val storage = TraceStorage(root, checkpoint = { if (it == "beforeRename") throw Died(it) })
        val m = written(storage, chunks)
        try { storage.publish(id, m) } catch (e: Died) { /* gone */ }
        return File(root, "imu_trace_tmp/$id")
    }

    @Test fun aManifestOnlyDirectoryOfAnotherEpochIsDeletedNotPublished() {
        manifestOnly(); File(root, "trace_epoch").writeText("inst-1\n2")
        TraceStorage(root).recover()
        assertEquals(0, TraceTestKit.readAll(root).size); assertTrue(File(root, "imu_trace_tmp").listFiles().isNullOrEmpty())
    }

    @Test fun aManifestOnlyDirectoryWhoseNameIsNotItsTraceIdIsDeletedNotPublished() {
        val d = manifestOnly(); d.renameTo(File(root, "imu_trace_tmp/trace-other-0002"))
        TraceStorage(root).recover()
        assertEquals(0, TraceTestKit.readAll(root).size); assertTrue(File(root, "imu_trace_tmp").listFiles().isNullOrEmpty())
    }

    @Test fun aManifestOnlyDirectoryMissingAChunkIsDeletedNotPublished() {
        val d = manifestOnly(); File(d, "chunk-0001.json").delete()
        TraceStorage(root).recover()
        assertEquals(0, TraceTestKit.readAll(root).size); assertTrue(File(root, "imu_trace_tmp").listFiles().isNullOrEmpty())
    }
}
