package com.rokid.context.glasses.capture.trace

import com.rokid.context.capture.trace.TraceEvent
import com.rokid.context.core.model.MotionState
import com.rokid.context.glasses.capture.motion.SampleTap

/**
 * Buffers raw glasses sensor samples (gyro, linear acceleration) at a developer-chosen rate and writes them in BATCHES through [SensorRecStore] (v0.9.7 B). It owns NO sensor registration:
 * it is a read-only [SampleTap] on the samples the classifier's existing `SensorFeed` already delivers (the same tap mechanism the trace recorder uses), so the requested sensor rates and the
 * rate policy are untouched and it can never see more than the policy delivers. It holds no reference to the classifier and nothing it records is read by it or by the capture session.
 * Samples are down-sampled in software to the chosen rate (the effective rate is therefore min(chosen, delivered)), the buffer is bounded ([maxBuffered]; overflow is counted, not kept), a batch goes out every [flushEvery] samples or [flushMs]
 * (on [poll], the supervisor's 1 s beat) and at a stop. Writes run on [io] (the trace writer thread). Logs name events only.
 */
class SensorRecorder internal constructor(
    private val store: SensorRecStore,
    private val clock: () -> Long,
    private val wallClock: () -> Long,
    private val io: (Runnable) -> Unit,
    private val log: (String) -> Unit,
    private val flushEvery: Int = 2_000,
    private val maxBuffered: Int = 6_000,
    private val flushMs: Long = 60_000,
) : SampleTap {
    private val lock = Any()
    private var hz = 0
    private var buf = ArrayList<TraceEvent>()
    private var firstWall = 0L
    private var firstElapsed = 0L
    /** The next due sensor time per sensor (ms); MIN_VALUE = none yet. */
    private var lastG = Long.MIN_VALUE
    private var lastA = Long.MIN_VALUE
    private var dropped = 0L
    private var seq = 0

    fun buffered(): Int = synchronized(lock) { buf.size }
    fun dropped(): Long = synchronized(lock) { dropped }

    /** 0 = off (flushes what was recorded), else a preset rate. */
    fun configure(newHz: Int) {
        val flush: Batch?
        synchronized(lock) {
            if (newHz == hz) return
            val was = hz; hz = newHz
            if (newHz > 0 && was == 0) { log("sensor recording started"); lastG = Long.MIN_VALUE; lastA = Long.MIN_VALUE }
            flush = if (newHz == 0) takeLocked() else null
            if (newHz == 0 && was > 0) log("sensor recording stopped")
        }
        flush?.let { writeAsync(it) }
    }

    fun onGyro(x: Float, y: Float, z: Float, sMs: Long) = add(true, x, y, z, sMs)
    fun onAccel(x: Float, y: Float, z: Float, sMs: Long) = add(false, x, y, z, sMs)
    // the classifier's tap: [tMs] is the sensor time in ms; the motion state and the gyro time are the classifier's business and ignored
    override fun onGyro(x: Float, y: Float, z: Float, tMs: Long, state: MotionState) = add(true, x, y, z, tMs)
    override fun onAccel(x: Float, y: Float, z: Float, tMs: Long, gyroNewestMs: Long, state: MotionState) = add(false, x, y, z, tMs)

    private fun add(gyro: Boolean, x: Float, y: Float, z: Float, sMs: Long) {
        var flush: Batch? = null
        synchronized(lock) {
            val rate = hz
            if (rate == 0 || sMs < 0 || !x.isFinite() || !y.isFinite() || !z.isFinite()) return
            // a schedule, not a gap: the next sample is due one period after the previous DUE time, so the mean rate is the chosen one whatever the sensor's own rate and jitter
            val due = if (gyro) lastG else lastA
            val period = 1_000L / rate
            if (due != Long.MIN_VALUE && sMs < due && due - sMs <= period) return
            val next = if (due == Long.MIN_VALUE || sMs - due >= period || sMs < due) sMs + period else due + period
            if (gyro) lastG = next else lastA = next
            if (buf.size >= maxBuffered) { dropped++; return }
            val t = clock()
            if (buf.isEmpty()) { firstWall = wallClock(); firstElapsed = t }
            buf.add(if (gyro) TraceEvent.Gyro(t, sMs, x, y, z) else TraceEvent.Accel(t, sMs, x, y, z))
            if (buf.size >= flushEvery) flush = takeLocked()
        }
        flush?.let { writeAsync(it) }
    }

    /** The supervisor's beat: a batch that has been collecting for [flushMs] goes out. */
    fun poll() {
        val flush = synchronized(lock) { if (hz > 0 && buf.isNotEmpty() && clock() - firstElapsed >= flushMs) takeLocked() else null }
        flush?.let { writeAsync(it) }
    }

    /** Writes whatever is buffered now. */
    fun flush() { val b = synchronized(lock) { takeLocked() }; b?.let { writeAsync(it) } }

    /** A Forget (range or epoch) arrived: what is buffered goes without being written. */
    fun discard() { synchronized(lock) { buf = ArrayList() } }

    private class Batch(val events: List<TraceEvent>, val startWall: Long, val seq: Int)

    private fun takeLocked(): Batch? {
        if (buf.isEmpty()) return null
        val out = Batch(buf, firstWall, seq++); buf = ArrayList()
        return out
    }

    private fun writeAsync(b: Batch) {
        val end = wallClock().coerceAtLeast(b.startWall)
        io(Runnable {
            when (store.write(b.startWall, end, b.seq, b.events)) {
                SensorRecStore.Result.WRITTEN -> { log("sensor batch written"); if (store.lastPruned > 0) log("sensor batch pruned") }
                else -> log("sensor recording blocked")
            }
        })
    }
}
