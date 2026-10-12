package com.rokid.context.memory.retrieval

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** `memory_vector.blob` = float32 little-endian, no header (dimension lives on the space). */
object VectorCodec {
    fun encode(v: FloatArray): ByteArray = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { v.forEach { putFloat(it) } }.array()
    /** Throws [IllegalArgumentException] for a blob that is not a whole number of float32s (truncated/corrupt). */
    fun decode(b: ByteArray): FloatArray {
        require(b.size % 4 == 0) { "vector blob of ${b.size} bytes is not float32-aligned" }
        val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(b.size / 4) { buf.getFloat() }
    }
}

object VectorMath {
    fun norm(v: FloatArray): Double = sqrt(v.sumOf { (it * it).toDouble() })

    /** True when [v] has a usable direction: a finite, non-zero norm. */
    fun usable(v: FloatArray): Boolean = norm(v).let { it != 0.0 && it.isFinite() }

    /** 0 for a zero or non-finite vector (never NaN); callers ensure equal sizes. */
    fun cosine(a: FloatArray, b: FloatArray): Double {
        val na = norm(a); val nb = norm(b)
        if (na == 0.0 || nb == 0.0 || !na.isFinite() || !nb.isFinite()) return 0.0
        var dot = 0.0
        for (i in a.indices) dot += a[i].toDouble() * b[i]
        val c = dot / (na * nb)
        return if (c.isFinite()) c else 0.0
    }

    fun l2(v: FloatArray): FloatArray { val n = norm(v); return if (n == 0.0 || !n.isFinite()) v.copyOf() else FloatArray(v.size) { (v[it] / n).toFloat() } }
}
