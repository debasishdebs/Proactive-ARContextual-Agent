package com.rokid.context.glasses.capture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The 1 s glimpse promise cannot absorb a sensor FIFO delay: every IMU/proximity listener is registered unbatched (maxReportLatency 0). */
class SensorFeedSourceRulesTest {
    @Test fun sensorsAreRegisteredWithMaxReportLatencyZero() {
        val src = File("src/main/java/com/rokid/context/glasses/capture/motion/SensorFeed.kt").readText()
        val calls = Regex("""\.registerListener\(([^)]*)\)""").findAll(src).map { it.groupValues[1] }.toList()
        assertTrue("one registerListener call expected, saw $calls", calls.size == 1)
        assertTrue(calls[0], Regex("""SENSOR_DELAY_GAME,\s*0,""").containsMatchIn(calls[0]))
    }
}
