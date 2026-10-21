package com.rokid.context.glasses.capture.motion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.rokid.context.glasses.capture.diag.DiagLog

/** Wires the IMU and proximity sensors into the pure classifiers. Fakeable in tests. */
interface SensorFeed {
    fun start(c: MotionClassifier, w: WearDetector)
    fun stop()
    /** Switches the IMU/proximity sampling rate; fakes may ignore it. */
    fun setRate(r: SensorRate) = Unit
}

/** FULL = SENSOR_DELAY_GAME unbatched; LOW = 10 Hz with a 1 s report latency. */
enum class SensorRate { FULL, LOW }

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
class AndroidSensorFeed internal constructor(
    private val sm: SensorRegistry,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) : SensorFeed {
    constructor(context: Context) : this(ManagerRegistry(context.getSystemService(Context.SENSOR_SERVICE) as SensorManager))

    private var thread: HandlerThread? = null
    private var listener: SensorEventListener? = null
    private var handler: Handler? = null
    private var tick: Runnable? = null
    private var sensors: List<Sensor> = emptyList()
    @Volatile var rate: SensorRate = SensorRate.FULL
        private set

    private fun registerAll(l: SensorEventListener, h: Handler, list: List<Sensor>, r: SensorRate) {
        val period = if (r == SensorRate.LOW) LOW_PERIOD_US else SensorManager.SENSOR_DELAY_GAME
        val latency = if (r == SensorRate.LOW) LOW_LATENCY_US else 0
        for (s in list) sm.register(l, s, period, latency, h)
    }

    @Synchronized override fun setRate(r: SensorRate) {
        if (r == rate) return
        rate = r
        val l = listener ?: return
        val h = handler ?: return
        sm.unregister(l)
        registerAll(l, h, sensors, r)
    }

    @Synchronized override fun start(c: MotionClassifier, w: WearDetector) {
        if (listener != null) return
        val ht = HandlerThread("motion-sensors").also { it.start() }
        val h = Handler(ht.looper)
        val prox = sm.nonWakeUp(Sensor.TYPE_PROXIMITY) ?: sm.default(Sensor.TYPE_PROXIMITY)
        val proxMax = prox?.maximumRange ?: 5f
        var lastRaw = Float.NaN // diagnostics only: the raw proximity reading, logged once a minute
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val t = e.timestamp / 1_000_000L
                val v = e.values
                when (e.sensor.type) {
                    Sensor.TYPE_GYROSCOPE -> c.onGyro(v[0], v[1], v[2], t)
                    Sensor.TYPE_LINEAR_ACCELERATION -> c.onLinearAccel(v[0], v[1], v[2], t)
                    Sensor.TYPE_PROXIMITY -> { lastRaw = v[0]; w.onProximity(v[0] < proxMax) }
                }
            }
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) = Unit
        }
        val list = listOfNotNull(
            sm.nonWakeUp(Sensor.TYPE_GYROSCOPE), sm.nonWakeUp(Sensor.TYPE_LINEAR_ACCELERATION), prox,
        )
        sensors = list
        registerAll(l, h, list, rate)
        if (prox == null) {
            DiagLog.w(TAG, "no proximity sensor: wear detection off, treating the glasses as worn")
            w.onProximity(true)
        }
        var ticks = 0
        val features = FeatureLogger(c) { DiagLog.i(TAG, it) } // K3: one count-only line a second for tuning
        val t = object : Runnable {
            override fun run() {
                w.tick()
                val f = c.features()
                features.log(f)
                w.onSecond(f.gyroMax, f.accVariance, now())
                // Fix wave H: the proximity sensor never gates capture; one count-only line a minute shows what it reads.
                if (++ticks % 60 == 0) DiagLog.i(TAG, proximityDiagLine(lastRaw, proxMax, prox != null, w.worn.value))
                h.postDelayed(this, 1000)
            }
        }
        h.postDelayed(t, 1000)
        thread = ht; handler = h; listener = l; tick = t
    }

    @Synchronized override fun stop() {
        listener?.let { sm.unregister(it) }
        tick?.let { handler?.removeCallbacks(it) }
        thread?.quitSafely()
        thread = null; handler = null; listener = null; tick = null; sensors = emptyList()
    }

    private companion object {
        const val TAG = "PcCapture"
        const val LOW_PERIOD_US = 100_000
        const val LOW_LATENCY_US = 1_000_000
    }
}

/** Count-only diagnostics line for the proximity sensor (raw reading, its max range, the detector's verdict). */
internal fun proximityDiagLine(raw: Float, max: Float, present: Boolean, worn: Boolean): String =
    if (!present) "proximity absent" else "proximity raw=${if (raw.isNaN()) "none" else raw.toString()} max=$max worn=$worn (diagnostic only)"
