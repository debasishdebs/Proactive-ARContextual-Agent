package com.rokid.context.core.model.context.graph

/**
 * The shared step catalogue: pure, allocation-free step functions (rules-graph R1.3). A graph node adapter and a 3c expression row call
 * the same function, so they cannot diverge. Each op writes into [out] (preallocated by the caller) and returns it; an UNAVAILABLE input
 * makes the result UNAVAILABLE, and [satisfied] is false for it (R6). Windows (`win`) and the stateful nodes are added with their own tasks.
 */
object StepOps {
    const val LT = 0
    const val LE = 1
    const val GT = 2
    const val GE = 3
    const val EQ = 4

    /** True only for an available BOOL sample that is true. UNAVAILABLE and non-bools are never satisfied. */
    fun satisfied(s: Sample): Boolean = s.available && s.type == Sample.Type.BOOL && s.flag

    fun cmp(out: Sample, a: Sample, b: Sample, op: Int): Sample {
        if (!a.available || !b.available) return out.setUnavailable()
        val r = when (op) {
            LT -> a.num < b.num
            LE -> a.num <= b.num
            GT -> a.num > b.num
            GE -> a.num >= b.num
            else -> a.num == b.num
        }
        return out.setBool(r, 0, 0, 1.0, 0).metaOf(a, b)
    }

    /** Set membership of an enum code. [set] is the sorted-or-not list of wire codes (at most a few entries). */
    fun inSet(out: Sample, a: Sample, set: IntArray): Sample {
        if (!a.available) return out.setUnavailable()
        var hit = false
        for (c in set) if (c == a.code) hit = true
        return out.setBool(hit, a.atMs, a.ageMs, a.coverage, a.gen)
    }

    fun and(out: Sample, a: Sample, b: Sample): Sample {
        if (!a.available || !b.available) return out.setUnavailable()
        return out.setBool(a.flag && b.flag, 0, 0, 1.0, 0).metaOf(a, b)
    }

    fun or(out: Sample, a: Sample, b: Sample): Sample {
        if (!a.available || !b.available) return out.setUnavailable()
        return out.setBool(a.flag || b.flag, 0, 0, 1.0, 0).metaOf(a, b)
    }

    fun not(out: Sample, a: Sample): Sample {
        if (!a.available) return out.setUnavailable()   // not(UNAVAILABLE) must never become true
        return out.setBool(!a.flag, a.atMs, a.ageMs, a.coverage, a.gen)
    }

    /** `cond ? a : b`. UNAVAILABLE when the condition or the chosen branch is. */
    fun choose(out: Sample, cond: Sample, a: Sample, b: Sample): Sample {
        if (!cond.available) return out.setUnavailable()
        val pick = if (cond.flag) a else b
        if (!pick.available) return out.setUnavailable()
        out.copyFrom(pick)
        return out.meta(minOf(cond.atMs, pick.atMs), maxOf(cond.ageMs, pick.ageMs), minOf(cond.coverage, pick.coverage), maxOf(cond.gen, pick.gen), true)
    }

    fun clamp(out: Sample, a: Sample, lo: Double, hi: Double): Sample {
        if (!a.available) return out.setUnavailable()
        return out.setNum(if (a.num < lo) lo else if (a.num > hi) hi else a.num, a.atMs, a.ageMs, a.coverage, a.gen)
    }

    /** Passes [a] through while its age is at most [maxAgeMs] (inclusive); an older sample becomes UNAVAILABLE. */
    fun fresh(out: Sample, a: Sample, maxAgeMs: Long): Sample =
        if (!a.available || a.ageMs > maxAgeMs) out.setUnavailable() else out.copyFrom(a)
}
