package com.rokid.context.ai.canvas

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeTypesV2Test {
    private val none = JsonObject(emptyMap())
    private fun node(id: String, type: String, params: JsonObject = none) = PipelineNode(id, type, id, params)
    private fun pipe(nodes: List<PipelineNode>, edges: List<PipelineEdge>, schema: Int = 2) = Pipeline(schema, "p", "P", nodes, edges)
    private fun problems(p: Pipeline) = PipelineValidator.validate(p)
    private fun assertHas(p: Pipeline, text: String) { val r = problems(p); assertTrue("expected '$text' in $r", text in r) }
    private fun signal(id: String, type: String, name: String, domain: String?) =
        node(id, type, buildJsonObject { put("name", name); if (domain != null) put("domain", domain) })

    private fun glassesChain() = pipe(
        listOf(
            node("head", NodeTypes.IMU_HEAD_MOTION), node("settle", NodeTypes.IMU_SETTLE), node("novel", NodeTypes.GATE_NOVELTY),
            node("rate", NodeTypes.POLICY_CAPTURE_RATE), node("cap", NodeTypes.GATE_RATE_CAP), node("cam", NodeTypes.CAPTURE_IMAGE),
            signal("cin", NodeTypes.SIGNAL_IN, "ctx", "RT_GLASSES"),
        ),
        listOf(
            PipelineEdge("head", "settle"), PipelineEdge("settle", "novel"), PipelineEdge("novel", "rate"), PipelineEdge("cin", "rate"),
            PipelineEdge("rate", "cap"), PipelineEdge("cap", "cam"),
        ),
    )

    @Test fun registryHasCaptureHalfTypes() {
        val expected = mapOf(
            NodeTypes.IMU_HEAD_MOTION to Domain.RT_GLASSES, NodeTypes.IMU_SETTLE to Domain.RT_GLASSES, NodeTypes.GATE_THRESHOLD to Domain.RT_GLASSES,
            NodeTypes.GATE_NOVELTY to Domain.RT_GLASSES, NodeTypes.POLICY_CAPTURE_RATE to Domain.RT_GLASSES, NodeTypes.GATE_RATE_CAP to Domain.RT_GLASSES,
            NodeTypes.CAPTURE_IMAGE to Domain.RT_GLASSES, NodeTypes.HUB_CONTEXT to Domain.RT_PHONE,
        )
        expected.forEach { (t, d) -> assertEquals(t, d, NodeTypes.byType(t)?.domain) }
        assertEquals(Category.SENSORS, NodeTypes.byType(NodeTypes.IMU_HEAD_MOTION)!!.category)
        assertEquals(Category.POLICY_FREQUENCY, NodeTypes.byType(NodeTypes.POLICY_CAPTURE_RATE)!!.category)
        assertEquals(ExecutorKind.TABLE, NodeTypes.byType(NodeTypes.POLICY_CAPTURE_RATE)!!.executor)
        assertEquals(ExecutorKind.SIGNAL, NodeTypes.byType(NodeTypes.SIGNAL_IN)!!.executor)
        // every short is a valid id prefix and unique
        val shorts = NodeTypes.all.map { it.short }
        assertEquals(shorts.size, shorts.toSet().size)
        assertTrue(shorts.all { Regex("^[a-z][a-z0-9]{0,15}$").matches(it) })
    }

    @Test fun legacyTypesStayAsyncV1() {
        val legacy = listOf(
            NodeTypes.INPUT_PHOTOS, NodeTypes.VISION_EXTRACT, NodeTypes.LOCAL_CRITIC, NodeTypes.CRITIC, NodeTypes.PROMPT, NodeTypes.SWITCH, NodeTypes.MEMORY_WRITE,
        )
        legacy.forEach { t ->
            val d = NodeTypes.resolve(t, 1)!!
            assertEquals(1, d.version); assertEquals(Domain.ASYNC, d.domain)
            assertEquals(d, NodeTypes.resolve(t, 2))
        }
        assertEquals("Inputs", NodeTypes.byType(NodeTypes.INPUT_PHOTOS)!!.category.label)
    }

    @Test fun schemaOneResolvesOnlyLegacyTypes() {
        assertNull(NodeTypes.resolve(NodeTypes.IMU_HEAD_MOTION, 1))
        assertNotNull(NodeTypes.resolve(NodeTypes.IMU_HEAD_MOTION, 2))
        val p = pipe(listOf(node("head", NodeTypes.IMU_HEAD_MOTION)), emptyList(), schema = 1)
        assertHas(p, "node head: unknown type")
    }

    @Test fun validGlassesChain() {
        assertEquals(emptyList<String>(), problems(glassesChain()))
    }

    @Test fun signalDomainPerInstance() {
        // allowed: a glasses receive and a phone send
        assertEquals(emptyList<String>(), problems(glassesChain()))
        val phone = pipe(
            listOf(node("hub", NodeTypes.HUB_CONTEXT), signal("cout", NodeTypes.SIGNAL_OUT, "ctx", "RT_PHONE"), signal("iin", NodeTypes.SIGNAL_IN, "intent", "RT_PHONE")),
            listOf(PipelineEdge("hub", "cout")),
        )
        assertEquals(emptyList<String>(), problems(phone))
        // refused per instance, with the fixed text
        assertHas(pipe(listOf(node("hub", NodeTypes.HUB_CONTEXT), signal("cout", NodeTypes.SIGNAL_OUT, "ctx", "ASYNC")), listOf(PipelineEdge("hub", "cout"))),
            "node cout: signal domain ASYNC not allowed here")
        assertHas(pipe(listOf(signal("cin", NodeTypes.SIGNAL_IN, "ctx", "RT_PHONE")), emptyList()), "node cin: signal domain RT_PHONE not allowed here")
        assertHas(pipe(listOf(signal("x", NodeTypes.SIGNAL_IN, "ctx", "BOGUS")), emptyList()), "node x: signal domain BOGUS not allowed here")
        assertHas(pipe(listOf(signal("o", NodeTypes.SIGNAL_OUT, "intent", "RT_PHONE")), emptyList()), "node o: signal domain RT_PHONE not allowed here")
    }

    @Test fun signalBindingNameUsedOncePerSide() {
        val p = pipe(
            listOf(node("head", NodeTypes.IMU_HEAD_MOTION), signal("a", NodeTypes.SIGNAL_IN, "ctx", "RT_GLASSES"), signal("b", NodeTypes.SIGNAL_IN, "ctx", "RT_GLASSES")),
            emptyList(),
        )
        assertHas(p, "node b: binding ctx used more than once")
    }

    @Test fun rootedDomainsValidatedPerGraph() {
        assertHas(pipe(listOf(node("head", NodeTypes.IMU_HEAD_MOTION), node("h2", NodeTypes.IMU_HEAD_MOTION)), emptyList()), "exactly one imu.head_motion required")
        assertHas(pipe(listOf(node("settle", NodeTypes.IMU_SETTLE)), emptyList()), "exactly one imu.head_motion required")
        assertHas(pipe(listOf(node("head", NodeTypes.IMU_HEAD_MOTION), node("settle", NodeTypes.IMU_SETTLE)), emptyList()), "node settle: unreachable")
        assertHas(pipe(listOf(node("hub", NodeTypes.HUB_CONTEXT), node("x", NodeTypes.HUB_CONTEXT)), emptyList()), "exactly one hub.context required")
        // an RT-only pipeline needs no photo input; an empty or ASYNC-only one still does
        assertFalse("exactly one input.photos required" in problems(glassesChain()))
        assertHas(pipe(emptyList(), emptyList()), "exactly one input.photos required")
        // a root has no incoming edge
        assertHas(pipe(listOf(node("head", NodeTypes.IMU_HEAD_MOTION), node("settle", NodeTypes.IMU_SETTLE)), listOf(PipelineEdge("head", "settle"), PipelineEdge("settle", "head"))),
            "node head: root must have no incoming edge")
    }

    @Test fun domainsConnectOnlyThroughBindings() {
        val photosEdge = pipe(
            glassesChain().nodes + node("photos", NodeTypes.INPUT_PHOTOS) + node("extract", NodeTypes.VISION_EXTRACT),
            glassesChain().edges + PipelineEdge("cam", "photos") + PipelineEdge("photos", "extract"),
        )
        assertEquals(emptyList<String>(), problems(photosEdge))
        val cross = pipe(
            glassesChain().nodes + node("hub", NodeTypes.HUB_CONTEXT),
            glassesChain().edges + PipelineEdge("hub", "rate"),
        )
        assertHas(cross, "edge hub->rate: crosses domains")
        // only capture.image feeds input.photos
        val bad = pipe(
            listOf(node("head", NodeTypes.IMU_HEAD_MOTION), node("photos", NodeTypes.INPUT_PHOTOS), node("extract", NodeTypes.VISION_EXTRACT)),
            listOf(PipelineEdge("head", "photos"), PipelineEdge("photos", "extract")),
        )
        assertTrue(problems(bad).any { it.startsWith("edge head->photos:") })
    }

    @Test fun paramsAreBounded() {
        fun head(vararg kv: Pair<String, Double>) = pipe(listOf(node("head", NodeTypes.IMU_HEAD_MOTION, buildJsonObject { kv.forEach { (k, v) -> put(k, v) } })), emptyList())
        assertEquals(emptyList<String>(), problems(head("onsetRadS" to 0.4, "releaseRadS" to 0.2)))
        assertHas(head("onsetRadS" to 1.1), "node head: onsetRadS must be between 0.4 and 1")
        assertHas(head("onsetRadS" to 0.5, "releaseRadS" to 0.6), "node head: releaseRadS must not exceed onsetRadS")
        assertHas(pipe(listOf(node("head", NodeTypes.IMU_HEAD_MOTION, buildJsonObject { put("nope", 1) })), emptyList()), "node head: unknown param nope")
        assertHas(pipe(listOf(node("head", NodeTypes.IMU_HEAD_MOTION), node("s", NodeTypes.IMU_SETTLE, buildJsonObject { put("settleMs", 999) })), listOf(PipelineEdge("head", "s"))),
            "node s: settleMs must be between 1000 and 3000")
        fun threshold(feature: String, op: String) = pipe(
            listOf(node("head", NodeTypes.IMU_HEAD_MOTION), node("t", NodeTypes.GATE_THRESHOLD, buildJsonObject { put("feature", feature); put("op", op); put("value", 1.0) })),
            listOf(PipelineEdge("head", "t")),
        )
        assertEquals(emptyList<String>(), problems(threshold("gyroPeak", ">")))
        assertEquals(emptyList<String>(), problems(threshold("boutMs", ">=")))
        assertHas(threshold("gyroPeak", ">="), "node t: op not allowed for feature gyroPeak")
        assertHas(threshold("boutRad", ">"), "node t: op not allowed for feature boutRad")
    }

    // ---- policy.capture_rate table ----

    private fun rate(rows: JsonObject) = pipe(
        listOf(node("head", NodeTypes.IMU_HEAD_MOTION), node("rate", NodeTypes.POLICY_CAPTURE_RATE, buildJsonObject { put("rows", rows) })),
        listOf(PipelineEdge("head", "rate")),
    )
    private fun row(vararg kv: Pair<String, Any?>) = buildJsonObject {
        kv.forEach { (k, v) -> when (v) { null -> put(k, JsonPrimitive(null as String?)); is Int -> put(k, v); is Boolean -> put(k, v); else -> error("type") } }
    }

    @Test fun policyTableColumnsNamedTimerPhotoGap() {
        val table = NodeTypes.byType(NodeTypes.POLICY_CAPTURE_RATE)!!.params.specs.first { it.name == "rows" }.table!!
        val label = "timer photo gap (settled/quiet/walk/still cap)"
        val gaps = table.cols.filter { it.name.endsWith("GapS") }
        assertEquals(listOf("settledGapS", "quietGapS", "walkGapS", "stillCapGapS"), gaps.map { it.name })
        assertTrue(gaps.all { it.group == label })
        assertEquals(listOf("REST", "VEHICLE_PASSENGER", "VEHICLE_DRIVING", "FLIGHT"), table.rows)
        assertEquals("capPerMin", table.cols.first().name)
        assertTrue(table.cols.first().blankable)
    }

    @Test fun policyTableBounds() {
        assertEquals(emptyList<String>(), problems(rate(buildJsonObject {
            put("REST", row("capPerMin" to 1, "settledGapS" to 0, "quietGapS" to 5, "walkGapS" to 300, "stillCapGapS" to 60, "forceSettle" to true, "nudges" to false))
            put("FLIGHT", row("capPerMin" to null))
        })))
        assertHas(rate(buildJsonObject { put("REST", row("capPerMin" to 5)) }), "node rate: REST capPerMin must be blank or between 1 and 4")
        assertHas(rate(buildJsonObject { put("REST", row("capPerMin" to 0)) }), "node rate: REST capPerMin must be blank or between 1 and 4")
        assertHas(rate(buildJsonObject { put("REST", row("quietGapS" to 4)) }), "node rate: REST quietGapS must be 0 or between 5 and 300")
        assertHas(rate(buildJsonObject { put("REST", row("walkGapS" to 301)) }), "node rate: REST walkGapS must be 0 or between 5 and 300")
        // WALKING and UNKNOWN are locked (S32); DRIVING nudges are locked off
        assertHas(rate(buildJsonObject { put("WALKING", row("capPerMin" to 1)) }), "node rate: unknown row WALKING")
        assertHas(rate(buildJsonObject { put("UNKNOWN", row("capPerMin" to 1)) }), "node rate: unknown row UNKNOWN")
        assertHas(rate(buildJsonObject { put("VEHICLE_DRIVING", row("nudges" to true)) }), "node rate: VEHICLE_DRIVING nudges are locked off")
        assertHas(rate(buildJsonObject { put("REST", row("pause" to true)) }), "node rate: REST unknown column pause")
    }

    @Test fun schemaTwoHashIncludesTypeVersionAndSchemaOneIsUnchanged() {
        val p2 = glassesChain()
        val canon = PipelineJson.canonical(p2)
        assertTrue(canon, "\"type\":\"imu.head_motion@1\"" in canon)
        // schema 2 decodes and round-trips to the same hash
        val back = PipelineJson.decode(PipelineJson.encode(p2)).getOrThrow()
        assertEquals(PipelineHash.of(p2), PipelineHash.of(back))
        // a schema-1 canonical form keeps the bare type (stored hashes are never recomputed)
        val p1 = Pipeline(1, "p", "P", listOf(node("photos", NodeTypes.INPUT_PHOTOS)), emptyList())
        assertTrue("\"type\":\"input.photos\"" in PipelineJson.canonical(p1))
    }
}
