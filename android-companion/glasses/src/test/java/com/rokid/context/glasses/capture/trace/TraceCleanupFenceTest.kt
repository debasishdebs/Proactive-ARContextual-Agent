package com.rokid.context.glasses.capture.trace

import com.rokid.context.capture.trace.CloseReason
import com.rokid.context.core.model.MotionState
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * LGC8-min (plan 3.9, finding 1): the cleanup the glasses ALREADY RECEIVED is persisted before it is acted on (`trace_cleanup_pending`), covers `imu_trace_tmp`,
 * is replayed at process start BEFORE recovery, and is kept after any failed delete until it succeeded. The file is the single source of truth for [TraceRecorder.cleanupPending].
 */
class TraceCleanupFenceTest {
    private val root = TraceTestKit.tempDir()
    private val aside = TraceTestKit.tempDir()
    private val clock = ManualClock()
    private val queue = ArrayDeque<Runnable>()
    private var inline = true
    private var ids = 0
    private val pending get() = File(root, "trace_cleanup_pending")
    private val wall = 1_700_000_000_000
    @AfterTest fun cleanup() { root.deleteRecursively(); aside.deleteRecursively() }

    private fun recorder(deleteTree: (File) -> Boolean = { it.deleteRecursively() }, writeRecord: (File, String) -> Boolean = com.rokid.context.glasses.log.DiagStateFile::atomicWrite, diag: Boolean = false) =
        TraceRecorder(root, clock, { wall + clock.t }, { "7" }, 1, io = { r -> if (inline) r.run() else queue.addLast(r) },
            newTraceId = { "trace-%04d-test".format(++ids) }, flushBytes = 2_000, deleteTree = deleteTree, writeRecord = writeRecord, diagEnabled = { diag })
    private fun drain() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    private fun TraceRecorder.begin() = start(TraceTestKit.start())
    private fun TraceRecorder.gyro(n: Int) = repeat(n) { clock.t += 20; onGyro(0.1f, -0.2f, 0.3f, clock.t - 5, MotionState.STILL) }
    private fun failing(vararg under: String): (File) -> Boolean = { f -> if (under.any { f.path.contains(it) }) false else f.deleteRecursively() }

    /** A process killed mid-trace (the recorder object is abandoned): the trace sits in `imu_trace_tmp` with a start record and at least one chunk. */
    private fun killedMidTrace(counter: Long = 1): String {
        val r = recorder(); r.onControl(TraceTestKit.collect(counter = counter)); r.begin()
        var guard = 0
        while (File(root, "imu_trace_tmp").listFiles()?.singleOrNull()?.list()?.any { it.startsWith("chunk-") && it.endsWith(".json") } != true && guard++ < 100_000) r.gyro(1)
        assertTrue(r.recording)
        return File(root, "imu_trace_tmp").listFiles()!!.single().name
    }

    /** Puts a killed trace back into tmp AFTER a recorder was built (a directory its own finish left behind: an I/O error keeps it for recovery). */
    private fun <T> withTmpTraceAddedLater(id: String, build: () -> T): T {
        val moved = File(aside, id); assertTrue(File(root, "imu_trace_tmp/$id").renameTo(moved))
        val r = build()
        assertTrue(moved.renameTo(File(root, "imu_trace_tmp/$id")))
        return r
    }

    private fun ctl(vararg tomb: String, from: Long? = null, to: Long? = null, install: String = "inst-1", counter: Long = 1) =
        TraceTestKit.collect(install = install, counter = counter).copy(atMs = wall + clock.t, tombstones = tomb.toList(), forgetFromMs = from, forgetToMs = to)

    // ---- RED 1 ----
    @Test fun ForgetThenFailedTempDirDeleteThenRestartReplaysBeforeRecovery() {
        val id = killedMidTrace()
        val r1 = withTmpTraceAddedLater(id) { recorder(failing("imu_trace_tmp")) }
        r1.onControl(ctl(id))
        assertTrue(File(root, "imu_trace_tmp/$id").exists(), "the temp deletion failed: the killed trace survives")
        assertTrue(pending.exists() && r1.cleanupPending(), "a failed delete keeps the pending record (the request is not cleared)")
        // the process dies; the next start must replay the cleanup BEFORE recover() could seal that trace
        val r2 = recorder()
        assertFalse(File(root, "imu_trace_tmp/$id").exists())
        assertEquals(0, TraceTestKit.readAll(root).size, "recover() never sealed the forgotten trace")
        assertFalse(pending.exists() || r2.cleanupPending(), "success clears the record")
    }

