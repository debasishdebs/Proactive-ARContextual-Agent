package com.rokid.context.capture.plan

import com.rokid.context.core.model.context.ContextPolicies
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.glasses.capture.motion.model.SecondFeatures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.math.floor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 3c X9: seeded differential fuzz of [ExprRunner] against the straight-line [ExprReference]. Random typed programs (every op, windows, choose, fresh), random traces
 * (gaps, stale reads, late reads), every entry compared after every second, the guard verdict against `keep`, the value against `tune`. Seeds are fixed, so a failure
 * reproduces; the message names the seed and the program.
 */
class ExprDifferentialFuzzTest {
    private enum class Ty { NUM, BOOL, ENUM }
    private val features = SignalCatalog.featureNames
    private val nums = listOf(-2.0, -1.0, 0.0, 0.5, 1.0, 1.5, 2.0, 3.0, 10.0, 1000.0)
    private val states = ContextState.entries.map { it.name }

    private class Prog(val json: JsonObject, val windows: Int)

    private fun gen(r: Random, role: String): Prog {
        val sb = ArrayList<String>(); val ty = ArrayList<Ty>()
        val terms = HashSet<String>()
        fun idx(t: Ty) = ty.indices.filter { ty[it] == t }
        fun add(t: Ty, j: String) { sb += j; ty += t }
        val n = 2 + r.nextInt(14)
        while (sb.size < n) {
            val nu = idx(Ty.NUM); val bo = idx(Ty.BOOL); val en = idx(Ty.ENUM)
            when (r.nextInt(14)) {
                0, 1 -> add(Ty.NUM, """{"op":"sig","name":"${features[r.nextInt(features.size)]}"}""")
                2 -> if (r.nextInt(3) == 0) add(Ty.ENUM, """{"op":"sig","name":"ctx"}""") else add(Ty.NUM, """{"op":"sig","name":"ctxAgeS"}""")
                3 -> if (r.nextBoolean()) add(Ty.NUM, """{"op":"const","v":${nums[r.nextInt(nums.size)]}}""") else add(Ty.BOOL, """{"op":"const","v":${r.nextBoolean()}}""")
                4, 5 -> {
                    val agg = listOf("max", "mean", "frac")[r.nextInt(3)]
                    val term = """{"op":"win","name":"${features[r.nextInt(features.size)]}","agg":"$agg","sec":${listOf(1, 2, 3, 5, 8, 12)[r.nextInt(6)]}${if (agg == "frac") ""","t":${nums[r.nextInt(6)]}""" else ""}}"""
                    if (terms.size < 4 || term in terms) { terms += term; add(Ty.NUM, term) }
                }
                6 -> if (nu.size >= 2) add(Ty.BOOL, """{"op":"cmp","f":"${listOf("<", "<=", ">", ">=", "==")[r.nextInt(5)]}","a":${nu.random(r)},"b":${nu.random(r)}}""")
                7 -> if (nu.size >= 2) add(Ty.NUM, """{"op":"arith","f":"${listOf("+", "-", "*", "/")[r.nextInt(4)]}","a":${nu.random(r)},"b":${nu.random(r)}}""")
                8 -> if (bo.size >= 2) add(Ty.BOOL, """{"op":"${if (r.nextBoolean()) "and" else "or"}","a":${bo.random(r)},"b":${bo.random(r)}}""")
                9 -> if (bo.isNotEmpty()) add(Ty.BOOL, """{"op":"not","a":${bo.random(r)}}""")
                10 -> if (en.isNotEmpty()) add(Ty.BOOL, """{"op":"in","a":${en.random(r)},"set":[${states.shuffled(r).take(1 + r.nextInt(3)).sorted().joinToString(",") { "\"$it\"" }}]}""")
                11 -> if (nu.isNotEmpty()) { val lo = nums[r.nextInt(6)]; add(Ty.NUM, """{"op":"clamp","a":${nu.random(r)},"lo":$lo,"hi":${lo + nums[r.nextInt(6)].let { if (it < 0) 0.0 else it }}}""") }
                12 -> if (bo.isNotEmpty() && nu.size >= 2) add(Ty.NUM, """{"op":"choose","a":${bo.random(r)},"b":${nu.random(r)},"c":${nu.random(r)}}""")
                    else if (bo.size >= 3) add(Ty.BOOL, """{"op":"choose","a":${bo.random(r)},"b":${bo.random(r)},"c":${bo.random(r)}}""")
                else -> if (sb.isNotEmpty()) { val i = r.nextInt(sb.size); add(ty[i], """{"op":"fresh","a":$i,"ms":${listOf(1, 500, 1000, 2500, 5000)[r.nextInt(5)]}}""") }
            }
        }
        val want = if (role == "guard") Ty.BOOL else Ty.NUM
        val outs = idx(want)
        val out = if (outs.isEmpty()) { sb += if (want == Ty.BOOL) """{"op":"const","v":true}""" else """{"op":"const","v":2}"""; ty += want; sb.size - 1 } else outs.last()
        val target = if (role == "value") ""","row":"REST","field":"${ExprValues.FIELDS[r.nextInt(5)]}"""" else ""
        return Prog(Json.parseToJsonElement("""{"id":"p","role":"$role"$target,"out":$out,"s":[${sb.joinToString(",")}]}""").jsonObject, terms.size)
    }

