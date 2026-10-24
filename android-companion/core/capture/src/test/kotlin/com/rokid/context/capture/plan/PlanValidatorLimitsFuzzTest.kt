package com.rokid.context.capture.plan

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 3c X9: the glasses-side limits under random plans. The glasses do not trust the phone compiler, so for random hand-built plans the validator must refuse EVERY plan
 * that breaks a limit (nodes over all programs <= 32, cost <= 64 per program, programs <= 6, window terms <= 4, plan bytes plus window state <= 8192) and accept every plan
 * that keeps them all. The expected verdict is computed here from the spec's numbers (plan section 10 and the E2a handoff), never from the validator.
 */
class PlanValidatorLimitsFuzzTest {
    private class Gen(val json: JsonObject, val nodes: Int, val cost: Int, val terms: Set<String>, val stateBytes: Int)

    private val signals = SignalCatalog.featureNames
    private val states = com.rokid.context.core.model.context.ContextState.entries.map { it.name }

    /** One program of [n] entries. Every entry is well formed; the unit costs are the spec's: clamp, choose, fresh and win 2, `in` 1 + the set size, the rest 1. */
    private fun program(r: Random, id: String, n: Int, role: String, target: String): Gen {
        val s = ArrayList<String>(); var cost = 0; val terms = HashSet<String>(); var state = 0
        s += """{"op":"const","v":1}"""; cost += 1
        if (n > 1) { s += """{"op":"sig","name":"ctx"}"""; cost += 1 }   // the enum the `in` entries read
        val heavy = r.nextInt(5) == 0   // mostly long windows: reaches the 8192-byte cap through window state alone
        while (s.size < n) {
            when (if (heavy) 3 else r.nextInt(6)) {
                0 -> { s += """{"op":"clamp","a":${r.nextInt(s.size)},"lo":0,"hi":9}"""; cost += 2 }
                1 -> { s += """{"op":"arith","f":"+","a":${r.nextInt(s.size)},"b":${r.nextInt(s.size)}}"""; cost += 1 }
                2 -> { s += """{"op":"sig","name":"${signals[r.nextInt(signals.size)]}"}"""; cost += 1 }
                3 -> {
                    val agg = listOf("max", "mean")[r.nextInt(2)]; val sec = if (heavy) 300 else listOf(1, 5, 10, 60, 300)[r.nextInt(5)]; val name = signals[r.nextInt(signals.size)]
                    s += """{"op":"win","name":"$name","agg":"$agg","sec":$sec}"""; cost += 2
                    if (terms.add("$name|$agg|$sec")) state += (if (agg == "max") 8 else 4) * sec
                }
                4 -> { val set = states.shuffled(r).take(1 + r.nextInt(6)).sorted(); s += """{"op":"in","a":1,"set":[${set.joinToString(",") { "\"$it\"" }}]}"""; cost += 1 + set.size }
                else -> { s += """{"op":"fresh","a":${r.nextInt(s.size)},"ms":1000}"""; cost += 2 }
            }
        }
        return Gen(Json.parseToJsonElement("""{"id":"$id","role":"$role"$target,"out":${s.size - 1},"s":[${s.joinToString(",")}]}""").jsonObject, s.size, cost, terms, state)
    }

    private fun verdict(progs: List<Gen>): Pair<List<String>, Int> {
        val hasWin = progs.any { it.terms.isNotEmpty() }
        val c = PlanResolver.identity().copy(exprs = progs.map { it.json }, sigSchema = if (hasWin || progs.any { it.json["role"].toString() == "\"value\"" }) SIG_SCHEMA_WIN else 1)
        val p = CapturePlan(planSchemaFor(c), PlanJson.configHash(c), c, PlanMeta("t", 1))
        val text = PlanJson.encode(p)
        return PlanValidator.check(text).first to text.toByteArray().size
    }

    @Test fun overLimitPlansAreAlwaysRefusedAndPlansWithinEveryLimitAreAccepted() {
        var refused = 0; var accepted = 0
        for (seed in 1L..600L) {
            val r = Random(seed)
            val count = 1 + r.nextInt(8)                       // up to 8 programs: 7 and 8 are over the 6-program limit
            val progs = (0 until count).map { i ->
                val value = i < 4 && r.nextInt(3) == 0
                // sizes: mostly small, sometimes large enough to break the node, cost or byte limits
                val n = if (r.nextInt(6) == 0) 20 + r.nextInt(60) else 1 + r.nextInt(9)
                program(r, "p$i", n, if (value) "value" else "guard", if (value) ""","row":"REST","field":"${ExprValues.FIELDS[i]}"""" else "")
            }
            val (problems, bytes) = verdict(progs)
            val allTerms = progs.flatMap { it.terms }.toSet()
            val state = progs.flatMap { g -> g.terms.map { it to 0 } }.map { it.first }.toSet().sumOf { t -> val (_, agg, sec) = t.split("|"); (if (agg == "max") 8 else 4) * sec.toInt() }
            val over = progs.sumOf { it.nodes } > 32 || progs.any { it.cost > 64 } || progs.size > 6 || allTerms.size > 4 || bytes + state > PLAN_MAX_BYTES
            if (over) { refused++; assertTrue(problems.isNotEmpty(), "seed=$seed: an over-limit plan was ACCEPTED (nodes ${progs.sumOf { it.nodes }}, costs ${progs.map { it.cost }}, ${progs.size} programs, ${allTerms.size} terms, $bytes bytes + $state state)") }
            else { accepted++; assertEquals(emptyList(), problems, "seed=$seed: a plan within every limit was refused") }
        }
        assertTrue(refused >= 100 && accepted >= 100, "both outcomes must be exercised: $refused refused, $accepted accepted")
    }

    @Test fun oneOverTheLimitIsRefusedAndExactlyAtTheLimitIsAccepted() {
        fun consts(n: Int) = (0 until n).joinToString(",") { """{"op":"const","v":$it}""" }
        fun g(n: Int, id: String) = Gen(Json.parseToJsonElement("""{"id":"$id","role":"guard","out":0,"s":[${consts(n)}]}""").jsonObject, n, n, emptySet(), 0)
        assertEquals(emptyList(), verdict(listOf(g(32, "a"))).first)
        assertTrue(verdict(listOf(g(33, "a"))).first.isNotEmpty())
        assertEquals(emptyList(), verdict(listOf(g(16, "a"), g(16, "b"))).first)
        assertTrue(verdict(listOf(g(16, "a"), g(17, "b"))).first.isNotEmpty())
        assertEquals(emptyList(), verdict((0 until 6).map { g(5, "p$it") }).first)
        assertTrue(verdict((0 until 7).map { g(1, "p$it") }).first.isNotEmpty())
    }
}
