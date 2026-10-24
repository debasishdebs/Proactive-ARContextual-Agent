package com.rokid.context.capture.plan

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.floor

/**
 * TEST-ONLY straight-line reference evaluator of a lowered expression program (3c X9). Written from the SPEC (the 3c design decisions, plan section 10 and the E2a
 * handoff), not from `ExprRunner`: it keeps the whole history in plain maps, evaluates entry by entry with immutable values and uses none of the shared step code
 * (`StepOps`, `Sample`, `WindowRing`), so a bug in those cannot hide in both.
 *
 * Rules it encodes: a value is NUM, BOOL or ENUM with an age in ms (the oldest input's age), or UNAVAILABLE, or an ERROR (div0 or non-finite). Any ERROR or UNAVAILABLE
 * input makes the result ERROR/UNAVAILABLE (strict, not Kleene; the first error among the inputs wins). `choose` reads only the branch it picks. `fresh` passes a value whose
 * age is at most `ms` and is UNAVAILABLE for an older one. A feature is UNAVAILABLE when none was written yet or the last write is more than 3000 ms old. A window is
 * UNAVAILABLE ("not ready") unless EVERY one of its last `sec` seconds holds a value and the feed is not stale; mean = (sum of quantised/n)/1000, max = max quantised/1000,
 * frac = (seconds whose quantised value is above the quantised threshold)/n, quantised = round(x * 1000) clamped to +-2,000,000.
 */
class ExprReference(private val s: JsonArray) {
    sealed interface V {
        val age: Long get() = 0
        data class Num(val x: Double, override val age: Long = 0) : V
        data class Bool(val x: Boolean, override val age: Long = 0) : V
        data class Enm(val code: Int, override val age: Long = 0) : V
        data class Err(val div0: Boolean) : V
        object Unavailable : V
        /** UNAVAILABLE because a window is not full yet. */
        object NotReady : V
    }

    /** The world one evaluation sees: the seven features as written at each whole second (second index -> values), the time of the newest write, the context. */
    class World(val bySecond: Map<Long, DoubleArray>, val newestSecond: Long, val lastWriteMs: Long?, val ctxCode: Int = 0, val ctxAgeS: Long = 0)

    private fun bad(a: V, b: V = a): V = if (a is V.Err) a else if (b is V.Err) b else V.Unavailable
    private fun usable(v: V) = v is V.Num || v is V.Bool || v is V.Enm
    private fun q(x: Double): Long = floor(x * 1000.0 + 0.5).toLong().coerceIn(-2_000_000L, 2_000_000L)