    private fun <T> List<T>.random(r: Random): T = this[r.nextInt(size)]

    private fun kindOf(v: ExprReference.V): String = when (v) {
        is ExprReference.V.Num -> "NUM"; is ExprReference.V.Bool -> "BOOL"; is ExprReference.V.Enm -> "ENUM"
        is ExprReference.V.Err -> if (v.div0) "DIV0" else "ERROR"
        ExprReference.V.Unavailable, ExprReference.V.NotReady -> "UNAV"
    }

    private fun kindOf(p: ExprRunner.Probe): String = when (p.kind) {
        ExprRunner.Probe.Kind.NUM -> "NUM"; ExprRunner.Probe.Kind.BOOL -> "BOOL"; ExprRunner.Probe.Kind.ENUM -> "ENUM"
        ExprRunner.Probe.Kind.ERROR -> "ERROR"; ExprRunner.Probe.Kind.DIV0 -> "DIV0"
        ExprRunner.Probe.Kind.NOT_READY, ExprRunner.Probe.Kind.UNAVAILABLE -> "UNAV"
    }

    /** One seeded case: the same random trace into the runner and the reference; every entry is compared at several read times after each second. */
    private fun case(seed: Long, role: String) {
        val r = Random(seed)
        val prog = gen(r, role)
        val steps = prog.json["s"] as JsonArray
        val outIdx = (prog.json["out"] as JsonPrimitive).content.toInt()
        val runner = ExprRunner.of(listOf(prog.json))!!
        val ref = ExprReference(steps)
        val sig = ExprSignals()
        runner.bind(sig)
        val palette = listOf(0.0, 0.5, 1.0, 1.5, 2.0, 2.5, 3.0, 10.0)
        val bySecond = HashMap<Long, DoubleArray>()
        val t0 = 100_000L
        var sec = 0L; var lastMs: Long? = null
        val ctx = ContextState.entries[r.nextInt(ContextState.entries.size)]
        val ctxAge = r.nextInt(5).toLong()
        fun world() = ExprReference.World(bySecond, sec, lastMs, ctx.ordinal, ctxAge)
        /** [verdict]: also compare the guard's keep() (calls are at least 500 ms apart, so the 2/s limit never answers from the previous verdict). */
        fun compare(now: Long, where: String, verdict: Boolean = true) {
            val want = ref.eval(world(), now)
            val got = runner.probe(role == "value", 0, now, sig, ctx, ctxAge)
            val at = "seed=$seed $where now=$now program=${prog.json}"
            assertEquals(want.size, got.size, at)
            for (i in want.indices) {
                assertEquals(kindOf(want[i]), kindOf(got[i]), "entry $i: $at")
                when (val w = want[i]) {
                    is ExprReference.V.Num -> assertEquals(w.x, got[i].num, "entry $i: $at")
                    is ExprReference.V.Bool -> assertEquals(w.x, got[i].flag, "entry $i: $at")
                    is ExprReference.V.Enm -> assertEquals(w.code, got[i].code, "entry $i: $at")
                    ExprReference.V.NotReady -> if (steps[i].jsonObject["op"] == JsonPrimitive("win")) assertEquals(ExprRunner.Probe.Kind.NOT_READY, got[i].kind, "entry $i: $at")
                    else -> Unit
                }
            }
            if (role == "guard" && verdict) {
                val o = want[outIdx]
                assertEquals(!(o is ExprReference.V.Bool && !o.x), runner.keep(now, sig, ctx, ctxAge), "keep: $at")
            }
        }
        compare(t0 - 5_000, "before any write")
        repeat(3 + r.nextInt(22)) { k ->
            if (r.nextInt(8) == 0) sec += 1 + r.nextInt(2)   // a missed second or two: gaps invalidate every window that covers them
            sec += 1
            val at = t0 + sec * 1000
            val ff = FloatArray(features.size) { (floor(palette[r.nextInt(palette.size)] * 10) + r.nextInt(3)).toFloat() / 10f }
            bySecond[sec] = DoubleArray(features.size) { ff[it].toDouble() }
            lastMs = at
            sig.set(SecondFeatures(ff[0], ff[1], ff[2], ff[3], ff[4], ff[5], ff[6]), at)
            if (r.nextBoolean()) runner.tick(sig)
            compare(at, "after write $k")
            if (r.nextInt(4) == 0) compare(at + 700, "late read", verdict = false)
            if (r.nextInt(6) == 0) { compare(at + 2_900, "almost stale", verdict = false); compare(at + 3_100, "stale") }   // keep() reads are >= 500 ms apart or skipped
        }
        if (role == "value") tuned(prog.json, runner, sig, lastMs!! + 100, ctx, ctxAge, ref.eval(world(), lastMs!! + 100)[outIdx])
    }

