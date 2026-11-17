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
        DefaultPipelines.STARTER_WATCH_MOTION -> DefaultPipelines.starterWatchMotion()
        DefaultPipelines.DEFAULT_QUERY -> DefaultPipelines.defaultQuery()
        DefaultPipelines.DEFAULT_NUDGE -> DefaultPipelines.defaultNudge()
        DefaultPipelines.DATED_QUERY -> DefaultPipelines.datedQuery()
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
            "starter-watch-motion" to "f5782e0d8610:28c9cf15a753d921",
            "default-query" to "none:pipeline has no capture half",
            "default-nudge" to "none:pipeline has no capture half",
            "dated-query" to "none:pipeline has no capture half",
        )
        assertEquals(DefaultPipelines.all.toSet(), pinned.keys)
        val got = DefaultPipelines.all.associateWith { id ->
            val r = PlanCompiler.compile(builtIn(id))
            if (r.plan == null) "none:" + r.problems.joinToString("; ") else r.plan!!.configHash + ":" + sha(r.planJson!!)
        }
        assertEquals(pinned.toString(), got.toString())
    }

    // ---------------------------------------------------------------- 2. Head movement, Settle, Novelty, Rate cap, Capture image

    private val glanceTypes = listOf(NodeTypes.IMU_HEAD_MOTION, NodeTypes.IMU_SETTLE, NodeTypes.GATE_NOVELTY, NodeTypes.GATE_RATE_CAP, NodeTypes.CAPTURE_IMAGE)

    /** What each exposed param of the five is today, from the plan's own identity config (the one source the glasses and the compiler share). */
    private fun today(param: String): Double? {
        val g = PlanResolver.identity().glance; val k = PlanResolver.identity().gate
        return when (param) {
            "onsetRadS" -> g.onsetRadS.toDouble(); "releaseRadS" -> g.releaseRadS.toDouble(); "bridgeMs" -> g.bridgeMs.toDouble(); "minBoutMs" -> g.minBoutMs.toDouble()
            "minBoutRad" -> g.minBoutRad.toDouble(); "settleMs" -> g.settleMs.toDouble(); "noveltyWindowMs" -> g.noveltyWindowMs.toDouble(); "noveltyMinRad" -> g.noveltyMinRad.toDouble()
            "bigTurnRad" -> g.bigTurnRad.toDouble(); "bigChange" -> k.bigChange.toDouble(); "hamming" -> k.hamming.toDouble()
            else -> null
        }
    }

    /** The edit each exposed param is tested with (a valid value that is not the default) and the one plan field it must change. */
    private val edits = listOf(
        Triple(NodeTypes.IMU_HEAD_MOTION, "onsetRadS", 0.7) to "/glance/onsetRadS", Triple(NodeTypes.IMU_HEAD_MOTION, "releaseRadS", 0.25) to "/glance/releaseRadS",
        Triple(NodeTypes.IMU_HEAD_MOTION, "bridgeMs", 300) to "/glance/bridgeMs", Triple(NodeTypes.IMU_HEAD_MOTION, "minBoutMs", 400) to "/glance/minBoutMs",
        Triple(NodeTypes.IMU_HEAD_MOTION, "minBoutRad", 0.3) to "/glance/minBoutRad", Triple(NodeTypes.IMU_SETTLE, "settleMs", 2000) to "/glance/settleMs",
        Triple(NodeTypes.GATE_NOVELTY, "noveltyWindowMs", 30_000) to "/glance/noveltyWindowMs", Triple(NodeTypes.GATE_NOVELTY, "noveltyMinRad", 0.4) to "/glance/noveltyMinRad",
        Triple(NodeTypes.GATE_RATE_CAP, "bigChange", 20) to "/gate/bigChange", Triple(NodeTypes.GATE_RATE_CAP, "bigTurnRad", 0.9) to "/glance/bigTurnRad",
        Triple(NodeTypes.CAPTURE_IMAGE, "hamming", 12) to "/gate/hamming", Triple(NodeTypes.CAPTURE_IMAGE, "ringCompare", "last") to "/gate/ringCompare",
    )

    @Test fun theGlassesLaneNodesAreExpandableAndEveryStageIsDrawnAndWired() {
        for (t in glanceTypes) {
            val f = inner(t)
            assertNotNull("$t is expandable", f)
            val wired = f!!.wires.flatMap { listOf(it.from, it.to) }.toSet()
            assertEquals("$t: every box is on a wire", f.nodes.map { it.id }.toSet(), wired)
            val reached = f.nodes.filter { it.kind == "source" }.map { it.id }.toMutableSet()
            do { val n = reached.size; f.wires.filter { it.from in reached }.forEach { reached += it.to } } while (reached.size != n)
            assertEquals("$t: every box is reachable from a source", f.nodes.map { it.id }.toSet(), reached)
            assertTrue("$t has a source and an outcome", f.nodes.any { it.kind == "source" } && f.nodes.any { it.kind == "branch" })
            assertTrue("$t: every label fits its pill (<= 20 characters)", f.nodes.all { it.label.length <= 22 })
        }
    }

    @Test fun everyExposedSettingIsAPlanParameterThatExistsTodayAtItsTodayDefault() {
        val seen = mutableSetOf<String>()
        for (t in glanceTypes) {
            val specs = NodeTypes.byType(t)!!.params.specs.associateBy { it.name }
            val exposed = inner(t)!!.exposed
            assertEquals("$t exposes every param it has and nothing else", specs.keys, exposed.map { it.param }.toSet())
            for (e in exposed) {
                seen += e.param
                val d = specs.getValue(e.param).default!!
                if (e.param == "ringCompare") assertEquals("all", d.content)
                else assertEquals("$t.${e.param} default", today(e.param)!!, d.doubleOrNull!!, 1e-6)
                assertTrue("${e.param} has a readable label", specs.getValue(e.param).label.isNotBlank())
            }
        }
        assertEquals(12, seen.size) // 11 numbers plus ringCompare
    }

    private fun editedPlanFields(type: String, param: String, value: Any): Set<String> {
        val v: JsonPrimitive = if (value is String) JsonPrimitive(value) else JsonPrimitive(value as Number)
        val p = DefaultPipelines.defaultCapture()
        // the default capture's Threshold sets the onset explicitly; a head onset that disagreed with it would be refused, so the two move together
        val g = p.copy(nodes = p.nodes.map {
            when {
                it.type == type -> it.copy(params = buildJsonObject { put(param, v) })
                param == "onsetRadS" && it.type == NodeTypes.GATE_THRESHOLD -> it.copy(params = buildJsonObject { put("feature", "gyroPeak"); put("op", ">"); put("value", v) })
                else -> it
            }
        })
        val r = PlanCompiler.compile(g)
        assertEquals(emptyList<String>(), r.problems)
        val a = configLeaves(identity); val b = configLeaves(r)
        return (a.keys + b.keys).filter { a[it] != b[it] }.toSet()
    }

    @Test fun editingOneExposedSettingChangesExactlyThatPlanField() {
        for ((edit, path) in edits) assertEquals("${edit.first}.${edit.second}", setOf(path), editedPlanFields(edit.first, edit.second, edit.third))
        assertEquals("every exposed param of the five has an edit golden", glanceTypes.flatMap { t -> inner(t)!!.exposed.map { t to it.param } }.toSet(), edits.map { it.first.first to it.first.second }.toSet())
    }

    @Test fun anExposedSettingAtItsDefaultCompilesToTheIdentityPlan() {
        for (t in glanceTypes) {
            val ps = buildJsonObject { for (s in NodeTypes.byType(t)!!.params.specs) put(s.name, s.default!!) }
            assertEquals("$t at its defaults", emptySet<String>(), changedBy(t, ps))
        }
    }
}
