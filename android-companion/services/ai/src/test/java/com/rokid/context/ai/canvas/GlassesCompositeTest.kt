package com.rokid.context.ai.canvas

import com.rokid.context.ai.canvas.plan.CompileResult
import com.rokid.context.ai.canvas.plan.PlanCompiler
import com.rokid.context.capture.plan.PlanJson
import com.rokid.context.capture.plan.PlanResolver
import com.rokid.context.capture.plan.PlanState
import com.rokid.context.core.model.context.ContextPolicies
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Composite phase C: the glasses lane's nodes expand into their real stages. Inner flows are DESCRIPTIONS, so these tests pin (a) what each
 * flow says against the real rules and constants, (b) that every exposed setting is a plan parameter that already exists and that editing
 * it changes exactly the intended plan field, and (c) that nothing the compiler emits for any built-in moved.
 */
class GlassesCompositeTest {
    private fun inner(type: String) = NodeTypes.byType(type)!!.inner
    private fun leaves(e: JsonElement, path: String = "", out: MutableMap<String, String> = sortedMapOf()): Map<String, String> {
        when (e) {
            is JsonObject -> e.forEach { (k, v) -> leaves(v, "$path/$k", out) }
            is JsonArray -> e.forEachIndexed { i, v -> leaves(v, "$path/$i", out) }
            else -> out[path] = e.toString()
        }
        return out
    }
    private fun configLeaves(r: CompileResult) = leaves(PlanJson.configJson(r.plan!!.config))
    private fun compileWith(type: String, params: JsonObject): CompileResult {
        val p = DefaultPipelines.defaultCapture()
        val g = p.copy(nodes = p.nodes.map { if (it.type == type) it.copy(params = params) else it })
        return PlanCompiler.compile(g).also { assertEquals(emptyList<String>(), it.problems); assertNotNull(it.plan) }
    }
    private val identity get() = PlanCompiler.compile(DefaultPipelines.defaultCapture())
    private fun changedBy(type: String, params: JsonObject): Set<String> {
        val a = configLeaves(identity); val b = configLeaves(compileWith(type, params))
        return (a.keys + b.keys).filter { a[it] != b[it] }.toSet()
    }

    // ---------------------------------------------------------------- 1. Capture frequency by activity

    private val rate = NodeTypes.POLICY_CAPTURE_RATE
    private val cols = listOf("capPerMin", "settledGapS", "quietGapS", "walkGapS", "stillCapGapS")
    private val planField = mapOf("capPerMin" to "capPerMin", "settledGapS" to "timerPhotoGapsMs/settledMs", "quietGapS" to "timerPhotoGapsMs/quietMs",
        "walkGapS" to "timerPhotoGapsMs/walkMs", "stillCapGapS" to "timerPhotoGapsMs/stillCapMs")

    @Test fun captureFrequencyShowsTheRealRowsAndTheLockedOnesAreReadOnly() {
        val f = inner(rate)
        assertNotNull("capture frequency is expandable", f)
        val rows = ContextPolicies.DEFAULT.keys.map { it.name }.toSet() // the real rows: REST, VEHICLE_*, FLIGHT, and WALKING and UNKNOWN (locked)
        val ids = f!!.nodes.map { it.id }.toSet()
        assertTrue("every real row has a box: $rows in $ids", ids.containsAll(rows))
        val editable = PlanState.entries.map { it.name }.toSet()
        assertEquals("only the editable rows own settings", editable, f.exposed.map { it.node }.toSet())
        assertEquals(setOf("WALKING", "UNKNOWN"), rows - editable)
        assertTrue("the walking veto is a stage and it owns no setting", "veto" in ids && f.exposed.none { it.node == "veto" })
        assertTrue(f.exposed.none { it.node in setOf("WALKING", "UNKNOWN") })
        assertTrue("the locked rows say they are locked", f.nodes.filter { it.id in setOf("WALKING", "UNKNOWN", "veto") }.all { it.label.contains("locked") })
    }

