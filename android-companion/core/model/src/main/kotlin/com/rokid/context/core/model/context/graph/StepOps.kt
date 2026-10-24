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

    const val ADD = 0
    const val SUB = 1
    const val MUL = 2
    const val DIV = 3

    /** UNAVAILABLE, or the first ERROR among [a] and [b]: an error dominates and keeps its kind. Writes no new object. */
    private fun bad(out: Sample, a: Sample, b: Sample): Sample =
        if (a.error) out.setError(a.errorKind) else if (b.error) out.setError(b.errorKind) else out.setUnavailable()

    /** True only for an available BOOL sample that is true. UNAVAILABLE and non-bools are never satisfied. */
    fun satisfied(s: Sample): Boolean = s.available && s.type == Sample.Type.BOOL && s.flag

    fun cmp(out: Sample, a: Sample, b: Sample, op: Int): Sample {
        if (!a.available || !b.available) return bad(out, a, b)
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
        if (!a.available) return bad(out, a, a)
        var hit = false
        for (c in set) if (c == a.code) hit = true
        return out.setBool(hit, a.atMs, a.ageMs, a.coverage, a.gen)
    }

    fun and(out: Sample, a: Sample, b: Sample): Sample {
        if (!a.available || !b.available) return bad(out, a, b)
        return out.setBool(a.flag && b.flag, 0, 0, 1.0, 0).metaOf(a, b)
    }

    fun or(out: Sample, a: Sample, b: Sample): Sample {
        if (!a.available || !b.available) return bad(out, a, b)
        return out.setBool(a.flag || b.flag, 0, 0, 1.0, 0).metaOf(a, b)
    }

    fun not(out: Sample, a: Sample): Sample {
        if (!a.available) return bad(out, a, a)   // not(UNAVAILABLE) must never become true
        return out.setBool(!a.flag, a.atMs, a.ageMs, a.coverage, a.gen)
    }

    /** `cond ? a : b`. UNAVAILABLE when the condition or the chosen branch is. */
    fun choose(out: Sample, cond: Sample, a: Sample, b: Sample): Sample {
        if (!cond.available) return bad(out, cond, cond)
        val pick = if (cond.flag) a else b
        if (!pick.available) return bad(out, pick, pick)
        out.copyFrom(pick)
        return out.meta(minOf(cond.atMs, pick.atMs), maxOf(cond.ageMs, pick.ageMs), minOf(cond.coverage, pick.coverage), maxOf(cond.gen, pick.gen), true)
    }

    fun clamp(out: Sample, a: Sample, lo: Double, hi: Double): Sample {
        if (!a.available) return bad(out, a, a)
        return out.setNum(if (a.num < lo) lo else if (a.num > hi) hi else a.num, a.atMs, a.ageMs, a.coverage, a.gen)
    }

    /** Passes [a] through while its age is at most [maxAgeMs] (inclusive); an older sample becomes UNAVAILABLE. */
    fun fresh(out: Sample, a: Sample, maxAgeMs: Long): Sample =
        if (a.error) bad(out, a, a) else if (!a.available || a.ageMs > maxAgeMs) out.setUnavailable() else out.copyFrom(a)

    /**
     * `a op b` on finite numbers. A non-finite operand or result is ERROR(NONFINITE); `x / 0` (either zero sign) is ERROR(DIV0), never 0.
     * The error is an ordinary result: the caller's role decides what it means (guard keeps the candidate, value takes the default).
     */
    fun arith(out: Sample, a: Sample, b: Sample, op: Int): Sample {
        if (!a.available || !b.available) return bad(out, a, b)
        val x = a.num; val y = b.num
        if (!x.isFinite() || !y.isFinite()) return out.setError(Sample.ERR_NONFINITE)
        if (op == DIV && y == 0.0) return out.setError(Sample.ERR_DIV0)
        val r = when (op) { ADD -> x + y; SUB -> x - y; MUL -> x * y; else -> x / y }
        if (!r.isFinite()) return out.setError(Sample.ERR_NONFINITE)
        return out.setNum(r, 0, 0, 1.0, 0).metaOf(a, b)
    }
}
