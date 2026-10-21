package com.rokid.context.phone.context

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.rokid.context.core.model.context.Evidence
import java.util.concurrent.atomic.AtomicInteger

/**
 * `phone_steps`: the hardware step detector (batched, non-wake-up), accumulated into 60 s buckets by **sensor time**
 * (spec §5). A bucket closes on the first event after its end or on [flush]; it emits `Steps(bucketEnd, count)` with the bucket
 * end translated to the fusion clock. A bucket that began before this run, an event for an already closed bucket, one older than
 * the run or out of order is dropped (`stale`) — buffered history is never replayed. No steps, no evidence. Counts only.
 */
class StepSource(
    private val sinkFor: (String) -> EvidenceSink,
    private val time: ObsTime = ObsTime(),
    private val platform: Platform,
) : ContextSource {
    interface Platform {
        fun present(): Boolean
        fun permitted(): Boolean
        fun register(): Boolean
        fun unregister()
    }

    override val id = ID
    private val accepted = AtomicInteger(); private val staleN = AtomicInteger()
    override val events: Int get() = accepted.get()
    val stale: Int get() = staleN.get()

    private val gate = ObsGate(time, MAX_AGE_MS)
    private var sink: EvidenceSink? = null
    private var bucketStart: Long? = null
    private var count = 0
    private var closedEnd = 0L

    override fun available(): Boolean = platform.present()

    @Synchronized
    override fun start(): Boolean {
        stopLocked()
        if (!platform.present() || !platform.permitted()) return false
        val s = sinkFor(ID)
        gate.begin(); sink = s; bucketStart = null; count = 0; closedEnd = 0L
        if (!runCatching { platform.register() }.getOrDefault(false)) { sink = null; return false }
        return true
    }

    @Synchronized override fun stop() { stopLocked() }

    private fun stopLocked() {
        if (sink == null) return
        sink = null; bucketStart = null; count = 0
        runCatching { platform.unregister() }
    }

    /** [count] steps at [atMs] (the sensor time, elapsed-realtime ms). */
    @Synchronized
    fun onSteps(count: Int, atMs: Long) {
        if (sink == null || count <= 0) return
        val obs = gate.admit(atMs) ?: run { staleN.incrementAndGet(); return }
        val start = obs - obs % BUCKET_MS
        val cur = bucketStart
        if (cur != null && start > cur) { close() }
        if (bucketStart == null) {
            if (start < closedEnd) { staleN.incrementAndGet(); return }
            bucketStart = start; this.count = 0
        }
        this.count += count
    }

    /** Closes the open bucket when it is complete at [nowElapsedMs] (the shell passes now minus the batching latency). */
    @Synchronized
    fun flush(nowElapsedMs: Long) {
        val cur = bucketStart ?: return
        if (nowElapsedMs >= cur + BUCKET_MS) close()
    }

    private fun close() {
        val start = bucketStart ?: return
        val n = count
        bucketStart = null; count = 0; closedEnd = start + BUCKET_MS
        // A bucket that began before the run saw only part of its minute: not a rate.
        if (start < gate.startMs) { staleN.incrementAndGet(); return }
        accepted.incrementAndGet()
        sink?.accept(Evidence.Steps(time.toWall(start + BUCKET_MS), n))
    }

    companion object {
        const val ID = "phone_steps"
        const val BUCKET_MS = 60_000L
        const val MAX_AGE_MS = 10 * 60_000L
        /** The step detector batches up to this long; a bucket is flushed this much after its end. */
        const val LATENCY_MS = 30_000L

        fun create(context: Context, sinkFor: (String) -> EvidenceSink): StepSource {
            lateinit var self: StepSource
            val p = AndroidPlatform(context.applicationContext) { self }
            self = StepSource(sinkFor, platform = p)
            return self
        }
    }

    private class AndroidPlatform(private val ctx: Context, private val source: () -> StepSource) : Platform {
        private val sm get() = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        private var thread: HandlerThread? = null
        private var listener: SensorEventListener? = null
        private var ticker: Runnable? = null

        override fun present() = runCatching { sm?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null }.getOrDefault(false)
        override fun permitted() = ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

        override fun register(): Boolean {
            val manager = sm ?: return false
            val sensor = manager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) ?: return false
            val t = HandlerThread("PcCtxSteps").also { it.start() }
            val h = Handler(t.looper)
            val l = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) { source().onSteps(1, event.timestamp / 1_000_000L) }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            val tick = object : Runnable {
                override fun run() { source().flush(SystemClock.elapsedRealtime() - LATENCY_MS); h.postDelayed(this, LATENCY_MS) }
            }
            // samplingPeriodUs = SENSOR_DELAY_NORMAL, maxReportLatencyUs = 30 s: batched and non-wake-up.
            val ok = manager.registerListener(l, sensor, SensorManager.SENSOR_DELAY_NORMAL, (LATENCY_MS * 1000).toInt(), h)
            if (!ok) { t.quitSafely(); return false }
            thread = t; listener = l; ticker = tick
            h.postDelayed(tick, LATENCY_MS)
            return true
        }

        override fun unregister() {
            listener?.let { runCatching { sm?.unregisterListener(it) } }
            ticker?.let { r -> thread?.let { th -> runCatching { Handler(th.looper).removeCallbacks(r) } } }
            thread?.quitSafely()
            thread = null; listener = null; ticker = null
        }
    }
}
