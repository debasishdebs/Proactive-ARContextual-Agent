package com.rokid.context.glasses.capture.diag

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagFileTest {
    private val dir = kotlin.io.path.createTempDirectory("diag").toFile()

    @Test fun writesTaggedLinesOnePerEvent() {
        val f = DiagFile(File(dir, "capture_diag.log"), wallClock = { 0L })
        f.append('I', "PcCapture", "status running=true")
        f.append('W', "PcUplink", "two\nlines")
        val lines = File(dir, "capture_diag.log").readLines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].endsWith("I PcCapture status running=true"))
        assertTrue(lines[1].endsWith("W PcUplink two lines"))
    }

    @Test fun rotatesToTwoFilesAndStaysWithinTheBudget() {
        val f = DiagFile(File(dir, "capture_diag.log"), maxBytes = 2_000)
        repeat(200) { f.append('I', "PcCapture", "line $it ".padEnd(60, 'x')) }
        val a = File(dir, "capture_diag.log"); val b = File(dir, "capture_diag.log.1")
        assertTrue(b.exists())
        assertTrue(a.length() + b.length() <= 2_000 + 100, "total ${a.length() + b.length()}")
        assertEquals(2, dir.listFiles()!!.size)
        assertTrue(a.readLines().last().contains("line 199"))
    }
}
