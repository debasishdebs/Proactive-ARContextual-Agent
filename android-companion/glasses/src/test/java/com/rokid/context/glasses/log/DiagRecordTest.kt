package com.rokid.context.glasses.log

import com.rokid.context.core.model.HudSettings
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * LGC4: the persisted `diag_record` request and its three-state bootstrap (`UNPROVISIONED` / `ARMED_NO_EPOCH` / `ENABLED`, plus `DISABLED` = provisioned but off).
 * The epoch is a supplier here (the recorder's durable epoch in production); the writer is a deferred queue so every ordering is deterministic (no sleeps).
 */
class DiagRecordTest {
    private val dir = kotlin.io.path.createTempDirectory("diagrec").toFile()
    private val file = File(dir, "diag_state")
    private val queue = ArrayDeque<Runnable>()
    private var epoch = false
    private var reads = 0
    private var writeOk = true
    private val writes = ArrayList<String>()
    @AfterTest fun cleanup() { dir.deleteRecursively() }

    private fun drain() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    private fun store() = DiagStateFile(
        file,
        read = { f -> reads++; if (f.exists()) f.readText() else null },
        write = { f, t -> writes += t; if (writeOk) { f.parentFile.mkdirs(); f.writeText(t); true } else false },
    )
    private fun rec(log: (String) -> Unit = {}) = DiagRecord(store(), epochSaved = { epoch }, io = { queue.addLast(it) }, log = log)

    @Test fun unprovisionedGlassesReportsUnprovisionedNotFault() {
        val r = rec(); drain()
        assertEquals(DiagFlagState.UNPROVISIONED, r.state())
        assertFalse(r.diagEnabled()); assertFalse(r.requested())
        assertEquals("unprovisioned", r.state().wire)
        assertFalse(r.persistFailed, "an unpaired glasses is the expected default, not a fault")
    }

    @Test fun oldPhoneSettingsDefaultOff() {
        epoch = true
        val r = rec(); drain()
        r.onSettings(HudSettings().diagRecord); drain() // an old phone's JSON decodes to false
        assertFalse(r.diagEnabled()); assertEquals(DiagFlagState.DISABLED, r.state())
        assertFalse(rec().also { drain() }.requested())
    }

    @Test fun settingsBeforeControlBecomesEnabledAfterEpochSaved() {
        val r = rec(); drain()
        r.onSettings(true); drain()
        assertEquals(DiagFlagState.ARMED_NO_EPOCH, r.state())
        assertFalse(r.diagEnabled(), "requested but no epoch saved: the trace gate stays closed")
        assertTrue(r.requested())
        epoch = true // the control arrived, the wipe finished and the epoch is durable
        assertTrue(r.diagEnabled())
        assertEquals(DiagFlagState.ENABLED, r.state())
    }

    @Test fun controlBeforeSettings() {
        epoch = true
        val r = rec(); drain()
        assertEquals(DiagFlagState.DISABLED, r.state(), "epoch saved, never asked: provisioned but off")
        assertFalse(r.diagEnabled())
        r.onSettings(true); drain()
        assertTrue(r.diagEnabled()); assertEquals(DiagFlagState.ENABLED, r.state())
    }

    @Test fun interruptionBetweenSettingsAndControlThenOfflineRestartStillArmedNotLostNotEnabled() {
        val first = rec(); drain()
        first.onSettings(true); drain()
        // the process dies here: settings applied, control never arrived; restart with no phone
        val second = rec(); drain()
        assertTrue(second.requested(), "the request survived the restart")
        assertEquals(DiagFlagState.ARMED_NO_EPOCH, second.state())
        assertFalse(second.diagEnabled(), "and it is NOT enabled without an epoch")
        epoch = true // the phone retries the control later
        assertEquals(DiagFlagState.ENABLED, second.state())
    }

    @Test fun theRequestAndItsClearingSurviveARestart() {
        epoch = true
        val a = rec(); drain(); a.onSettings(true); drain()
        assertTrue(rec().also { drain() }.diagEnabled())
        a.onSettings(false); drain()
        val b = rec(); drain()
        assertFalse(b.requested()); assertFalse(b.diagEnabled()); assertEquals(DiagFlagState.DISABLED, b.state())
    }

    @Test fun aCorruptStateFileMeansOff() {
        epoch = true
        file.writeText("\u0000garbage")
        val r = rec(); drain()
        assertFalse(r.requested()); assertFalse(r.diagEnabled())
    }

    @Test fun aFailedWriteIsCheckedReportedAndRetriedAtTheNextSettings() {
        epoch = true
        val r = rec(); drain()
        writeOk = false
        r.onSettings(true); drain()
        assertTrue(r.diagEnabled(), "the in-memory request applies even if it could not be saved")
        assertTrue(r.persistFailed, "the failed save is visible, not silent")
        assertFalse(file.exists())
        writeOk = true
        r.onSettings(true); drain() // the phone re-sends settings at every session-ready
        assertFalse(r.persistFailed)
        assertTrue(rec().also { drain() }.requested(), "the retry reached the disk")
    }

    @Test fun clearingTheRequestWhoseWriteFailedFallsBackToRemovingTheFile() {
        epoch = true
        val a = rec(); drain(); a.onSettings(true); drain()
        writeOk = false
        a.onSettings(false); drain()
        assertFalse(a.diagEnabled())
        assertFalse(rec().also { drain() }.requested(), "a failed 'off' write must not leave a true value that re-enables recording after a restart")
    }

    @Test fun construction_andSettingsDoNoFilesystemWorkOnTheCallingThread() {
        val r = rec()
        assertEquals(0, reads, "the load is queued on the writer, not run by the constructor")
        r.onSettings(true)
        assertEquals(0, writes.size, "the save is queued too")
        assertFalse(r.diagEnabled(), "nothing is effective before the writer ran the load")
        drain()
        assertEquals(1, reads); assertEquals(1, writes.size)
    }

    @Test fun settingsArrivingBeforeTheQueuedLoadWinOverTheOldFileValue() {
        epoch = true
        val a = rec(); drain(); a.onSettings(true); drain()
        val b = rec() // load queued, not run
        b.onSettings(false) // queued after the load
        drain()
        assertFalse(b.requested(), "the later settings are not overwritten by the earlier-queued load")
        assertFalse(rec().also { drain() }.requested())
    }

    @Test fun onChangedFiresWhenTheEffectiveRequestChangesAndNotOtherwise() {
        epoch = true
        val r = rec(); drain()
        var n = 0
        r.onChanged = { n++ }
        r.onSettings(true); drain(); assertEquals(1, n)
        r.onSettings(true); drain(); assertEquals(1, n, "no change, no callback")
        r.onSettings(false); drain(); assertEquals(2, n)
    }

    @Test fun theSourceNeverTouchesPcLogPolicy() {
        // capture_diag.log stays always-on in LG-MIN (the heartbeat readers need it): the flag must never feed PcLog.setDiagnostic.
        val src = File("src/main/java/com/rokid/context/glasses/log/DiagRecord.kt").readText()
        assertFalse(src.contains("setDiagnostic("), "DiagRecord must not drive the log policy")
        assertTrue(GlassesLogging.DIAGNOSTIC_RECORD, "the log policy is the constant ON")
    }
}
