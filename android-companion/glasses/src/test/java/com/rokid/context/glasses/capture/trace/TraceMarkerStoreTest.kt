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

/** LGC9 (plan 3.8): the transfer marker lives OUTSIDE the frozen trace directory, so a pulled trace still verifies after it was marked. */
class TraceMarkerStoreTest {
    private val root = TraceTestKit.tempDir()
    private val storage = TraceStorage(root)
    @AfterTest fun cleanup() { root.deleteRecursively() }

    /** A trace sealed through the real recorder path: start record, chunks, `publish` (stamp, manifest, rename into `traces/`). */
    private fun seal(id: String, chunks: Int = 2): File {
        File(root, "trace_epoch").writeText("inst-1\n1")
        fun mf(n: Int, ev: Long, raw: Long, shas: List<String>) = Manifest(
            traceId = id, installId = "inst-1", deletionCounter = 1, revision = 1, parentTraceId = null, bootId = Manifest.bootIdOf(7),
            startElapsedMs = 1_000, endElapsedMs = 1_000 + ev * 2, startWallMs = 1_700_000_000_000, endWallMs = 1_700_000_000_000 + ev * 2,
            startPhoneWallMs = null, endPhoneWallMs = null, startPhoneAgeMs = null, endPhoneAgeMs = null, phoneOffsetMs = null, offsetUncertaintyMs = null,
            chunkCount = n, eventCount = ev, rawBytes = raw, traceSha256 = Manifest.traceSha(shas), recorded = TraceStatus.COMPLETE, closeReason = CloseReason.STOP, snapshot = TraceTestKit.snapshot(),
        )
        storage.writeStart(id, mf(1, 1, 1, listOf("0".repeat(64))))
        var t = 1_000L; val shas = ArrayList<String>(); var ev = 0L; var raw = 0L
        for (seq in 0 until chunks) {
            val e = ChunkCodec.encode(id, seq, (0 until 50).map { t += 2; TraceEvent.Gyro(t, t - 1, 0.1f, 0.2f, 0.3f) })
            storage.writeChunk(id, seq, TraceChunk(traceId = id, kind = ChunkCodec.KIND, seq = seq, count = 0, installId = "inst-1", deletionCounter = 1, sha256 = e.sha256, b64 = e.b64))
            shas += e.sha256; ev += e.eventCount; raw += e.rawBytes
        }
        storage.publish(id, mf(chunks, ev, raw, shas))
        return File(storage.tracesDir, id)
    }

    private fun verifies(dir: File) = TraceFiles.verify(dir.list()!!.toSet()) { File(dir, it).readText() }.isSuccess

    /** What `tools/trace-pull.sh` does: archive `files/imu/traces` only, keep id-named directories that have a manifest, copy them whole into [out]. */
    private fun pull(out: File): List<String> {
        out.deleteRecursively(); out.mkdirs()
        val pulled = ArrayList<String>()
        for (d in storage.tracesDir.listFiles()!!.filter { it.isDirectory }.sortedBy { it.name }) {
            if (!d.name.matches(Regex("[a-z0-9-]+")) || !File(d, "manifest.json").isFile) continue
            d.copyRecursively(File(out, d.name)); pulled += d.name
        }
        return pulled
    }

    @Test fun markerOutsideTraceDirKeepsVerifyGreen() {
        val dir = seal("trace-mark-0001"); val before = dir.list()!!.toSet()
        assertFalse(storage.isTransferred("trace-mark-0001"))
        assertTrue(storage.markTransferred("trace-mark-0001"))
        assertTrue(storage.isTransferred("trace-mark-0001"))
        val marker = File(root, "transferred/trace-mark-0001")
        assertTrue(marker.isFile); assertEquals(0L, marker.length())
        assertEquals(before, dir.list()!!.toSet(), "the frozen trace directory is untouched")
        assertEquals(listOf("trace-mark-0001"), storage.tracesDir.list()!!.toList(), "traces/ holds only trace directories")
        assertTrue(verifies(dir))
        assertEquals(listOf("trace-mark-0001"), storage.transferredIds())
        assertTrue(storage.markTransferred("trace-mark-0001"), "marking twice is fine")
    }

