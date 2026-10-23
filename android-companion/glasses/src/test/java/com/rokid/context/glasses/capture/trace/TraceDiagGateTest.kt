package com.rokid.context.glasses.capture.trace

import com.rokid.context.capture.trace.CloseReason
import com.rokid.context.core.model.MotionState
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** LGC4: the trace gate is ONE predicate, `diagEnabled() || (collect && now < collectUntil)`, with every ANR-fix guarantee (recovery first, storage settled) kept. */
class TraceDiagGateTest {
    private val root = TraceTestKit.tempDir()
    private val clock = ManualClock()
    private val queue = ArrayDeque<Runnable>()
    private var inline = true
    private var diag = false
    private var ids = 0
    private var failDelete = false
    @AfterTest fun cleanup() { root.deleteRecursively() }

    private fun recorder() = TraceRecorder(
        root, clock, { 1_700_000_000_000 + clock.t }, { "7" }, 1, io = { r -> if (inline) r.run() else queue.addLast(r) },
        newTraceId = { "trace-%04d-gate".format(++ids) }, deleteTree = { f -> if (failDelete) false else f.deleteRecursively() },
        diagEnabled = { diag },
    )
    private fun drain() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    private fun TraceRecorder.begin() = start(TraceTestKit.start())
    private fun TraceRecorder.gyro(n: Int, stepMs: Long = 20) = repeat(n) { clock.t += stepMs; onGyro(0.1f, -0.2f, 0.3f, clock.t - 5, MotionState.STILL) }
    /** The control that provisions the epoch without opening any window (what a phone with Developer mode sends before/without a laptop). */
    private fun TraceRecorder.provision(install: String = "inst-1", counter: Long = 0) = onControl(TraceTestKit.collect(install = install, counter = counter).copy(collect = false))

    @Test fun diagOnStartsATraceWithoutAnyCollectWindow() {
        val r = recorder(); r.provision(); diag = true
        assertTrue(r.wantsStart)
        assertTrue(r.begin()); assertTrue(r.recording)
        r.gyro(10); r.close(CloseReason.STOP)
        assertEquals(1, TraceTestKit.readAll(root).size)
    }

    @Test fun diagOffAndNoWindowRecordsNothing() {
        val r = recorder(); r.provision()
        assertFalse(r.wantsStart); assertFalse(r.begin())
        assertEquals(TraceStartBlocker.GATE_OFF, r.startBlocker())
    }

    @Test fun aLaptopCollectWindowStillWorksWithDiagOffAndWinsForItsOwnWindow() {
        val r = recorder(); r.onControl(TraceTestKit.collect(until = 1_000 + 5_000, atMs = 1_000))
        assertTrue(r.wantsStart); assertTrue(r.begin()); r.gyro(100, 20); assertTrue(r.recording)
        r.gyro(200, 20) // past the 5 s window, diag off
        assertFalse(r.recording, "the window ended and diagnostics are off: the trace closes")
        assertEquals("stop", TraceTestKit.readAll(root).single().manifest.closeReason)
        assertFalse(r.wantsStart)
    }

    @Test fun aCollectWindowExpiryDoesNotCloseADiagTrace() {
        val r = recorder(); r.onControl(TraceTestKit.collect(until = 1_000 + 5_000, atMs = 1_000)); diag = true
        r.begin(); r.gyro(300, 20) // 6 s: past the window
        assertTrue(r.recording, "diagnostics are on: the window ending closes nothing")
        r.onControl(TraceTestKit.collect(atMs = 9_000).copy(collect = false)) // the laptop stops collecting
        assertTrue(r.recording, "collect off with diagnostics on keeps the trace")
    }

    @Test fun theTraceClosesWhenDiagGoesOffAndNoWindowIsActive() {
        val r = recorder(); r.provision(); diag = true
        r.begin(); r.gyro(10)
        diag = false
        r.onDiagChanged()
        assertFalse(r.recording)
        val t = TraceTestKit.readAll(root).single()
        assertEquals("stop", t.manifest.closeReason)
        assertFalse(r.wantsStart)
    }

