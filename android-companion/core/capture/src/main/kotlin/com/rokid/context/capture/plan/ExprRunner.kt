package com.rokid.context.capture.plan

import com.rokid.context.core.model.context.ContextState
import com.rokid.context.core.model.context.graph.Sample
import com.rokid.context.core.model.context.graph.StepOps
import com.rokid.context.core.model.context.graph.WindowRing
import com.rokid.context.glasses.capture.motion.model.SecondFeatures
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The last per-second features the glasses already compute (`SecondFeatures`, written once a second by the sensor feed). Read-only for the
 * runner; it reads no sensor and starts nothing (F3). Thread-safe, allocation-free.
 */
class ExprSignals {
    private val f = DoubleArray(SignalCatalog.featureNames.size)
    private var atMs = Long.MIN_VALUE
    /** The last [HIST] seconds of every feature as quantised Int32 slots ([INVALID] = no value), the source the window rings are fed from (3c C1b). */
    private val hist = IntArray(SignalCatalog.featureNames.size * HIST)
    private var histAtMs = Long.MIN_VALUE
    /** Absolute index of the newest slot since the last [reset] (0 = none). A missing second is an INVALID slot, never a repeat. */
    private var sec = 0L
    private var epoch = 0

    @Synchronized fun set(s: SecondFeatures, atMs: Long) {
        f[0] = s.gyroMax.toDouble(); f[1] = s.gyroMean.toDouble(); f[2] = s.gyroRms.toDouble(); f[3] = s.aboveOnFrac.toDouble()
        f[4] = s.accVar.toDouble(); f[5] = s.stepHz.toDouble(); f[6] = s.rotRad.toDouble()
        this.atMs = atMs
        // one slot per whole second of event time; a stall leaves INVALID slots, a repeat inside the same second is not a second slot
        val d = if (histAtMs == Long.MIN_VALUE) 1L else Math.round((atMs - histAtMs) / 1000.0)
        if (d < 1) return
        histAtMs = atMs
        for (k in 1 until minOf(d, HIST.toLong())) { sec++; for (i in f.indices) hist[i * HIST + (sec % HIST).toInt()] = INVALID }
        if (d > HIST) sec += d - HIST   // the skipped seconds are older than the history: they stay INVALID in effect
        sec++
        for (i in f.indices) hist[i * HIST + (sec % HIST).toInt()] = WindowRing.quantiseOr(f[i], INVALID)
    }

    /** A new capture session: the features and every window history start empty (3c C1b). Runners notice through the epoch and reset their rings. */
    @Synchronized fun reset() {
        java.util.Arrays.fill(f, 0.0); java.util.Arrays.fill(hist, INVALID)
        atMs = Long.MIN_VALUE; histAtMs = Long.MIN_VALUE; sec = 0L; epoch++
    }

    /** Feature [i] at [nowMs], or NaN when none was written yet or the last one is older than the catalogue's staleness bound. */
    @Synchronized internal fun read(i: Int, nowMs: Long): Double =
        if (atMs == Long.MIN_VALUE || nowMs - atMs > STALE_MS) Double.NaN else f[i]

    @Synchronized internal fun ageMs(nowMs: Long): Long = nowMs - atMs

    /** Brings [w] up to the newest slot: bound at the current slot on first use (the plan apply), reset on a new epoch, otherwise the slots it has not seen. */
    @Synchronized internal fun feed(w: WindowState) {
        if (w.epoch < 0) { w.epoch = epoch; w.cursor = sec; return }   // the apply: nothing before this slot is read
        if (w.epoch != epoch) { w.resetRings(); w.epoch = epoch; w.cursor = 0 }   // a session restart: the new history starts at slot 1
        if (sec - w.cursor > HIST) { w.invalidateAll(); w.cursor = sec - HIST }   // more than the history behind: everything missed is a gap
        while (w.cursor < sec) {
            w.cursor++
            val slot = (w.cursor % HIST).toInt()
            for (t in w.terms.indices) w.push(t, hist[w.terms[t].feature * HIST + slot])
        }
    }

    companion object { const val STALE_MS = 3_000L; const val HIST = WindowRing.MAX_SECONDS; internal const val INVALID = Int.MIN_VALUE }
}