    @Test fun everyExposedCellIsARealTableCellWithItsTodayDefault() {
        val f = inner(rate)!!
        val table = NodeTypes.byType(rate)!!.params.specs.single { it.name == "rows" }.table!!
        assertEquals(PlanState.entries.size * cols.size, f.exposed.size)
        for (e in f.exposed) {
            assertEquals("rows", e.param); assertEquals("the owner box is the row", e.row, e.node)
            assertTrue("${e.col} is a column", table.cols.any { it.name == e.col }); assertTrue(e.row in table.rows)
            val today = PlanResolver.identity().policy.getValue(PlanState.valueOf(e.row))
            val expect: Long? = when (e.col) {
                "capPerMin" -> today.capPerMin?.toLong()
                "settledGapS" -> today.timerPhotoGapsMs.settledMs / 1000; "quietGapS" -> today.timerPhotoGapsMs.quietMs / 1000
                "walkGapS" -> today.timerPhotoGapsMs.walkMs / 1000; else -> today.timerPhotoGapsMs.stillCapMs / 1000
            }
            // a gap of 0 is "inherit the settings profile": it has no number to show, so no default (the editor says "inherit")
            assertEquals("${e.row}.${e.col} default", if (e.col != "capPerMin" && expect == 0L) null else expect, e.default?.doubleOrNull?.toLong())
        }
    }

    @Test fun editingOneExposedCellChangesExactlyThatPlanField() {
        for (e in inner(rate)!!.exposed) {
            val v = if (e.col == "capPerMin") 3 else 45
            val rows = buildJsonObject { put(e.row, buildJsonObject { put(e.col, v) }) }
            assertEquals("${e.row}.${e.col}", setOf("/policy/${e.row}/${planField.getValue(e.col)}"), changedBy(rate, buildJsonObject { put("rows", rows) }))
        }
    }

    @Test fun anAbsentOrDefaultTableCompilesToTheIdentityPlan() {
        assertEquals("f5782e0d8610", identity.plan!!.configHash)
        val rows = buildJsonObject { put("REST", buildJsonObject { put("capPerMin", 2); put("settledGapS", 30) }) }
        assertEquals(emptySet<String>(), changedBy(rate, buildJsonObject { put("rows", rows) }))
    }

    // ---------------------------------------------------------------- goldens: nothing the compiler emits for a built-in moved

    private fun sha(s: String) = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    private fun builtIn(id: String) = when (id) {
        DefaultPipelines.LIVE_EXTRACT -> DefaultPipelines.liveExtract()
        DefaultPipelines.INTENT_DRAFT -> DefaultPipelines.intentDraft("SEED {{recent_context}}")
        DefaultPipelines.DEFAULT_CAPTURE -> DefaultPipelines.defaultCapture()
        DefaultPipelines.STARTER -> DefaultPipelines.starter()
        else -> DefaultPipelines.starterMomentSummary()
    }

    /** The compiled bytes (not only the hash) of every saved built-in, pinned before phase C; an async-only pipeline has no capture half and says so. */
    @Test fun everyBuiltInCompilesToTheSamePlanBytesAndHashAsBeforePhaseC() {
        val pinned = mapOf(
            "live-extract" to "none:pipeline has no capture half",
            "intent-draft" to "none:pipeline has no capture half",
            "default-capture" to "f5782e0d8610:c6ea8af3a364cc71",
            "starter-capture-extract" to "f5782e0d8610:db81ad1a86a50070",
            "starter-moment-summary" to "f5782e0d8610:3ff66ed4cf6aaf77",
        )
        assertEquals(DefaultPipelines.all.toSet(), pinned.keys)
        val got = DefaultPipelines.all.associateWith { id ->
            val r = PlanCompiler.compile(builtIn(id))
            if (r.plan == null) "none:" + r.problems.joinToString("; ") else r.plan!!.configHash + ":" + sha(r.planJson!!)
        }
        assertEquals(pinned.toString(), got.toString())
    }
}