    // ---- RED 2 ----
    @Test fun FailedEpochWipeThenRestartRetried() {
        val r0 = recorder(); r0.onControl(TraceTestKit.collect(counter = 1)); r0.begin(); r0.gyro(5); r0.close(CloseReason.STOP)
        assertEquals(1, TraceTestKit.readAll(root).size)
        val r1 = recorder(failing("traces"))
        r1.onControl(TraceTestKit.collect(install = "inst-b", counter = 7)) // a new epoch: the wipe fails
        assertFalse(r1.epochSaved); assertTrue(pending.exists(), "the received cleanup and its target epoch are on disk")
        assertEquals(1, TraceTestKit.readAll(root).size)
        // restart, no control at all: the wipe is retried from the persisted record and the target epoch saved
        val r2 = recorder()
        assertEquals(0, TraceTestKit.readAll(root).size)
        assertEquals("inst-b\n7", File(root, "trace_epoch").readText())
        assertTrue(r2.epochSaved && !r2.cleanupPending())
    }

    // ---- RED 3 ----
    @Test fun QueuedWriteAfterForgetDropped() {
        val r = recorder(); r.onControl(TraceTestKit.collect(counter = 1))
        inline = false
        assertTrue(r.begin()); r.gyro(200) // buffers are handed to the writer: queued, not run
        assertTrue(queue.isNotEmpty())
        r.onControl(ctl(from = wall + clock.t - 60_000, to = wall + clock.t + 60_000)) // the forget hits the running trace
        assertFalse(r.recording)
        drain()
        assertTrue(File(root, "imu_trace_tmp").listFiles().isNullOrEmpty(), "no queued chunk recreated the forgotten trace")
        assertEquals(0, TraceTestKit.readAll(root).size)
        assertFalse(pending.exists())
    }

    // ---- RED 4 ----
    @Test fun PullRefusedWhilePendingCleanup() {
        val r = recorder(); r.onControl(TraceTestKit.collect(counter = 1)); r.begin(); r.gyro(5); r.close(CloseReason.STOP)
        val id = TraceTestKit.readAll(root).single().manifest.traceId
        assertEquals(listOf(id), r.exportableTraceIds()); assertFalse(r.cleanupPending())
        pending.writeText("cleanup1\nR 1 2\n") // a pending cleanup exists (the file is the single source of truth)
        assertTrue(r.cleanupPending())
        assertEquals(emptyList(), r.exportableTraceIds(), "ALL traces are refused while a cleanup is pending")
        pending.delete()
        assertEquals(listOf(id), r.exportableTraceIds())
    }

    // ---- the other behaviours ----
    @Test fun PendingRecordIsOnDiskBeforeAnyDeleteRuns() {
        val r0 = recorder(); r0.onControl(TraceTestKit.collect(counter = 1)); r0.begin(); r0.gyro(5); r0.close(CloseReason.STOP)
        val seen = ArrayList<Boolean>()
        val r = recorder(deleteTree = { f -> seen += pending.exists(); f.deleteRecursively() })
        seen.clear()
        r.onControl(TraceTestKit.collect(install = "inst-c", counter = 3))
        assertTrue(seen.isNotEmpty() && seen.all { it }, "every delete saw the persisted record: $seen")
    }

    @Test fun ARangeForgetCoversAKilledTraceInTmpButNotTheLiveOne() {
        val id = killedMidTrace()
        val r = withTmpTraceAddedLater(id) { recorder() }
        r.onControl(TraceTestKit.collect(counter = 1)); r.begin(); r.gyro(40) // a live trace: its tmp directory must survive the forget below
        val live = File(root, "imu_trace_tmp").list()!!.single { it != id }
        // a range in the FUTURE: the live trace does not overlap it (it is not aborted), yet by its start record alone a tmp directory would match: the live job must still be spared
        r.onControl(ctl(from = wall + clock.t + 3_600_000, to = wall + clock.t + 7_200_000))
        assertTrue(File(root, "imu_trace_tmp/$live").exists(), "the live job's directory is never touched by a forget")
        r.onControl(ctl(id))
        assertFalse(File(root, "imu_trace_tmp/$id").exists())
        assertTrue(File(root, "imu_trace_tmp/$live").exists())
        assertFalse(pending.exists())
    }

    @Test fun ReplayThatFailsAgainNeverSealsTheKilledTrace() {
        val id = killedMidTrace()
        val r1 = withTmpTraceAddedLater(id) { recorder(failing("imu_trace_tmp")) }
        r1.onControl(ctl(id))
        val r2 = recorder(failing("imu_trace_tmp")) // the restart's delete fails too
        assertEquals(0, TraceTestKit.readAll(root).size, "recovery must not seal a trace the pending cleanup targets")
        assertTrue(File(root, "imu_trace_tmp/$id").exists() && r2.cleanupPending())
        recorder() // a later start succeeds
        assertFalse(File(root, "imu_trace_tmp/$id").exists()); assertFalse(pending.exists()); assertEquals(0, TraceTestKit.readAll(root).size)
    }