    fun eval(w: World, nowMs: Long): List<V> {
        val r = ArrayList<V>(s.size)
        for (e in s) {
            e as JsonObject
            fun str(k: String) = (e[k] as JsonPrimitive).content
            fun ix(k: String) = (e[k] as JsonPrimitive).content.toInt()
            fun at(k: String) = r[ix(k)]
            val v: V = when (str("op")) {
                "sig" -> {
                    val name = SignalCatalog.canonical(str("name"))!!
                    when (name) {
                        "ctx" -> V.Enm(w.ctxCode)
                        "ctxAgeS" -> V.Num(w.ctxAgeS.toDouble())
                        else -> feature(w, SignalCatalog.featureNames.indexOf(name), nowMs)
                    }
                }
                "const" -> (e["v"] as JsonPrimitive).let { p -> p.booleanOrNull?.let { V.Bool(it) } ?: V.Num(p.doubleOrNull!!) }
                "win" -> window(w, e, nowMs)
                "cmp" -> {
                    val a = at("a"); val b = at("b")
                    if (a !is V.Num || b !is V.Num) bad(a, b) else {
                        val x = a.x; val y = b.x
                        V.Bool(when (str("f")) { "<" -> x < y; "<=" -> x <= y; ">" -> x > y; ">=" -> x >= y; else -> x == y }, maxOf(a.age, b.age))
                    }
                }
                "arith" -> {
                    val a = at("a"); val b = at("b")
                    if (a !is V.Num || b !is V.Num) bad(a, b)
                    else if (!a.x.isFinite() || !b.x.isFinite()) V.Err(div0 = false)
                    else if (str("f") == "/" && b.x == 0.0) V.Err(div0 = true)
                    else {
                        val z = when (str("f")) { "+" -> a.x + b.x; "-" -> a.x - b.x; "*" -> a.x * b.x; else -> a.x / b.x }
                        if (z.isFinite()) V.Num(z, maxOf(a.age, b.age)) else V.Err(div0 = false)
                    }
                }
                "and" -> { val a = at("a"); val b = at("b"); if (a !is V.Bool || b !is V.Bool) bad(a, b) else V.Bool(a.x && b.x, maxOf(a.age, b.age)) }
                "or" -> { val a = at("a"); val b = at("b"); if (a !is V.Bool || b !is V.Bool) bad(a, b) else V.Bool(a.x || b.x, maxOf(a.age, b.age)) }
                "not" -> { val a = at("a"); if (a !is V.Bool) bad(a) else V.Bool(!a.x, a.age) }
                "in" -> {
                    val a = at("a")
                    if (a !is V.Enm) bad(a) else V.Bool((e["set"] as JsonArray).any { com.rokid.context.core.model.context.ContextState.valueOf((it as JsonPrimitive).content).ordinal == a.code }, a.age)
                }
                "clamp" -> {
                    val a = at("a")
                    if (a !is V.Num) bad(a) else {
                        val lo = (e["lo"] as JsonPrimitive).doubleOrNull!!; val hi = (e["hi"] as JsonPrimitive).doubleOrNull!!
                        V.Num(if (a.x < lo) lo else if (a.x > hi) hi else a.x, a.age)
                    }
                }
                "choose" -> {
                    val c = at("a")
                    if (c !is V.Bool) bad(c) else {
                        val pick = if (c.x) at("b") else at("c")
                        when (pick) {
                            is V.Num -> V.Num(pick.x, maxOf(c.age, pick.age))
                            is V.Bool -> V.Bool(pick.x, maxOf(c.age, pick.age))
                            is V.Enm -> V.Enm(pick.code, maxOf(c.age, pick.age))
                            else -> bad(pick)
                        }
                    }
                }
                else -> { // fresh
                    val a = at("a")
                    val ms = (e["ms"] as JsonPrimitive).content.toLong()
                    if (a is V.Err) a else if (!usable(a) || a.age > ms) V.Unavailable else a
                }
            }
            r += v
        }
        return r
    }

    private fun feature(w: World, i: Int, nowMs: Long): V {
        val last = w.lastWriteMs ?: return V.Unavailable
        if (nowMs - last > 3000) return V.Unavailable
        val vals = w.bySecond[w.newestSecond] ?: return V.Unavailable
        return V.Num(vals[i], nowMs - last)
    }

    private fun window(w: World, e: JsonObject, nowMs: Long): V {
        val last = w.lastWriteMs ?: return V.NotReady
        if (nowMs - last > 3000) return V.NotReady
        val i = SignalCatalog.featureNames.indexOf(SignalCatalog.canonical((e["name"] as JsonPrimitive).content)!!)
        val n = (e["sec"] as JsonPrimitive).content.toInt()
        val qs = ArrayList<Long>(n)
        for (k in 0 until n) { val row = w.bySecond[w.newestSecond - k] ?: return V.NotReady; qs += q(row[i]) }
        val age = nowMs - last
        return when ((e["agg"] as JsonPrimitive).content) {
            "mean" -> V.Num((qs.sum().toDouble() / n) / 1000.0, age)
            "max" -> V.Num(qs.max() / 1000.0, age)
            else -> { val thr = q((e["t"] as JsonPrimitive).doubleOrNull!!); V.Num(qs.count { it > thr }.toDouble() / n, age) }
        }
    }
}