    @Test fun theTraceDoesNotCloseWhenDiagGoesOffInsideAnActiveCollectWindow() {
        val r = recorder(); r.onControl(TraceTestKit.collect()); diag = true
        r.begin(); r.gyro(10)
        diag = false; r.onDiagChanged()
        assertTrue(r.recording, "the laptop window is still open: it keeps recording")
        r.gyro(10); assertTrue(r.recording)
    }

    @Test fun aTraceStillClosesOnTheNextSampleWhenDiagWentOffWithoutANotification() {
        val r = recorder(); r.provision(); diag = true
        r.begin(); r.gyro(10)
        diag = false
        r.gyro(1)
        assertFalse(r.recording, "the append path checks the same predicate")
    }

    @Test fun diagOnWithoutAnEpochNeverStarts() {
        diag = true
        val r = recorder()
        assertFalse(r.wantsStart); assertFalse(r.begin())
        assertEquals(TraceStartBlocker.NO_EPOCH, r.startBlocker())
        assertFalse(r.epochSaved)
    }

    @Test fun diagOnWithRecoveryPendingWaitsForRecovery() {
        inline = false
        val r = recorder(); r.provision("inst-1", 0); diag = true
        assertFalse(r.wantsStart); assertFalse(r.begin())
        assertEquals(TraceStartBlocker.RECOVERING, r.startBlocker())
        drain()
        assertTrue(r.wantsStart); assertNull(r.startBlocker())
    }

    @Test fun diagOnWithAWipePendingOrFailedDoesNotStartAndIsNotEpochSaved() {
        inline = false
        val r = recorder(); drain(); diag = true
        r.onControl(TraceTestKit.collect(install = "inst-n", counter = 3).copy(collect = false))
        assertFalse(r.epochSaved, "the epoch is adopted but not durable until the wipe ran")
        assertFalse(r.wantsStart); assertEquals(TraceStartBlocker.WIPE_PENDING, r.startBlocker())
        failDelete = true
        File(root, "traces/keep").mkdirs()
        drain()
        assertFalse(r.epochSaved); assertEquals(TraceStartBlocker.WIPE_FAILED, r.startBlocker())
        assertFalse(r.wantsStart, "the check queues a retry")
        failDelete = false
        drain() // the retry succeeds
        assertTrue(r.epochSaved); assertTrue(r.wantsStart); assertNull(r.startBlocker())
    }

    @Test fun epochSavedIsFalseUntilTheEpochIsOnDiskAndSurvivesARestart() {
        val r = recorder()
        assertFalse(r.epochSaved)
        r.provision("inst-1", 4)
        assertTrue(r.epochSaved); assertEquals("inst-1\n4", File(root, "trace_epoch").readText())
        assertTrue(recorder().epochSaved, "a restart reads the persisted epoch")
    }

    @Test fun anEpochThatCouldNotBeWrittenIsNotSavedAndIsRetriedByTheStartChecks() {
        File(root, "trace_epoch.part").mkdirs() // the temp file cannot be created: the write throws
        diag = true
        val r = recorder(); r.provision("inst-1", 1)
        assertFalse(r.epochSaved, "the write failed: not durable")
        assertFalse(r.wantsStart, "and the failure blocks starts like a failed wipe")
        File(root, "trace_epoch.part").deleteRecursively()
        clock.t += 120_000
        r.wantsStart // queues the retry (inline: runs at once)
        assertTrue(r.epochSaved); assertTrue(r.wantsStart)
    }

    @Test fun theBlockerPriorityIsRecoveryThenWipeThenGateThenEpochThenDisk() {
        inline = false
        val r = recorder()
        assertEquals(TraceStartBlocker.RECOVERING, r.startBlocker())
        drain()
        assertEquals(TraceStartBlocker.GATE_OFF, r.startBlocker(), "diag off and no window comes before the missing epoch")
        diag = true
        assertEquals(TraceStartBlocker.NO_EPOCH, r.startBlocker())
        r.provision(); drain()
        assertNull(r.startBlocker())
        r.begin()
        assertNull(r.startBlocker(), "already recording is not a fault")
    }
}