/** The window rings of one runner (one per distinct term) and how far they have read the [ExprSignals] history. Built empty at plan apply. */
internal class WindowState(val terms: Array<ExprWindows.Term>) {
    val rings = Array(terms.size) { WindowRing(terms[it].seconds, terms[it].agg) }
    var cursor = -1L
    var epoch = -1
    val stateBytes: Int get() = terms.sumOf { it.stateBytes }

    fun resetRings() { for (r in rings) r.reset() }
    fun invalidateAll() { for (r in rings) repeat(r.seconds) { r.pushInvalid() } }
    fun push(t: Int, q: Int) {
        val r = rings[t]
        when {
            q == ExprSignals.INVALID -> r.pushInvalid()
            terms[t].agg == WindowRing.AGG_FRAC -> r.push(if (q > terms[t].thr) 1 else 0)
            else -> r.push(q)
        }
    }
}

/**
 * The glasses-side runner of `PlanConfig.exprs` (3c X3): GUARDS only. Evaluates each lowered program post-order over its `s` list with the shared
 * [StepOps] (the same functions as the rules graph), into preallocated [Sample]s, so an evaluation allocates nothing. It runs only when [keep] is
 * called, and [keep] is called only at a glance candidate that already happened; evaluations are limited to [MAX_EVAL_PER_S] per second (a call
 * inside the interval answers the previous verdict).
 *
 * A guard is DROP-ONLY: `false` drops the candidate; `true`, UNAVAILABLE and ERROR all keep it. An ERROR is counted ([exprErr], and [exprDiv0] for
 * `x / 0`, which never counts toward auto-revert). Signals read the existing features and the applied context state; a stale or missing feature is
 * UNAVAILABLE, never a default value. `in.set` state names map to wire codes through [ctxCode].
 */
class ExprRunner private constructor(private val progs: List<Prog>, private val win: WindowState?) {
    private class Prog(
        val id: String, val out: Int, val op: IntArray, val a: IntArray, val b: IntArray, val c: IntArray, val f: IntArray,
        val num: DoubleArray, val hi: DoubleArray, val ms: LongArray, val sig: IntArray, val sets: Array<IntArray?>, val flag: BooleanArray, val wi: IntArray,
    ) {
        val st = Array(op.size) { Sample() }
        var drops = 0
    }

    val guardIds: List<String> = progs.map { it.id }
    val size: Int get() = progs.size

    /** Evaluations that ended in a non-div0 ERROR / a div0 ERROR since the last [takeCounts] (and in total). */
    var exprErr = 0; private set
    var exprDiv0 = 0; private set
    /** Non-div0 errors since construction (never reset): what auto-revert counts. */
    var errTotal = 0L; private set
    /** Evaluations answered DROP, per guard (index = [guardIds]). In SHADOW these are would-drop counts. */
    fun drops(i: Int): Int = progs[i].drops
    val totalDrops: Int get() { var n = 0; for (p in progs) n += p.drops; return n }

    private val parr: Array<Prog> = progs.toTypedArray()
    private var lastEvalMs = Long.MIN_VALUE
    private var lastKeep = true

    /** Bytes of window state (rings and deques) this runner holds; 0 without window terms. */
    val windowStateBytes: Int get() = win?.stateBytes ?: 0
    val windowTerms: Int get() = win?.terms?.size ?: 0

    /** Binds the window rings to [sig] at this instant (the plan apply): they start empty and fill one second at a time. Idempotent. */
    fun bind(sig: ExprSignals) { win?.let { sig.feed(it) } }

    /** Reads the seconds written since the last call into the rings (the 1 Hz tick). Cheap; evaluations do it too, so a missed tick loses nothing. */
    fun tick(sig: ExprSignals) { win?.let { sig.feed(it) } }

    /** Returns and clears the counters since the last call (error, div0, drops). Not hot-path. */
    fun takeCounts(): IntArray {
        val r = intArrayOf(exprErr, exprDiv0, totalDrops)
        exprErr = 0; exprDiv0 = 0
        return r
    }

