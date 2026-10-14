package com.rokid.context.glasses.capture.motion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread

/** Wires the IMU and proximity sensors into the pure classifiers. Fakeable in tests. */
interface SensorFeed {
    fun start(c: MotionClassifier, w: WearDetector)
    fun stop()
}

/**
 * Unbatched (maxReportLatency 0) SENSOR_DELAY_GAME, non-wakeup sensors: the 1 s glimpse promise can't absorb a FIFO delay.
 * Sensor timestamps are elapsed-realtime based, which is also the clock the classifiers are given.
 */
class AndroidSensorFeed(context: Context) : SensorFeed {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var thread: HandlerThread? = null
    private var listener: SensorEventListener? = null
    private var handler: Handler? = null
    private var tick: Runnable? = null

    override fun start(c: MotionClassifier, w: WearDetector) {
        if (listener != null) return
        val ht = HandlerThread("motion-sensors").also { it.start() }
        val h = Handler(ht.looper)
        val proxMax = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY, false)?.maximumRange ?: 5f
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val t = e.timestamp / 1_000_000L
                val v = e.values
                when (e.sensor.type) {
                    Sensor.TYPE_GYROSCOPE -> c.onGyro(v[0], v[1], v[2], t)
                    Sensor.TYPE_LINEAR_ACCELERATION -> c.onLinearAccel(v[0], v[1], v[2], t)
                    Sensor.TYPE_PROXIMITY -> w.onProximity(v[0] < proxMax, t)
                }
            }
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) = Unit
        }
        for (type in intArrayOf(Sensor.TYPE_GYROSCOPE, Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_PROXIMITY)) {
            val s = sm.getDefaultSensor(type, false) ?: continue
            sm.registerListener(l, s, SensorManager.SENSOR_DELAY_GAME, 0, h)
        }
        val t = object : Runnable { override fun run() { w.tick(); h.postDelayed(this, 1000) } }
        h.postDelayed(t, 1000)
        thread = ht; handler = h; listener = l; tick = t
    }

    override fun stop() {
        listener?.let { sm.unregisterListener(it) }
        tick?.let { handler?.removeCallbacks(it) }
        thread?.quitSafely()
        thread = null; handler = null; listener = null; tick = null
    }
}
