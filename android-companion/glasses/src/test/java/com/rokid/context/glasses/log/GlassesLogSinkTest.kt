package com.rokid.context.glasses.log

import com.rokid.context.core.log.AsyncLineWriter
import com.rokid.context.core.log.Level
import com.rokid.context.core.log.Line
import com.rokid.context.core.log.LogFs
import com.rokid.context.core.log.RealLogFs
import com.rokid.context.core.log.Route
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GlassesLogSinkTest {
    private val dir = kotlin.io.path.createTempDirectory("glsink").toFile()
    private val main get() = File(dir, "capture_diag.log")
    private val older get() = File(dir, "capture_diag.log.1")

    private class Logcat { val calls = mutableListOf<Triple<Level, String, String>>(); val fn: (Level, String, String) -> Unit = { l, t, m -> calls += Triple(l, t, m) } }

    private fun sink(maxBytes: Long = GlassesLogSink.MAX_BYTES, logcat: Logcat = Logcat()) =
        GlassesLogSink(AsyncLineWriter(main, maxBytes = maxBytes), logcat.fn)

    /** The pre-LGC3 DiagFile.append format, kept here as the oracle the new writer must match byte for byte. */
    private fun oldFormat(wall: Long, level: Char, tag: String, msg: String) =
        "${SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(wall))} $level $tag ${msg.replace('\n', ' ')}\n"

    @Test fun nameAndBudgetAreTheOnesTheToolsRead() {
        assertEquals("capture_diag.log", GlassesLogSink.FILE_NAME)
        assertEquals(3_000_000L, GlassesLogSink.MAX_BYTES)
    }

    @Test fun routingMatrixExecutesTheRouteAndNeverDecides() {
        for (logcatOn in listOf(false, true)) for (fileOn in listOf(false, true)) {
            main.delete(); older.delete()
            val lc = Logcat(); val s = sink(logcat = lc)
            s.emit(Line(Level.I, "PcCapture", "m", 5L), Route(logcat = logcatOn, file = fileOn))
            assertTrue(s.awaitIdle(2_000))
            assertEquals(if (logcatOn) 1 else 0, lc.calls.size, "logcat for $logcatOn/$fileOn")
            assertEquals(fileOn, main.exists(), "file for $logcatOn/$fileOn")
            s.close()
        }
    }

    @Test fun logcatReceivesLevelTagAndMessageUnchanged() {
        val lc = Logcat(); val s = sink(logcat = lc)
        s.emit(Line(Level.W, "PcUplink", "two\nlines", 0L), Route(true, false))
        assertEquals(listOf(Triple(Level.W, "PcUplink", "two\nlines")), lc.calls)
        s.close()
    }

    @Test fun lineFormatIsByteIdenticalToTheOldDiagFile() {
        val s = sink()
        val cases = listOf(
            Triple(Level.I, "PcCapture", "status running=true reason=OK"),
            Triple(Level.W, "PcUplink", "two\nlines"),
            Triple(Level.I, "PcCapture", "heartbeat v=2 plan=abc"),
        )
        cases.forEachIndexed { i, (l, t, m) -> s.emit(Line(l, t, m, 1_700_000_000_000L + i * 1234), Route(false, true)) }
        assertTrue(s.awaitIdle(2_000))
        val expected = cases.mapIndexed { i, (l, t, m) -> oldFormat(1_700_000_000_000L + i * 1234, l.name[0], t, m) }.joinToString("")
        assertEquals(expected, main.readText(Charsets.UTF_8))
        // what tools/battery-run.sh and tools/m1-run.sh match: an MM-DD HH:MM:SS.mmm prefix and the literal 'heartbeat v=2'
        val lines = main.readLines()
        assertTrue(lines.all { Regex("""^\d\d-\d\d \d\d:\d\d:\d\d\.\d{3} [IW] \S+ .*""").matches(it) })
        assertEquals(1, lines.count { it.contains("heartbeat v=2") })
        s.close()
    }

    @Test fun rotatesToTwoFilesAndStaysWithinTheScaledBudget() {
        val s = GlassesLogSink.create(dir, maxBytes = 4_000)      // the production factory and its constructor seam
        repeat(200) { s.emit(Line(Level.I, "PcCapture", "line $it ".padEnd(60, 'x'), 0L), Route(false, true)) }
        assertTrue(s.awaitIdle(5_000))
        assertTrue(older.exists())
        assertTrue(main.length() + older.length() <= 4_000, "total ${main.length() + older.length()}")
        assertEquals(setOf("capture_diag.log", "capture_diag.log.1"), dir.listFiles()!!.map { it.name }.toSet())
        assertTrue(main.readLines().last().contains("line 199"))
        s.close()
    }

    @Test fun clearAndFenceAreTheWriterApiAndWorkOnBothRotations() {
        val s = sink(maxBytes = 4_000)
        repeat(100) { s.emit(Line(Level.I, "PcCapture", "x".repeat(60), 0L), Route(false, true)) }
        assertTrue(s.awaitIdle(5_000)); assertTrue(older.exists())
        assertTrue(s.fence().get(2, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(s.clear().get(2, java.util.concurrent.TimeUnit.SECONDS))
        assertFalse(main.exists()); assertFalse(older.exists())
        s.close()
    }

    @Test fun diskFailureNeverReachesTheCaller() {
        val failing = object : LogFs by RealLogFs { override fun append(f: File, bytes: ByteArray) { throw java.io.IOException("disk") } }
        val s = GlassesLogSink.create(dir, fs = failing)
        s.emit(Line(Level.W, "PcCapture", "m", 0L), Route(false, true))
        s.close()
    }
}
