package com.rokid.context.glasses.log

import com.rokid.context.core.log.Level
import com.rokid.context.core.log.Line
import com.rokid.context.core.log.LogFs
import com.rokid.context.core.log.LogSink
import com.rokid.context.core.log.PcLog
import com.rokid.context.core.log.RealLogFs
import com.rokid.context.core.log.Route
import com.rokid.context.glasses.capture.diag.DiagLog
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlassesLoggingTest {
    private val dir = kotlin.io.path.createTempDirectory("gllog").toFile()

    @BeforeTest fun reset() = PcLog.testOnlyResetNeverCallFromProduction()
    @AfterTest fun after() = PcLog.testOnlyResetNeverCallFromProduction()

    private class Rec : LogSink {
        val got = CopyOnWriteArrayList<Pair<Line, Route>>()
        override fun emit(line: Line, route: Route) { got += line to route }
    }

    @Test fun diagLogIsAThinShimOverPcLog() {
        val rec = Rec()
        PcLog.setDiagnostic(true)
        assertTrue(PcLog.install(rec))
        DiagLog.i("PcCapture", "status ok")
        DiagLog.w("PcUplink", "bad")
        assertEquals(listOf(Level.I to "PcCapture", Level.W to "PcUplink"), rec.got.map { it.first.level to it.first.tag })
        assertEquals(listOf("status ok", "bad"), rec.got.map { it.first.msg })
        assertTrue(rec.got.all { it.second.file && it.second.logcat }, "the glasses diag log records everything it did before")
    }

    @Test fun linesLoggedBeforeInstallAreKeptAndReplayedInOrder() {
        DiagLog.i("PcCapture", "early 1")
        DiagLog.w("PcCapture", "early 2")
        val rec = Rec()
        PcLog.setDiagnostic(GlassesLogging.DIAGNOSTIC_RECORD)
        assertTrue(PcLog.install(rec))
        assertEquals(listOf("early 1", "early 2"), rec.got.map { it.first.msg })
        assertTrue(rec.got.all { it.second.file })
    }

    @Test fun currentPolicyIsAlwaysOnWithASingleOwner() {
        assertTrue(GlassesLogging.DIAGNOSTIC_RECORD)
        val order = mutableListOf<String>()
        val h = GlassesLogging.install(dir, installer = { order += "install"; true }, once = AtomicBoolean(false), setDiagnostic = { order += "diag=$it" })
        assertNotNull(h)
        assertEquals(listOf("diag=true", "install"), order, "the flag is applied before the ring drains")
        h.close()
    }

    @Test fun installIsOnceAndARefusedInstallClosesTheWriterAndRearms() {
        val once = AtomicBoolean(false)
        val h1 = GlassesLogging.install(dir, installer = { false }, once = once, setDiagnostic = {})
        assertNull(h1)
        assertFalse(once.get(), "re-armed after a refusal")
        val h2 = GlassesLogging.install(dir, installer = { true }, once = once, setDiagnostic = {})
        assertNotNull(h2)
        assertNull(GlassesLogging.install(dir, installer = { true }, once = once, setDiagnostic = {}), "second call in the same process")
        h2.close()
    }

    @Test fun installDoesNoFilesystemWorkOnTheCallingThread() {
        val caller = Thread.currentThread()
        val onCaller = CopyOnWriteArrayList<String>()
        val onWriter = CopyOnWriteArrayList<String>()
        fun note(op: String) { (if (Thread.currentThread() === caller) onCaller else onWriter) += op }
        val fs = object : LogFs {
            override fun length(f: File) = RealLogFs.length(f).also { note("length") }
            override fun exists(f: File) = RealLogFs.exists(f).also { note("exists") }
            override fun delete(f: File) = RealLogFs.delete(f).also { note("delete") }
            override fun rename(from: File, to: File) = RealLogFs.rename(from, to).also { note("rename") }
            override fun append(f: File, bytes: ByteArray) { note("append"); RealLogFs.append(f, bytes) }
        }
        val missing = File(dir, "not-created-yet")
        val h = GlassesLogging.install(missing, installer = { true }, once = AtomicBoolean(false), setDiagnostic = {}, sinkFactory = { GlassesLogSink.create(it, fs = fs) })
        assertNotNull(h)
        assertFalse(missing.exists(), "install created nothing")
        assertTrue(onCaller.isEmpty(), "FS calls on the calling thread: $onCaller")
        h.emit(Line(Level.I, "PcCapture", "m", 0L), Route(false, true))
        assertTrue(h.awaitIdle(2_000))
        assertTrue(onWriter.contains("append") && onCaller.isEmpty(), "writes happen on the writer thread: $onWriter / $onCaller")
        h.close()
    }

    @Test fun endToEndThroughTheRealInstallWritesCaptureDiagLog() {
        val h = GlassesLogging.install(dir, once = AtomicBoolean(false))
        assertNotNull(h)
        DiagLog.i("PcCapture", "heartbeat v=2 ok")
        DiagLog.w("PcUplink", "careful")
        assertTrue(h.awaitIdle(3_000))
        val lines = File(dir, "capture_diag.log").readLines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].endsWith(" I PcCapture heartbeat v=2 ok"))
        assertTrue(lines[1].endsWith(" W PcUplink careful"))
        h.close()
    }
}
