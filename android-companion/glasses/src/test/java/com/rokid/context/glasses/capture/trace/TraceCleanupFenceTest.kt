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

    private fun recorder(deleteTree: (File) -> Boolean = { it.deleteRecursively() }) =
        TraceRecorder(root, clock, { wall + clock.t }, { "7" }, 1, io = { r -> if (inline) r.run() else queue.addLast(r) },
            newTraceId = { "trace-%04d-test".format(++ids) }, flushBytes = 2_000, deleteTree = deleteTree)
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
        val r = recorder { f -> seen += pending.exists(); f.deleteRecursively() }
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
}
