package com.rokid.context.glasses.capture.motion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

/** Wires the IMU and proximity sensors into the pure classifiers. Fakeable in tests. */
interface SensorFeed {
    fun start(c: MotionClassifier, w: WearDetector)
    fun stop()
}

/** The slice of [SensorManager] the feed uses; a seam so tests can pin what gets registered and how. */
interface SensorRegistry {
    /** `getDefaultSensor(type, false)`: the non-wake-up variant, or null when the HAL has none. */
    fun nonWakeUp(type: Int): Sensor?
    /** `getDefaultSensor(type)`: the default variant (the wake-up one for proximity on most devices). */
    fun default(type: Int): Sensor?
    fun register(l: SensorEventListener, s: Sensor, samplingPeriodUs: Int, maxReportLatencyUs: Int, h: Handler): Boolean
    fun unregister(l: SensorEventListener)
}

private class ManagerRegistry(private val sm: SensorManager) : SensorRegistry {
    override fun nonWakeUp(type: Int): Sensor? = sm.getDefaultSensor(type, false)
    override fun default(type: Int): Sensor? = sm.getDefaultSensor(type)
    override fun register(l: SensorEventListener, s: Sensor, samplingPeriodUs: Int, maxReportLatencyUs: Int, h: Handler) =
        sm.registerListener(l, s, samplingPeriodUs, maxReportLatencyUs, h)
    override fun unregister(l: SensorEventListener) = sm.unregisterListener(l)
}

/**
 * Unbatched (maxReportLatency 0) SENSOR_DELAY_GAME, non-wakeup sensors: the 1 s glimpse promise can't absorb a FIFO delay.
 * Proximity falls back to the default (wake-up) variant, because many HALs have no non-wake-up one; with no proximity
 * sensor at all the glasses count as worn. Sensor timestamps are elapsed-realtime based, which is also the clock the
 * motion classifier is given; [WearDetector] uses its own injected clock (SystemClock.elapsedRealtime in production).
 */
class AndroidSensorFeed internal constructor(private val sm: SensorRegistry) : SensorFeed {
    constructor(context: Context) : this(ManagerRegistry(context.getSystemService(Context.SENSOR_SERVICE) as SensorManager))

    private var thread: HandlerThread? = null
    private var listener: SensorEventListener? = null
    private var handler: Handler? = null
    private var tick: Runnable? = null

    override fun start(c: MotionClassifier, w: WearDetector) {
        if (listener != null) return
        val ht = HandlerThread("motion-sensors").also { it.start() }
        val h = Handler(ht.looper)
        val prox = sm.nonWakeUp(Sensor.TYPE_PROXIMITY) ?: sm.default(Sensor.TYPE_PROXIMITY)
        val proxMax = prox?.maximumRange ?: 5f
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val t = e.timestamp / 1_000_000L
                val v = e.values
                when (e.sensor.type) {
                    Sensor.TYPE_GYROSCOPE -> c.onGyro(v[0], v[1], v[2], t)
                    Sensor.TYPE_LINEAR_ACCELERATION -> c.onLinearAccel(v[0], v[1], v[2], t)
                    Sensor.TYPE_PROXIMITY -> w.onProximity(v[0] < proxMax)
                }
            }
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) = Unit
        }
        for (type in intArrayOf(Sensor.TYPE_GYROSCOPE, Sensor.TYPE_LINEAR_ACCELERATION)) {
            val s = sm.nonWakeUp(type) ?: continue
            sm.register(l, s, SensorManager.SENSOR_DELAY_GAME, 0, h)
        }
        if (prox != null) sm.register(l, prox, SensorManager.SENSOR_DELAY_GAME, 0, h)
        else {
            Log.w(TAG, "no proximity sensor: wear detection off, treating the glasses as worn")
            w.onProximity(true)
        }
        val t = object : Runnable { override fun run() { w.tick(); h.postDelayed(this, 1000) } }
        h.postDelayed(t, 1000)
        thread = ht; handler = h; listener = l; tick = t
    }

    override fun stop() {
        listener?.let { sm.unregister(it) }
        tick?.let { handler?.removeCallbacks(it) }
        thread?.quitSafely()
        thread = null; handler = null; listener = null; tick = null
    }

    private companion object { const val TAG = "PcCapture" }
}