    @Test fun APendingRecordThatCannotBeReadIsTreatedAsWipeEverything() {
        val r0 = recorder(); r0.onControl(TraceTestKit.collect(counter = 1)); r0.begin(); r0.gyro(5); r0.close(CloseReason.STOP)
        pending.writeText("garbage \u0000 not a record")
        val r = recorder()
        assertEquals(0, TraceTestKit.readAll(root).size)
        assertFalse(r.cleanupPending())
    }

    @Test fun AForgetStillClearsTheTransferMarker() {
        val r = recorder(); r.onControl(TraceTestKit.collect(counter = 1)); r.begin(); r.gyro(5); r.close(CloseReason.STOP)
        val id = TraceTestKit.readAll(root).single().manifest.traceId
        File(root, "transferred").mkdirs(); File(root, "transferred/$id").writeBytes(ByteArray(0))
        r.onControl(ctl(id))
        assertEquals(0, TraceTestKit.readAll(root).size); assertFalse(File(root, "transferred/$id").exists())
    }

    @Test fun AMarkerThatCouldNotBeRemovedIsRetriedAndNeverOutlivesItsTrace() {
        val r0 = recorder(); r0.onControl(TraceTestKit.collect(counter = 1)); r0.begin(); r0.gyro(5); r0.close(CloseReason.STOP)
        val id = TraceTestKit.readAll(root).single().manifest.traceId
        File(root, "transferred").mkdirs(); File(root, "transferred/$id").writeBytes(ByteArray(0))
        var failMarker = true
        val storage = TraceStorage(root, deleteMarker = { f -> if (failMarker) false else f.delete() })
        storage.addForget(listOf("T $id"))
        assertFalse(storage.runForget(), "the marker could not be removed: the request stays")
        assertTrue(File(root, "trace_forget").exists())
        failMarker = false
        assertTrue(storage.runForget())
        assertEquals(0, TraceTestKit.readAll(root).size); assertFalse(File(root, "transferred/$id").exists())
        // the pair the other way round: the trace is already gone (an earlier attempt) and only the marker is left
        File(root, "transferred/$id").writeBytes(ByteArray(0)); storage.addForget(listOf("T $id"))
        assertTrue(storage.runForget()); assertFalse(File(root, "transferred/$id").exists(), "an orphan marker is pruned by the forget itself")
    }

    // ---- Codex review of LGC8-min ----

    /** (1) The record could not be saved: nothing is deleted, recording is blocked with a named word, and a retry saves the record before it proceeds. */
    @Test fun PersistFailureDeletesNothingBlocksRecordingAndIsRetried() {
        val r0 = recorder(); r0.onControl(TraceTestKit.collect(counter = 1)); r0.begin(); r0.gyro(5); r0.close(CloseReason.STOP)
        var failPending = true
        val r = recorder(writeRecord = { f, t -> if (failPending && f.name == "trace_cleanup_pending") false else com.rokid.context.glasses.log.DiagStateFile.atomicWrite(f, t) })
        r.onControl(TraceTestKit.collect(install = "inst-b", counter = 4))
        assertEquals(1, TraceTestKit.readAll(root).size, "nothing was deleted before the record was durable")
        assertEquals("inst-1\n1", File(root, "trace_epoch").readText(), "the epoch file is untouched too")
        assertEquals(TraceStartBlocker.CLEANUP_PERSIST_FAILED, r.startBlocker()); assertFalse(r.wantsStart)
        assertTrue("cleanup_persist_failed" in TraceStateReporter.VOCABULARY)
        failPending = false; clock.t += 60_000
        r.wantsStart // the start check queues the retry (inline here)
        assertTrue(pending.exists().not() && TraceTestKit.readAll(root).isEmpty(), "the retry saved the record, then ran the cleanup to the end")
        assertEquals("inst-b\n4", File(root, "trace_epoch").readText()); assertTrue(r.epochSaved)
        assertEquals(null, r.startBlocker()?.takeIf { it == TraceStartBlocker.CLEANUP_PERSIST_FAILED })
    }