    /** True = keep the glance candidate; false = DROP it (and only that: nothing is ever force-captured). Never throws. */
    fun keep(nowMs: Long, sig: ExprSignals, ctx: ContextState, ctxAgeS: Long): Boolean {
        val arr = parr; val n = arr.size
        if (n == 0) return true
        val dt = nowMs - lastEvalMs
        if (lastEvalMs != Long.MIN_VALUE && dt >= 0 && dt < MIN_INTERVAL_MS) return lastKeep
        lastEvalMs = nowMs
        win?.let { sig.feed(it) }
        var keep = true
        for (pi in 0 until n) {
            val p = arr[pi]
            val r = run(p, nowMs, sig, ctx, ctxAgeS)
            if (r.error) { if (r.errorKind == Sample.ERR_DIV0) exprDiv0++ else { exprErr++; errTotal++ } }
            else if (r.available && r.type == Sample.Type.BOOL && !r.flag) { p.drops++; keep = false }
        }
        lastKeep = keep
        return keep
    }

    private fun run(p: Prog, now: Long, sig: ExprSignals, ctx: ContextState, ctxAgeS: Long): Sample {
        val st = p.st
        for (i in 0 until p.op.size) {
            val o = st[i]
            when (p.op[i]) {
                OP_SIG -> {
                    val k = p.sig[i]
                    if (k < FEATURES) { val v = sig.read(k, now); if (v.isNaN()) o.setUnavailable() else o.setNum(v, now, sig.ageMs(now), 1.0, 0) }
                    else if (k == SIG_CTX) o.setEnum(ctxCode(ctx), now, 0, 1.0, 0)
                    else o.setNum(ctxAgeS.toDouble(), now, 0, 1.0, 0)
                }
                OP_WIN -> { val v = if (sig.read(0, now).isNaN()) Double.NaN else win!!.rings[p.wi[i]].value(); if (v.isNaN()) o.setUnavailable() else o.setNum(v, now, sig.ageMs(now), 1.0, 0) }
                OP_CONST -> if (p.flag[i]) o.setBool(p.num[i] != 0.0, 0, 0, 1.0, 0) else o.setNum(p.num[i], 0, 0, 1.0, 0)
                OP_CMP -> StepOps.cmp(o, st[p.a[i]], st[p.b[i]], p.f[i])
                OP_ARITH -> StepOps.arith(o, st[p.a[i]], st[p.b[i]], p.f[i])
                OP_AND -> StepOps.and(o, st[p.a[i]], st[p.b[i]])
                OP_OR -> StepOps.or(o, st[p.a[i]], st[p.b[i]])
                OP_NOT -> StepOps.not(o, st[p.a[i]])
                OP_IN -> StepOps.inSet(o, st[p.a[i]], p.sets[i]!!)
                OP_CLAMP -> StepOps.clamp(o, st[p.a[i]], p.num[i], p.hi[i])
                OP_CHOOSE -> StepOps.choose(o, st[p.a[i]], st[p.b[i]], st[p.c[i]])
                else -> StepOps.fresh(o, st[p.a[i]], p.ms[i])
            }
        }
        return st[p.out]
    }

