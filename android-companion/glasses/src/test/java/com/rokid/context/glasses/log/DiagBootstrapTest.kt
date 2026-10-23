package com.rokid.context.glasses.log

import com.rokid.context.capture.trace.CloseReason
import com.rokid.context.core.model.MotionState
import com.rokid.context.glasses.capture.trace.ManualClock
import com.rokid.context.glasses.capture.trace.TraceRecorder
import com.rokid.context.glasses.capture.trace.TraceStartBlocker
import com.rokid.context.glasses.capture.trace.TraceTestKit
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * LGC4 production wiring: the REAL [DiagRecord] on the REAL [TraceRecorder] (one shared deferred writer, like `GlassesRuntime`), files in a temp dir.
 * Order that must hold on the glasses: fence/epoch wipe (control) -> epoch saved -> only then the request becomes effective.
 */
class DiagBootstrapTest {
    private val root = TraceTestKit.tempDir()
    private val clock = ManualClock()
    private val queue = ArrayDeque<Runnable>()
    private var failDelete = false
    private var killOnDelete = false
    private var ids = 0
    @AfterTest fun cleanup() { root.deleteRecursively() }

    private class Glasses(val rec: TraceRecorder, val diag: DiagRecord)

    private fun boot(): Glasses {
        lateinit var rec: TraceRecorder
        val diag = DiagRecord(DiagStateFile(File(root, "diag_state")), epochSaved = { rec.epochSaved }, io = { queue.addLast(it) })
        rec = TraceRecorder(
            File(root, "imu"), clock, { 1_700_000_000_000 + clock.t }, { "7" }, 1, io = { queue.addLast(it) }, newTraceId = { "trace-%04d-boot".format(++ids) },
            deleteTree = { f -> if (killOnDelete) throw Error("killed inside the wipe"); if (failDelete) false else f.deleteRecursively() }, diagEnabled = diag::diagEnabled,
        )
        diag.onChanged = { rec.onDiagChanged() }
        drain()
        return Glasses(rec, diag)
    }
    private fun drain() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    private fun Glasses.control(install: String = "inst-1", counter: Long = 0) = rec.onControl(TraceTestKit.collect(install = install, counter = counter).copy(collect = false))

    @Test fun settingsBeforeControlBecomesEnabledAfterEpochSaved() {
        val g = boot()
        g.diag.onSettings(true); drain()
        assertEquals(DiagFlagState.ARMED_NO_EPOCH, g.diag.state())
        assertFalse(g.rec.wantsStart); assertEquals(TraceStartBlocker.GATE_OFF, g.rec.startBlocker(), "armed: the gate is closed; the FLAG state says why (armed_no_epoch)")
        g.control(); drain()
        assertEquals(DiagFlagState.ENABLED, g.diag.state())
        assertTrue(g.rec.wantsStart, "diagnostics start recording with no collect window and no laptop")
    }

    @Test fun controlBeforeSettings() {
        val g = boot()
        g.control(); drain()
        assertEquals(DiagFlagState.DISABLED, g.diag.state()); assertFalse(g.rec.wantsStart)
        g.diag.onSettings(true); drain()
        assertEquals(DiagFlagState.ENABLED, g.diag.state()); assertTrue(g.rec.wantsStart)
    }

    @Test fun theRequestIsNotEffectiveWhileTheFenceAndEpochWipeAreStillQueued() {
        val g = boot()
        g.diag.onSettings(true); drain()
        g.control(install = "inst-new", counter = 7) // adopts the epoch, the wipe is queued
        assertTrue(queue.isNotEmpty(), "the wipe has not run")
        assertFalse(g.diag.diagEnabled(), "fence first, epoch second, flag last: nothing is effective before the epoch is durable")
        assertEquals(DiagFlagState.ARMED_NO_EPOCH, g.diag.state())
        drain()
        assertTrue(g.diag.diagEnabled()); assertEquals("inst-new\n7", File(root, "imu/trace_epoch").readText())
    }

    @Test fun aNewEpochOnAnEnabledGlassesSuspendsDiagUntilItsFenceAndEpochAreDone() {
        val g = boot()
        g.control(install = "inst-1", counter = 1); g.diag.onSettings(true); drain()
        assertTrue(g.diag.diagEnabled())
        g.control(install = "inst-2", counter = 0) // a re-pairing: the old traces must go before the new epoch is saved and diagnostics resume
        assertTrue(queue.isNotEmpty())
        assertFalse(g.diag.diagEnabled(), "fence first: the old epoch's flag does not carry over while the wipe is queued")
        assertEquals(DiagFlagState.ARMED_NO_EPOCH, g.diag.state())
        drain()
        assertTrue(g.diag.diagEnabled()); assertEquals("inst-2\n0", File(root, "imu/trace_epoch").readText())
    }

