package com.rokid.context.glasses.capture.plan

import com.rokid.context.capture.plan.ExprRunner
import com.rokid.context.capture.plan.ExprSignals
import com.rokid.context.core.model.PlanMode
import com.rokid.context.core.model.context.ContextPolicies
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.glasses.capture.motion.model.SecondFeatures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** 3c E2a: value programs on the glasses: the row hook of the active config, and auto-revert covering their errors. */
class GlanceGuardValueTest {
    private fun value(id: String, row: String, field: String, s: String, out: Int): JsonObject =
        Json.parseToJsonElement("""{"id":"$id","role":"value","row":"$row","field":"$field","out":$out,"s":[$s]}""").jsonObject
    private fun const(row: String, field: String, v: String) = value("v", row, field, """{"op":"const","v":$v}""", 0)
    private val nonFinite = value("n", "REST", "capPerMin", """{"op":"const","v":1.0E308},{"op":"const","v":10},{"op":"arith","f":"*","a":0,"b":1}""", 2)
    private val div0 = value("d", "REST", "capPerMin", """{"op":"const","v":1},{"op":"const","v":0},{"op":"arith","f":"/","a":0,"b":1}""", 2)
    private val A = PlanId("p1", 1, PlanMode.TRIAL)

    private fun cfg(e: JsonObject, ctxBound: Boolean = true) = ActiveConfig.SETTINGS.copy(exprRunner = ExprRunner.of(listOf(e)), ctxBound = ctxBound, forceSettle = true)
    private fun signals() = ExprSignals().also { it.set(SecondFeatures(0f, 0f, 0f, 0f, 0f, 0f, 0f), 500) }

    @Test fun theRowHookTunesOnlyTheNamedFieldOfTheNamedRowAfterATick() {
        val c = cfg(const("REST", "capPerMin", "4"))
        val g = GlanceGuard({ c }, signals(), { ContextState.REST }, { 0L }, AutoRevert()) { A }
        val before = ContextPolicies.DEFAULT.getValue(ContextState.REST)
        assertEquals(before, c.policyFor(ContextState.REST), "nothing is tuned before the first tick")
        g.tickValues(1_000)
        assertEquals(4, c.policyFor(ContextState.REST).capPerMin); assertEquals(4, c.contextCap(ContextState.REST))
        assertEquals(before.copy(capPerMin = 4), c.policyFor(ContextState.REST))
        for (s in ContextState.entries.filter { it != ContextState.REST }) assertEquals(ContextPolicies.of(s, c.table), c.policyFor(s), "$s")
    }

    @Test fun WalkingAndUnknownStayTheLockedRowsWhateverAValueSays() {
        val c = cfg(const("REST", "capPerMin", "4"))
        GlanceGuard({ c }, signals(), { ContextState.WALKING }, { 0L }, AutoRevert()) { A }.tickValues(1_000)
        for (s in listOf(ContextState.WALKING, ContextState.UNKNOWN)) assertSame(ContextPolicies.of(s, c.table), c.policyFor(s))
        assertEquals(ContextPolicies.DEFAULT.getValue(ContextState.WALKING), c.policyFor(ContextState.WALKING))
    }

    @Test fun aPlanThatIgnoresTheContextKeepsEveryStateOnTheUnknownRow() {
        val c = cfg(const("REST", "capPerMin", "4"), ctxBound = false)
        GlanceGuard({ c }, signals(), { ContextState.REST }, { 0L }, AutoRevert()) { A }.tickValues(1_000)
        assertEquals(ContextPolicies.DEFAULT.getValue(ContextState.UNKNOWN), c.policyFor(ContextState.REST), "ctxBound = false: the UNKNOWN row is used and it is locked")
    }

    @Test fun ValueErrAutoRevertsTest() {
        val c = cfg(nonFinite)
        val revert = AutoRevert()
        val g = GlanceGuard({ c }, signals(), { ContextState.REST }, { 0L }, revert) { A }
        var t = 1_000L
        repeat(10) { g.tickValues(t); t += 1_000 }
        assertNull(revert.check(t, A), "exactly 10 in 10 s: not MORE than 10")
        g.tickValues(t)
        assertEquals("auto:exprErr", revert.check(t, A), "value programs count like guards")
        assertEquals(ContextPolicies.DEFAULT.getValue(ContextState.REST), c.policyFor(ContextState.REST), "an erroring value leaves the row default")
    }

    @Test fun Div0InAValueNeverRevertsEither() {
        val c = cfg(div0)
        val revert = AutoRevert()
        val g = GlanceGuard({ c }, signals(), { ContextState.REST }, { 0L }, revert) { A }
        var t = 1_000L
        repeat(40) { g.tickValues(t); t += 1_000 }
        assertEquals(40, c.exprRunner!!.exprDiv0); assertNull(revert.check(t, A))
    }

    @Test fun valueErrorsOfSettingsOrShadowAreDropped() {
        val c = cfg(nonFinite)
        val revert = AutoRevert()
        val g = GlanceGuard({ c }, signals(), { ContextState.REST }, { 0L }, revert) { null }
        var t = 1_000L
        repeat(30) { g.tickValues(t); t += 1_000 }
        assertNull(revert.check(t, null)); assertNull(revert.check(t, A))
    }
}
