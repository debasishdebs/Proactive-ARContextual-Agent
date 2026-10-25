package com.rokid.context.glasses.capture.trace

import com.rokid.context.capture.trace.CloseReason
import com.rokid.context.capture.trace.TraceStatus
import com.rokid.context.core.model.MotionState
import com.rokid.context.glasses.log.DiagFlagState
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * LGC5 (plan 3.5, 3.6), recorder level: the ONE trace gate `diagEnabled() || collectWindow`, a restart that finds a killed trace, and the state/boot lines that say why
 * nothing records. The writer runs inline. A "restart" is a new recorder over the same directory (the epoch file is what survives, never a control message).
 */
class TraceGateLgc5Test {
    private val root = TraceTestKit.tempDir()
    private val clock = ManualClock()
    private var diag = false
    private var ids = 0
    private var failDelete = false
    private val recovered = ArrayList<RecoveryInfo>()
    @AfterTest fun cleanup() { root.deleteRecursively() }

    private fun recorder(flush: Int = TraceRecorder.FLUSH_BYTES) = TraceRecorder(
        root, clock, { 1_700_000_000_000 + clock.t }, { "7" }, 1, io = { it.run() }, newTraceId = { "trace-%04d-l5".format(++ids) }, flushBytes = flush,
        deleteTree = { f -> if (failDelete) false else f.deleteRecursively() }, diagEnabled = { diag }, onRecovered = { recovered += it },
    )
    private fun persistedEpoch(counter: Long = 0) { root.mkdirs(); File(root, "trace_epoch").writeText("inst-1\n$counter") }
    private fun TraceRecorder.begin() = start(TraceTestKit.start())
    private fun TraceRecorder.gyro(n: Int, stepMs: Long = 20) = repeat(n) { clock.t += stepMs; onGyro(0.1f, -0.2f, 0.3f, clock.t - 5, MotionState.STILL) }

    // ---- the gate ----

    @Test fun DiagOnStartsTraceWithoutControl() {
        persistedEpoch() // the state a restarted glasses is in: an epoch on disk, NO control message since the process began
        diag = true
        val r = recorder()
        assertTrue(r.epochSaved); assertTrue(r.wantsStart); assertNull(r.startBlocker())
        assertTrue(r.begin()); r.gyro(10); r.close(CloseReason.STOP)
        assertEquals(1, TraceTestKit.readAll(root).size)
    }

    @Test fun DiagOffNoTraceUnlessCollect() {
        persistedEpoch()
        val r = recorder()
        assertFalse(r.wantsStart); assertFalse(r.begin()); assertEquals(TraceStartBlocker.GATE_OFF, r.startBlocker())
        r.onControl(TraceTestKit.collect(counter = 0)) // the laptop opens a window: the OTHER side of the OR
        assertTrue(r.wantsStart); assertTrue(r.begin())
    }

    @Test fun CollectWindowExpiryDoesNotCloseDiagTrace() {
        persistedEpoch(); diag = true
        val r = recorder()
        r.onControl(TraceTestKit.collect(until = 1_000 + 5_000, atMs = 1_000))
        assertTrue(r.begin()); r.gyro(300, 20) // 6 s: the window is over
        assertFalse(r.state().collect, "the window ended")
        assertTrue(r.recording, "diagnostics are on: the window ending closes only window traces")
        diag = false; r.onDiagChanged()
        assertFalse(r.recording, "now nothing allows it")
    }

    // ---- a restart that finds a killed trace ----

