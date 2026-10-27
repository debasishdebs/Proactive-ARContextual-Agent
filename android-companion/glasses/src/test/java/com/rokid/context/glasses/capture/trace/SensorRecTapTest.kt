package com.rokid.context.glasses.capture.trace

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.os.Handler
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.glasses.capture.motion.AndroidSensorFeed
import com.rokid.context.glasses.capture.motion.MotionClassifier
import com.rokid.context.glasses.capture.motion.SensorRate
import com.rokid.context.glasses.capture.motion.SensorRegistry
import com.rokid.context.glasses.capture.motion.WearDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSensorManager
import java.io.File

/** v0.9.7 B item 3 (review fix): the recording is a read-only tap on what the classifier's feed already delivers: no registration of its own, the classifier is byte-identical with it on or off, and it never records faster than the delivery. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SensorRecTapTest {
    private class Reg : SensorRegistry {
        val sensors = listOf(Sensor.TYPE_GYROSCOPE, Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_PROXIMITY).associateWith { ShadowSensor.newInstance(it) }
        val calls = ArrayList<Triple<Int, Int, Int>>(); val listeners = ArrayList<SensorEventListener>()
        override fun nonWakeUp(type: Int) = sensors[type]
        override fun default(type: Int) = sensors[type]
        override fun register(l: SensorEventListener, s: Sensor, samplingPeriodUs: Int, maxReportLatencyUs: Int, h: Handler): Boolean { calls += Triple(s.type, samplingPeriodUs, maxReportLatencyUs); listeners += l; return true }
        override fun unregister(l: SensorEventListener) = Unit
    }

    private fun event(s: Sensor, ns: Long, x: Float): SensorEvent = ShadowSensorManager.createSensorEvent(3).also {
        SensorEvent::class.java.getField("sensor").apply { isAccessible = true }.set(it, s)
        SensorEvent::class.java.getField("timestamp").apply { isAccessible = true }.setLong(it, ns)
        it.values[0] = x; it.values[1] = 0.1f; it.values[2] = 0f
    }

    private fun recorder(): Pair<SensorRecorder, SensorRecStore> {
        val store = SensorRecStore(File(TraceTestKit.tempDir(), "sensor_rec"), freeBytes = { Long.MAX_VALUE })
        var t = 1_000L
        return SensorRecorder(store, clock = { t += 20; t }, wallClock = { 1_700_000_000_000L }, io = { it.run() }, log = {}) to store
    }

    /** Runs the real feed with [rate] and [seconds] of events every [stepMs]; returns what was registered and the classifier's features. */
    private fun run(rate: SensorRate, stepMs: Int, seconds: Int, tap: SensorRecorder?): Pair<List<Triple<Int, Int, Int>>, MotionClassifier.Features> {
        val reg = Reg(); var now = 0L
        val c = MotionClassifier { now }
        tap?.let { c.addTap(it) }
        val feed = AndroidSensorFeed(reg)
        feed.setRate(rate); feed.start(c, WearDetector { now })
        val l = reg.listeners.first()
        val g = reg.sensors.getValue(Sensor.TYPE_GYROSCOPE); val a = reg.sensors.getValue(Sensor.TYPE_LINEAR_ACCELERATION)
        var ms = 0
        while (ms < seconds * 1000) { now = ms.toLong(); l.onSensorChanged(event(g, ms * 1_000_000L, 0.3f + (ms % 7) * 0.1f)); l.onSensorChanged(event(a, ms * 1_000_000L, 0.2f + (ms % 5) * 0.1f)); ms += stepMs }
        val f = c.features(); feed.stop()
        return reg.calls to f
    }

    @Test fun theClassifierIsByteIdenticalWithTheRecordingOnOrOffAtEveryRate() {
        for ((rate, step) in listOf(SensorRate.FULL to 20, SensorRate.STILL to 50, SensorRate.LOW to 100)) {
            val (offCalls, offF) = run(rate, step, 6, null)
            val (rec, _) = recorder(); rec.configure(25)
            val (onCalls, onF) = run(rate, step, 6, rec)
            assertEquals("$rate registrations", offCalls, onCalls)
            assertEquals("$rate classifier output", dump(offF), dump(onF))
            assertTrue("$rate: the tap did see samples", rec.buffered() > 0)
        }
    }

    @Test fun theRecordingNeverRunsFasterThanTheDeliveryAndNeverFasterThanChosen() {
        // delivered 10 Hz (LOW), chosen 25: effective 10; delivered 50 Hz, chosen 5: effective 5
        val (r1, s1) = recorder(); r1.configure(25); run(SensorRate.LOW, 100, 10, r1); r1.flush()
        val n1 = count(s1)
        assertTrue("min(chosen, delivered) = 10 Hz, got $n1 per sensor", n1 in 95..100)
        val (r2, s2) = recorder(); r2.configure(5); run(SensorRate.FULL, 20, 10, r2); r2.flush()
        val n2 = count(s2)
        assertTrue("chosen 5 Hz, got $n2 per sensor", n2 in 48..52)
    }

    private fun dump(f: Any): String = f.javaClass.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }.sortedBy { it.name }.joinToString(";") { it.isAccessible = true; "${it.name}=${it.get(f)}" }

    private fun count(s: SensorRecStore): Int = (s.dir.listFiles() ?: emptyArray()).sumOf { f ->
        com.rokid.context.capture.trace.TraceFiles.decodeFile(f.readText()).getOrThrow().let { c -> com.rokid.context.capture.trace.ChunkCodec.decode(c.traceId, c.seq, c.sha256, c.b64).getOrThrow().events }
            .count { it is com.rokid.context.capture.trace.TraceEvent.Gyro }
    }

    @Test fun theFeedRegistersTheSameThreeSensorsOnceWhateverTheRecorderDoes() {
        val (rec, _) = recorder(); rec.configure(25)
        val (calls, _) = run(SensorRate.FULL, 20, 1, rec)
        assertEquals(setOf(Sensor.TYPE_GYROSCOPE, Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_PROXIMITY), calls.map { it.first }.toSet())
        assertEquals(3, calls.size)
    }
}
