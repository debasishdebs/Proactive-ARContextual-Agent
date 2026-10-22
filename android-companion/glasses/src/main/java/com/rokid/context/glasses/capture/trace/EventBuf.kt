package com.rokid.context.glasses.capture.trace

import com.rokid.context.capture.trace.TraceEvent

/**
 * A fixed-capacity buffer of trace events, struct-of-arrays for the two sensor kinds so an appended sample allocates nothing on the sensor
 * thread (the arrays are allocated once per buffer; buffers are pooled by [TraceRecorder]). Any other event is one object. Events are
 * materialised as [TraceEvent]s only by [events], on the writer thread. Not thread-safe: the recorder's lock guards it.
 */
internal class EventBuf(val capacity: Int) {
    private val kind = ByteArray(capacity)            // 0 = object, 1 = gyro, 2 = accel
    private val t = LongArray(capacity)
    private val s = LongArray(capacity)
    private val x = FloatArray(capacity)
    private val y = FloatArray(capacity)
    private val z = FloatArray(capacity)
    private val obj = arrayOfNulls<TraceEvent>(capacity)
    var size = 0
        private set

    val isFull: Boolean get() = size == capacity

    fun addSample(gyro: Boolean, tMs: Long, sMs: Long, vx: Float, vy: Float, vz: Float) {
        val i = size++
        kind[i] = if (gyro) 1 else 2
        t[i] = tMs; s[i] = sMs; x[i] = vx; y[i] = vy; z[i] = vz
    }

    fun add(e: TraceEvent) {
        val i = size++
        kind[i] = 0
        obj[i] = e
    }

    fun events(): List<TraceEvent> {
        val out = ArrayList<TraceEvent>(size)
        for (i in 0 until size) out += when (kind[i].toInt()) {
            1 -> TraceEvent.Gyro(t[i], s[i], x[i], y[i], z[i])
            2 -> TraceEvent.Accel(t[i], s[i], x[i], y[i], z[i])
            else -> obj[i]!!
        }
        return out
    }

    fun clear() {
        java.util.Arrays.fill(obj, 0, size, null)
        size = 0
    }
}
