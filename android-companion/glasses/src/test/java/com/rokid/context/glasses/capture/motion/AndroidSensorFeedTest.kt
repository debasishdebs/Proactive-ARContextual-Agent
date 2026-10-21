package com.rokid.context.glasses.capture.motion

import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.MotionLabel
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import android.os.HandlerThread
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertFalse
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSensor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AndroidSensorFeedTest {
    private class Reg(val nonWakeUpProx: Boolean, val hasProx: Boolean = true) : SensorRegistry {
        val sensors = mutableMapOf<Int, Sensor>()
        class Call(val type: Int, val period: Int, val maxLatencyUs: Int)
        val calls = mutableListOf<Call>()
        var unregistered = 0
        private fun s(type: Int) = sensors.getOrPut(type) { ShadowSensor.newInstance(type) }
        override fun nonWakeUp(type: Int): Sensor? =
            if (type == Sensor.TYPE_PROXIMITY && !nonWakeUpProx) null else s(type)
        override fun default(type: Int): Sensor? = if (type == Sensor.TYPE_PROXIMITY && !hasProx) null else s(type)
        override fun register(l: SensorEventListener, s: Sensor, samplingPeriodUs: Int, maxReportLatencyUs: Int, h: Handler): Boolean {
            calls += Call(s.type, samplingPeriodUs, maxReportLatencyUs); return true
        }
        override fun unregister(l: SensorEventListener) { unregistered++ }
    }

    private fun run(reg: Reg, wear: WearDetector = WearDetector { 0L }): List<Reg.Call> {
        val feed = AndroidSensorFeed(reg)
        feed.start(MotionClassifier { 0L }, wear)
        feed.stop()
        return reg.calls
    }

    @Test fun everySensorIsRegisteredUnbatched() {
        val calls = run(Reg(nonWakeUpProx = true))
        assertEquals(
            setOf(Sensor.TYPE_GYROSCOPE, Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_PROXIMITY),
            calls.map { it.type }.toSet(),
        )
        assertTrue(calls.all { it.maxLatencyUs == 0 }, "maxReportLatency must be 0 (no FIFO batching)")
        assertTrue(calls.all { it.period == SensorManager.SENSOR_DELAY_GAME })
    }

    @Test fun proximityFallsBackToTheDefaultSensor() {
        val calls = run(Reg(nonWakeUpProx = false))
        assertEquals(1, calls.count { it.type == Sensor.TYPE_PROXIMITY })
        assertEquals(0, calls.single { it.type == Sensor.TYPE_PROXIMITY }.maxLatencyUs)
    }

    @Test fun noProximitySensorMeansWorn() {
        var now = 0L
        val wear = WearDetector { now }
        val reg = Reg(nonWakeUpProx = false, hasProx = false)
        val calls = run(reg, wear)
        assertEquals(0, calls.count { it.type == Sensor.TYPE_PROXIMITY })
        now = 60_000; wear.tick()
        assertTrue(wear.worn.value)
        assertEquals(1, reg.unregistered)
    }

    @Test fun setRateLowReRegistersWithLowPeriodAndLatency() {
        val reg = Reg(nonWakeUpProx = true)
        val feed = AndroidSensorFeed(reg)
        feed.start(MotionClassifier { 0L }, WearDetector { 0L })
        reg.calls.clear()
        feed.setRate(SensorRate.LOW)
        assertEquals(SensorRate.LOW, feed.rate)
        assertEquals(1, reg.unregistered)
        assertEquals(3, reg.calls.size)
        assertTrue(reg.calls.all { it.period == 100_000 && it.maxLatencyUs == 1_000_000 })
        feed.stop()
    }

    @Test fun setRateLowTwiceReRegistersOnce() {
        val reg = Reg(nonWakeUpProx = true)
        val feed = AndroidSensorFeed(reg)
        feed.start(MotionClassifier { 0L }, WearDetector { 0L })
        reg.calls.clear()
        feed.setRate(SensorRate.LOW); feed.setRate(SensorRate.LOW)
        assertEquals(3, reg.calls.size)
        feed.stop()
    }

    @Test fun setRateFullGoesBackToGameUnbatched() {
        val reg = Reg(nonWakeUpProx = true)
        val feed = AndroidSensorFeed(reg)
        feed.start(MotionClassifier { 0L }, WearDetector { 0L })
        feed.setRate(SensorRate.LOW)
        reg.calls.clear()
        feed.setRate(SensorRate.FULL)
        assertEquals(SensorRate.FULL, feed.rate)
        assertEquals(3, reg.calls.size)
        assertTrue(reg.calls.all { it.period == SensorManager.SENSOR_DELAY_GAME && it.maxLatencyUs == 0 })
        feed.stop()
    }

    @Test fun setRateWhileStoppedOnlyRemembers() {
        val reg = Reg(nonWakeUpProx = true)
        val feed = AndroidSensorFeed(reg)
        feed.setRate(SensorRate.LOW)
        assertTrue(reg.calls.isEmpty())
        assertEquals(0, reg.unregistered)
    }

    @Test fun setRateRacingStopNeverThrowsOrReRegistersAfterStop() {
        val reg = Reg(nonWakeUpProx = true)
        val feed = AndroidSensorFeed(reg)
        feed.start(MotionClassifier { 0L }, WearDetector { 0L })
        val flip = Thread { repeat(2000) { feed.setRate(if (it % 2 == 0) SensorRate.LOW else SensorRate.FULL) } }
        var err: Throwable? = null
        flip.setUncaughtExceptionHandler { _, e -> err = e }
        flip.start()
        feed.stop()
        flip.join()
        assertNull(err)
        val callsAtStop = reg.calls.size
        feed.setRate(SensorRate.LOW)
        assertEquals(callsAtStop, reg.calls.size, "a stopped feed registers nothing")
    }

    @Test fun setRateStillRegistersFiftyMsPeriodAndHalfSecondLatency() {
        val reg = Reg(nonWakeUpProx = true)
        val feed = AndroidSensorFeed(reg)
        feed.start(MotionClassifier { 0L }, WearDetector { 0L })
        reg.calls.clear()
        feed.setRate(SensorRate.STILL)
        assertEquals(SensorRate.STILL, feed.rate)
        assertEquals(3, reg.calls.size)
        assertTrue(reg.calls.all { it.period == 50_000 && it.maxLatencyUs == 500_000 })
        feed.stop()
    }

    @Test fun stillToFullGivesGameUnbatched() {
        val reg = Reg(nonWakeUpProx = true)
        val feed = AndroidSensorFeed(reg)
        feed.start(MotionClassifier { 0L }, WearDetector { 0L })
        feed.setRate(SensorRate.STILL)
        reg.calls.clear()
        feed.setRate(SensorRate.FULL)
        assertEquals(3, reg.calls.size)
        assertTrue(reg.calls.all { it.period == SensorManager.SENSOR_DELAY_GAME && it.maxLatencyUs == 0 })
        feed.stop()
    }

    @Test fun stillToLowGivesTenHzOneSecond() {
        val reg = Reg(nonWakeUpProx = true)
        val feed = AndroidSensorFeed(reg)
        feed.start(MotionClassifier { 0L }, WearDetector { 0L })
        feed.setRate(SensorRate.STILL)
        reg.calls.clear()
        feed.setRate(SensorRate.LOW)
        assertEquals(3, reg.calls.size)
        assertTrue(reg.calls.all { it.period == 100_000 && it.maxLatencyUs == 1_000_000 })
        feed.stop()
    }

    @Test fun setRateStillTwiceReRegistersOnce() {
        val reg = Reg(nonWakeUpProx = true)
        val feed = AndroidSensorFeed(reg)
        feed.start(MotionClassifier { 0L }, WearDetector { 0L })
        reg.calls.clear()
        feed.setRate(SensorRate.STILL); feed.setRate(SensorRate.STILL)
        assertEquals(3, reg.calls.size)
        feed.stop()
    }

    private fun tickOnce() {
        val ht = Thread.getAllStackTraces().keys.filterIsInstance<HandlerThread>().first { it.name == "motion-sensors" }
        shadowOf(ht.looper).idleFor(1_100, TimeUnit.MILLISECONDS)
    }

    private fun dir() = java.nio.file.Files.createTempDirectory("feed").toFile()

    @Test fun noCsvWhenLabelNoneAndNotVerbose() {
        val d = dir()
        val feed = AndroidSensorFeed(Reg(true), csvDir = d)
        feed.start(MotionClassifier { 0L }, WearDetector { 0L })
        tickOnce(); feed.stop()
        assertFalse(File(d, "motion_features.csv").exists())
    }

    @Test fun labelEatingWritesOneRowPerTick() {
        val d = dir()
        val feed = AndroidSensorFeed(Reg(true), label = { MotionLabel.EATING }, csvDir = d)
        feed.start(MotionClassifier { 0L }, WearDetector { 0L })
        tickOnce(); feed.stop()
        val lines = File(d, "motion_features.csv").readLines()
        assertEquals(2, lines.size)
        assertTrue(lines[1].contains(",EATING,"))
        assertEquals(15, lines[1].split(",").size)
        assertTrue(lines[1].endsWith(",full"))
    }

    @Test fun shadowModelDoesNotChangeCaptureObservables() {
        fun run(csv: File?, label: MotionLabel): List<Any> {
            val c = MotionClassifier { 0L }
            val w = WearDetector { 0L }
            for (i in 0 until 40) c.onGyro(if (i % 2 == 0) 1.5f else -1.5f, 0f, 0f, i * 20L)
            val feed = AndroidSensorFeed(Reg(true), label = { label }, csvDir = csv)
            feed.start(c, w)
            tickOnce(); feed.stop()
            val f = c.features()
            return listOf(c.state.value, w.worn.value, f.gyroMax, f.accVariance, f.stepHz, f.state, f.glances, f.micro, c.rotationSinceMarkRad())
        }
        val off = run(null, MotionLabel.NONE)
        val on = run(dir(), MotionLabel.WALKING)
        assertEquals(off, on)
    }
}