    @Test fun PersistFailureOfAForgetOnlyCleanupDeletesNothingUntilSaved() {
        val id = killedMidTrace()
        var failPending = true
        val r = withTmpTraceAddedLater(id) { recorder(writeRecord = { f, t -> if (failPending && f.name == "trace_cleanup_pending") false else com.rokid.context.glasses.log.DiagStateFile.atomicWrite(f, t) }) }
        r.onControl(ctl(id))
        assertTrue(File(root, "imu_trace_tmp/$id").exists()); assertEquals(TraceStartBlocker.CLEANUP_PERSIST_FAILED, r.startBlocker())
        failPending = false; clock.t += 60_000; r.wantsStart
        assertFalse(File(root, "imu_trace_tmp/$id").exists()); assertFalse(pending.exists())
    }

    /** (2) A request whose working-list update failed is never cleared because ANOTHER request's delete succeeded. */
    @Test fun FailedWorkingListUpdateWithASecondPendingRequestClearsNeitherRequest() {
        val r0 = recorder(); r0.onControl(TraceTestKit.collect(counter = 1)); r0.begin(); r0.gyro(5); r0.close(CloseReason.STOP)
        val b = TraceTestKit.readAll(root).single().manifest.traceId
        val a = killedMidTrace()
        var failTmp = true; var failList = false
        val tree: (File) -> Boolean = { f -> if (failTmp && f.path.contains("imu_trace_tmp")) false else f.deleteRecursively() }
        val w: (File, String) -> Boolean = { f, t -> if (failList && f.name == "trace_forget") false else com.rokid.context.glasses.log.DiagStateFile.atomicWrite(f, t) }
        val r = withTmpTraceAddedLater(a) { recorder(tree, w) }
        r.onControl(ctl(a)); assertTrue(File(root, "imu_trace_tmp/$a").exists(), "request A is pending: its delete failed")
        failTmp = false; failList = true // A would now succeed, but B cannot be written to the working list
        r.onControl(ctl(b))
        assertTrue(pending.readText().contains("T $b") && pending.readText().contains("T $a"), "both requests are still on record: ${pending.readText()}")
        assertTrue(r.cleanupPending() && r.exportableTraceIds().isEmpty())
        failList = false
        r.onControl(ctl())
        assertEquals(0, TraceTestKit.readAll(root).size, "B is gone once the list could be written"); assertFalse(File(root, "imu_trace_tmp/$a").exists()); assertFalse(pending.exists())
    }

    /** (3) An unreadable record invalidates the epoch read from disk: no recording under it, diagnostics on or not, until a valid control saves a new one. */
    @Test fun CorruptRecordThenDiagOnNeverRecordsUnderTheOldEpoch() {
        val r0 = recorder(); r0.onControl(TraceTestKit.collect(counter = 1)); r0.begin(); r0.gyro(5); r0.close(CloseReason.STOP)
        pending.writeText("\u0000garbage")
        var fail = true
        val r = recorder({ f -> if (fail) false else f.deleteRecursively() }, diag = true)
        assertFalse(r.epochSaved, "the old epoch is not durable any more"); assertFalse(r.wantsStart)
        assertEquals(TraceStartBlocker.WIPE_FAILED, r.startBlocker())
        fail = false; clock.t += 60_000; r.wantsStart
        assertEquals(0, TraceTestKit.readAll(root).size); assertFalse(File(root, "trace_epoch").exists()); assertFalse(pending.exists())
        assertFalse(r.wantsStart, "wiped, but still no epoch: diagnostics alone record nothing"); assertEquals(TraceStartBlocker.NO_EPOCH, r.startBlocker())
        r.onControl(TraceTestKit.collect(install = "inst-n", counter = 2)) // a valid control establishes a durable epoch
        assertTrue(r.epochSaved && r.wantsStart); assertEquals("inst-n\n2", File(root, "trace_epoch").readText())
    }

    /** (4) Any malformed row makes the whole record "wipe everything" instead of being dropped by the range parser. */
    @Test fun AMalformedRowMakesTheWholeRecordWipeEverything() {
        for (bad in listOf("R broken", "R 5 1", "R 1 2 3", "T ../x", "T ", "X 1", "R 1")) {
            root.deleteRecursively(); root.mkdirs()
            val r0 = recorder(); r0.onControl(TraceTestKit.collect(counter = 1)); r0.begin(); r0.gyro(5); r0.close(CloseReason.STOP)
            assertEquals(1, TraceTestKit.readAll(root).size)
            pending.writeText("cleanup1\nT trace-0099-nope\n$bad\n")
            val r = recorder()
            assertEquals(0, TraceTestKit.readAll(root).size, "'$bad': the valid trace did not survive a record it cannot fully read")
            assertFalse(r.cleanupPending(), "'$bad'")
        }
    }
}