    companion object {
        /** At most two evaluations a second (F1). */
        const val MAX_EVAL_PER_S = 2
        const val MIN_INTERVAL_MS = 1_000L / MAX_EVAL_PER_S
        private val FEATURES = SignalCatalog.featureNames.size
        private val SIG_CTX = FEATURES
        private const val OP_SIG = 0; private const val OP_CONST = 1; private const val OP_CMP = 2; private const val OP_ARITH = 3
        private const val OP_AND = 4; private const val OP_OR = 5; private const val OP_NOT = 6; private const val OP_IN = 7
        private const val OP_CLAMP = 8; private const val OP_CHOOSE = 9; private const val OP_FRESH = 10; private const val OP_WIN = 11

        /** The wire code of a context state in `in` sets and `ctx` samples: its position in [ContextState]. */
        fun ctxCode(s: ContextState): Int = s.ordinal

        private val CMP = mapOf("<" to StepOps.LT, "<=" to StepOps.LE, ">" to StepOps.GT, ">=" to StepOps.GE, "==" to StepOps.EQ)
        private val ARITH = mapOf("+" to StepOps.ADD, "-" to StepOps.SUB, "*" to StepOps.MUL, "/" to StepOps.DIV)

        /** Builds the runner of the `guard` entries of [exprs]; null when there are none or an entry is malformed (no guard = capture unchanged). */
        fun of(exprs: List<JsonObject>): ExprRunner? {
            val g = exprs.filter { (it["role"] as? JsonPrimitive)?.content == "guard" }
            if (g.isEmpty()) return null
            return try {
                val terms = ExprWindows.termsOf(g) ?: return null
                val ws = if (terms.isEmpty()) null else WindowState(terms.toTypedArray())
                ExprRunner(g.map { parse(it, terms) }, ws)
            } catch (e: RuntimeException) { null }
        }

        private fun num(o: JsonObject, k: String): Double = (o[k] as JsonPrimitive).doubleOrNull ?: error("$k")
        private fun idx(o: JsonObject, k: String, own: Int): Int = (o[k] as JsonPrimitive).intOrNull?.also { require(it in 0 until own) } ?: error("$k")

        private fun parse(e: JsonObject, terms: List<ExprWindows.Term>): Prog {
            val s = e["s"] as JsonArray
            val n = s.size
            val op = IntArray(n); val a = IntArray(n); val b = IntArray(n); val c = IntArray(n); val f = IntArray(n)
            val nm = DoubleArray(n); val hi = DoubleArray(n); val ms = LongArray(n); val sg = IntArray(n)
            val sets = arrayOfNulls<IntArray>(n); val flag = BooleanArray(n); val wi = IntArray(n)
            for (i in 0 until n) {
                val x = s[i] as JsonObject
                when (val name = (x["op"] as JsonPrimitive).content) {
                    "sig" -> {
                        op[i] = OP_SIG
                        val cn = SignalCatalog.canonical((x["name"] as JsonPrimitive).content) ?: error("sig")
                        sg[i] = when (cn) { "ctx" -> SIG_CTX; "ctxAgeS" -> SIG_CTX + 1; else -> SignalCatalog.featureIndex(cn)!! }
                    }
                    "win" -> { op[i] = OP_WIN; wi[i] = terms.indexOf(ExprWindows.term(x) ?: error("win")) }
                    "const" -> {
                        op[i] = OP_CONST; val v = x["v"] as JsonPrimitive
                        val bl = v.booleanOrNull
                        if (bl != null) { flag[i] = true; nm[i] = if (bl) 1.0 else 0.0 } else nm[i] = v.doubleOrNull ?: error("const")
                    }
                    "cmp" -> { op[i] = OP_CMP; f[i] = CMP[(x["f"] as JsonPrimitive).content] ?: error("cmp"); a[i] = idx(x, "a", i); b[i] = idx(x, "b", i) }
                    "arith" -> { op[i] = OP_ARITH; f[i] = ARITH[(x["f"] as JsonPrimitive).content] ?: error("arith"); a[i] = idx(x, "a", i); b[i] = idx(x, "b", i) }
                    "and" -> { op[i] = OP_AND; a[i] = idx(x, "a", i); b[i] = idx(x, "b", i) }
                    "or" -> { op[i] = OP_OR; a[i] = idx(x, "a", i); b[i] = idx(x, "b", i) }
                    "not" -> { op[i] = OP_NOT; a[i] = idx(x, "a", i) }
                    "in" -> {
                        op[i] = OP_IN; a[i] = idx(x, "a", i)
                        sets[i] = (x["set"] as JsonArray).map { ctxCode(ContextState.valueOf((it as JsonPrimitive).content)) }.toIntArray()
                    }
                    "clamp" -> { op[i] = OP_CLAMP; a[i] = idx(x, "a", i); nm[i] = num(x, "lo"); hi[i] = num(x, "hi") }
                    "choose" -> { op[i] = OP_CHOOSE; a[i] = idx(x, "a", i); b[i] = idx(x, "b", i); c[i] = idx(x, "c", i) }
                    "fresh" -> { op[i] = OP_FRESH; a[i] = idx(x, "a", i); ms[i] = (x["ms"] as JsonPrimitive).longOrNull ?: error("ms") }
                    else -> error(name)
                }
            }
            val id = (e["id"] as JsonPrimitive).content
            val out = (e["out"] as JsonPrimitive).intOrNull ?: error("out")
            require(out in 0 until n)
            return Prog(id, out, op, a, b, c, f, nm, hi, ms, sg, sets, flag, wi)
        }
    }
}
