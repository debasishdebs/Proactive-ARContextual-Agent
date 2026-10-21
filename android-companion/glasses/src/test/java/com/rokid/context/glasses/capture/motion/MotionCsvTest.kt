package com.rokid.context.glasses.capture.motion

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MotionCsvTest {
    private fun dir() = java.nio.file.Files.createTempDirectory("mcsv").toFile()
    private val row = "1000,0.100,0.050,0.060,0.300,0.020,1.500,0.200,1,2,STILL,heuristic-v1,STILL,EATING,full"

    @Test fun headerThenRows() {
        val d = dir(); val c = MotionCsv(d)
        c.append(row)
        val f = File(d, "motion_features.csv")
        assertEquals(listOf(MotionCsv.HEADER, row), f.readLines())
        c.append(row)
        assertEquals(3, f.readLines().size)
        assertEquals("t_ms,gyro_max,gyro_mean,gyro_rms,above_on_frac,acc_var,step_hz,rot_rad,glances,micro,state,model,fused,label,rate", MotionCsv.HEADER)
        assertEquals(0, c.failures)
    }

    @Test fun emptyExistingFileGetsHeader() {
        val d = dir(); File(d, "motion_features.csv").writeText("")
        MotionCsv(d).append(row)
        assertEquals(MotionCsv.HEADER, File(d, "motion_features.csv").readLines().first())
    }

    @Test fun rotatesAtMaxBytes() {
        val d = dir(); val c = MotionCsv(d, maxBytes = 200)
        repeat(6) { c.append(row) }
        val f = File(d, "motion_features.csv"); val old = File(d, "motion_features.csv.1")
        assertTrue(old.exists())
        assertEquals(MotionCsv.HEADER, f.readLines().first())
        assertEquals(MotionCsv.HEADER, old.readLines().first())
        assertTrue(f.length() < 200 + row.length + 2)
        assertTrue(f.readLines().size >= 2, "the newest row is in the live file")
        assertTrue(old.length() >= 200, "the rotated file holds what filled the limit")
    }

    @Test fun neverThrows() {
        val bad = File(dir(), "nope").also { it.writeText("x") } // a file, not a dir
        val c = MotionCsv(bad)
        c.append(row)
        assertEquals(1, c.failures)
    }

    @Test fun freeTextRowsAreRefusedAndNeverWritten() {
        val d = dir(); val c = MotionCsv(d)
        c.append(row)
        for (bad in listOf("1000,hello world", "1000,a;b", "1000,\"quoted\"", "1000,caf\u00e9", "1000,x\ny")) c.append(bad)
        assertEquals(5, c.failures)
        assertEquals(listOf(MotionCsv.HEADER, row), File(d, "motion_features.csv").readLines())
    }
}
