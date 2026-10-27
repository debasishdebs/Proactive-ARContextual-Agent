package com.rokid.context.glasses.capture.motion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.rokid.context.core.model.MotionLabel
import com.rokid.context.glasses.capture.diag.DiagLog
import com.rokid.context.glasses.capture.motion.model.HeuristicMotionModel
import com.rokid.context.glasses.capture.motion.model.SecondFeatures
import com.rokid.context.glasses.capture.motion.source.GlassesImuSource
import com.rokid.context.glasses.capture.motion.source.MotionFusion
import java.io.File
import java.util.Locale

/** Wires the IMU and proximity sensors into the pure classifiers. Fakeable in tests. */
interface SensorFeed {
    fun start(c: MotionClassifier, w: WearDetector)
    fun stop()
    /** Switches the IMU/proximity sampling rate; fakes may ignore it. */
    fun setRate(r: SensorRate) = Unit
    /** v0.9.7 A: the classifier thresholds in force (the shadow heuristic reads them); fakes may ignore it. */
    fun setThresholds(t: com.rokid.context.core.model.signals.ClassifierThresholds) = Unit
}

/**
 * FULL = SENSOR_DELAY_GAME unbatched; STILL = 20 Hz with a 0.5 s report latency (the classifier is batch-safe);
 * LOW = 10 Hz with a 1 s report latency.
 */
enum class SensorRate { FULL, STILL, LOW }

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
    private val verboseFeatures: Boolean = false,
    private val label: () -> MotionLabel = { MotionLabel.NONE },
    private val csvDir: File? = null,
    /** 3c X3: receives each second's features for the expression guards (a plain write, no sensor, timer or wakeup of its own). */
    private val signals: com.rokid.context.capture.plan.ExprSignals? = null,
) : SensorFeed {
    constructor(context: Context, verboseFeatures: Boolean = false, label: () -> MotionLabel = { MotionLabel.NONE }, csvDir: File? = null, signals: com.rokid.context.capture.plan.ExprSignals? = null) : this(
        ManagerRegistry(context.getSystemService(Context.SENSOR_SERVICE) as SensorManager),
        verboseFeatures = verboseFeatures, label = label, csvDir = csvDir, signals = signals,
    )

    // Shadow only: the model and fusion are logged to the CSV and never read by capture.
    private val heuristic = HeuristicMotionModel()
    private val imuSource = GlassesImuSource(heuristic)

    override fun setThresholds(t: com.rokid.context.core.model.signals.ClassifierThresholds) { heuristic.thresholds = t }
    private val csv: MotionCsv? = csvDir?.let { MotionCsv(it) }
    /** Motion CSV write failures so far (count only, for the heartbeat). */
    val csvFailures: Int get() = csv?.failures ?: 0

    private var thread: HandlerThread? = null
    private var listener: SensorEventListener? = null
    private var handler: Handler? = null
    private var tick: Runnable? = null
    private var sensors: List<Sensor> = emptyList()
    @Volatile var rate: SensorRate = SensorRate.FULL
        private set

    private fun registerAll(l: SensorEventListener, h: Handler, list: List<Sensor>, r: SensorRate) {
        val (period, latency) = when (r) {
            SensorRate.FULL -> SensorManager.SENSOR_DELAY_GAME to 0
            SensorRate.STILL -> STILL_PERIOD_US to STILL_LATENCY_US
            SensorRate.LOW -> LOW_PERIOD_US to LOW_LATENCY_US
        }
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
        val gyro = sm.nonWakeUp(Sensor.TYPE_GYROSCOPE)
        val acc = sm.nonWakeUp(Sensor.TYPE_LINEAR_ACCELERATION)
        val list = listOfNotNull(gyro, acc, prox)
        runCatching { DiagLog.i(TAG, "sensors fifo gyro=${gyro?.fifoMaxEventCount ?: -1} acc=${acc?.fifoMaxEventCount ?: -1}") }
        sensors = list
        registerAll(l, h, list, rate)
        if (prox == null) {
            DiagLog.w(TAG, "no proximity sensor: wear detection off, treating the glasses as worn")
            w.onProximity(true)
        }
        var ticks = 0
        val features = FeatureLogger(c, { DiagLog.i(TAG, it) }, now, verboseFeatures) // K3: count-only tuning line, throttled in wave A2
        val t = object : Runnable {
            override fun run() {
                w.tick()
                val f = c.features()
                features.log(f)
                shadow(f)
                w.onSecond(f.gyroMax, f.accVariance, now())
                // Fix wave H: the proximity sensor never gates capture; one count-only line a minute shows what it reads.
                if (++ticks % 60 == 0) DiagLog.i(TAG, proximityDiagLine(lastRaw, proxMax, prox != null, w.worn.value))
                h.postDelayed(this, 1000)
            }
        }
        h.postDelayed(t, 1000)
        thread = ht; handler = h; listener = l; tick = t
    }

    /** Once a second: classify in shadow, fuse, and (diag marker or a label set) append one CSV row. Touches no classifier state. */
    private fun shadow(f: MotionClassifier.Features) {
        runCatching {
            val t = now()
            val sf = SecondFeatures(f.gyroMax, f.gyroMean, f.gyroRms, f.aboveOnFrac, f.accVariance, f.stepHz.toFloat(), f.rotRad)
            signals?.set(sf, t)
            val ev = imuSource.onSecond(sf, t)
            val fused = MotionFusion.fuse(listOf(ev), t)
            val l = label()
            val out = csv ?: return
            if (!verboseFeatures && l == MotionLabel.NONE) return
            out.append(String.format(
                Locale.US, "%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%d,%d,%s,%s,%s,%s,%s",
                t, sf.gyroMax, sf.gyroMean, sf.gyroRms, sf.aboveOnFrac, sf.accVar, sf.stepHz, sf.rotRad,
                f.glances, f.micro, f.state.name, ev.state.name, fused?.state?.name ?: "NONE", l.name, rate.name.lowercase(),
            ))
        }
    }

    @Synchronized override fun stop() {
        listener?.let { sm.unregister(it) }
        tick?.let { handler?.removeCallbacks(it) }
        thread?.quitSafely()
        thread = null; handler = null; listener = null; tick = null; sensors = emptyList()
    }

    private companion object {
        const val TAG = "PcCapture"
        const val STILL_PERIOD_US = 50_000
        const val STILL_LATENCY_US = 500_000
        const val LOW_PERIOD_US = 100_000
        const val LOW_LATENCY_US = 1_000_000
    }
}

/** Count-only diagnostics line for the proximity sensor (raw reading, its max range, the detector's verdict). */
internal fun proximityDiagLine(raw: Float, max: Float, present: Boolean, worn: Boolean): String =
    if (!present) "proximity absent" else "proximity raw=${if (raw.isNaN()) "none" else raw.toString()} max=$max worn=$worn (diagnostic only)"