    @Test fun RestartKilledTraceSealedPartialThenNewStarts() {
        persistedEpoch(); diag = true
        val r1 = recorder(flush = 2_000); r1.begin()
        var guard = 0
        while (File(root, "imu_trace_tmp").listFiles()?.singleOrNull()?.list()?.any { it.startsWith("chunk-") } != true && guard++ < 100_000) r1.gyro(1)
        assertTrue(r1.recording, "never closed: the process dies here")
        val r2 = recorder() // the restart: no control, the flag and the epoch were persisted
        val killed = TraceTestKit.readAll(root).single()
        assertEquals(TraceStatus.PARTIAL, killed.manifest.recorded)
        assertEquals(1, recovered.last().found); assertEquals(1, recovered.last().sealed)
        assertTrue(r2.wantsStart, "recovery is done and the gate is open: no collect step needed")
        assertTrue(r2.begin()); r2.gyro(10); r2.close(CloseReason.STOP)
        val all = TraceTestKit.readAll(root)
        assertEquals(2, all.size); assertTrue(all[0].manifest.traceId != all[1].manifest.traceId)
        assertEquals(2, r2.state().traces, "the sealed killed trace and the new one")
    }

    @Test fun RecoveryHookRunsOnceAfterRecoveredWithTheKilledCount() {
        val seen = ArrayList<Boolean>(); val q = ArrayDeque<Runnable>()
        lateinit var r: TraceRecorder
        r = TraceRecorder(root, clock, { 1L }, { "7" }, 1, io = { q.addLast(it) }, onRecovered = { seen += r.state().recovered })
        assertTrue(seen.isEmpty(), "the recovery (and its report) run on the writer, never in the constructor")
        while (q.isNotEmpty()) q.removeFirst().run()
        assertEquals(listOf(true), seen, "recovered is already true when the hook runs, and it runs once")
    }

    @Test fun DiagOnNeverRecordsUnderAForgottenEpochAcrossARestart() {
        persistedEpoch(); diag = true
        val r1 = recorder(); failDelete = true; File(root, "traces/keep").mkdirs()
        r1.onControl(TraceTestKit.collect(install = "inst-b", counter = 7).copy(collect = false)) // a new epoch: its wipe fails
        assertFalse(r1.wantsStart); assertEquals(TraceStartBlocker.WIPE_FAILED, r1.startBlocker())
        val r2 = recorder() // restart: the cleanup is replayed from its record, fails again
        assertFalse(r2.epochSaved); assertFalse(r2.wantsStart); assertFalse(r2.begin(), "no recording under an epoch whose cleanup is not done")
        failDelete = false
        val r3 = recorder()
        assertTrue(r3.epochSaved && !r3.cleanupPending() && r3.wantsStart)
    }

    // ---- the state and boot lines ----

    private val lines = ArrayList<Pair<Boolean, String>>()
    private var flag = DiagFlagState.ENABLED
    private val boundary = TraceBoundaryReporter()
    private fun reporter(r: TraceRecorder) = TraceStateReporter(r, boundary, { flag }, clock, { warn, l -> lines += warn to l })

    @Test fun StateLineOncePerBlockerChange() {
        persistedEpoch(); diag = true
        val r = recorder(); val sr = reporter(r)
        boundary.report(null)
        repeat(5) { sr.poll() }
        assertEquals(1, lines.size, "the first poll prints, an unchanged blocker prints nothing")
        clock.t += 30_000; sr.poll()
        assertEquals(1, lines.size, "30 s later, same blocker: still one line")
        boundary.report(BoundaryBlocker.NOT_QUIET); sr.poll(); sr.poll()
        assertEquals(2, lines.size); assertTrue(lines[1].second.contains("blocker=no_boundary_not_quiet"), lines[1].second)
        clock.t += 60_000; sr.poll(); sr.poll()
        assertEquals(3, lines.size, "one heartbeat per 60 s")
        val l = lines[2].second
        assertTrue(Regex("""^trace state: blocker=\S+ recovered=1 recoveredMs=\d+ collect=0 untilInMs=- diag=1 flagState=enabled epoch=set usedMB=\d+ softMB=\d+ rec=0 traces=0 offers=\d+ starts=\d+ startRefused=\d+ pause_src=-$""").matches(l), l)
    }

