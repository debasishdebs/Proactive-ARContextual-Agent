package com.rokid.context.ai.canvas.plan

import com.rokid.context.capture.plan.SignalCatalog
import com.rokid.context.core.model.context.ContextState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

enum class ExprRole(val wire: String) { GUARD("guard"), VALUE("value") }

/** Every refusal of the expression graph has one stable code; [ExprDiag.toString] is what the compile view shows after `node <id>: `. */
enum class ExprCode {
    EXPR_NO_GRAPH, EXPR_SHAPE, EXPR_BAD_NAME, EXPR_DUP_NAME, EXPR_UNKNOWN_REF, EXPR_FOREIGN_NODE, EXPR_LOOP, EXPR_UNKNOWN_SIGNAL,
    EXPR_WRONG_TYPE, EXPR_PORT, EXPR_BAD_PARAM, EXPR_GUARD_NOT_BOOL, EXPR_TOO_MANY_NODES, EXPR_TOO_MANY_PROGRAMS, EXPR_COST, EXPR_BYTES,
    EXPR_GUARD_READS_CTX,
}

data class ExprDiag(val code: ExprCode, val text: String) { override fun toString() = "$code: $text" }

/** A step input: a catalogue signal, an earlier step's output, or a constant (number or boolean) wired into the box under [name]. */
data class ExprInput(val name: String, val kind: Kind, val ref: String = "", val num: Double = 0.0, val flag: Boolean = false) {
    enum class Kind { SIG, STEP, NUM, BOOL }
}

/** One catalogue node of a step; only the fields its [op] uses are set. */
data class ExprNode(
    val id: String, val op: String, val f: String = "", val value: Double = 0.0, val lo: Double = 0.0, val hi: Double = 0.0,
    val ms: Long = 0, val set: List<String> = emptyList(), val name: String = "", val flag: Boolean = false,
)

/** [to] is `node.port`; [from] is a node id or an input name of the same step. */
data class ExprWire(val from: String, val node: String, val port: String)

data class ExprStep(val name: String, val inputs: List<ExprInput>, val nodes: List<ExprNode>, val wires: List<ExprWire>, val out: String)

data class ExprChecked(
    val problems: List<ExprDiag>, val warnings: List<ExprDiag>, val stepOrder: List<String>,
    val nodes: Int, val cost: Int, val lowered: List<JsonObject>, val out: Int,
)

/**
 * The two-level expression graph of one `expr.guard` node (slice 3c X2). OUTER: named [steps] wired box to box (a step's inputs are
 * signals, constants or other steps). INNER: each step is a small graph of catalogue nodes with one [ExprStep.out]. Authoring order is
 * never meaningful: [check] orders steps and nodes by dependency with a lexicographic tie-break, so a loop is refused and a forward
 * reference cannot exist in what is lowered.
 */
