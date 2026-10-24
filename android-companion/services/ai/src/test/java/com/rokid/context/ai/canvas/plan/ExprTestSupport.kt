package com.rokid.context.ai.canvas.plan

import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.canvas.Pipeline
import com.rokid.context.ai.canvas.PipelineEdge
import com.rokid.context.ai.canvas.PipelineNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/** Shared builders for the expression tests: graphs are written as JSON text, exactly what the canvas stores in `params.graph`. */
object ExprTestSupport {
    val none = JsonObject(emptyMap())
    fun json(s: String) = Json.parseToJsonElement(s)

    /** step json fragment: [inputs] and [nodes] and [wires] are the raw array bodies. */
    fun step(name: String, inputs: String, nodes: String, wires: String, out: String) =
        """{"name":"$name","inputs":[$inputs],"nodes":[$nodes],"wires":[$wires],"out":"$out"}"""

    fun graph(vararg steps: String, out: String) = """{"steps":[${steps.joinToString(",")}],"out":"$out"}"""

    /** One step: gyroMax > 1.5. */
    val FAST = step("fast", """{"name":"g","sig":"gyroMax"}""", """{"id":"k","op":"const","value":1.5},{"id":"c","op":"cmp","f":">"}""",
        """["g","c.a"],["k","c.b"]""", "c")
    val DRIVE = step("drive", """{"name":"x","sig":"ctx"}""", """{"id":"i","op":"in","set":["VEHICLE_DRIVING"]}""", """["x","i.a"]""", "i")
    val FINAL = step("final", """{"name":"f","step":"fast"},{"name":"d","step":"drive"}""",
        """{"id":"n","op":"not"},{"id":"o","op":"and"}""", """["d","n.a"],["f","o.a"],["n","o.b"]""", "o")
    /** Three steps: fast and not-driving. Reads ctx. */
    val G1 = graph(FINAL, DRIVE, FAST, out = "final")
    /** The same graph with every list in another order. */
    val G1_SHUFFLED = graph(
        FAST.replace("""["g","c.a"],["k","c.b"]""", """["k","c.b"],["g","c.a"]""").replace(
            """{"id":"k","op":"const","value":1.5},{"id":"c","op":"cmp","f":">"}""", """{"id":"c","op":"cmp","f":">"},{"id":"k","op":"const","value":1.5}"""),
        FINAL.replace("""["d","n.a"],["f","o.a"],["n","o.b"]""", """["n","o.b"],["f","o.a"],["d","n.a"]""").replace(
            """{"name":"f","step":"fast"},{"name":"d","step":"drive"}""", """{"name":"d","step":"drive"},{"name":"f","step":"fast"}"""),
        DRIVE, out = "final",
    )
    /** Only a motion threshold: no ctx. */
    val G_NOCTX = graph(FAST, out = "fast")

    fun parse(role: ExprRole, g: String) = ExprProgram.parse(role, json(g))

    /** parse and check; returns the diagnostics of both. */
    fun codes(g: String, role: ExprRole = ExprRole.GUARD): List<ExprCode> {
        val (p, d) = parse(role, g)
        return (d + (p?.check()?.problems ?: emptyList())).map { it.code }
    }

    fun guardNode(id: String, g: String) = PipelineNode(id, NodeTypes.EXPR_GUARD, id, buildJsonObject { put("graph", json(g)) })

    /** The default capture-half graph with the given expression nodes on the chain between head and threshold. */
    fun pipeline(vararg guards: PipelineNode, name: String = "P"): Pipeline {
        fun n(id: String, type: String, params: JsonObject = none) = PipelineNode(id, type, id, params)
        fun sig(id: String, type: String, d: String) = n(id, type, buildJsonObject { put("name", kotlinx.serialization.json.JsonPrimitive("ctx")); put("domain", kotlinx.serialization.json.JsonPrimitive(d)) })
        val ids = guards.map { it.id }
        val chain = if (ids.isEmpty()) listOf(PipelineEdge("head", "thresh")) else
            listOf(PipelineEdge("head", ids.first())) + ids.zipWithNext { a, b -> PipelineEdge(a, b) } + PipelineEdge(ids.last(), "thresh")
        return Pipeline(
            2, "p", name,
            listOf(
                n("head", NodeTypes.IMU_HEAD_MOTION), n("thresh", NodeTypes.GATE_THRESHOLD), n("settle", NodeTypes.IMU_SETTLE), n("novel", NodeTypes.GATE_NOVELTY),
                n("rate", NodeTypes.POLICY_CAPTURE_RATE), n("cap", NodeTypes.GATE_RATE_CAP), n("cam", NodeTypes.CAPTURE_IMAGE),
                sig("cin", NodeTypes.SIGNAL_IN, "RT_GLASSES"), n("hub", NodeTypes.HUB_CONTEXT), sig("cout", NodeTypes.SIGNAL_OUT, "RT_PHONE"),
            ) + guards,
            chain + listOf(
                PipelineEdge("thresh", "settle"), PipelineEdge("settle", "novel"), PipelineEdge("novel", "rate"), PipelineEdge("cin", "rate"),
                PipelineEdge("rate", "cap"), PipelineEdge("cap", "cam"), PipelineEdge("hub", "cout"),
            ),
        )
    }
}
