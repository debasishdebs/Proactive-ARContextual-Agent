package com.rokid.context.core.log

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class RecSink : LogSink {
    val got = CopyOnWriteArrayList<Pair<Line, Route>>()
    override fun emit(line: Line, route: Route) { got += line to route }
}

class PcLogTest {
    private val sink = RecSink()

    @BeforeTest fun setUp() { PcLog.testOnlyResetNeverCallFromProduction() }
    @AfterTest fun tearDown() { PcLog.testOnlyResetNeverCallFromProduction() }

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
            PcLog.testOnlyResetNeverCallFromProduction(); PcLog.install(sink); PcLog.setDiagnostic(d)
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

    @Test fun diagnosticFlagIsAVolatileSnapshotFlippedOutsideLogging() {
        PcLog.install(sink)
        PcLog.d("T", "off"); assertEquals(0, sink.got.size)
        PcLog.setDiagnostic(true); PcLog.d("T", "on"); assertEquals(1, sink.got.size)
        PcLog.setDiagnostic(false); PcLog.d("T", "off again"); assertEquals(1, sink.got.size)
    }

    @Test fun blockedFlagLoaderDoesNotBlockDirectOrPortCalls() {
        PcLog.install(sink)
        val release = CountDownLatch(1); val entered = CountDownLatch(1)
        // the flag owner loads the persisted flag on ITS thread and then publishes the snapshot
        val loader = Thread { entered.countDown(); release.await(120, TimeUnit.SECONDS); PcLog.setDiagnostic(true) }
            .apply { isDaemon = true; start() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val caller = Thread { PcLog.e("T", "direct"); PcLog.port("canvas").log(Level.E, "T", "port") }.apply { isDaemon = true; start() }
            caller.join(30_000)   // shorter than the loader's own 120 s wait: logging can only finish if it never waited for the loader
            assertFalse(caller.isAlive, "logging waited for the flag loader")
            assertEquals(2, sink.got.size)
            assertTrue(sink.got.all { it.second == Route(true, true) })   // W/E reach both outputs while the flag is unknown
        } finally { release.countDown(); loader.join(5_000) }
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
        PcLog.install(bad)
        PcLog.e("T", "x"); PcLog.w("T", "y", RuntimeException("secret text"))
        PcLog.port("a").log(Level.E, "T", "z")
        assertEquals(3, calls)
        PcLog.i("T", "x".repeat(2_000_000))
    }

    @Test fun throwableLogsClassNameOnly() {
        PcLog.install(sink)
        PcLog.w("T", "failed", IllegalArgumentException("user secret text 12345"))
        PcLog.e("T", "failed2", java.io.IOException("path /sdcard/private.jpg"))
        assertEquals(listOf("failed IllegalArgumentException", "failed2 IOException"), sink.got.map { it.first.msg })
    }

    @Test fun throwableClassNameSurvivesAHugeMessage() {
        PcLog.install(sink)
        PcLog.e("T", "m".repeat(100_000), IllegalStateException("x"))
        assertTrue(sink.got.single().first.msg.endsWith(" IllegalStateException"))
    }

    @Test fun preInstallKeepsExactlyTheLast256InOrder() {
        repeat(300) { PcLog.e("T", "early $it") }
        val buf = PcLog.bufferedForTest()
        assertEquals(PcLog.RING_CAPACITY, buf.size)
        assertEquals("early 44", buf.first().msg); assertEquals("early 299", buf.last().msg)
        PcLog.install(sink)
        assertEquals((44..299).map { "early $it" }, sink.got.map { it.first.msg })
        assertEquals(0, PcLog.bufferedForTest().size)
    }

    @Test fun preInstallRecordsAreRoutedAtReplayWithTheDiagnosticSnapshotThen() {
        PcLog.d("T", "dbg"); PcLog.i("T", "info")
        PcLog.setDiagnostic(true)                 // diagnostics are on by the time the sink is installed
        PcLog.install(sink)
        assertEquals(listOf("dbg" to Route(true, true), "info" to Route(true, true)), sink.got.map { it.first.msg to it.second })
    }

    @Test fun preInstallDebugIsDroppedAtReplayWhenDiagnosticsAreOff() {
        PcLog.d("T", "dbg"); PcLog.i("T", "info")
        PcLog.install(sink)
        assertEquals(listOf("info" to Route(true, false)), sink.got.map { it.first.msg to it.second })
    }

    @Test fun liveCallsDuringReplayDoNotOvertakeEarlyLines() {
        val order = CopyOnWriteArrayList<String>()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val slow = object : LogSink {
            override fun emit(line: Line, route: Route) {
                if (line.msg == "early1") { entered.countDown(); release.await(30, TimeUnit.SECONDS) }
                order += line.msg
            }
        }
        PcLog.e("T", "early1"); PcLog.e("T", "early2")
        val installer = Thread { PcLog.install(slow) }.apply { isDaemon = true; start() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            PcLog.e("T", "live")                       // replay is in progress: must queue behind the early lines
            assertEquals(emptyList(), order.toList())
        } finally { release.countDown(); installer.join(10_000) }
        assertEquals(listOf("early1", "early2", "live"), order.toList())
        PcLog.e("T", "later"); assertEquals("later", order.last())
    }

