package com.rokid.context.ai.canvas.plan

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
    val OPS = setOf("sig", "const", "cmp", "in", "and", "or", "not", "arith", "clamp", "choose", "fresh")
    private val KEYS = setOf("op", "a", "b", "c", "f", "v", "name", "set", "lo", "hi", "ms")
    private val PROGRAM_KEYS = setOf("id", "role", "out", "s")

    data class Result(val exprs: List<JsonObject>, val problems: List<String>, val warnings: List<String>, val nodes: Int, val cost: Int)

    /** [graphs]: (canvas node id, its `graph` param). Problems and warnings read `node <id>: <CODE>: <text>`. */
    fun lower(graphs: List<Pair<String, JsonElement?>>, role: ExprRole = ExprRole.GUARD): Result {
        val problems = ArrayList<String>(); val warnings = ArrayList<String>()
        val exprs = ArrayList<JsonObject>()
        var nodes = 0; var cost = 0
        for ((n, g) in graphs.sortedBy { it.first }.withIndex()) {
            val (id, el) = g
            if (n >= ExprProgram.MAX_PROGRAMS) {
                problems += "node $id: ${ExprDiag(ExprCode.EXPR_TOO_MANY_PROGRAMS, "more than ${ExprProgram.MAX_PROGRAMS} expression programs")}"; continue
            }
            val (program, bad) = ExprProgram.parse(role, el)
            val c = program?.check()
            problems += (bad + (c?.problems ?: emptyList())).map { "node $id: $it" }
            warnings += (c?.warnings ?: emptyList()).map { "node $id: $it" }
            if (c == null || c.problems.isNotEmpty()) continue
            nodes += c.nodes; cost += c.cost
            if (nodes > ExprProgram.MAX_NODES) {
                problems += "node $id: ${ExprDiag(ExprCode.EXPR_TOO_MANY_NODES, "$nodes nodes in all programs (max ${ExprProgram.MAX_NODES})")}"; continue
            }
            exprs += buildJsonObject {
                put("id", id); put("role", role.wire); put("out", c.out)
                put("s", kotlinx.serialization.json.JsonArray(c.lowered))
            }
        }
        if (problems.isEmpty()) exprs.forEach { assertPure(it) }
        return Result(if (problems.isEmpty()) exprs else emptyList(), problems, warnings, nodes, cost)
    }

    /** The by-construction guarantee, asserted: only the pure op set and key set, so no lowered program can carry a capture. */
    fun assertPure(p: JsonObject) {
        check(p.keys == PROGRAM_KEYS) { "lowered program has unexpected keys" }
        for (s in (p["s"] as kotlinx.serialization.json.JsonArray)) {
            s as JsonObject
            check((s["op"] as JsonPrimitive).content in OPS) { "lowered op outside the pure set" }
            check(s.keys.all { it in KEYS }) { "lowered node has an unexpected key" }
        }
    }
}
