package com.rokid.context.glasses.capture.trace

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.os.Handler
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.capture.trace.TraceEvent
import com.rokid.context.glasses.capture.motion.SensorRegistry
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSensorManager
import java.io.File
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** v0.9.7 B item 3: the recording feed is its own sensor registration (own listener, own period, own thread); it touches nothing the classifier feed owns. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SensorRecFeedTest {
    private class Reg(types: List<Int>) : SensorRegistry {
        val sensors = types.associateWith { ShadowSensor.newInstance(it) }
        class R(val l: SensorEventListener, val s: Sensor, val periodUs: Int, val latencyUs: Int)
        val regs = ArrayList<R>(); val unregistered = ArrayList<SensorEventListener>()
        override fun nonWakeUp(type: Int) = sensors[type]
        override fun default(type: Int) = sensors[type]
        override fun register(l: SensorEventListener, s: Sensor, samplingPeriodUs: Int, maxReportLatencyUs: Int, h: Handler): Boolean { regs += R(l, s, samplingPeriodUs, maxReportLatencyUs); return true }
        override fun unregister(l: SensorEventListener) { unregistered += l }
    }

    private val both = listOf(Sensor.TYPE_GYROSCOPE, Sensor.TYPE_LINEAR_ACCELERATION)
    private fun recorder(): Pair<SensorRecorder, SensorRecStore> {
        val store = SensorRecStore(File(TraceTestKit.tempDir(), "sensor_rec"), freeBytes = { Long.MAX_VALUE })
        var t = 1_000L
        return SensorRecorder(store, clock = { t += 20; t }, wallClock = { 1_700_000_000_000L }, io = { it.run() }, log = {}) to store
    }
    private fun event(s: Sensor, ns: Long, x: Float): SensorEvent = ShadowSensorManager.createSensorEvent(3).also {
        SensorEvent::class.java.getField("sensor").apply { isAccessible = true }.set(it, s)
        SensorEvent::class.java.getField("timestamp").apply { isAccessible = true }.setLong(it, ns)
        it.values[0] = x; it.values[1] = 0f; it.values[2] = 0f
    }

    @Test fun registersGyroAndLinearAccelerationAtTheChosenPeriodWithItsOwnListenerAndStopsOnlyThat() {
        val reg = Reg(both); val (rec, _) = recorder(); val feed = SensorRecFeed(reg, rec)
        feed.start(25)
        assertEquals(both.toSet(), reg.regs.map { it.s.type }.toSet())
        assertTrue(reg.regs.all { it.periodUs == 40_000 && it.latencyUs == 2_000_000 })
        assertEquals("one listener for both sensors", 1, reg.regs.map { it.l }.distinct().size)
        feed.start(25); assertEquals("a second start with the same rate is a no-op", 2, reg.regs.size)
        feed.stop()
        assertEquals(listOf(reg.regs[0].l), reg.unregistered)
        feed.stop(); assertEquals("a second stop is a no-op", 1, reg.unregistered.size)
    }

    @Test fun aRateChangeRegistersAgainAtTheNewPeriod() {
        val reg = Reg(both); val (rec, _) = recorder(); val feed = SensorRecFeed(reg, rec)
        feed.start(5); feed.start(10)
        assertEquals(listOf(200_000, 200_000, 100_000, 100_000), reg.regs.map { it.periodUs })
        assertEquals("the old registration was dropped first", 1, reg.unregistered.size)
    }

    @Test fun aMissingSensorIsSkippedNotACrash() {
        val reg = Reg(listOf(Sensor.TYPE_GYROSCOPE)); val (rec, _) = recorder()
        SensorRecFeed(reg, rec).start(10)
        assertEquals(1, reg.regs.size)
    }

    @Test fun theListenerFeedsTheRecorderWithTheSensorTimeInMilliseconds() {
        val reg = Reg(both); val (rec, store) = recorder(); val feed = SensorRecFeed(reg, rec)
        rec.configure(25); feed.start(25)
        val l = reg.regs[0].l
        l.onSensorChanged(event(reg.sensors.getValue(Sensor.TYPE_GYROSCOPE)!!, 5_000_000_000L, 1.5f))
        l.onSensorChanged(event(reg.sensors.getValue(Sensor.TYPE_LINEAR_ACCELERATION)!!, 5_000_000_000L, 2.5f))
        rec.flush()
        val ev = (store.dir.listFiles()!!.single().let { com.rokid.context.capture.trace.TraceFiles.decodeFile(it.readText()).getOrThrow() }).let { com.rokid.context.capture.trace.ChunkCodec.decode(it.traceId, it.seq, it.sha256, it.b64).getOrThrow().events }
        assertEquals(listOf(5_000L), ev.filterIsInstance<TraceEvent.Gyro>().map { it.sMs }); assertEquals(1.5f, ev.filterIsInstance<TraceEvent.Gyro>().single().x)
        assertEquals(2.5f, ev.filterIsInstance<TraceEvent.Accel>().single().x)
    }
}
