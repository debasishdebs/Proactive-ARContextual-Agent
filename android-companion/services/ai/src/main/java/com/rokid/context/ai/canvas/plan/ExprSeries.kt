package com.rokid.context.ai.canvas.plan

import com.rokid.context.capture.plan.ExprRunner.Probe
import com.rokid.context.capture.plan.ExprSignals
import com.rokid.context.capture.plan.SignalCatalog
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.glasses.capture.motion.model.SecondFeatures

/**
 * The derived-signal try-it over a recorded series (v1.2 row 2): ONE expression, evaluated once per second over [rows] with the SAME lowering and the SAME `ExprRunner` as [ExprTry]
 * (and so the glasses); there is no second evaluator. Pure: reads and writes nothing, no clock, no model. Raw numbers only ("Raw expression output"): nothing is clamped to a row field.
 *
 * A second without a row (a gap, or a second the caller EXCLUDED, for instance a zone-held one) is fed as all-NaN features: it counts as MISSING, it adds no value to any window, and
 * a window with fewer valid seconds than its tolerance asks is NOT_READY exactly as on the device. Excluded seconds are never passed in, so they cannot influence a state or a summary.
 * Bounded: at most [MAX_SECONDS] evaluated seconds, [MAX_POINTS] points returned.
 */
object ExprSeries {
    const val MAX_SECONDS = 1800
    const val MAX_POINTS = 300
    private const val T0 = 1_000_000L

    /** One recorded second: its phone-clock second, the seven features in [SignalCatalog.featureNames] order, and the context state name (null = none seen yet). */
    class Row(val second: Long, val features: DoubleArray, val context: String?)

    /** One second's answer. [kind] is the probe kind name, or MISSING for a second without a row. */
    class Cell(val second: Long, val kind: String, val num: Double = 0.0, val flag: Boolean = false)

    class Point(val second: Long, val state: String, val value: Double?, val min: Double?, val max: Double?, val flag: Boolean? = null)

    class Summary(
        val role: ExprRole, val states: Map<String, Int>,
        /** Seconds with a row (covered), seconds without (missing), seconds with a usable answer (valid = NUM or BOOL) and the denominator of every percentage (= valid). */
        val covered: Int, val missing: Int, val valid: Int,
        val min: Double?, val max: Double?, val mean: Double?, val trueCount: Int, val trueFrac: Double?,
        val contextDefaulted: Int,
    )

    sealed class Result {
        class Ok(val role: ExprRole, val cells: List<Cell>, val summary: Summary, val points: List<Point>, val truncated: Boolean, val windowStateBytes: Int, val windowTerms: Int) : Result()
        class Problems(val problems: List<String>) : Result()
        class Bad(val code: String, val msg: String) : Result()
    }