    /** 2026-10-25: a pause with no named source. The state line carries it, and a change of source prints a new line even when the blocker did not change. */
    @Test fun StateLineNamesThePauseSourceAndReprintsWhenItChanges() {
        persistedEpoch(); diag = true
        var src = "-"
        val r = recorder(); val sr = TraceStateReporter(r, boundary, { flag }, clock, { warn, l -> lines += warn to l }, pauseSource = { src })
        boundary.report(null); sr.poll(); sr.poll()
        assertEquals(1, lines.size); assertTrue(lines[0].second.endsWith(" pause_src=-"), lines[0].second)
        src = "USER_PHONE"; sr.poll()
        assertEquals(2, lines.size); assertTrue(lines[1].second.endsWith(" pause_src=USER_PHONE"), lines[1].second)
    }

    @Test fun InactiveSessionIsTheBlockerWhenOnlyTheSessionIsMissing() {
        persistedEpoch(); diag = true
        val r = recorder(); val sr = reporter(r)
        assertEquals("session_inactive", sr.blocker(), "the gate is open, nothing recorded: the app is idle")
        diag = false
        assertEquals("gate_off", sr.blocker(), "the recorder's own blocker comes first")
    }

    @Test fun AlreadyRecordingIsNotAFault() {
        persistedEpoch(); diag = true
        val r = recorder(); val sr = reporter(r)
        assertTrue(r.begin()); r.gyro(3)
        assertNull(r.startBlocker())
        sr.poll()
        val (warn, line) = lines.single()
        assertFalse(warn, "a normal state, never logged as a warning")
        assertTrue(line.contains("blocker=already_recording") && line.contains("rec=1"), line)
    }

    @Test fun AFailedWipeIsAFaultAndArmedNoEpochIsNamed() {
        persistedEpoch(); diag = true
        val r = recorder(); val sr = reporter(r); failDelete = true; File(root, "traces/keep").mkdirs()
        r.onControl(TraceTestKit.collect(install = "inst-b", counter = 3).copy(collect = false))
        sr.poll()
        assertTrue(lines.last().first, "wipe_failed is a warning"); assertTrue(lines.last().second.contains("blocker=wipe_failed"))
        // requested but no durable epoch: named armed_no_epoch, not the generic gate_off
        val fresh = TraceTestKit.tempDir()
        try {
            val r2 = TraceRecorder(fresh, clock, { 1L }, { "7" }, 1, io = { it.run() }, diagEnabled = { false })
            flag = DiagFlagState.ARMED_NO_EPOCH
            assertEquals("armed_no_epoch", TraceStateReporter(r2, boundary, { flag }, clock, { _, _ -> }).blocker())
        } finally { fresh.deleteRecursively() }
    }

    @Test fun BootLineCarriesFlagStateEpochAndKilledCount() {
        persistedEpoch(); diag = true
        val r = recorder(); val sr = reporter(r)
        assertEquals("trace boot: restart=2 flagState=enabled epoch=set killedTraces=1", sr.bootLine(restart = 2, killedTraces = 1))
        flag = DiagFlagState.ARMED_NO_EPOCH
        val fresh = TraceTestKit.tempDir()
        try {
            val r2 = TraceRecorder(fresh, clock, { 1L }, { "7" }, 1, io = { it.run() })
            assertEquals("trace boot: restart=0 flagState=armed_no_epoch epoch=unset killedTraces=0", TraceStateReporter(r2, boundary, { flag }, clock, { _, _ -> }).bootLine(0, 0))
        } finally { fresh.deleteRecursively() }
    }

    // ---- structure ----

    @Test fun RecorderNeverReferencesPairing() {
        val dir = File("src/main/java/com/rokid/context/glasses/capture/trace")
        val files = dir.listFiles { f -> f.extension == "kt" }!!
        assertTrue(files.any { it.name == "TraceRecorder.kt" }, "the scan found the recorder: ${dir.absolutePath}")
        val bad = Regex("""(?i)pairing|canTransfer|pair_?code|bearer""")
        val hits = files.flatMap { f -> f.readLines().withIndex().filter { bad.containsMatchIn(it.value) }.map { "${f.name}:${it.index + 1}" } }
        assertTrue(hits.isEmpty(), "recording is never gated by pairing: $hits")
    }
}
