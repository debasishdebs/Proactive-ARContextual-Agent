package com.rokid.context.capture.plan

import com.rokid.context.core.model.CapturePlanMessage
import com.rokid.context.core.model.PlanAckState
import com.rokid.context.core.model.PlanMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** X1: planSchema 2 carries `exprs`; the 8 KB cap counts them; glasses without the `expr` cap refuse (S24). */
class ExprPlanSchemaTest {
    private fun expr(pad: Int = 0) = JsonObject(
        mapOf("id" to JsonPrimitive("g1"), "role" to JsonPrimitive("guard" + "x".repeat(pad)), "ast" to JsonArray(listOf(JsonPrimitive(">"), JsonPrimitive(1)))),
    )
    private fun exprPlan(pad: Int = 0, name: String = "t"): CapturePlan {
        val c = PlanResolver.identity().copy(exprs = listOf(expr(pad)), sigSchema = SIG_SCHEMA)
        return CapturePlan(PLAN_SCHEMA_EXPR, PlanJson.configHash(c), c, PlanMeta(name, 1))
    }
    private fun exprPlanWithBytes(target: Int): CapturePlan {
        val base = PlanJson.encode(exprPlan()).toByteArray(Charsets.UTF_8).size
        return exprPlan(pad = target - base)
    }

    @Test fun exprPlanRoundTripsAsSchema2() {
        val p = exprPlan()
        val text = PlanJson.encode(p)
        assertTrue("\"planSchema\":2" in text && "\"exprs\":[" in text && "\"sigSchema\":1" in text)
        assertEquals(p, PlanJson.decodeStrict(text).getOrThrow())
        assertEquals(emptyList(), PlanValidator.check(text).first)
    }

    @Test fun exprsChangeTheHashButSchema1PlansDoNot() {
        assertTrue(PlanJson.configHash(exprPlan().config) != PlanJson.configHash(PlanResolver.identity()))
        val t = PlanFixtures.text()
        assertTrue("exprs" !in t && "sigSchema" !in t && "\"planSchema\":1" in t)
    }

    @Test fun schemaAndExprsMustAgree() {
        val withExprsAsV1 = PlanJson.encode(exprPlan()).replace("\"planSchema\":2", "\"planSchema\":1")
        assertTrue(PlanValidator.check(withExprsAsV1).first.any { it.startsWith("plan schema ") })
        val v2NoExprs = PlanFixtures.text().replace("\"planSchema\":1", "\"planSchema\":2")
        assertTrue(PlanValidator.check(v2NoExprs).first.any { it.startsWith("plan schema ") })
        val badSig = PlanJson.encode(exprPlan()).replace("\"sigSchema\":1", "\"sigSchema\":9")
        assertTrue(PlanValidator.check(badSig).first.isNotEmpty())
    }

    @Test fun PlanTooBigAt8193Test() {
        val ok = PlanJson.encode(exprPlanWithBytes(8_192))
        assertEquals(8_192, ok.toByteArray(Charsets.UTF_8).size)
        assertEquals(emptyList(), PlanValidator.check(ok).first)
        val big = PlanJson.encode(exprPlanWithBytes(8_193))
        assertEquals(listOf("plan is 8193 bytes (max 8192)"), PlanValidator.check(big).first)
    }

    @Test fun PlanAt8192Accepted() {
        val p = exprPlanWithBytes(8_192)
        val r = PlanReceiver(Mem(), { 1L }, { "b" }, caps = PlanReceiver.CAPS_EXPR)
        val a = r.onMessage(msg(p))
        assertEquals(PlanAckState.STAGED, a.state)
    }

    @Test fun OldGlassesRejectUnsupportedExprTest() {
        val p = exprPlan()
        val old = PlanReceiver(Mem(), { 1L }, { "b" })   // today's caps: no `expr`
        assertTrue("expr" !in PlanReceiver.CAPS)
        val a = old.onMessage(msg(p))
        assertEquals(PlanAckState.REJECTED, a.state); assertEquals("unsupported:expr", a.reason)
        assertNotNull(a.caps); assertTrue("expr" !in a.caps)
        val now = PlanReceiver(Mem(), { 1L }, { "b" }, caps = PlanReceiver.CAPS_EXPR)
        assertEquals(PlanAckState.STAGED, now.onMessage(msg(p)).state)
        // a plan without exprs is still accepted by the old caps
        val plain = PlanFixtures.plan()
        assertEquals(PlanAckState.STAGED, PlanReceiver(Mem(), { 1L }, { "b" }).onMessage(msg(plain)).state)
    }

    private class Mem(var s: ReceiverState? = null) : PlanPersistence {
        override fun load() = s
        override fun commit(s: ReceiverState) { this.s = s }
    }
    private fun msg(p: CapturePlan) =
        CapturePlanMessage(gen = 1, mode = PlanMode.SHADOW, configHash = p.configHash, planJson = PlanJson.encode(p), phoneInstall = "p1", atMs = 5)
}