data class ExprProgram(val role: ExprRole, val steps: List<ExprStep>, val out: String) {
    companion object {
        const val MAX_NODES = 32       // lowered nodes, over ALL programs of a plan
        const val MAX_PROGRAMS = 6
        const val MAX_COST = 64        // per program (options doc 4.4); 32 nodes cost at most 224, so the plan cap of 256 is implied
        val NAME = Regex("^[a-z][a-z0-9_]{0,23}$")

        private val ARITY = mapOf(
            "sig" to 0, "const" to 0, "not" to 1, "in" to 1, "clamp" to 1, "fresh" to 1, "cmp" to 2, "and" to 2, "or" to 2, "arith" to 2, "choose" to 3,
        )
        private val PORTS = listOf("a", "b", "c")
        private val CMP = setOf("<", "<=", ">", ">=", "==")
        private val ARITH = setOf("+", "-", "*", "/")
        private val STATES = ContextState.entries.map { it.name }.toSet()
        private const val MAX_CONST = 1_000_000.0
        private const val MAX_FRESH_MS = 60_000L

        private val GRAPH_KEYS = setOf("steps", "out")
        private val STEP_KEYS = setOf("name", "inputs", "nodes", "wires", "out")
        private val INPUT_KEYS = setOf("name", "sig", "step", "const")
        private val NODE_KEYS = setOf("id", "op", "f", "value", "lo", "hi", "ms", "set", "name")

        fun parse(role: ExprRole, el: JsonElement?): Pair<ExprProgram?, List<ExprDiag>> {
            val bad = ArrayList<ExprDiag>()
            fun shape(t: String) { bad += ExprDiag(ExprCode.EXPR_SHAPE, t) }
            if (el == null) return null to listOf(ExprDiag(ExprCode.EXPR_NO_GRAPH, "graph is missing"))
            val g = el as? JsonObject ?: return null to listOf(ExprDiag(ExprCode.EXPR_SHAPE, "graph must be an object"))
            g.keys.filter { it !in GRAPH_KEYS }.forEach { shape("unknown graph key ${shown(it)}") }
            val stepsEl = g["steps"] as? JsonArray
            val out = str(g["out"])
            if (stepsEl == null) shape("steps must be a list")
            if (out == null) shape("out must be a step name")
            val steps = ArrayList<ExprStep>()
            stepsEl?.forEachIndexed { i, s -> parseStep(i, s, bad)?.let { steps += it } }
            if (bad.isNotEmpty() || out == null) return null to bad
            return ExprProgram(role, steps, out) to emptyList()
        }

        private fun parseStep(i: Int, s: JsonElement, bad: MutableList<ExprDiag>): ExprStep? {
            val before = bad.size
            fun shape(t: String) { bad += ExprDiag(ExprCode.EXPR_SHAPE, "step $i: $t") }
            val o = s as? JsonObject ?: run { shape("must be an object"); return null }
            o.keys.filter { it !in STEP_KEYS }.forEach { shape("unknown key ${shown(it)}") }
            val name = str(o["name"]) ?: run { shape("name missing"); "" }
            val out = str(o["out"]) ?: run { shape("out missing"); "" }
            val inputs = (o["inputs"] as? JsonArray ?: run { shape("inputs must be a list"); null })?.mapNotNull { parseInput(i, it, bad) } ?: emptyList()
            val nodes = (o["nodes"] as? JsonArray ?: run { shape("nodes must be a list"); null })?.mapNotNull { parseNode(i, it, bad) } ?: emptyList()
            val wires = (o["wires"] as? JsonArray ?: run { shape("wires must be a list"); null })?.mapNotNull { parseWire(i, it, bad) } ?: emptyList()
            return if (bad.size == before) ExprStep(name, inputs, nodes, wires, out) else null
        }

        private fun parseInput(i: Int, e: JsonElement, bad: MutableList<ExprDiag>): ExprInput? {
            fun shape(t: String): ExprInput? { bad += ExprDiag(ExprCode.EXPR_SHAPE, "step $i input: $t"); return null }
            val o = e as? JsonObject ?: return shape("must be an object")
            o.keys.filter { it !in INPUT_KEYS }.forEach { return shape("unknown key ${shown(it)}") }
            val name = str(o["name"]) ?: return shape("name missing")
            val kinds = listOf("sig", "step", "const").filter { it in o }
            if (kinds.size != 1) return shape("exactly one of sig, step, const")
            return when (kinds[0]) {
                "sig" -> ExprInput(name, ExprInput.Kind.SIG, ref = str(o["sig"]) ?: return shape("sig must be a name"))
                "step" -> ExprInput(name, ExprInput.Kind.STEP, ref = str(o["step"]) ?: return shape("step must be a name"))
                else -> {
                    val p = o["const"] as? JsonPrimitive ?: return shape("const must be a number or true/false")
                    if (p.isString) return shape("const must be a number or true/false")
                    p.booleanOrNull?.let { return ExprInput(name, ExprInput.Kind.BOOL, flag = it) }
                    val d = p.doubleOrNull?.takeIf { it.isFinite() && Math.abs(it) <= MAX_CONST } ?: return shape("const must be a finite number within 1000000")
                    ExprInput(name, ExprInput.Kind.NUM, num = d)
                }
            }
        }

        private fun parseNode(i: Int, e: JsonElement, bad: MutableList<ExprDiag>): ExprNode? {
            fun shape(t: String): ExprNode? { bad += ExprDiag(ExprCode.EXPR_SHAPE, "step $i node: $t"); return null }
            fun param(t: String): ExprNode? { bad += ExprDiag(ExprCode.EXPR_BAD_PARAM, "step $i node: $t"); return null }
            val o = e as? JsonObject ?: return shape("must be an object")
            o.keys.filter { it !in NODE_KEYS }.forEach { return shape("unknown key ${shown(it)}") }
            val id = str(o["id"]) ?: return shape("id missing")
            val op = str(o["op"]) ?: return shape("op missing")
            if (op !in ARITY) return shape("unknown op ${shown(op)}")
            fun num(k: String): Double? = (o[k] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() && Math.abs(it) <= MAX_CONST }
            return when (op) {
                "sig" -> ExprNode(id, op, name = str(o["name"]) ?: return param("$id: name missing"))
                "const" -> {
                    val p = o["value"] as? JsonPrimitive
                    if (p != null && !p.isString && p.booleanOrNull != null) ExprNode(id, op, flag = p.booleanOrNull!!, f = "bool")
                    else ExprNode(id, op, value = num("value") ?: return param("$id: value must be a finite number within 1000000"))
                }
                "cmp" -> ExprNode(id, op, f = str(o["f"])?.takeIf { it in CMP } ?: return param("$id: f must be one of ${CMP.joinToString(" ")}"))
                "arith" -> ExprNode(id, op, f = str(o["f"])?.takeIf { it in ARITH } ?: return param("$id: f must be one of ${ARITH.joinToString(" ")}"))
                "in" -> {
                    val set = (o["set"] as? JsonArray)?.map { str(it) ?: return param("$id: set holds names") }
                    if (set == null || set.isEmpty() || set.size > STATES.size || set.any { it !in STATES }) return param("$id: set must list known states")
                    ExprNode(id, op, set = set.toSortedSet().toList())
                }
                "clamp" -> {
                    val lo = num("lo") ?: return param("$id: lo must be a finite number"); val hi = num("hi") ?: return param("$id: hi must be a finite number")
                    if (lo > hi) return param("$id: lo must not exceed hi")
                    ExprNode(id, op, lo = lo, hi = hi)
                }
                "fresh" -> ExprNode(id, op, ms = (o["ms"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it in 1..MAX_FRESH_MS }
                    ?: return param("$id: ms must be between 1 and $MAX_FRESH_MS"))
                else -> ExprNode(id, op)
            }
        }

        private fun parseWire(i: Int, e: JsonElement, bad: MutableList<ExprDiag>): ExprWire? {
            val pair = (e as? JsonArray)?.takeIf { it.size == 2 }?.map { str(it) }
            val from = pair?.get(0); val to = pair?.get(1)?.split('.')
            if (from == null || to == null || to.size != 2 || to[1] !in PORTS) {
                bad += ExprDiag(ExprCode.EXPR_SHAPE, "step $i wire: must be [from, \"node.port\"] with port a, b or c"); return null
            }
            return ExprWire(from, to[0], to[1])
        }

        private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun shown(s: String) = if (s.length > 40) s.take(40) + "..." else s

        /** Dependency order with a lexicographic tie-break; the second value is the keys that sit on or behind a loop. */
        internal fun topo(keys: Collection<String>, deps: Map<String, Set<String>>): Pair<List<String>, Set<String>> {
            val left = keys.toSortedSet()
            val done = ArrayList<String>()
            while (true) {
                val next = left.firstOrNull { k -> deps[k].orEmpty().none { it in left } } ?: break
                left.remove(next); done += next
            }
            return done to left
        }
    }

    private enum class Ty { NUM, BOOL, ENUM }
    private class Emit(val lowered: ArrayList<JsonObject> = ArrayList(), var cost: Int = 0)

    private fun nodeCost(n: ExprNode) = when (n.op) { "clamp", "choose", "fresh" -> 2; "in" -> 1 + n.set.size; else -> 1 }

    /** Validates the whole graph and lowers it to the runner's step list (see the spec section "expr.guard graph"). Pure and deterministic. */
    fun check(): ExprChecked {
        val problems = ArrayList<ExprDiag>()
        val warnings = ArrayList<ExprDiag>()
        fun bad(c: ExprCode, t: String) { problems += ExprDiag(c, t) }

        // outer names
        val names = HashSet<String>()
        for (s in steps) {
            if (!NAME.matches(s.name)) bad(ExprCode.EXPR_BAD_NAME, "step name ${shown(s.name)} must match [a-z][a-z0-9_]{0,23}")
            else if (!names.add(s.name)) bad(ExprCode.EXPR_DUP_NAME, "step name ${s.name} is used twice")
        }
        if (problems.isNotEmpty()) return ExprChecked(problems, warnings, emptyList(), 0, 0, emptyList(), -1)
        val byName = steps.associateBy { it.name }
        if (out !in byName) bad(ExprCode.EXPR_UNKNOWN_REF, "out names no step (${shown(out)})")
        val nodeOwner = HashMap<String, MutableSet<String>>()   // node id -> steps that hold it (to tell a foreign node from a typo)
        for (s in steps) for (n in s.nodes) nodeOwner.getOrPut(n.id) { HashSet() } += s.name

        // outer graph: step inputs must name steps; no loop
        val deps = HashMap<String, Set<String>>()
        for (s in steps) {
            val d = HashSet<String>()
            for (i in s.inputs) if (i.kind == ExprInput.Kind.STEP) {
                if (i.ref !in byName) bad(ExprCode.EXPR_UNKNOWN_REF, "step ${s.name}: input ${i.name} reads unknown step ${shown(i.ref)}") else d += i.ref
            }
            deps[s.name] = d
        }
        val (order, stuck) = topo(byName.keys, deps)
        if (stuck.isNotEmpty()) bad(ExprCode.EXPR_LOOP, "steps ${stuck.joinToString(", ")} read each other in a loop")
        if (problems.isNotEmpty()) return ExprChecked(problems, warnings, order, 0, 0, emptyList(), -1)

        val emit = Emit()
        val stepOut = HashMap<String, Pair<Int, Ty>>()   // step -> (index of its output in the lowered list, type)
        var readsCtx = false
        val failed = HashSet<String>()
        for (name in order) {
            val s = byName.getValue(name)
            if (deps[name].orEmpty().any { it in failed }) { failed += name; continue }   // its inputs are already reported
            val before = problems.size
            fun sbad(c: ExprCode, t: String) = bad(c, "step $name: $t")
            // inputs
            val inTy = HashMap<String, Ty>()
            val inputs = HashMap<String, ExprInput>()
            for (i in s.inputs) {
                if (!NAME.matches(i.name)) { sbad(ExprCode.EXPR_BAD_NAME, "input name ${shown(i.name)} must match [a-z][a-z0-9_]{0,23}"); continue }
                if (i.name in inputs || s.nodes.any { it.id == i.name }) { sbad(ExprCode.EXPR_DUP_NAME, "${i.name} is used twice in the step"); continue }
                inputs[i.name] = i
                when (i.kind) {
                    ExprInput.Kind.SIG -> {
                        val sig = SignalCatalog.byName(i.ref)
                        if (sig == null) sbad(ExprCode.EXPR_UNKNOWN_SIGNAL, "unknown signal ${shown(i.ref)}")
                        else inTy[i.name] = if (sig.type == SignalCatalog.Type.ENUM) Ty.ENUM else Ty.NUM
                    }
                    ExprInput.Kind.STEP -> inTy[i.name] = stepOut.getValue(i.ref).second
                    ExprInput.Kind.NUM -> inTy[i.name] = Ty.NUM
                    ExprInput.Kind.BOOL -> inTy[i.name] = Ty.BOOL
                }
            }
            // inner nodes
            val nodes = HashMap<String, ExprNode>()
            for (n in s.nodes) {
                if (!NAME.matches(n.id)) sbad(ExprCode.EXPR_BAD_NAME, "node id ${shown(n.id)} must match [a-z][a-z0-9_]{0,23}")
                else if (nodes.put(n.id, n) != null) sbad(ExprCode.EXPR_DUP_NAME, "node id ${n.id} is used twice in the step")
                if (n.op == "sig" && SignalCatalog.byName(n.name) == null) sbad(ExprCode.EXPR_UNKNOWN_SIGNAL, "unknown signal ${shown(n.name)}")
            }
            // wires: port -> source
            val feed = HashMap<String, MutableMap<String, String>>()
            for (w in s.wires) {
                val target = nodes[w.node]
                if (target == null) { sbad(if (w.node in nodeOwner) ExprCode.EXPR_FOREIGN_NODE else ExprCode.EXPR_UNKNOWN_REF, "wire into unknown node ${shown(w.node)}"); continue }
                if (w.from !in nodes && w.from !in inputs) {
                    sbad(if (w.from in nodeOwner) ExprCode.EXPR_FOREIGN_NODE else ExprCode.EXPR_UNKNOWN_REF, "wire from ${shown(w.from)}: not a node or input of this step"); continue
                }
                if (PORTS.indexOf(w.port) >= ARITY.getValue(target.op)) { sbad(ExprCode.EXPR_PORT, "node ${w.node} has no port ${w.port}"); continue }
                if (feed.getOrPut(w.node) { HashMap() }.put(w.port, w.from) != null) sbad(ExprCode.EXPR_PORT, "port ${w.node}.${w.port} is wired twice")
            }
            for (n in nodes.values.sortedBy { it.id }) for (p in PORTS.take(ARITY.getValue(n.op))) {
                if (feed[n.id]?.get(p) == null) sbad(ExprCode.EXPR_PORT, "port ${n.id}.$p is not wired")
            }
            if (s.out !in nodes) sbad(ExprCode.EXPR_UNKNOWN_REF, "out names no node (${shown(s.out)})")
            if (problems.size > before) { failed += name; continue }
            val (nOrder, nStuck) = topo(nodes.keys, nodes.keys.associateWith { id -> feed[id].orEmpty().values.filter { it in nodes }.toSet() })
            if (nStuck.isNotEmpty()) { sbad(ExprCode.EXPR_LOOP, "nodes ${nStuck.sorted().joinToString(", ")} feed each other in a loop"); failed += name; continue }

            // type check and lowering in one pass; an input becomes a lowered node when first used
            val idx = HashMap<String, Int>(); val types = HashMap<String, Ty>()
            val inIdx = HashMap<String, Int>()
            fun source(from: String): Int {
                nodes[from]?.let { return idx.getValue(it.id) }
                return inIdx.getOrPut(from) {
                    val i = inputs.getValue(from)
                    when (i.kind) {
                        ExprInput.Kind.STEP -> stepOut.getValue(i.ref).first
                        ExprInput.Kind.SIG -> {
                            val c = SignalCatalog.canonical(i.ref)!!
                            if (c == "ctx" || c == "ctxAgeS") readsCtx = true
                            add(emit, buildJsonObject { put("op", "sig"); put("name", c) }, 1)
                        }
                        ExprInput.Kind.NUM -> add(emit, buildJsonObject { put("op", "const"); put("v", number(i.num)) }, 1)
                        ExprInput.Kind.BOOL -> add(emit, buildJsonObject { put("op", "const"); put("v", i.flag) }, 1)
                    }
                }
            }
            fun tyOf(from: String): Ty = types[from] ?: inTy.getValue(from)
            for (id in nOrder) {
                val n = nodes.getValue(id)
                val a = feed[id]?.get("a"); val b = feed[id]?.get("b"); val c = feed[id]?.get("c")
                fun need(port: String, from: String?, want: Ty): Boolean {
                    if (from == null) return true
                    val got = tyOf(from)
                    if (got != want) { sbad(ExprCode.EXPR_WRONG_TYPE, "node $id: port $port needs ${want.name.lowercase()} but gets ${got.name.lowercase()}"); return false }
                    return true
                }
                var ok = true
                val result: Ty = when (n.op) {
                    "sig" -> SignalCatalog.byName(n.name)?.let { if (it.type == SignalCatalog.Type.ENUM) Ty.ENUM else Ty.NUM } ?: Ty.NUM
                    "const" -> if (n.f == "bool") Ty.BOOL else Ty.NUM
                    "cmp" -> { ok = need("a", a, Ty.NUM) and need("b", b, Ty.NUM); Ty.BOOL }
                    "arith" -> { ok = need("a", a, Ty.NUM) and need("b", b, Ty.NUM); Ty.NUM }
                    "and", "or" -> { ok = need("a", a, Ty.BOOL) and need("b", b, Ty.BOOL); Ty.BOOL }
                    "not" -> { ok = need("a", a, Ty.BOOL); Ty.BOOL }
                    "in" -> { ok = need("a", a, Ty.ENUM); Ty.BOOL }
                    "clamp" -> { ok = need("a", a, Ty.NUM); Ty.NUM }
                    "fresh" -> tyOf(a!!)
                    else -> {   // choose
                        ok = need("a", a, Ty.BOOL)
                        val t = tyOf(b!!)
                        if (t == Ty.ENUM) { sbad(ExprCode.EXPR_WRONG_TYPE, "node $id: choose yields a number or a boolean"); ok = false }
                        ok = need("c", c, t) && ok
                        t
                    }
                }
                if (!ok) { types[id] = result; idx[id] = -1; continue }
                types[id] = result
                val o = buildJsonObject {
                    put("op", n.op)
                    when (n.op) {
                        "sig" -> { put("name", SignalCatalog.canonical(n.name)!!) ; if (SignalCatalog.canonical(n.name) in listOf("ctx", "ctxAgeS")) readsCtx = true }
                        "const" -> if (n.f == "bool") put("v", n.flag) else put("v", number(n.value))
                        else -> {
                            if (n.f.isNotEmpty()) put("f", n.f)
                            a?.let { put("a", source(it)) }; b?.let { put("b", source(it)) }; c?.let { put("c", source(it)) }
                            if (n.op == "in") put("set", JsonArray(n.set.map { JsonPrimitive(it) }))
                            if (n.op == "clamp") { put("lo", number(n.lo)); put("hi", number(n.hi)) }
                            if (n.op == "fresh") put("ms", n.ms)
                        }
                    }
                }
                idx[id] = add(emit, o, nodeCost(n))
            }
            if (problems.size > before) { failed += name; continue }
            val outTy = types.getValue(s.out)
            if (outTy == Ty.ENUM) { sbad(ExprCode.EXPR_WRONG_TYPE, "the step output must be a number or a boolean"); failed += name; continue }
            stepOut[name] = idx.getValue(s.out) to outTy
        }
        if (problems.isNotEmpty()) return ExprChecked(problems, warnings, order, emit.lowered.size, emit.cost, emptyList(), -1)

        val (outIdx, outTy) = stepOut.getValue(out)
        when (role) {
            ExprRole.GUARD -> if (outTy != Ty.BOOL) bad(ExprCode.EXPR_GUARD_NOT_BOOL, "a guard must end in a boolean (the output of step $out is ${outTy.name.lowercase()})")
            ExprRole.VALUE -> if (outTy != Ty.NUM) bad(ExprCode.EXPR_WRONG_TYPE, "a value must end in a number (the output of step $out is ${outTy.name.lowercase()})")
        }
        if (emit.lowered.size > MAX_NODES) bad(ExprCode.EXPR_TOO_MANY_NODES, "${emit.lowered.size} nodes (max $MAX_NODES)")
        if (emit.cost > MAX_COST) bad(ExprCode.EXPR_COST, "evaluation cost ${emit.cost} (max $MAX_COST)")
        if (role == ExprRole.GUARD && readsCtx) {
            warnings += ExprDiag(ExprCode.EXPR_GUARD_READS_CTX, "this guard reads ctx: it may drop glances while the state is wrong (walking and the 60 s timer photo attempt still run)")
        }
        return ExprChecked(problems, warnings, order, emit.lowered.size, emit.cost, emit.lowered, outIdx)
    }

    private fun add(e: Emit, o: JsonObject, cost: Int): Int { e.lowered += o; e.cost += cost; return e.lowered.size - 1 }
    private fun number(d: Double): JsonPrimitive = if (d == Math.floor(d) && Math.abs(d) < 1e9) JsonPrimitive(d.toLong()) else JsonPrimitive(d)
    private fun shown(s: String) = if (s.length > 40) s.take(40) + "..." else s
}