    /**
     * Evaluates [graph] over the seconds `fromS .. fromS + seconds - 1`. [rows] are the covered seconds (any order; rows outside the window are ignored; the first row of a second wins).
     * The role is a guard when the definition is a boolean, else a value (a number).
     */
    fun run(graph: kotlinx.serialization.json.JsonElement?, rows: List<Row>, fromS: Long, seconds: Int, windowMinValidPct: Int = WindowTolerance.current(), cfg: SignalConfig = SignalConfigs.current()): Result {
        if (seconds !in 1..MAX_SECONDS) return Result.Bad("bad_value", "The window is 1 to $MAX_SECONDS seconds.")
        var role = ExprRole.GUARD
        var prep = ExprTry.prepare(graph, role, null, null, windowMinValidPct, cfg)
        if (prep.problems.isNotEmpty() && graph is kotlinx.serialization.json.JsonObject) {
            val guard = ExprProgram.parse(ExprRole.GUARD, graph).first
            if (guard != null && guard.check().problems.let { p -> p.isNotEmpty() && p.all { it.code == ExprCode.EXPR_GUARD_NOT_BOOL } }) {
                role = ExprRole.VALUE
                prep = ExprTry.prepare(graph, role, "REST", "capPerMin", windowMinValidPct, cfg)   // the row and field only satisfy the lowering; the raw number is reported
            }
        }
        if (prep.problems.isNotEmpty()) return Result.Problems(prep.problems)
        val checked = prep.checked!!
        val runner = prep.runner ?: return Result.Bad("bad_request", "The program cannot run.")
        val byS = HashMap<Long, Row>()
        for (r in rows) if (r.second in fromS until fromS + seconds && r.features.size == SignalCatalog.featureNames.size) byS.putIfAbsent(r.second, r)
        val sig = ExprSignals()
        runner.bind(sig)
        val cells = ArrayList<Cell>(seconds)
        var ctx = ContextState.REST
        var ctxSince = 0
        var seenCtx: String? = null
        var defaulted = 0
        val nan = FloatArray(SignalCatalog.featureNames.size) { Float.NaN }
        for (t in 0 until seconds) {
            val s = fromS + t
            val row = byS[s]
            val f = row?.let { r -> FloatArray(r.features.size) { r.features[it].toFloat() } } ?: nan
            sig.set(SecondFeatures(f[0], f[1], f[2], f[3], f[4], f[5], f[6]), T0 + t * 1000L)
            runner.tick(sig)
            if (row != null) {
                if (row.context == null) defaulted++
                val named = row.context?.let { n -> ContextState.entries.firstOrNull { it.name == n } }
                if (named != null && row.context != seenCtx) { ctx = named; seenCtx = row.context; ctxSince = t }
            }
            if (row == null) { cells += Cell(s, "MISSING"); continue }
            val c = runner.probe(role == ExprRole.VALUE, 0, T0 + t * 1000L, sig, ctx, (t - ctxSince).toLong())[checked.out]
            cells += Cell(s, c.kind.name, if (c.kind == Probe.Kind.NUM) c.num else 0.0, c.kind == Probe.Kind.BOOL && c.flag)
        }
        val summary = summarise(role, cells, defaulted)
        val (points, truncated) = bucket(cells)
        return Result.Ok(role, cells, summary, points, truncated, runner.windowStateBytes, runner.windowTerms)
    }

    private fun summarise(role: ExprRole, cells: List<Cell>, defaulted: Int): Summary {
        val states = LinkedHashMap<String, Int>()
        for (k in listOf("NUM", "BOOL", "ENUM", "NOT_READY", "ERROR", "DIV0", "UNAVAILABLE", "MISSING")) states[k] = 0
        cells.forEach { states[it.kind] = (states[it.kind] ?: 0) + 1 }
        val nums = cells.filter { it.kind == "NUM" }.map { it.num }
        val bools = cells.filter { it.kind == "BOOL" }
        val valid = nums.size + bools.size
        val missing = states["MISSING"]!!
        val tc = bools.count { it.flag }
        return Summary(role, states, cells.size - missing, missing, valid, nums.minOrNull(), nums.maxOrNull(), nums.takeIf { it.isNotEmpty() }?.average(), tc, if (bools.isEmpty()) null else tc.toDouble() / bools.size, defaulted)
    }

    /** At most [MAX_POINTS] points: buckets of `ceil(n / 300)` seconds, each the LAST second's state, with min/max over a bucket's NUM seconds (BOOL: true when any second in the bucket was true is NOT used; the last state is). */
    private fun bucket(cells: List<Cell>): Pair<List<Point>, Boolean> {
        if (cells.isEmpty()) return emptyList<Point>() to false
        val size = (cells.size + MAX_POINTS - 1) / MAX_POINTS
        val out = ArrayList<Point>()
        for (i in cells.indices step size) {
            val b = cells.subList(i, minOf(i + size, cells.size))
            val last = b.last()
            val nums = b.filter { it.kind == "NUM" }.map { it.num }
            out += Point(last.second, last.kind, if (last.kind == "NUM") last.num else null, nums.minOrNull(), nums.maxOrNull(), if (last.kind == "BOOL") last.flag else null)
        }
        return out to (size > 1)
    }
}
