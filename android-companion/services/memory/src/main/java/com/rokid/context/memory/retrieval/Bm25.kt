package com.rokid.context.memory.retrieval

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln
import kotlin.math.max

/** Okapi BM25 from FTS4 `matchinfo(…, 'pcnalx')`: p, c, n, a[c], l[c], x[3·p·c] (spec §4.6). */
object Bm25 {
    const val K1 = 1.2
    const val B = 0.75

    fun score(mi: IntArray): Double {
        val p = mi[0]; val c = mi[1]; val n = mi[2].toDouble()
        val xBase = 3 + 2 * c
        var s = 0.0
        for (col in 0 until c) {
            val avg = max(mi[3 + col].toDouble(), 1.0)
            val len = mi[3 + c + col].toDouble()
            for (i in 0 until p) {
                val o = xBase + 3 * (i * c + col)
                val tf = mi[o].toDouble(); val df = mi[o + 2].toDouble()
                if (tf <= 0.0) continue
                val idf = ln((n - df + 0.5) / (df + 0.5) + 1.0)
                s += idf * (tf * (K1 + 1)) / (tf + K1 * (1 - B + B * len / avg))
            }
        }
        return s
    }

    fun ints(blob: ByteArray): IntArray {
        val buf = ByteBuffer.wrap(blob).order(ByteOrder.nativeOrder())
        return IntArray(blob.size / 4) { buf.getInt() }
    }
}
