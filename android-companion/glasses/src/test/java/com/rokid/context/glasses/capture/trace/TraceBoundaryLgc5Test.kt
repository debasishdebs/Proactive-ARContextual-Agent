package com.rokid.context.glasses.capture.trace

import com.rokid.context.core.model.CaptureMode
import com.rokid.context.core.model.HudSettings
import com.rokid.context.glasses.capture.session.Rig
import com.rokid.context.glasses.log.DiagFlagState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** LGC5, session level: the real [com.rokid.context.glasses.capture.session.SamplingSession], gate, fake camera and recorder in virtual time; diagnostics ON, no laptop, no control. */
class TraceBoundaryLgc5Test {
    private val root = TraceTestKit.tempDir()
    @AfterTest fun cleanup() { root.deleteRecursively() }

    private class Fx(val t: TestScope, val root: File, trace: ((TraceRecorder) -> SessionTrace)? = null) {
        var ids = 0
        init { File(root, "trace_epoch").also { it.parentFile.mkdirs() }.writeText("inst-1\n0") }
        val rec = TraceRecorder(
            root, { t.testScheduler.currentTime }, { 1_700_000_000_000 + t.testScheduler.currentTime }, { "7" }, 1, io = { it.run() },
            newTraceId = { "trace-%04d-l5s".format(++ids) }, diagEnabled = { true },
        )
        val boundary = TraceBoundaryReporter()
        val lines = ArrayList<Pair<Boolean, String>>()
        val state = TraceStateReporter(rec, boundary, { DiagFlagState.ENABLED }, { t.testScheduler.currentTime }, { w, l -> lines += w to l })
        val seen = LinkedHashSet<String>()
        val sessionTrace: SessionTrace = trace?.invoke(rec) ?: rec
        fun rig(settings: HudSettings = HudSettings(captureMode = CaptureMode.SMART)) = Rig(t, settings, trace = sessionTrace, boundary = boundary).also { it.motion.addTap(rec) }
        fun watch() { t.backgroundScope.launch { while (isActive) { state.poll(); seen += boundary.current?.wire ?: "none"; delay(250) } } }
        /** The blocker of every state line, consecutive repeats removed. */
        fun sequence() = lines.map { Regex("blocker=(\\S+)").find(it.second)!!.groupValues[1] }.fold(ArrayList<String>()) { a, b -> if (a.lastOrNull() != b) a += b; a }
    }

    private fun <T> List<T>.hasInOrder(vararg xs: T): Boolean { var i = 0; for (e in this) if (i < xs.size && e == xs[i]) i++; return i == xs.size }

    @Test fun SessionBoundaryBlockersReachStateLine() = runTest {
        val fx = Fx(this, root); fx.watch(); val r = fx.rig()
        delay(1_000)
        r.startSensors(); r.gyroMag = 3f // turning: never quiet
        r.session.start()
        delay(30_000)
        assertFalse(fx.rec.recording)
        assertTrue(fx.sequence().hasInOrder("session_inactive", "no_boundary_not_quiet"), fx.sequence().toString())
        r.gyroMag = 0.01f
        delay(120_000)
        assertTrue(fx.rec.recording)
        assertTrue(fx.sequence().hasInOrder("session_inactive", "no_boundary_not_quiet", "already_recording"), fx.sequence().toString())
        assertTrue(fx.lines.last().second.contains("rec=1") || fx.sequence().last() != "already_recording")
        r.paused = true // a privacy pause: the recorder wants a trace, the session cannot offer one
        delay(30_000)
        assertFalse(fx.rec.recording)
        assertTrue(fx.sequence().hasInOrder("already_recording", "no_boundary_capture_blocked"), fx.sequence().toString())
        assertTrue(fx.boundary.starts >= 1 && fx.boundary.offers >= fx.boundary.starts, "offers=${fx.boundary.offers} starts=${fx.boundary.starts}")
        r.session.stop("test")
    }

    @Test fun InactiveSessionReported() = runTest {
        val fx = Fx(this, root); fx.watch()
        delay(2_000)
        assertEquals("session_inactive", fx.state.blocker(), "no capture session has run in this process: the app is idle, not broken")
        val r = fx.rig(); r.startSensors(); r.gyroMag = 0.01f
        delay(1_000)
        r.session.start(); delay(60_000)
        assertTrue(fx.rec.recording); assertEquals("already_recording", fx.state.blocker())
        r.session.stop("mode")
        delay(2_000)
        assertEquals("session_inactive", fx.state.blocker(), "stopped: reported again, so 'no trace' with the app idle is explained")
    }

    @Test fun SessionStopSealsAndNextSessionStartsNew() = runTest {
        val fx = Fx(this, root)
        val a = fx.rig(); a.startSensors(); a.gyroMag = 0.01f
        delay(1_000); a.session.start(); delay(60_000)
        assertTrue(fx.rec.recording)
        a.session.stop("mode")
        assertFalse(fx.rec.recording)
        val first = TraceTestKit.readAll(root).single()
        assertEquals("stop", first.manifest.closeReason); assertEquals("ok", first.verification.reason)
        val b = fx.rig(); b.startSensors(); b.gyroMag = 0.01f
        b.session.start(); delay(60_000) // the next capture session: no laptop, no control, no restart
        assertTrue(fx.rec.recording, "a new trace starts at the next clean boundary")
        b.session.stop("mode")
        val all = TraceTestKit.readAll(root)
        assertEquals(2, all.size); assertTrue(all[0].manifest.traceId != all[1].manifest.traceId)
        assertTrue(all[1].manifest.startElapsedMs >= all[0].manifest.endElapsedMs, "segments never overlap")
    }

    @Test fun AnEpisodeAndAGateWindowAreNamedWhenARecorderKeepsRefusing() = runTest {
        // A recorder that always wants a trace and never takes one: the session keeps offering and keeps saying what stood in the way.
        val fx = Fx(this, root) { inner ->
            object : SessionTrace by inner { override val wantsStart: Boolean get() = true; override fun start(s: TraceStart) = false }
        }
        fx.watch(); val r = fx.rig(); r.startSensors(); r.gyroMag = 0.01f
        delay(1_000); r.session.start(); delay(240_000)
        assertTrue("no_boundary_episode" in fx.seen, fx.seen.toString())
        assertTrue(fx.boundary.offers > 0 && fx.boundary.startRefused == fx.boundary.offers && fx.boundary.starts == 0, "offers=${fx.boundary.offers} refused=${fx.boundary.startRefused}")
        r.session.stop("test")
    }
}