    private fun tuned(json: JsonObject, runner: ExprRunner, sig: ExprSignals, now: Long, ctx: ContextState, age: Long, out: ExprReference.V) {
        val field = (json["field"] as JsonPrimitive).content
        runner.tickValues(now, sig, ctx, age)
        val base = ContextPolicies.DEFAULT.getValue(ContextState.REST)
        val p = runner.tune(ContextState.REST, base)
        if (out is ExprReference.V.Num) {
            if (field == "capPerMin") assertEquals(floor(out.x + 0.5).toInt().coerceIn(1, 4), p.capPerMin, "$json")
            else {
                val ms = floor(out.x * 1000.0 + 0.5).toLong().coerceIn(5_000L, 300_000L)
                val got = when (field) { "settledGapS" -> p.minSettledCheckMs; "quietGapS" -> p.minQuietCheckMs; "walkGapS" -> p.minWalkCheckMs; else -> p.minStillCapMs }
                assertEquals(ms, got, "$json")
            }
        } else assertEquals(base, p, "an error or an unavailable value keeps the row: $json")
    }

    @Test fun guardsAgainstTheReferenceOverRandomProgramsAndTraces() {
        for (seed in 1L..3000L) case(seed, "guard")
    }

    @Test fun valuesAgainstTheReferenceAndTheRowClamp() {
        for (seed in 5001L..7500L) case(seed, "value")
    }

    @Test fun theGeneratorReallyCoversEveryOpAndWindows() {
        val seen = HashSet<String>(); var windows = 0
        for (seed in 1L..1500L) {
            val p = gen(Random(seed), "guard")
            (p.json["s"] as JsonArray).forEach { seen += it.jsonObject["op"].toString() }
            windows += p.windows
        }
        assertEquals(setOf("sig", "const", "win", "cmp", "arith", "and", "or", "not", "in", "clamp", "choose", "fresh").map { "\"$it\"" }.toSet(), seen)
        assertTrue(windows > 300, "windows in $windows programs")
    }
}
