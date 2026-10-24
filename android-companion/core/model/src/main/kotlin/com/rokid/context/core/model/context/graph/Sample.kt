package com.rokid.context.core.model.context.graph

/**
 * One value flowing through a step graph or an expression program (rules-graph "Shared interfaces"). Mutable and reused: the step
 * functions in [StepOps] write into a preallocated output [Sample], so evaluation never allocates. A sample that is not [available]
 * is UNAVAILABLE: every logic step treats it as not satisfied and propagates it, never as a stale true.
 *
 * Exactly one of [num], [flag], [code] is meaningful, chosen by [type]. [code] is an enum wire code.
 */
class Sample {
    enum class Type { NUM, BOOL, ENUM }

    var type: Type = Type.NUM; private set
    var num: Double = 0.0; private set
    var flag: Boolean = false; private set
    var code: Int = 0; private set
    var atMs: Long = 0; private set
    var ageMs: Long = 0; private set
    /** 0..1: how much of the intended window or input this value is built from. */
    var coverage: Double = 1.0; private set
    var gen: Long = 0; private set
    var available: Boolean = false; private set

    fun setNum(v: Double, atMs: Long, ageMs: Long, coverage: Double, gen: Long): Sample {
        type = Type.NUM; num = v; flag = false; code = 0; return meta(atMs, ageMs, coverage, gen, true)
    }

    fun setBool(v: Boolean, atMs: Long, ageMs: Long, coverage: Double, gen: Long): Sample {
        type = Type.BOOL; flag = v; num = 0.0; code = 0; return meta(atMs, ageMs, coverage, gen, true)
    }

    fun setEnum(v: Int, atMs: Long, ageMs: Long, coverage: Double, gen: Long): Sample {
        type = Type.ENUM; code = v; num = 0.0; flag = false; return meta(atMs, ageMs, coverage, gen, true)
    }

    fun setUnavailable(): Sample {
        num = 0.0; flag = false; code = 0; return meta(0, 0, 0.0, 0, false)
    }

    /** Copies [o] (type, value, metadata, availability). */
    fun copyFrom(o: Sample): Sample {
        type = o.type; num = o.num; flag = o.flag; code = o.code
        return meta(o.atMs, o.ageMs, o.coverage, o.gen, o.available)
    }

    internal fun meta(atMs: Long, ageMs: Long, coverage: Double, gen: Long, available: Boolean): Sample {
        this.atMs = atMs; this.ageMs = ageMs; this.coverage = coverage; this.gen = gen; this.available = available; return this
    }

    /** Metadata of a result built from [a] and [b]: the oldest age, the least coverage, the newest gen. */
    internal fun metaOf(a: Sample, b: Sample): Sample =
        meta(minOf(a.atMs, b.atMs), maxOf(a.ageMs, b.ageMs), minOf(a.coverage, b.coverage), maxOf(a.gen, b.gen), true)
}
