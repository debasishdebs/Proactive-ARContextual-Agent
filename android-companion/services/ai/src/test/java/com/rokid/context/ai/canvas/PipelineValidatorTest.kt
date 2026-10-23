package com.rokid.context.ai.canvas

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineValidatorTest {
    private val none = JsonObject(emptyMap())
    private fun photos() = PipelineNode("photos", NodeTypes.INPUT_PHOTOS, "Photo", none)
    private fun extract(id: String = "extract", params: JsonObject = none) = PipelineNode(id, NodeTypes.VISION_EXTRACT, id, params)
    private fun node(id: String, type: String, params: JsonObject = none) = PipelineNode(id, type, id, params)
    private fun pipe(nodes: List<PipelineNode>, edges: List<PipelineEdge>) = Pipeline(1, "p", "P", nodes, edges)
    private fun minimal() = pipe(listOf(photos(), extract()), listOf(PipelineEdge("photos", "extract")))
    private fun problems(p: Pipeline) = PipelineValidator.validate(p)
    private fun assertHas(p: Pipeline, text: String) { val r = problems(p); assertTrue("expected '$text' in $r", text in r) }
    private fun promptSwitch(cases: List<String>) = node("sw", NodeTypes.SWITCH, buildJsonObject { put("field", "activity"); put("cases", JsonArray(cases.map { JsonPrimitive(it) })) })
    private val schema = "{\"type\":\"object\"}"
    private fun aiPrompt(id: String = "intent", extra: JsonObject.() -> Map<String, kotlinx.serialization.json.JsonElement> = { emptyMap() }) =
        node(id, NodeTypes.PROMPT, JsonObject(none.extra()))

    @Test fun validMinimalPipeline() {
        assertEquals(emptyList<String>(), problems(minimal()))
        val full = pipe(
            listOf(
                photos(), extract(), node("trust", NodeTypes.LOCAL_CRITIC), node("critic", NodeTypes.CRITIC), node("write", NodeTypes.MEMORY_WRITE),
                aiPrompt("intent") { mapOf("schema" to JsonPrimitive(schema)) }, promptSwitch(listOf("a", "b")),
                extract("ea"),
            ),
            listOf(
                PipelineEdge("photos", "extract"), PipelineEdge("extract", "trust"), PipelineEdge("trust", "write", "trusted"),
                PipelineEdge("trust", "critic", "unsure"), PipelineEdge("critic", "write", "pass"), PipelineEdge("critic", "write", "fail"),
                PipelineEdge("photos", "intent"), PipelineEdge("intent", "sw"), PipelineEdge("sw", "ea", "a"),
            ),
        )
        assertEquals(emptyList<String>(), problems(full))
    }

    @Test fun duplicateIds() {
        assertHas(pipe(listOf(photos(), extract(), extract()), listOf(PipelineEdge("photos", "extract"))), "duplicate node id extract")
    }

    @Test fun unknownEdgeEnd() {
        assertHas(pipe(listOf(photos(), extract()), listOf(PipelineEdge("photos", "extract"), PipelineEdge("photos", "x"))), "edge photos->x: unknown node x")
        assertHas(pipe(listOf(photos(), extract()), listOf(PipelineEdge("photos", "extract"), PipelineEdge("y", "extract"))), "edge y->extract: unknown node y")
    }

    @Test fun exactlyOneInput() {
        assertHas(pipe(listOf(extract()), emptyList()), "exactly one input.photos required")
        assertHas(pipe(listOf(photos(), node("p2", NodeTypes.INPUT_PHOTOS), extract()), listOf(PipelineEdge("photos", "extract"))), "exactly one input.photos required")
    }

    @Test fun inputHasNoIncoming() {
        val p = pipe(listOf(photos(), extract()), listOf(PipelineEdge("photos", "extract"), PipelineEdge("extract", "photos")))
        assertHas(p, "node photos: input must have no incoming edge")
    }

    @Test fun cycleRejected() {
        val p = pipe(
            listOf(photos(), extract(), node("trust", NodeTypes.LOCAL_CRITIC)),
            listOf(PipelineEdge("photos", "extract"), PipelineEdge("extract", "trust"), PipelineEdge("trust", "extract", "unsure")),
        )
        assertTrue(problems(p).any { it.startsWith("cycle through ") })
        assertHas(p, "cycle through extract")
    }

    @Test fun portTypeMismatch() {
        // EXTRACTION output into a node that only accepts JSON (switch)
        val p = pipe(listOf(photos(), extract(), promptSwitch(listOf("a"))), listOf(PipelineEdge("photos", "extract"), PipelineEdge("extract", "sw")))
        assertHas(p, "edge extract->sw: EXTRACTION not accepted by sw")
    }

    @Test fun mixedIncomingTypes() {
        val p = pipe(
            listOf(photos(), extract(), aiPrompt("intent") { mapOf("schema" to JsonPrimitive(schema)) }, extract("e2")),
            listOf(PipelineEdge("photos", "extract"), PipelineEdge("photos", "intent"), PipelineEdge("extract", "e2"), PipelineEdge("photos", "e2")),
        )
        assertHas(p, "node e2: incoming edges carry different types")
    }

    @Test fun branchEdgeNeedsKnownWhen() {
        val base = listOf(photos(), extract(), node("trust", NodeTypes.LOCAL_CRITIC), node("write", NodeTypes.MEMORY_WRITE))
        val e = listOf(PipelineEdge("photos", "extract"), PipelineEdge("extract", "trust"))
        assertHas(pipe(base, e + PipelineEdge("trust", "write")), "edge trust->write: when required (trusted, unsure)")
        assertHas(pipe(base, e + PipelineEdge("trust", "write", "bogus")), "edge trust->write: unknown when bogus")
    }

    @Test fun plainEdgeMustNotHaveWhen() {
        assertHas(pipe(listOf(photos(), extract()), listOf(PipelineEdge("photos", "extract", "x"))), "edge photos->extract: when not allowed")
    }

    @Test fun unreachableNode() {
        assertHas(pipe(listOf(photos(), extract(), extract("lonely")), listOf(PipelineEdge("photos", "extract"))), "node lonely: unreachable")
    }

    @Test fun batchRange() {
        listOf(0, 4).forEach { b ->
            val p = pipe(listOf(photos(), extract(params = buildJsonObject { put("batch", b) })), listOf(PipelineEdge("photos", "extract")))
            assertHas(p, "node extract: batch must be 1..3")
        }
        val ok = pipe(listOf(photos(), extract(params = buildJsonObject { put("batch", 1) })), listOf(PipelineEdge("photos", "extract")))
        assertEquals(emptyList<String>(), problems(ok))
    }

    @Test fun draftSchemaMustBeObject() {
        fun withSchema(s: String) = pipe(
            listOf(photos(), extract(params = buildJsonObject { put("prompt", buildJsonObject { put("source", "draft"); put("template", "T"); put("schema", s) }) })),
            listOf(PipelineEdge("photos", "extract")),
        )
        assertHas(withSchema("[1]"), "node extract: schema must be a JSON object")
        assertHas(withSchema("nope"), "node extract: schema must be a JSON object")
        assertEquals(emptyList<String>(), problems(withSchema("{}")))
    }

    @Test fun promptNodeRequiresSchema() {
        val missing = pipe(listOf(photos(), aiPrompt()), listOf(PipelineEdge("photos", "intent")))
        assertHas(missing, "node intent: schema required")
        val bad = pipe(listOf(photos(), aiPrompt { mapOf("schema" to JsonPrimitive("[]")) }), listOf(PipelineEdge("photos", "intent")))
        assertHas(bad, "node intent: schema must be a JSON object")
    }

    @Test fun switchCasesLimits() {
        fun p(cases: List<String>) = pipe(
            listOf(photos(), aiPrompt("intent") { mapOf("schema" to JsonPrimitive(schema)) }, promptSwitch(cases)),
            listOf(PipelineEdge("photos", "intent"), PipelineEdge("intent", "sw")),
        )
        val msg = "node sw: cases must be 1..12 unique labels like ids"
        assertHas(p(emptyList()), msg)
        assertHas(p((1..13).map { "c$it" }), msg)
        assertHas(p(listOf("Bad Label")), msg)
        assertHas(p(listOf("a", "a")), msg)
        assertHas(p(listOf("other")), msg)
        assertEquals(emptyList<String>(), problems(p((1..12).map { "c$it" })))
    }

    @Test fun embedRoleRejected() {
        val p = pipe(
            listOf(photos(), aiPrompt { mapOf("schema" to JsonPrimitive(schema), "role" to JsonPrimitive("EMBED")) }),
            listOf(PipelineEdge("photos", "intent")),
        )
        assertHas(p, "node intent: role not allowed")
        val ok = pipe(
            listOf(photos(), aiPrompt { mapOf("schema" to JsonPrimitive(schema), "role" to JsonPrimitive("CRITIC")) }),
            listOf(PipelineEdge("photos", "intent")),
        )
        assertEquals(emptyList<String>(), problems(ok))
    }

    @Test fun problemsCarryNodeIds() {
        val unknownEnd = pipe(listOf(photos(), extract()), listOf(PipelineEdge("photos", "extract"), PipelineEdge("photos", "x")))
        val e = PipelineValidator.problems(unknownEnd).single { it.text == "edge photos->x: unknown node x" }
        assertEquals(PipelineEdge("photos", "x"), e.edge); assertEquals(null, e.nodeId)
        val badBatch = pipe(listOf(photos(), extract(params = buildJsonObject { put("batch", 9) })), listOf(PipelineEdge("photos", "extract")))
        val b = PipelineValidator.problems(badBatch).single()
        assertEquals("node extract: batch must be 1..3", b.text); assertEquals("extract", b.nodeId); assertEquals(null, b.edge)
        val unreachable = PipelineValidator.problems(pipe(listOf(photos(), extract(), extract("lonely")), listOf(PipelineEdge("photos", "extract"))))
        assertEquals("lonely", unreachable.single { it.text == "node lonely: unreachable" }.nodeId)
        assertEquals(null, PipelineValidator.problems(pipe(listOf(extract()), emptyList())).single { it.text == "exactly one input.photos required" }.nodeId)
        val typed = PipelineValidator.problems(pipe(listOf(photos(), extract(), promptSwitch(listOf("a"))), listOf(PipelineEdge("photos", "extract"), PipelineEdge("extract", "sw"))))
        assertEquals(PipelineEdge("extract", "sw"), typed.single { it.text.startsWith("edge extract->sw") }.edge)
    }

    @Test fun validateTextsUnchanged() {
        // frozen golden texts: a rewording of any rule fails here
        assertEquals(listOf("duplicate node id extract"), problems(pipe(listOf(photos(), extract(), extract()), listOf(PipelineEdge("photos", "extract")))))
        assertEquals(listOf("edge photos->extract: when not allowed"), problems(pipe(listOf(photos(), extract()), listOf(PipelineEdge("photos", "extract", "x")))))
        assertEquals(listOf("node lonely: unreachable"), problems(pipe(listOf(photos(), extract(), extract("lonely")), listOf(PipelineEdge("photos", "extract")))))
        assertEquals(listOf("node intent: schema required"), problems(pipe(listOf(photos(), aiPrompt()), listOf(PipelineEdge("photos", "intent")))))
        assertEquals(listOf("exactly one input.photos required"), problems(pipe(listOf(extract()), emptyList())))
        assertEquals(listOf("edge photos->x: unknown node x"), problems(pipe(listOf(photos(), extract()), listOf(PipelineEdge("photos", "extract"), PipelineEdge("photos", "x")))))
        assertEquals(emptyList<String>(), problems(DefaultPipelines.liveExtract()))
        assertEquals(problems(minimal()), PipelineValidator.problems(minimal()).map { it.text })
    }
    // ---- WP8: a py.fn graph is valid only in the shapes the executor supports ----
    // Supported: an ASYNC-only photo-experiment graph (no capture half), 1..MAX_PY_FN py.fn nodes, each with a non-blank `source`, an `outSchema` that is a JSON object and
    // exactly one incoming edge (JSON in, from the photos path). Anything else fails validation, so it can never be frozen or run.

    private fun pyFn(id: String = "fn", params: JsonObject = pyParams()) = node(id, NodeTypes.PY_FN, params)
    private fun pyParams(source: String? = "def run(x):\n    return {}\n", outSchema: String? = "{\"type\":\"object\",\"additionalProperties\":false}") = buildJsonObject {
        source?.let { put("source", it) }; outSchema?.let { put("outSchema", it) }
    }
    private fun pipe2(nodes: List<PipelineNode>, edges: List<PipelineEdge>) = Pipeline(2, "p", "P", nodes, edges)
    private fun prompt(id: String = "intent") = aiPrompt(id) { mapOf("schema" to JsonPrimitive(schema)) }
    private fun pyGraph(fn: PipelineNode = pyFn()) = pipe2(listOf(photos(), prompt(), fn), listOf(PipelineEdge("photos", "intent"), PipelineEdge("intent", fn.id)))

    @Test fun pyFnGraphOfTheSupportedShapeIsValid() {
        assertEquals(emptyList<String>(), problems(pyGraph()))
        // a py.fn may follow a switch branch and feed on to another py.fn
        val chain = pipe2(listOf(photos(), prompt(), pyFn("a"), pyFn("b")), listOf(PipelineEdge("photos", "intent"), PipelineEdge("intent", "a"), PipelineEdge("a", "b")))
        assertEquals(emptyList<String>(), problems(chain))
    }

    @Test fun pyFnNeedsASource() {
        assertHas(pyGraph(pyFn(params = pyParams(source = null))), "node fn: py.fn source required")
        assertHas(pyGraph(pyFn(params = pyParams(source = "  \n"))), "node fn: py.fn source required")
    }

    @Test fun pyFnNeedsAnOutSchemaThatIsAJsonObject() {
        assertHas(pyGraph(pyFn(params = pyParams(outSchema = null))), "node fn: py.fn outSchema must be a JSON object")
        assertHas(pyGraph(pyFn(params = pyParams(outSchema = "[1]"))), "node fn: py.fn outSchema must be a JSON object")
        assertHas(pyGraph(pyFn(params = pyParams(outSchema = "not json"))), "node fn: py.fn outSchema must be a JSON object")
    }

    @Test fun pyFnTakesExactlyOneInputEdge() {
        val two = pipe2(
            listOf(photos(), prompt("p1"), prompt("p2"), pyFn()),
            listOf(PipelineEdge("photos", "p1"), PipelineEdge("photos", "p2"), PipelineEdge("p1", "fn"), PipelineEdge("p2", "fn")),
        )
        assertHas(two, "node fn: py.fn takes exactly one input edge")
        // no input edge at all: a py.fn is also unreachable (the existing rule), and the shape rule says so too
        assertHas(pipe2(listOf(photos(), prompt(), pyFn()), listOf(PipelineEdge("photos", "intent"))), "node fn: py.fn takes exactly one input edge")
    }

    @Test fun pyFnGraphHasNoCaptureHalf() {
        val rt = listOf(
            node("head", NodeTypes.IMU_HEAD_MOTION), node("cam", NodeTypes.CAPTURE_IMAGE),
        )
        val g = pipe2(
            rt + listOf(photos(), prompt(), pyFn()),
            listOf(PipelineEdge("head", "cam"), PipelineEdge("cam", "photos"), PipelineEdge("photos", "intent"), PipelineEdge("intent", "fn")),
        )
        assertHas(g, "node fn: py.fn runs only in a graph without a capture half")
    }

    @Test fun pyFnNodeCountIsBounded() {
        val max = PipelineValidator.MAX_PY_FN
        fun chain(n: Int): Pipeline {
            val fns = (1..n).map { pyFn("f$it") }
            val edges = listOf(PipelineEdge("photos", "intent"), PipelineEdge("intent", "f1")) + (2..n).map { PipelineEdge("f${it - 1}", "f$it") }
            return pipe2(listOf(photos(), prompt()) + fns, edges)
        }
        assertEquals(emptyList<String>(), problems(chain(max)))
        assertHas(chain(max + 1), "too many py.fn nodes (max $max)")
    }

    @Test fun pyFnRulesDoNotChangeAnyOtherGraph() {
        // frozen: a graph without py.fn validates exactly as before (the rules are all keyed on the py.fn type)
        assertEquals(emptyList<String>(), problems(DefaultPipelines.liveExtract()))
        assertEquals(emptyList<String>(), problems(minimal()))
    }
}
