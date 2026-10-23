package com.rokid.context.glasses.log

import com.rokid.context.core.model.Decoded
import com.rokid.context.core.model.Hello
import com.rokid.context.core.model.Wire
import com.rokid.context.glasses.capture.trace.BoundaryBlocker
import com.rokid.context.glasses.capture.trace.ManualClock
import com.rokid.context.glasses.capture.trace.TraceBoundaryReporter
import com.rokid.context.glasses.capture.trace.TraceRecorder
import com.rokid.context.glasses.capture.trace.TraceStartBlocker
import com.rokid.context.glasses.capture.trace.TraceStateReporter
import com.rokid.context.glasses.capture.trace.TraceTestKit
import com.rokid.context.phone.hub.plan.DiagControlRetry
import com.rokid.context.phone.hub.plan.DiagFlagState as PhoneFlag
import com.rokid.context.phone.hub.plan.MutableGlassesDiagStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * LGC6-min, the production-path integration test (plan 0A / LGC6-min row): REAL bootstrap ([DiagRecord] on the real [TraceRecorder], files in a temp dir) and the real
 * state reporter -> the real [withDiagFields] producer -> the real wire codec -> the REAL phone status store ([MutableGlassesDiagStatus.onHello]) -> the REAL
 * [DiagControlRetry]. The phone's `DiagControlRetry.kt` is compiled into this test source set (see `build.gradle.kts`): this module and `:phone` are siblings, so this is
 * the only place both halves exist. Acyclic: it depends on LGC4P (status store, retry) and LGC5 (reporter).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BootstrapRecorderHelloPhoneStoreControlRetryTest {
    private val root = TraceTestKit.tempDir()
    private val clock = ManualClock()
    private val queue = ArrayDeque<Runnable>()
    @AfterTest fun cleanup() { root.deleteRecursively() }

    private class Glasses(val rec: TraceRecorder, val diag: DiagRecord, val reporter: TraceStateReporter, val boundary: TraceBoundaryReporter)

    private fun boot(): Glasses {
        lateinit var rec: TraceRecorder
        val diag = DiagRecord(DiagStateFile(File(root, "diag_state")), epochSaved = { rec.epochSaved }, io = { queue.addLast(it) })
        rec = TraceRecorder(File(root, "imu"), clock, { 1_700_000_000_000 + clock.t }, { "7" }, 1, io = { queue.addLast(it) }, newTraceId = { "trace-0001-hello" }, diagEnabled = diag::diagEnabled)
        diag.onChanged = { rec.onDiagChanged() }
        val boundary = TraceBoundaryReporter()
        val reporter = TraceStateReporter(rec, boundary, diag::state, clock, emit = { _, _ -> })
        drain()
        return Glasses(rec, diag, reporter, boundary)
    }
    private fun drain() { while (queue.isNotEmpty()) queue.removeFirst().run() }

    /** What the glasses would send now, through the production producer and the real wire codec, as the phone would decode it. */
    private fun Glasses.helloOnTheWire(): Hello {
        val h = Hello(glassesBatteryPct = 80, bufferSize = 0, glassesNowMs = 5, pauses = emptyList()).withDiagFields(diag, reporter)
        return (Wire.decode<Hello>(Wire.encode(h)) as Decoded.Ok).value
    }

    @Test fun unprovisionedGlassesReportsUnprovisionedAndTheRetryKeepsGoingWithoutFault() {
        val g = boot(); val status = MutableGlassesDiagStatus()
        status.onHello(g.helloOnTheWire())
        assertEquals(PhoneFlag.UNPROVISIONED, status.state.value)
        assertEquals("gate_off", status.traceBlocker.value)
    }

    @Test fun armedToEnabledTransitionEndsTheControlRetryOnlyWhenTheRealStateSaysEnabled() = runTest {
        val g = boot(); val status = MutableGlassesDiagStatus(); val resends = mutableListOf<Long>()
        // phone Developer mode on: the settings arrive first, the control has not (the real bootstrap is armed, with no epoch)
        g.diag.onSettings(true); drain()
        status.onHello(g.helloOnTheWire())
        assertEquals(PhoneFlag.ARMED_NO_EPOCH, status.state.value, "the Hello carries the REAL armed_no_epoch, not a constant")
        assertEquals("armed_no_epoch", status.traceBlocker.value)

        val retry = DiagControlRetry(backgroundScope, status, diagWanted = { true }, resend = {
            resends += testScheduler.currentTime
            g.rec.onControl(TraceTestKit.collect().copy(collect = false)); drain() // the re-sent control reaches the glasses: fence, epoch saved, flag effective
            status.onHello(g.helloOnTheWire())
        })
        retry.restart(); runCurrent()
        advanceTimeBy(4_999); assertEquals(0, resends.size, "armed: nothing before the first backoff")
        advanceTimeBy(2); assertEquals(1, resends.size, "armed_no_epoch keeps the control re-send going")
        assertEquals(PhoneFlag.ENABLED, status.state.value)
        assertTrue(g.rec.wantsStart, "the real recorder agrees: diagnostics record")
        advanceTimeBy(600_000); assertEquals(1, resends.size, "no re-send once the glasses report enabled")
    }

    @Test fun disabledAfterControlStopsTheRetryAndSettingsLaterReportEnabled() {
        val g = boot(); val status = MutableGlassesDiagStatus()
        g.rec.onControl(TraceTestKit.collect().copy(collect = false)); drain()
        status.onHello(g.helloOnTheWire()); assertEquals(PhoneFlag.DISABLED, status.state.value)
        g.diag.onSettings(true); drain()
        status.onHello(g.helloOnTheWire()); assertEquals(PhoneFlag.ENABLED, status.state.value)
    }

    @Test fun theBlockerOnTheWireIsAlwaysAFixedVocabularyWord() {
        val g = boot()
        val seen = mutableSetOf<String?>()
        seen += g.helloOnTheWire().traceBlocker                       // unprovisioned
        g.diag.onSettings(true); drain(); seen += g.helloOnTheWire().traceBlocker // armed
        g.rec.onControl(TraceTestKit.collect().copy(collect = false)); drain(); seen += g.helloOnTheWire().traceBlocker // enabled, session idle
        g.boundary.report(BoundaryBlocker.NOT_QUIET); seen += g.helloOnTheWire().traceBlocker
        assertTrue(seen.all { it != null && it in TraceStateReporter.VOCABULARY }, "$seen")
        assertEquals(TraceStartBlocker.entries.map { it.wire }.toSet() + BoundaryBlocker.entries.map { it.wire } + setOf("armed_no_epoch", "already_recording", "none"), TraceStateReporter.VOCABULARY)
    }

    @Test fun beforeTheRecorderExistsTheProducerNeverBuildsIt() {
        var built = false
        val diag = DiagRecord(DiagStateFile(File(root, "diag_state")), epochSaved = { built = true; false }, io = { queue.addLast(it) })
        diag.onSettings(true); drain() // the writer's own log line reads the state: that is not the producer
        built = false
        val h = Hello(glassesBatteryPct = 1, bufferSize = 0, glassesNowMs = 1, pauses = emptyList()).withDiagFields(diag, null)
        assertEquals("armed_no_epoch", h.diagFlagState); assertNull(h.traceBlocker)
        assertTrue(!built, "no recorder read (the real runtime would construct it on the link thread)")
    }
}
