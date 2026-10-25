package com.rokid.context.ai.canvas.plan

import com.rokid.context.capture.plan.ExprRunner
import com.rokid.context.capture.plan.ExprRunner.Probe
import com.rokid.context.capture.plan.ExprSignals
import com.rokid.context.capture.plan.ExprValues
import com.rokid.context.capture.plan.SignalCatalog
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.core.model.context.graph.WindowRing
import com.rokid.context.glasses.capture.motion.model.SecondFeatures
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The phone-evaluated try-it (3c X8): runs ONE expression program through the SAME [ExprRunner] code the glasses use (no second evaluator) on the values the
 * user gave for the program's signals, and reports every step's value. Window terms need history: a signal is a constant (held for as many seconds as the
 * longest window needs) or a list of per-second values (oldest first, the last one is "now"; a null is a missing second); all lists of one request have the same length. A window whose horizon has not
 * elapsed, or that has fewer valid seconds than its tolerance asks, is NOT_READY, exactly like a warming window on the device. Input is bounded: [MAX_SIGNALS] signals,
 * [MAX_VALUES] values each, [MAX_BYTES] of body (the route checks the size). Nothing here reads or writes any state.
 */
object ExprTry {
    const val MAX_SIGNALS = 8
    const val MAX_VALUES = 300
    const val MAX_BYTES = 64 * 1024
    const val MAX_ABS = 1_000_000.0
    private const val T0 = 1_000_000L

    sealed class Outcome {
        class Ok(val body: JsonObject) : Outcome()
        /** A typed refusal: [code] is the error code of the route's 400, [msg] a fixed text (nothing of the input is echoed). */
        class Bad(val code: String, val msg: String, val problems: List<String> = emptyList()) : Outcome()
    }

    private fun bad(code: String, msg: String, problems: List<String> = emptyList()) = Outcome.Bad(code, msg, problems)
    private const val NUMBERS = "Every value is a number within 1000000."

    /**
     * [body]: `{graph, role?: "guard"|"value", row?, field?, signals: {name: number | [number...]}, ctx?: <state>, ctxAgeS?: number}`.
     * A program that does not compile answers `{ok:false, problems}` (not a refusal); a bad request is [Outcome.Bad].
     */
    fun run(body: JsonObject, windowMinValidPct: Int = WindowTolerance.current()): Outcome {
        val role = when ((body["role"] as? JsonPrimitive)?.content ?: "guard") { "guard" -> ExprRole.GUARD; "value" -> ExprRole.VALUE; else -> return bad("bad_request", "Role must be guard or value.") }
        val sigs = body["signals"] as? JsonObject ?: return bad("bad_request", "signals must be an object.")
        if (sigs.size > MAX_SIGNALS) return bad("too_many_signals", "At most $MAX_SIGNALS signals.")
        val series = HashMap<String, DoubleArray>()
        var len = 0
        for ((k, v) in sigs) {
            val name = SignalCatalog.canonical(k)?.takeIf { SignalCatalog.featureIndex(it) != null } ?: return bad("unknown_signal", "A signal is not one of the seven per-second features.")
            if (name in series) return bad("bad_request", "A signal is given twice.")
            val xs = if (v is JsonArray) {
                if (v.isEmpty() || v.size > MAX_VALUES) return bad("too_many_values", "A signal list holds 1 to $MAX_VALUES values.")
                if (len != 0 && len != v.size) return bad("ragged_signals", "Signal lists must all have the same length.")
                len = v.size
                DoubleArray(v.size) { if (v[it] is JsonNull) Double.NaN else num(v[it]) ?: return bad("bad_value", NUMBERS) }   // null = a missing second
            } else doubleArrayOf(num(v) ?: return bad("bad_value", NUMBERS))
            series[name] = xs
        }
        val ctx = (body["ctx"] as? JsonPrimitive)?.content?.let { c -> ContextState.entries.firstOrNull { it.name == c } ?: return bad("bad_request", "ctx is not a known state.") } ?: ContextState.REST
        val age = body["ctxAgeS"]?.let { num(it)?.takeIf { a -> a >= 0 } ?: return bad("bad_value", "ctxAgeS is a number from 0 to 1000000.") } ?: 0.0

        val field = (body["field"] as? JsonPrimitive)?.content
        val low = ExprLowering.lowerAll(listOf(ExprLowering.Entry("try", body["graph"], role, (body["row"] as? JsonPrimitive)?.content, field)), windowMinValidPct)
        if (low.problems.isNotEmpty() || low.exprs.isEmpty()) return Outcome.Ok(buildJsonObject { put("ok", false); putJsonArray("problems") { low.problems.forEach { add(JsonPrimitive(it)) } } })
        val checked = ExprProgram.parse(role, body["graph"]).first!!.check(windowMinValidPct.coerceIn(WindowRing.MIN_VALID_PCT, 100))
        val runner = ExprRunner.of(low.exprs) ?: return bad("bad_request", "The program cannot run.")

        val read = (low.exprs[0]["s"] as JsonArray).map { it as JsonObject }.filter { (it["op"] as JsonPrimitive).content.let { op -> op == "sig" || op == "win" } }
            .map { (it["name"] as JsonPrimitive).content }.filter { SignalCatalog.featureIndex(it) != null }.distinct()
        val missing = read.filter { it !in series }
        if (missing.isNotEmpty()) return bad("missing_signal", "Give a value for every signal the program reads.", missing)

        val seconds = if (len > 0) len else maxOf(1, runner.windowHorizonS)
        val sig = ExprSignals()
        runner.bind(sig)
        for (t in 0 until seconds) {
            val f = FloatArray(SignalCatalog.featureNames.size) { i -> series[SignalCatalog.featureNames[i]]?.let { xs -> xs[if (xs.size == 1) 0 else t].toFloat() } ?: 0f }
            sig.set(SecondFeatures(f[0], f[1], f[2], f[3], f[4], f[5], f[6]), T0 + t * 1000L)
            runner.tick(sig)
        }
        val cells = runner.probe(role == ExprRole.VALUE, 0, T0 + (seconds - 1) * 1000L, sig, ctx, age.toLong())
        fun cell(c: Probe) = buildJsonObject {
            when (c.kind) {
                Probe.Kind.NUM -> { put("state", "NUM"); put("value", c.num) }
                Probe.Kind.BOOL -> { put("state", "BOOL"); put("value", c.flag) }
                Probe.Kind.ENUM -> { put("state", "ENUM"); put("value", ContextState.entries[c.code].name) }
                else -> put("state", c.kind.name)
            }
        }
        val out = cells[checked.out]
        return Outcome.Ok(buildJsonObject {
            put("ok", true); put("seconds", seconds); put("windowStateBytes", runner.windowStateBytes); put("windowTerms", runner.windowTerms)
            putJsonArray("steps") { for (name in checked.stepOrder) add(buildJsonObject { put("name", name); checked.stepOuts[name]?.let { put("result", cell(cells[it])) } }) }
            put("output", cell(out))
            if (role == ExprRole.VALUE && out.kind == Probe.Kind.NUM && field != null) put("applied", if (field == "capPerMin") ExprValues.clampCap(out.num).toDouble() else ExprValues.clampGapMs(out.num) / 1000.0)
            if (role == ExprRole.GUARD) put("verdict", if (out.kind == Probe.Kind.BOOL && !out.flag) "drop" else "keep")
        })
    }

    private fun num(e: JsonElement?): Double? = (e as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() && Math.abs(it) <= MAX_ABS }
}
