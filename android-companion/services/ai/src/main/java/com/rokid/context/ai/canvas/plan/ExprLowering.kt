package com.rokid.context.ai.canvas.plan

import com.rokid.context.capture.plan.ExprValues
import com.rokid.context.capture.plan.ExprWindows
import com.rokid.context.capture.plan.PlanState
import com.rokid.context.core.model.context.graph.WindowRing
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The lowering pass inside [PlanCompiler]: each `expr.guard` graph becomes one entry of `PlanConfig.exprs`,
 * `{id, role, out, s:[...]}`, where `s` is the step list in dependency order (post-order: an entry only refers to smaller indexes by `a`, `b`, `c`).
 * Entries are ordered by node id and every graph is ordered by dependency with a lexicographic tie-break, so the result (and the plan hash) never
 * depends on authoring order. A lowered program holds only pure ops over catalogue signals: there is no field that could name a capture, a timer,
 * a sensor or a row, so a guard can only drop a candidate (it cannot open the camera). [assertPure] checks that on every lowering.
 */
object ExprLowering {
    val OPS = setOf("sig", "const", "win", "cmp", "in", "and", "or", "not", "arith", "clamp", "choose", "fresh")
    private val KEYS = setOf("op", "a", "b", "c", "f", "v", "name", "set", "lo", "hi", "ms", "agg", "sec", "t", "mv")
    private val PROGRAM_KEYS = setOf("id", "role", "out", "s")
    private val VALUE_KEYS = PROGRAM_KEYS + setOf("row", "field")

    /** [windowTerms] are the distinct window terms over all programs, [stateBytes] their ring bytes (counted in the plan size cap). */
    data class Result(val exprs: List<JsonObject>, val problems: List<String>, val warnings: List<String>, val nodes: Int, val cost: Int, val windowTerms: Int = 0, val stateBytes: Int = 0)

    /** One expression node: a guard, or a value with its target (`row`, `field` = one policy field of one editable row, see [ExprValues]). */
    data class Entry(val id: String, val graph: JsonElement?, val role: ExprRole = ExprRole.GUARD, val row: String? = null, val field: String? = null)

    /** [graphs]: (canvas node id, its `graph` param). Problems and warnings read `node <id>: <CODE>: <text>`. */
    fun lower(graphs: List<Pair<String, JsonElement?>>, role: ExprRole = ExprRole.GUARD, windowMinValidPct: Int = ExprProgram.DEFAULT_MIN_VALID_PCT): Result =
        lowerAll(graphs.map { Entry(it.first, it.second, role) }, windowMinValidPct)

    /** Guards and values of one plan together: the 6 programs, 32 nodes and 4 window terms are shared limits over ALL of them. */
    fun lowerAll(entries: List<Entry>, windowMinValidPct: Int = ExprProgram.DEFAULT_MIN_VALID_PCT): Result {
        val problems = ArrayList<String>(); val warnings = ArrayList<String>()
        val exprs = ArrayList<JsonObject>()
        var nodes = 0; var cost = 0
        val terms = LinkedHashSet<ExprWindows.Term>()
        val targets = HashSet<String>()
        for ((n, e) in entries.sortedBy { it.id }.withIndex()) {
            val id = e.id; val el = e.graph; val role = e.role
            if (role == ExprRole.VALUE) {
                val bad = when {
                    e.row == "WALKING" || e.row == "UNKNOWN" -> "row ${e.row} is locked: a value program tunes only REST, VEHICLE_PASSENGER, VEHICLE_DRIVING or FLIGHT"
                    e.row == null || ExprValues.rowIndex(e.row) < 0 -> "row must be one of ${PlanState.entries.joinToString(", ")}"
                    e.field == null || ExprValues.fieldIndex(e.field) < 0 -> "field must be one of ${ExprValues.FIELDS.joinToString(", ")}"
                    !targets.add("${e.row}.${e.field}") -> "${e.row}.${e.field} is tuned by another value program"
                    else -> null
                }
                if (bad != null) { problems += "node $id: ${ExprDiag(ExprCode.EXPR_VALUE_TARGET, bad)}"; continue }
            }
            if (n >= ExprProgram.MAX_PROGRAMS) {
                problems += "node $id: ${ExprDiag(ExprCode.EXPR_TOO_MANY_PROGRAMS, "more than ${ExprProgram.MAX_PROGRAMS} expression programs")}"; continue
            }
            val (program, bad) = ExprProgram.parse(role, el)
            val c = program?.check(windowMinValidPct.coerceIn(WindowRing.MIN_VALID_PCT, 100))
            problems += (bad + (c?.problems ?: emptyList())).map { "node $id: $it" }
            warnings += (c?.warnings ?: emptyList()).map { "node $id: $it" }
            if (c == null || c.problems.isNotEmpty()) continue
            nodes += c.nodes; cost += c.cost
            if (nodes > ExprProgram.MAX_NODES) {
                problems += "node $id: ${ExprDiag(ExprCode.EXPR_TOO_MANY_NODES, "$nodes nodes in all programs (max ${ExprProgram.MAX_NODES})")}"; continue
            }
            val lowered = buildJsonObject {
                put("id", id); put("role", role.wire); put("out", c.out)
                if (role == ExprRole.VALUE) { put("row", e.row!!); put("field", e.field!!) }
                put("s", kotlinx.serialization.json.JsonArray(c.lowered))
            }
            // distinct (signal, aggregate, seconds[, threshold]) terms over ALL programs: the same term used twice shares one ring
            val own = ExprWindows.termsOf(listOf(lowered)) ?: emptyList()
            if ((terms + own).size > WindowRing.MAX_TERMS) {
                problems += "node $id: ${ExprDiag(ExprCode.EXPR_TOO_MANY_WINDOWS, "${(terms + own).size} distinct window terms in all programs (max ${WindowRing.MAX_TERMS})")}"; continue
            }
            terms += own
            exprs += lowered
        }
        if (problems.isEmpty()) exprs.forEach { assertPure(it) }
        return Result(if (problems.isEmpty()) exprs else emptyList(), problems, warnings, nodes, cost, terms.size, ExprWindows.stateBytes(terms.toList()))
    }

    /** The by-construction guarantee, asserted: only the pure op set and key set, so no lowered program can carry a capture. */
    fun assertPure(p: JsonObject) {
        check(p.keys == if ((p["role"] as? JsonPrimitive)?.content == "value") VALUE_KEYS else PROGRAM_KEYS) { "lowered program has unexpected keys" }
        for (s in (p["s"] as kotlinx.serialization.json.JsonArray)) {
            s as JsonObject
            check((s["op"] as JsonPrimitive).content in OPS) { "lowered op outside the pure set" }
            check(s.keys.all { it in KEYS }) { "lowered node has an unexpected key" }
        }
    }
}
