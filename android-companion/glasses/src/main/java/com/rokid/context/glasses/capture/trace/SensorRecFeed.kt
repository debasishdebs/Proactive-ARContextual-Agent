package com.rokid.context.glasses.capture.trace

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import com.rokid.context.glasses.capture.motion.SensorRegistry

/**
 * The developer sensor recording's OWN sensor registration (v0.9.7 B): one listener on its own thread for gyroscope and linear acceleration at the chosen rate, with a 2 s report latency so the
 * hardware batches (fewer wake-ups). It shares nothing with the classifier's `SensorFeed` (that class, its listener, its rate policy and its classifier are not touched); its samples go only to
 * the [SensorRecorder]. Start and stop are idempotent; a missing sensor is skipped.
 */
class SensorRecFeed(private val sm: SensorRegistry, private val rec: SensorRecorder) {
    private var thread: HandlerThread? = null
    private var listener: SensorEventListener? = null
    private var hz = 0

    constructor(context: Context, rec: SensorRecorder) : this(ManagerRegistry(context.getSystemService(Context.SENSOR_SERVICE) as SensorManager), rec)

    @Synchronized fun start(rateHz: Int) {
        if (rateHz <= 0) { stop(); return }
        if (listener != null && hz == rateHz) return
        stop()
        val ht = HandlerThread("sensor-rec").also { it.start() }
        val h = Handler(ht.looper)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val t = e.timestamp / 1_000_000L
                val v = e.values
                when (e.sensor.type) {
                    Sensor.TYPE_GYROSCOPE -> rec.onGyro(v[0], v[1], v[2], t)
                    Sensor.TYPE_LINEAR_ACCELERATION -> rec.onAccel(v[0], v[1], v[2], t)
                }
            }
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) = Unit
        }
        for (type in listOf(Sensor.TYPE_GYROSCOPE, Sensor.TYPE_LINEAR_ACCELERATION)) {
            val s = sm.nonWakeUp(type) ?: continue
            sm.register(l, s, 1_000_000 / rateHz, LATENCY_US, h)
        }
        thread = ht; listener = l; hz = rateHz
    }

    @Synchronized fun stop() {
        listener?.let { sm.unregister(it) }
        thread?.quitSafely()
        thread = null; listener = null; hz = 0
    }

    private class ManagerRegistry(private val sm: SensorManager) : SensorRegistry {
        override fun nonWakeUp(type: Int): Sensor? = sm.getDefaultSensor(type, false)
        override fun default(type: Int): Sensor? = sm.getDefaultSensor(type)
        override fun register(l: SensorEventListener, s: Sensor, samplingPeriodUs: Int, maxReportLatencyUs: Int, h: Handler) = sm.registerListener(l, s, samplingPeriodUs, maxReportLatencyUs, h)
        override fun unregister(l: SensorEventListener) = sm.unregisterListener(l)
    }

    private companion object { const val LATENCY_US = 2_000_000 }
}