    @Test fun markerRemovedWithTrace() {
        seal("trace-mark-0001"); seal("trace-mark-0002"); seal("trace-mark-0003")
        listOf("trace-mark-0001", "trace-mark-0002", "trace-mark-0003").forEach { assertTrue(storage.markTransferred(it)) }
        storage.addForget(listOf("T trace-mark-0001")); assertTrue(storage.runForget())
        assertFalse(File(root, "transferred/trace-mark-0001").exists(), "a forgotten trace takes its marker")
        assertTrue(File(root, "transferred/trace-mark-0002").exists(), "the others keep theirs")
        assertTrue(storage.clearMarker("trace-mark-0002")); assertFalse(storage.isTransferred("trace-mark-0002"))
        assertTrue(storage.clearMarker("trace-mark-0002"), "clearing an absent marker is fine")
        assertTrue(storage.wipe())
        assertFalse(File(root, "transferred").exists(), "a wipe takes every marker")
        assertEquals(emptyList(), storage.transferredIds())
    }

    @Test fun realFormatPullImportMarkPullUnchanged() {
        seal("trace-mark-0001"); seal("trace-mark-0002")
        val out = File(root, "pulled")
        assertEquals(listOf("trace-mark-0001", "trace-mark-0002"), pull(out))
        val first = out.listFiles()!!.associate { d -> d.name to d.listFiles()!!.associate { it.name to it.readText() } }
        out.listFiles()!!.forEach { assertTrue(verifies(it), "${it.name} verifies after the first pull") }
        // import happened (the importer verifies exactly like this), the hand-over script then marks the trace
        assertTrue(storage.markTransferred("trace-mark-0001"))
        assertEquals(listOf("trace-mark-0001", "trace-mark-0002"), pull(out), "the marker does not hide or break the trace in the second pull")
        out.listFiles()!!.forEach { d ->
            assertTrue(verifies(d), "${d.name} still verifies (no bad_file_set)")
            assertEquals("manifest.json" in d.list()!!, true); assertFalse(d.list()!!.any { it.contains("transferred") })
        }
        val second = out.listFiles()!!.associate { d -> d.name to d.listFiles()!!.associate { it.name to it.readText() } }
        assertEquals(first, second, "the second pull is byte-identical, so an import is Unchanged")
        assertFalse(out.walkTopDown().any { it.name == "transferred" || it.name == "trace-mark-0001" && it.isFile }, "no marker anywhere in the pulled tree")
    }

    @Test fun markerNeedsASealedTraceAndASafeId() {
        seal("trace-mark-0001")
        assertFalse(storage.markTransferred("trace-nope-0001"), "no marker for a trace that is not sealed here")
        for (bad in listOf("", ".", "..", "../traces/trace-mark-0001", "a/b", "trace-mark-0001/", "TRACE-MARK-0001", "trace-mark-0001\n", "x".repeat(65), "short")) {
            assertFalse(storage.markTransferred(bad), "refused: '$bad'"); assertFalse(storage.isTransferred(bad)); assertFalse(storage.clearMarker(bad))
        }
        assertFalse(File(root, "transferred").exists(), "nothing was created for a refused id")
        assertEquals(listOf("trace-mark-0001"), storage.tracesDir.list()!!.toList())
    }

    @Test fun anOutsideFileIsNeverRemovedThroughAnId() {
        seal("trace-mark-0001"); assertTrue(storage.markTransferred("trace-mark-0001"))
        val outside = File(root, "keep.txt").apply { writeText("x") }
        assertFalse(storage.clearMarker("../keep.txt")); assertTrue(outside.exists())
        assertTrue(File(root, "transferred/trace-mark-0001").exists())
    }

    @Test fun listingIgnoresTempAndStrangeNames() {
        seal("trace-mark-0001"); assertTrue(storage.markTransferred("trace-mark-0001"))
        File(storage.transferredDir, "trace-mark-0002.part").writeText("")
        File(storage.transferredDir, "Weird Name").writeText("")
        File(storage.transferredDir, "dir-like-0001").mkdirs()
        assertEquals(listOf("trace-mark-0001"), storage.transferredIds())
        assertFalse(storage.isTransferred("dir-like-0001"), "a directory is not a marker")
    }

    @Test fun recoveryDropsOrphanMarkersAndTempFiles() {
        seal("trace-mark-0001"); assertTrue(storage.markTransferred("trace-mark-0001"))
        File(storage.transferredDir, "trace-gone-0001").writeText("")
        File(storage.transferredDir, "trace-mark-0001.part").writeText("")
        TraceStorage(root).recover()
        assertEquals(listOf("trace-mark-0001"), storage.transferredIds())
        assertEquals(setOf("trace-mark-0001"), storage.transferredDir.list()!!.toSet())
        assertTrue(verifies(File(storage.tracesDir, "trace-mark-0001")))
    }
}
