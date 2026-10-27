package com.rokid.context.glasses.capture.trace

import com.rokid.context.capture.trace.TraceEvent
import com.rokid.context.core.model.MotionState
import com.rokid.context.glasses.capture.motion.SampleTap

/**
 * Buffers raw glasses sensor samples (gyro, linear acceleration) at a developer-chosen rate and writes them in BATCHES through [SensorRecStore] (v0.9.7 B). It owns NO sensor registration:
 * it is a read-only [SampleTap] on the samples the classifier's existing `SensorFeed` already delivers (the same tap mechanism the trace recorder uses), so the requested sensor rates and the
 * rate policy are untouched and it can never see more than the policy delivers. It holds no reference to the classifier and nothing it records is read by it or by the capture session.
 * Samples are down-sampled in software to the chosen rate (the effective rate is therefore min(chosen, delivered)), the buffer is bounded ([maxBuffered]; overflow drops the NEWEST sample and is counted), a batch goes out
 * from [poll] (the supervisor's 1 s beat) every [flushEvery] samples or [flushMs], and at a stop. Writes run on [io] (the trace writer thread). Logs name events only.
 *
 * The tap path runs on the sensor thread inside the classifier's callback and must never wait: it reads two volatile fields ([hz], [eligible]), owns its schedule fields, and appends to a lock-free queue; no lock,
 * log or I/O is taken there, and a throw is caught and counted ([errors]), never passed to the classifier. [setEligible] is flipped by the pause/park/heat/battery/rate-policy transitions themselves; going
 * ineligible DROPS the buffered samples (they are not flushed).
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
    @Volatile private var hz = 0
    @Volatile private var eligible = true
    /** Bumped when the schedule must restart (a new rate, eligible again); the tap thread resets its own due times when it sees a new value. */
    @Volatile private var scheduleEpoch = 0
    /** A sample and the generation the tap read BEFORE it looked at eligibility: a cleanup or an eligibility change that happens after that read leaves it stale, and it is dropped at take(). */
    private class Sample(val event: TraceEvent, val generation: Long)
    private val queue = java.util.concurrent.ConcurrentLinkedQueue<Sample>()
    /** Reserved slots: incremented (below the cap, by compare-and-set) BEFORE a sample is offered, decremented once per polled sample. Never negative, never above [maxBuffered]. */
    private val size = java.util.concurrent.atomic.AtomicInteger()
    /** Test seams: between the tap's eligibility check and its offer, and inside the reserve loop with the value just read. */
    @Volatile internal var tapHook: (() -> Unit)? = null
    @Volatile internal var reserveHook: ((Int) -> Unit)? = null
    private val drops = java.util.concurrent.atomic.AtomicLong()
    private val errs = java.util.concurrent.atomic.AtomicLong()
    /** Bumped by every cleanup ([discard]) and every eligibility change: samples and batches of an older generation are never written. */
    private val generation = java.util.concurrent.atomic.AtomicLong()
    // tap-thread-only: the next due sensor time per sensor (ms), MIN_VALUE = none yet
    private var seenEpoch = 0
    private var lastG = Long.MIN_VALUE
    private var lastA = Long.MIN_VALUE
    // consumer side only (poll / flush / configure), never touched by the tap
    private val consumer = Any()
    private var seq = 0

    fun buffered(): Int = size.get()
    fun dropped(): Long = drops.get()
    fun errors(): Long = errs.get()

    /** 0 = off (what was recorded is written), else a preset rate. Volatile writes first; logging after, outside everything the tap touches. */
    fun configure(newHz: Int) {
        val was = hz
        if (newHz == was) return
        hz = newHz
        scheduleEpoch = scheduleEpoch + 1
        if (newHz > 0 && was == 0) log("sensor recording started")
        if (newHz == 0) { flush(); if (was > 0) log("sensor recording stopped") }
    }

    /** False = a pause, park, heat, battery or rate-policy state forbids recording right now: samples are ignored at once and what is buffered is DROPPED, not written. */
    fun setEligible(e: Boolean) {
        val was = eligible
        if (was == e) return
        eligible = e
        generation.incrementAndGet() // after the volatile write: a tap that read the old flag holds the old generation
        if (!e) dropBuffer() else scheduleEpoch = scheduleEpoch + 1
    }

    fun onGyro(x: Float, y: Float, z: Float, sMs: Long) = add(true, x, y, z, sMs)
    fun onAccel(x: Float, y: Float, z: Float, sMs: Long) = add(false, x, y, z, sMs)
    // the classifier's tap: [tMs] is the sensor time in ms; the motion state and the gyro time are the classifier's business and ignored
    override fun onGyro(x: Float, y: Float, z: Float, tMs: Long, state: MotionState) = add(true, x, y, z, tMs)
    override fun onAccel(x: Float, y: Float, z: Float, tMs: Long, gyroNewestMs: Long, state: MotionState) = add(false, x, y, z, tMs)

    private fun add(gyro: Boolean, x: Float, y: Float, z: Float, sMs: Long) {
        try {
            val gen = generation.get() // BEFORE the eligibility read
            val rate = hz
            if (rate == 0 || !eligible || sMs < 0 || !x.isFinite() || !y.isFinite() || !z.isFinite()) return
            val e = scheduleEpoch
            if (e != seenEpoch) { seenEpoch = e; lastG = Long.MIN_VALUE; lastA = Long.MIN_VALUE }
            // a schedule, not a gap: the next sample is due one period after the previous DUE time, so the mean rate is the chosen one whatever the sensor's own rate and jitter
            val due = if (gyro) lastG else lastA
            val period = 1_000L / rate
            if (due != Long.MIN_VALUE && sMs < due && due - sMs <= period) return
            val next = if (due == Long.MIN_VALUE || sMs - due >= period || sMs < due) sMs + period else due + period
            if (gyro) lastG = next else lastA = next
            tapHook?.invoke()
            if (!reserve()) { drops.incrementAndGet(); return } // drop-newest
            try {
                val t = clock()
                queue.add(Sample(if (gyro) TraceEvent.Gyro(t, sMs, x, y, z) else TraceEvent.Accel(t, sMs, x, y, z), gen))
            } catch (t: Throwable) { size.decrementAndGet(); throw t } // a reserved slot that never got its sample is given back
        } catch (t: Throwable) { errs.incrementAndGet() }
    }

    /** Takes one slot if the buffer is below its cap: one compare-and-set loop, so concurrent offers can never exceed [maxBuffered]. */
    private fun reserve(): Boolean {
        while (true) {
            val c = size.get()
            reserveHook?.invoke(c)
            if (c >= maxBuffered) return false
            if (size.compareAndSet(c, c + 1)) return true
        }
    }

    /** The supervisor's beat: full batches go out, and a batch that has been collecting for [flushMs] too. */
    fun poll() {
        if (hz == 0) return
        repeat(MAX_BATCHES_PER_POLL) {
            val n = size.get()
            if (n == 0) return
            val old = queue.peek()?.let { clock() - it.event.tMs >= flushMs } ?: false
            if (n < flushEvery && !old) return
            take()?.let { writeAsync(it) }
        }
    }

    /** Writes whatever is buffered now. */
    fun flush() { while (true) { val b = take() ?: return; writeAsync(b) } }

    /** A Forget (range or epoch) arrived: what is buffered goes without being written, and a batch already taken is dropped when it reaches the writer. */
    fun discard() { generation.incrementAndGet(); dropBuffer() }

    private fun dropBuffer() { while (queue.poll() != null) size.decrementAndGet() }

    private class Batch(val events: List<TraceEvent>, val startWall: Long, val seq: Int, val generation: Long, val storeEpoch: Int)

    /** Up to [flushEvery] CURRENT-generation samples off the queue (older ones are discarded as they are polled); sized from what was polled, not from the counter. Null when nothing is left. */
    private fun take(): Batch? = synchronized(consumer) {
        val gen = generation.get()
        val out = ArrayList<TraceEvent>()
        while (out.size < flushEvery) {
            val smp = queue.poll() ?: break
            size.decrementAndGet()
            if (smp.generation == gen) out.add(smp.event)
        }
        if (out.isEmpty()) return null
        val now = clock()
        Batch(out, (wallClock() - (now - out.first().tMs)).coerceAtLeast(0), seq++, gen, store.epoch.get())
    }

    private fun writeAsync(b: Batch) {
        val end = wallClock().coerceAtLeast(b.startWall)
        io(Runnable {
            // A Forget (range or epoch) that completed after this batch was taken, in memory ([generation]) or on disk (the store's wipe/forget epoch), means these samples are forgotten: drop them
            if (b.generation != generation.get() || b.storeEpoch != store.epoch.get()) return@Runnable
            when (store.write(b.startWall, end, b.seq, b.events)) {
                SensorRecStore.Result.WRITTEN -> { log("sensor batch written"); if (store.lastPruned > 0) log("sensor batch pruned") }
                else -> log("sensor recording blocked")
            }
        })
    }

    private companion object { const val MAX_BATCHES_PER_POLL = 4 }
}
