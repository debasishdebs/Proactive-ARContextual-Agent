package com.rokid.context.glasses.capture.trace

import com.rokid.context.capture.trace.TraceEvent
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.TraceControl
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** v0.9.7 B item 3: recorded sensor data is covered by the trace deletion rules (new epoch = Forget all / Delete all, range Forget), is kept out of backup, never reaches `traces/`, and is not in any settings hash. */
class SensorRecDeletionTest {
    private val root = TraceTestKit.tempDir()
    private val clock = ManualClock()
    private val wall = 1_700_000_000_000
    private var hooks = 0
    @AfterTest fun cleanup() { root.deleteRecursively() }

    private fun recorder() = TraceRecorder(root, clock, { wall + clock.t }, { "7" }, 1, io = { it.run() }, newTraceId = { "trace-0001-test" }, flushBytes = 2_000, diagEnabled = { false })
    private fun ev(n: Int) = (0 until n).map { TraceEvent.Gyro(100L + it, 100L + it, 0f, 0f, 0f) }
    private fun sensorFiles() = File(root, "sensor_rec").listFiles()?.map { it.name }?.sorted() ?: emptyList()

    @Test fun aNewEpochWipesTheBatchesAndTellsTheSensorRecorderToDropItsBuffer() {
        val r = recorder(); r.onCleanupQueued = { hooks++ }
        r.onControl(TraceTestKit.collect(install = "inst-a", counter = 1))
        r.sensorStore.write(wall + 1_000, wall + 2_000, 0, ev(5)); assertEquals(1, sensorFiles().size)
        val before = hooks
        r.onControl(TraceTestKit.collect(install = "inst-a", counter = 2))
        assertTrue(hooks > before, "the buffer hook ran")
        assertTrue(sensorFiles().isEmpty(), "the batches are gone: ${sensorFiles()}")
    }

    @Test fun aRangeForgetDeletesOverlappingBatchesAndKeepsOthers() {
        val r = recorder(); r.onCleanupQueued = { hooks++ }
        r.onControl(TraceTestKit.collect(install = "inst-a", counter = 1))
        r.sensorStore.write(wall + 1_000, wall + 2_000, 0, ev(5)); r.sensorStore.write(wall + 900_000, wall + 901_000, 1, ev(5))
        val before = hooks
        r.onControl(TraceTestKit.collect(install = "inst-a", counter = 1).copy(atMs = wall + clock.t, forgetFromMs = wall + 1_500, forgetToMs = wall + 1_800))
        assertTrue(hooks > before)
        assertEquals(1, sensorFiles().size); assertTrue(sensorFiles().single().startsWith("s-${wall + 900_000}-"))
    }

    @Test fun theRecordingIsNotPartOfAnySettingsHashAndNeverMakesAnImportableTrace() {
        assertEquals(TraceSettingsHash.of(HudSettings()), TraceSettingsHash.of(HudSettings(sensorRecHz = 25)))
        val r = recorder(); r.sensorStore.write(wall, wall + 1, 0, ev(3))
        assertTrue(r.exportableTraceIds().isEmpty()); assertFalse(File(root, "traces").exists())
    }

    @Test fun theRecordingCodeNeverNamesTheClassifierTheSessionOrTheRatePolicy() {
        val dir = File("src/main/java/com/rokid/context/glasses/capture/trace")
        val forbidden = Regex("MotionClassifier|AndroidSensorFeed|SensorFeed\\b|SensorRate|RatePolicy|CaptureSession|KeyframeGate|MotionModel|WearDetector")
        for (f in listOf("SensorRecorder.kt", "SensorRecFeed.kt", "SensorRecStore.kt", "SensorRecPolicy.kt")) {
            val text = File(dir, f).readText().lines().filter { !it.trimStart().startsWith("*") && !it.trimStart().startsWith("/*") && !it.trimStart().startsWith("//") }.joinToString("\n")
            assertFalse(forbidden.containsMatchIn(text), "$f must not touch the classifier path: ${forbidden.find(text)?.value}")
        }
        // the classifier's own feed is not changed to know about the recording
        val sensorFeed = File("src/main/java/com/rokid/context/glasses/capture/motion/SensorFeed.kt").readText()
        assertFalse(sensorFeed.contains("SensorRec") || sensorFeed.contains("sensorRec"))
    }

    @Test fun theBatchFolderSitsUnderTheBackupExcludedImuRoot() {
        assertEquals(File(root, "sensor_rec"), TraceStorage(root).sensor.dir)
        assertTrue(File("src/main/java/com/rokid/context/glasses/GlassesRuntime.kt").readText().contains("root = File(app.filesDir, \"imu\")"))
    }
}
