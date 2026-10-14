package com.rokid.context.glasses.capture.motion

import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSensor
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