    @Test fun concurrentInstallPublishesExactlyOneSink() {
        val a = RecSink(); val b = RecSink()
        val go = CountDownLatch(1); val results = CopyOnWriteArrayList<Boolean>()
        val ts = listOf(a, b).map { s -> Thread { go.await(); results += PcLog.install(s) }.apply { start() } }
        go.countDown(); ts.forEach { it.join(10_000) }
        assertEquals(listOf(false, true), results.sorted())
        PcLog.e("T", "x")
        assertEquals(1, a.got.size + b.got.size)
    }

    @Test fun installIsOnce() {
        val other = RecSink()
        assertTrue(PcLog.install(sink)); assertFalse(PcLog.install(other))
        PcLog.i("T", "x")
        assertEquals(1, sink.got.size); assertEquals(0, other.got.size)
    }

    @Test fun throwingSinkDuringReplayOnInstallDoesNotThrow() {
        PcLog.e("T", "early")
        PcLog.install(object : LogSink { override fun emit(line: Line, route: Route) { throw IllegalStateException("boom") } })
    }

    @Test fun recordsAreByteBoundedInRingAndSink() {
        val huge = "é€😀".repeat(50_000)
        PcLog.e(huge, huge)
        val ring = PcLog.bufferedForTest().single()
        assertTrue(ring.tag.toByteArray().size <= Line.MAX_TAG_BYTES && ring.msg.toByteArray().size <= Line.MAX_MSG_BYTES)
        assertFalse(ring.msg.contains('�')); assertEquals(ring.msg, String(ring.msg.toByteArray(), Charsets.UTF_8))
        PcLog.install(sink)
        assertTrue(sink.got.single().first.msg.toByteArray().size <= Line.MAX_MSG_BYTES)
    }

    @Test fun milestoneIsAnInfoLineThatAlwaysReachesTheFile() {
        PcLog.install(sink)
        PcLog.milestone("T", "m-off"); PcLog.i("T", "plain-off")
        PcLog.setDiagnostic(true)
        PcLog.milestone("T", "m-on")
        assertEquals(
            listOf(Triple("m-off", Level.I, Route(true, true)), Triple("plain-off", Level.I, Route(true, false)), Triple("m-on", Level.I, Route(true, true))),
            sink.got.map { Triple(it.first.msg, it.first.level, it.second) },
        )
        assertEquals(listOf(true, false, true), sink.got.map { it.first.milestone })
    }

    @Test fun milestoneBeforeInstallKeepsItsFlagAtReplay() {
        PcLog.milestone("T", "early"); PcLog.i("T", "plain")
        PcLog.install(sink)   // diagnostics off
        assertEquals(listOf("early" to Route(true, true), "plain" to Route(true, false)), sink.got.map { it.first.msg to it.second })
    }

    @Test fun milestoneIsByteBoundedLikeEveryLine() {
        PcLog.install(sink)
        PcLog.milestone("é€😀".repeat(100), "é€😀".repeat(50_000))
        val l = sink.got.single().first
        assertTrue(l.tag.toByteArray().size <= Line.MAX_TAG_BYTES && l.msg.toByteArray().size <= Line.MAX_MSG_BYTES)
    }

    @Test fun milestoneWritesTheFileWithDiagnosticsOffAndRotatesLikeAnyLine() {
        val dir = kotlin.io.path.createTempDirectory("ms").toFile()
        val file = java.io.File(dir, LogFiles.PCA_LOG)
        val writer = AsyncLineWriter(file, maxBytes = 4_000)
        // the phone sink's rule, executed on a real writer: logcat is not under test here, the file is
        PcLog.install(object : LogSink { override fun emit(line: Line, route: Route) { if (route.file) writer.append(line) } })
        try {
            PcLog.i("T", "info-hidden"); PcLog.d("T", "debug-hidden")
            PcLog.milestone("IndexMaintenance", "index build start: space=space_x model=m text_total=3 text_todo=3")
            assertTrue(writer.awaitIdle(10_000))
            val text = file.readText()
            assertTrue(text.contains(" I IndexMaintenance index build start: space=space_x model=m text_total=3 text_todo=3"), text)
            assertFalse(text.contains("info-hidden")); assertFalse(text.contains("debug-hidden"))
            repeat(200) { PcLog.milestone("T", "milestone $it ".padEnd(100, 'x')) }   // far past the 4 KB cap: the writer rotates, the file stays bounded
            assertTrue(writer.awaitIdle(10_000))
            val rotated = java.io.File(dir, LogFiles.PCA_LOG + ".1")
            assertTrue(rotated.exists(), "rotated")
            assertTrue(file.length() + rotated.length() <= 4_000, "bounded: ${file.length()} + ${rotated.length()}")
        } finally { writer.close(drain = false) }
    }

    @Test fun disabledDebugDoesNoWork() {
        PcLog.install(sink)
        repeat(200_000) { PcLog.d("T", "chatty") }
        assertEquals(0, sink.got.size); assertEquals(0, PcLog.bufferedForTest().size)
    }
}
