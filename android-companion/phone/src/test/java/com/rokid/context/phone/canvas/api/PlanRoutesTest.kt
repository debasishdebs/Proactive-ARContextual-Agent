package com.rokid.context.phone.canvas.api

import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.canvas.Pipeline
import com.rokid.context.ai.canvas.PipelineEdge
import com.rokid.context.ai.canvas.PipelineJson
import com.rokid.context.ai.canvas.PipelineNode
import com.rokid.context.phone.canvas.server.HttpRequest
import com.rokid.context.phone.hub.plan.PlanPublishPort
import com.rokid.context.phone.hub.plan.PlanSendResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanRoutesTest {
    private class Port : PlanPublishPort {
        val sent = mutableListOf<Pair<String, String>>()
        override suspend fun sendShadow(planJson: String, configHash: String): PlanSendResult { sent += planJson to configHash; return PlanSendResult(gen = sent.size.toLong(), sent = true) }
    }

    private val none = JsonObject(emptyMap())
    private fun node(id: String, type: String, params: JsonObject = none) = PipelineNode(id, type, id, params)
    private fun sig(id: String, type: String, name: String, domain: String) = node(id, type, buildJsonObject { put("name", name); put("domain", domain) })

    /** The default capture-half graph (as the compiler's own tests): every node at its defaults. */
    private val good = Pipeline(
        2, "p", "P",
        listOf(
            node("head", NodeTypes.IMU_HEAD_MOTION), node("thresh", NodeTypes.GATE_THRESHOLD, buildJsonObject { put("feature", "gyroPeak"); put("op", ">"); put("value", 0.6) }),
            node("settle", NodeTypes.IMU_SETTLE), node("novel", NodeTypes.GATE_NOVELTY), node("rate", NodeTypes.POLICY_CAPTURE_RATE), node("cap", NodeTypes.GATE_RATE_CAP),
            node("cam", NodeTypes.CAPTURE_IMAGE), sig("cin", NodeTypes.SIGNAL_IN, "ctx", "RT_GLASSES"), node("hub", NodeTypes.HUB_CONTEXT), sig("cout", NodeTypes.SIGNAL_OUT, "ctx", "RT_PHONE"),
        ),
        listOf(
            PipelineEdge("head", "thresh"), PipelineEdge("thresh", "settle"), PipelineEdge("settle", "novel"), PipelineEdge("novel", "rate"),
            PipelineEdge("cin", "rate"), PipelineEdge("rate", "cap"), PipelineEdge("cap", "cam"), PipelineEdge("hub", "cout"),
        ),
    )
    private val bad = good.copy(edges = good.edges.drop(1))

    private var dev = true
    private val port = Port()
    private var portAvailable = true
    private val routes = PlanRoutes({ dev }, { if (portAvailable) port else null })
    private fun body(p: Pipeline, extra: JsonObject = none) = buildJsonObject { put("json", Json.parseToJsonElement(PipelineJson.encode(p))); extra.forEach { (k, v) -> put(k, v) } }.toString()
    private fun post(path: String, b: String) = runBlocking { routes.handle(HttpRequest("POST", path, emptyMap(), emptyMap(), b.toByteArray())) }
    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content

    @Test fun compileReturnsThePlanAndSendsNothing() {
        val r = post("/api/plans/compile", body(good))!!
        assertEquals(200, r.status)
        val o = Json.parseToJsonElement(String(r.body)).jsonObject
        assertTrue(o["ok"]!!.jsonPrimitive.boolean)
        assertNotNull(o["planJson"]); assertTrue(o.str("configHash").isNotEmpty())
        assertEquals(emptyList<Any>(), o["problems"]!!.jsonArray.toList())
        assertTrue("compile never publishes", port.sent.isEmpty())
    }

    @Test fun compileReportsProblemsAndRefusesBadBodies() {
        val o = Json.parseToJsonElement(String(post("/api/plans/compile", body(bad))!!.body)).jsonObject
        assertEquals(false, o["ok"]!!.jsonPrimitive.boolean)
        assertTrue(o["problems"]!!.jsonArray.isNotEmpty())
        assertEquals(400, post("/api/plans/compile", "[]")!!.status)
        assertEquals(400, post("/api/plans/compile", """{"json":"not a pipeline"}""")!!.status)
        assertEquals(404, post("/api/plans/nope", "{}")!!.status)
    }

    @Test fun debugShadowSendsOnlyShadowAndSaysSo() {
        val r = post("/api/plans/debug-shadow", body(good))!!
        assertEquals(200, r.status)
        val o = Json.parseToJsonElement(String(r.body)).jsonObject
        assertEquals("SHADOW", o.str("mode")); assertEquals("debug SHADOW (no publish gates)", o.str("label")); assertEquals(1, port.sent.size)
        assertEquals(o.str("configHash"), port.sent[0].second)
        assertEquals(200, post("/api/plans/debug-shadow", body(good, buildJsonObject { put("mode", "SHADOW") }))!!.status)
    }

    @Test fun debugShadowRefusesLiveAndTrial() {
        for (m in listOf("LIVE", "TRIAL", "SETTINGS", "live", "")) {
            val r = post("/api/plans/debug-shadow", body(good, buildJsonObject { put("mode", m) }))!!
            assertEquals(m, 400, r.status)
        }
        assertEquals(400, post("/api/plans/debug-shadow", body(good, buildJsonObject { put("mode", JsonArray(listOf(JsonPrimitive("LIVE")))) }))!!.status)
        assertTrue("nothing reached the hub", port.sent.isEmpty())
    }

    @Test fun debugShadowNeedsDeveloperMode() {
        dev = false
        val r = post("/api/plans/debug-shadow", body(good))!!
        assertEquals(403, r.status)
        assertTrue(port.sent.isEmpty())
        dev = true
        assertEquals(200, post("/api/plans/debug-shadow", body(good))!!.status)
    }

    @Test fun debugShadowRefusesAnInvalidPipelineAndAMissingHub() {
        assertEquals(422, post("/api/plans/debug-shadow", body(bad))!!.status)
        assertTrue(port.sent.isEmpty())
        portAvailable = false
        assertEquals(503, post("/api/plans/debug-shadow", body(good))!!.status)
    }

    @Test fun otherPathsAreNotPlanRoutes() { assertEquals(null, runBlocking { routes.handle(HttpRequest("GET", "/api/runs", emptyMap(), emptyMap(), ByteArray(0))) }) }
    // ---- WP7 review: S6 on the plan routes that compile or send a pipeline ----

    private val withPyFn = good.copy(nodes = good.nodes + node("fn", "py.fn"))
    private val withTool = good.copy(nodes = good.nodes + node("tl", "tool.draft"))

    @Test fun debugShadowRefusesTier3AndSendsNothing() {
        for (p in listOf(withPyFn, withTool)) {
            val r = post("/api/plans/debug-shadow", body(p))!!
            assertEquals(409, r.status)
            val o = Json.parseToJsonElement(String(r.body)).jsonObject
            assertEquals("publish_blocked", o.str("error"))
            assertEquals(listOf(com.rokid.context.ai.canvas.plan.PlanShape.S6_REFUSAL), o["problems"]!!.jsonArray.map { it.jsonPrimitive.content })
        }
        assertTrue("nothing reached the hub", port.sent.isEmpty())
    }

    @Test fun compileRouteReportsTheS6ReasonAndOffersNoPlan() {
        for (p in listOf(withPyFn, withTool)) {
            val o = Json.parseToJsonElement(String(post("/api/plans/compile", body(p))!!.body)).jsonObject
            assertEquals(false, o["ok"]!!.jsonPrimitive.boolean)
            assertEquals(listOf(com.rokid.context.ai.canvas.plan.PlanShape.S6_REFUSAL), o["problems"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals("null", o["planJson"].toString())
        }
        assertTrue(port.sent.isEmpty())
    }

    /** Behaviour, not source text: LIVE, TRIAL, SETTINGS and a non-string mode are refused with `shadow_only` and the hub sees nothing. */
    @Test fun debugShadowIsShadowOnlyThroughTheRoute() {
        for (m in listOf("LIVE", "TRIAL", "SETTINGS")) {
            val r = post("/api/plans/debug-shadow", body(good, buildJsonObject { put("mode", m) }))!!
            assertEquals(m, 400, r.status); assertEquals("shadow_only", Json.parseToJsonElement(String(r.body)).jsonObject.str("error"))
        }
        assertTrue(port.sent.isEmpty())
        assertEquals(200, post("/api/plans/debug-shadow", body(good, buildJsonObject { put("mode", "SHADOW") }))!!.status)
        assertEquals("SHADOW", Json.parseToJsonElement(String(post("/api/plans/debug-shadow", body(good))!!.body)).jsonObject.str("mode"))
    }
}
