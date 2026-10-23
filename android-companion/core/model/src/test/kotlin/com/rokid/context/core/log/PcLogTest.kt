package com.rokid.context.core.log

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class RecSink : LogSink {
    val got = CopyOnWriteArrayList<Pair<Line, Route>>()
    override fun emit(line: Line, route: Route) { got += line to route }
}

class PcLogTest {
    @Volatile private var diag = false
    private val sink = RecSink()

    @BeforeTest fun setUp() { PcLog.resetForTest(); diag = false }
    @AfterTest fun tearDown() { PcLog.resetForTest() }

    /** The table is written out literally here (not via LogPolicy) so the test pins the contract itself. */
    private fun expected(level: Level, diag: Boolean): Route = when (level) {
        Level.D -> if (diag) Route(logcat = true, file = true) else Route(logcat = false, file = false)
        Level.I -> if (diag) Route(logcat = true, file = true) else Route(logcat = true, file = false)
        Level.W, Level.E -> Route(logcat = true, file = true)
    }

    private fun direct(level: Level, msg: String) = when (level) {
        Level.D -> PcLog.d("T", msg); Level.I -> PcLog.i("T", msg)
        Level.W -> PcLog.w("T", msg); Level.E -> PcLog.e("T", msg)
    }

    @Test fun gateMatrixSameForDirectAndPort() {
        val viaPort: LogPort = PcLog.port("canvas")
        val viaPort3b: LogPort = PcLog.port("phone")
        for (d in listOf(false, true)) for (level in Level.values()) {
            diag = d
            PcLog.resetForTest(); PcLog.install(sink, { diag })
            sink.got.clear()
            direct(level, "direct")
            viaPort.log(level, "T", "port")
            viaPort3b.log(level, "T", "port2")
            val want = expected(level, d)
            val wantCount = if (want.logcat || want.file) 3 else 0
            assertEquals(wantCount, sink.got.size, "level=$level diag=$d")
            sink.got.forEach { assertEquals(want, it.second, "level=$level diag=$d msg=${it.first.msg}") }
        }
    }

    @Test fun noApiAcceptsSinkAsCallTarget() {
        assertFalse(LogSink::class.java.isAssignableFrom(LogPort::class.java))
        assertFalse(LogPort::class.java.isAssignableFrom(LogSink::class.java))
        assertFalse(LogSink::class.java.isAssignableFrom(PcLog.port("x").javaClass))
        assertFalse(LogSink::class.java.isAssignableFrom(PcLog::class.java))
        val takesSink = PcLog::class.java.methods.filter { m -> m.parameterTypes.any { LogSink::class.java.isAssignableFrom(it) } }
        assertEquals(setOf("install"), takesSink.map { it.name }.toSet(), "only install may receive a sink")
        val ret = PcLog::class.java.methods.filter { LogSink::class.java.isAssignableFrom(it.returnType) }
        assertTrue(ret.isEmpty(), "no PcLog API hands a sink out")
    }

    @Test fun pcLogNeverThrows() {
        var calls = 0
        val bad = object : LogSink { override fun emit(line: Line, route: Route) { calls++; throw IllegalStateException("boom") } }
        PcLog.install(bad, { throw IllegalStateException("policy boom") })
        PcLog.e("T", "x"); PcLog.w("T", "y", RuntimeException("secret text"))
        PcLog.port("a").log(Level.E, "T", "z")
        assertTrue(calls >= 1, "an error line still reaches the sink even if diag lookup throws (diag treated as off)")
        // a null-ish / huge message must not throw either
        PcLog.i("T", "x".repeat(2_000_000))
    }

    @Test fun throwableLogsClassNameOnly() {
        PcLog.install(sink, { false })
        PcLog.w("T", "failed", IllegalArgumentException("user secret text 12345"))
        PcLog.e("T", "failed2", java.io.IOException("path /sdcard/private.jpg"))
        val msgs = sink.got.map { it.first.msg }
        assertEquals(listOf("failed IllegalArgumentException", "failed2 IOException"), msgs)
    }

    @Test fun preInstallLinesAreBoundedThenReplayedOnInstall() {
        repeat(5_000) { PcLog.i("T", "early $it") }
        assertTrue(PcLog.bufferedForTest().size <= PcLog.RING_CAPACITY)
        PcLog.install(sink, { false })
        assertTrue(sink.got.size in 1..PcLog.RING_CAPACITY)
        assertEquals("early 4999", sink.got.last().first.msg)
        assertEquals(0, PcLog.bufferedForTest().size)
    }

    @Test fun throwingSinkDuringReplayOnInstallDoesNotThrow() {
        PcLog.e("T", "early")
        PcLog.install(object : LogSink { override fun emit(line: Line, route: Route) { throw IllegalStateException("boom") } }, { false })
    }

    @Test fun installIsOnce() {
        val other = RecSink()
        assertTrue(PcLog.install(sink, { false }))
        assertFalse(PcLog.install(other, { true }))
        PcLog.i("T", "x")
        assertEquals(1, sink.got.size); assertEquals(0, other.got.size)
    }

    @Test fun disabledDebugDoesNoWork() {
        var diagReads = 0
        PcLog.install(sink, { diagReads++; false })
        repeat(200_000) { PcLog.d("T", "chatty") }
        assertEquals(0, sink.got.size)
        assertEquals(0, PcLog.bufferedForTest().size)
        assertTrue(diagReads <= 200_000, "at most one diag read per call")
    }
}