    @Test fun aFailedFenceThenOfflineRestartDoesNotRecordUnderTheForgottenEpoch() {
        val g = boot()
        g.control(install = "inst-1", counter = 0); g.diag.onSettings(true); drain()
        assertTrue(g.diag.diagEnabled())
        File(root, "imu/traces/keep").mkdirs()
        failDelete = true
        g.control(install = "inst-1", counter = 1); drain() // Delete all: the wipe fails
        val second = boot() // restart, phone offline
        assertFalse(second.rec.wantsStart, "the deleted epoch must not be reused after a restart")
        assertEquals(DiagFlagState.ARMED_NO_EPOCH, second.diag.state())
        assertFalse(second.diag.diagEnabled())
        failDelete = false
        second.control(install = "inst-1", counter = 1); drain() // a control re-provisions
        assertEquals(DiagFlagState.ENABLED, second.diag.state()); assertTrue(second.rec.wantsStart)
    }

    @Test fun aProcessDeathInsideTheWipeThenOfflineRestartDoesNotRecordUnderTheForgottenEpoch() {
        val g = boot()
        g.control(install = "inst-1", counter = 0); g.diag.onSettings(true); drain()
        File(root, "imu/traces/keep").mkdirs()
        killOnDelete = true
        g.control(install = "inst-1", counter = 1)
        try { drain() } catch (e: Error) { /* the process died here */ }
        queue.clear(); killOnDelete = false
        assertTrue(File(root, "imu/trace_cleanup_pending").exists(), "LGC8-min: the received cleanup and its target epoch were persisted before the wipe ran")
        val second = boot()
        // LGC8-min: the restart REPLAYS the persisted cleanup before recovery: the wipe completes, the target epoch is saved from the record (never the forgotten one), and only then
        // does the request become effective. Fence, epoch, flag: the order holds across the process death, without waiting for a control.
        assertFalse(File(root, "imu/traces/keep").exists(), "the interrupted wipe was finished at start")
        assertEquals("inst-1\n1", File(root, "imu/trace_epoch").readText())
        assertEquals(DiagFlagState.ENABLED, second.diag.state())
        assertFalse(File(root, "imu/trace_cleanup_pending").exists())
    }

    @Test fun aFailedFenceKeepsTheFlagArmedNotEnabledUntilTheRetrySucceeds() {
        val g = boot()
        g.diag.onSettings(true); drain()
        File(root, "imu/traces/keep").mkdirs()
        failDelete = true
        g.control(install = "inst-new", counter = 1); drain()
        assertEquals(DiagFlagState.ARMED_NO_EPOCH, g.diag.state()); assertFalse(g.diag.diagEnabled())
        failDelete = false; clock.t += 120_000
        g.rec.wantsStart; drain()
        assertEquals(DiagFlagState.ENABLED, g.diag.state())
    }

    @Test fun interruptionBetweenSettingsAndControlThenOfflineRestartStillArmedNotLostNotEnabled() {
        val first = boot()
        first.diag.onSettings(true); drain() // settings applied; the control never arrives; the process dies
        val second = boot() // restart, phone offline
        assertEquals(DiagFlagState.ARMED_NO_EPOCH, second.diag.state(), "not lost, not enabled, and no trace without an epoch")
        assertFalse(second.rec.wantsStart); assertEquals(TraceStartBlocker.GATE_OFF, second.rec.startBlocker())
        second.control(); drain() // the phone comes back and retries the control
        assertEquals(DiagFlagState.ENABLED, second.diag.state()); assertTrue(second.rec.wantsStart)
    }

    @Test fun anInterruptedControlAfterTheEpochWasSavedRestartsEnabledOffline() {
        val first = boot()
        first.control(); drain(); first.diag.onSettings(true); drain()
        val second = boot()
        assertEquals(DiagFlagState.ENABLED, second.diag.state(), "both halves are durable: a lone glasses restart records again")
        assertTrue(second.rec.wantsStart)
    }

    @Test fun diagWentOffViaSettingsClosesTheRunningTraceAndDeletesNothing() {
        val g = boot()
        g.control(); g.diag.onSettings(true); drain()
        assertTrue(g.rec.start(TraceTestKit.start()))
        repeat(10) { clock.t += 20; g.rec.onGyro(0.1f, 0.2f, 0.3f, clock.t - 5, MotionState.STILL) }
        g.diag.onSettings(false); drain()
        assertFalse(g.rec.recording)
        val t = TraceTestKit.readAll(File(root, "imu")).single()
        assertEquals(CloseReason.STOP, t.manifest.closeReason)
        assertFalse(g.rec.wantsStart); assertEquals(DiagFlagState.DISABLED, g.diag.state())
    }

    @Test fun anOldPhoneThatNeverSendsTheFlagKeepsAnUnpairedGlassesOffAndTurnsAnArmedOneOff() {
        val g = boot()
        g.diag.onSettings(false); drain()
        assertEquals(DiagFlagState.UNPROVISIONED, g.diag.state())
        g.diag.onSettings(true); drain()
        g.diag.onSettings(false); drain()
        assertFalse(g.diag.requested())
    }
}
