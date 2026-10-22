package com.rokid.context.ai.canvas.plan

import com.rokid.context.ai.canvas.Pipeline
import com.rokid.context.ai.canvas.PipelineEdge
import com.rokid.context.ai.canvas.PipelineNode
import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.capture.plan.CanonicalJson
import com.rokid.context.capture.plan.PlanJson
import com.rokid.context.capture.plan.PlanResolver
import com.rokid.context.capture.plan.PlanState
import com.rokid.context.capture.plan.PlanValidator
import com.rokid.context.glasses.capture.motion.GlanceDetector
import com.rokid.context.glasses.capture.motion.GlanceParams
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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

class PlanCompilerTest {
    private val none = JsonObject(emptyMap())
    private fun node(id: String, type: String, params: JsonObject = none, title: String = id) = PipelineNode(id, type, title, params)
    private fun sig(id: String, type: String, name: String, domain: String) =
        node(id, type, buildJsonObject { put("name", name); put("domain", domain) })
    private fun obj(vararg kv: Pair<String, Any?>) = buildJsonObject {
        kv.forEach { (k, v) ->
            when (v) {
                null -> put(k, JsonNull)
                is Int -> put(k, v); is Double -> put(k, v); is Boolean -> put(k, v); is String -> put(k, v)
                is JsonElement -> put(k, v)
                else -> error("type")
            }
        }
    }
    private val chainEdges = listOf(
        PipelineEdge("head", "thresh"), PipelineEdge("thresh", "settle"), PipelineEdge("settle", "novel"), PipelineEdge("novel", "rate"),
        PipelineEdge("cin", "rate"), PipelineEdge("rate", "cap"), PipelineEdge("cap", "cam"), PipelineEdge("hub", "cout"),
    )

    /** The default capture-half graph: every node at its defaults, the policy node with no rows. */
    private fun graph(
        head: JsonObject = none, thresh: JsonObject = obj("feature" to "gyroPeak", "op" to ">", "value" to 0.6), settle: JsonObject = none,
        novel: JsonObject = none, rate: JsonObject = none, cap: JsonObject = none, cam: JsonObject = none, hub: JsonObject = none,
        name: String = "P", edges: List<PipelineEdge> = chainEdges, extra: List<PipelineNode> = emptyList(),
    ) = Pipeline(
        2, "p", name,
        listOf(
            node("head", NodeTypes.IMU_HEAD_MOTION, head), node("thresh", NodeTypes.GATE_THRESHOLD, thresh), node("settle", NodeTypes.IMU_SETTLE, settle),
            node("novel", NodeTypes.GATE_NOVELTY, novel), node("rate", NodeTypes.POLICY_CAPTURE_RATE, rate), node("cap", NodeTypes.GATE_RATE_CAP, cap),
            node("cam", NodeTypes.CAPTURE_IMAGE, cam), sig("cin", NodeTypes.SIGNAL_IN, "ctx", "RT_GLASSES"),
            node("hub", NodeTypes.HUB_CONTEXT, hub), sig("cout", NodeTypes.SIGNAL_OUT, "ctx", "RT_PHONE"),
        ) + extra,
        edges,
    )

    private fun ok(p: Pipeline): CompileResult = PlanCompiler.compile(p).also { assertEquals(emptyList<String>(), it.problems); assertNotNull(it.plan) }
    private fun refused(p: Pipeline): List<String> = PlanCompiler.compile(p).also { assertNull(it.plan); assertNull(it.planJson) }.problems

    private val identityRows = obj(
        "REST" to obj("capPerMin" to 2, "settledGapS" to 30, "quietGapS" to 60, "walkGapS" to 30, "stillCapGapS" to 120, "forceSettle" to true, "nudges" to true),
        "VEHICLE_PASSENGER" to obj("capPerMin" to null, "settledGapS" to 0, "quietGapS" to 0, "walkGapS" to 0, "stillCapGapS" to 0, "forceSettle" to false, "nudges" to true),
        "VEHICLE_DRIVING" to obj("capPerMin" to null, "settledGapS" to 0, "quietGapS" to 0, "walkGapS" to 0, "stillCapGapS" to 0, "forceSettle" to false, "nudges" to false),
        "FLIGHT" to obj("capPerMin" to 1, "settledGapS" to 120, "quietGapS" to 300, "walkGapS" to 120, "stillCapGapS" to 300, "forceSettle" to true, "nudges" to true),
    )
    private fun rateWith(rows: JsonObject) = obj("rows" to rows)
    private fun rowsWithRest(vararg kv: Pair<String, Any?>): JsonObject {
        val m = identityRows.toMutableMap()
        val rest = (m.getValue("REST") as JsonObject).toMutableMap()
        kv.forEach { (k, v) -> rest[k] = when (v) { null -> JsonNull; is Boolean -> JsonPrimitive(v); else -> JsonPrimitive(v as Number) } }
        m["REST"] = JsonObject(rest)
        return JsonObject(m)
    }

    private fun rowsWith(state: String, vararg kv: Pair<String, Any?>): JsonObject {
        val m = identityRows.toMutableMap()
        val row = (m.getValue(state) as JsonObject).toMutableMap()
        kv.forEach { (k, v) -> row[k] = when (v) { null -> JsonNull; is Boolean -> JsonPrimitive(v); else -> JsonPrimitive(v as Number) } }
        m[state] = JsonObject(row)
        return JsonObject(m)
    }

    private fun leaves(e: JsonElement, path: String = "", out: MutableMap<String, String> = sortedMapOf()): Map<String, String> {
        when (e) {
            is JsonObject -> e.forEach { (k, v) -> leaves(v, "$path/$k", out) }
            is JsonArray -> e.forEachIndexed { i, v -> leaves(v, "$path/$i", out) }
            else -> out[path] = e.toString()
        }
        return out
    }
    private fun configLeaves(r: CompileResult) = leaves(PlanJson.configJson(r.plan!!.config))
    private fun changed(a: CompileResult, b: CompileResult): Set<String> {
        val x = configLeaves(a); val y = configLeaves(b)
        return (x.keys + y.keys).filter { x[it] != y[it] }.toSet()
    }

    private fun resource(n: String) = javaClass.classLoader!!.getResourceAsStream(n)!!.readBytes().toString(Charsets.UTF_8)

    // ---- goldens ----

    @Test fun goldenCompilesExactly() {
        val g = graph(
            name = "section1 capture half",
            thresh = obj("feature" to "gyroPeak", "op" to ">", "value" to 0.7), settle = obj("settleMs" to 2000),
            novel = obj("noveltyWindowMs" to 30_000, "noveltyMinRad" to 0.3), cap = obj("bigChange" to 20, "bigTurnRad" to 0.9),
            cam = obj("hamming" to 10, "ringCompare" to "last"),
            rate = rateWith(obj(
                "REST" to obj("capPerMin" to 1, "settledGapS" to 60, "quietGapS" to 120, "walkGapS" to 60, "stillCapGapS" to 240, "forceSettle" to true, "nudges" to true),
                "VEHICLE_PASSENGER" to obj("capPerMin" to 2, "forceSettle" to false, "nudges" to true),
            )),
        )
        val r = ok(g)
        assertEquals(resource("canvas/section1-capture-plan.golden.json"), r.planJson)
        assertEquals("5ca1e1c3c69f", r.plan!!.configHash)
        assertEquals(emptyList<String>(), r.warnings)
    }

    @Test fun identityGraphEqualsIdentityPlanBitForBit() {
        val identity = PlanResolver.identity()
        for (g in listOf(graph(), graph(rate = rateWith(identityRows)))) {
            val r = ok(g)
            assertEquals("f5782e0d8610", r.plan!!.configHash)
            assertEquals(identity, r.plan!!.config)
            assertEquals(CanonicalJson.encode(PlanJson.configJson(identity)), CanonicalJson.encode(PlanJson.configJson(r.plan!!.config)))
            assertEquals(PlanJson.encode(r.plan!!), r.planJson)
            assertEquals(emptyList<String>(), r.warnings)
        }
    }

    @Test fun planPassesValidatorAndFitsTheCap() {
        val r = ok(graph())
        val (problems, decoded) = PlanValidator.check(r.planJson!!)
        assertEquals(emptyList<String>(), problems)
        assertEquals(r.plan, decoded)
        assertTrue(r.planJson!!.toByteArray(Charsets.UTF_8).size <= 8_192)
    }

    // ---- one changed param changes only that field ----

    @Test fun oneChangedParamChangesOnlyThatField() {
        val base = ok(graph())
        fun only(g: Pipeline, path: String) = assertEquals(setOf(path), changed(base, ok(g)))
        only(graph(head = obj("bridgeMs" to 300)), "/glance/bridgeMs")
        only(graph(head = obj("minBoutMs" to 400)), "/glance/minBoutMs")
        only(graph(head = obj("minBoutRad" to 0.3)), "/glance/minBoutRad")
        only(graph(head = obj("releaseRadS" to 0.25)), "/glance/releaseRadS")
        only(graph(thresh = obj("feature" to "gyroPeak", "op" to ">", "value" to 0.8)), "/glance/onsetRadS")
        only(graph(thresh = obj("feature" to "boutMs", "op" to ">=", "value" to 350)), "/glance/minBoutMs")
        only(graph(thresh = obj("feature" to "boutRad", "op" to ">=", "value" to 0.3)), "/glance/minBoutRad")
        only(graph(settle = obj("settleMs" to 2000)), "/glance/settleMs")
        only(graph(novel = obj("noveltyWindowMs" to 30_000)), "/glance/noveltyWindowMs")
        only(graph(novel = obj("noveltyMinRad" to 0.4)), "/glance/noveltyMinRad")
        only(graph(cap = obj("bigChange" to 24)), "/gate/bigChange")
        only(graph(cap = obj("bigTurnRad" to 1.0)), "/glance/bigTurnRad")
        only(graph(cam = obj("hamming" to 12)), "/gate/hamming")
        only(graph(cam = obj("ringCompare" to "last")), "/gate/ringCompare")
        only(graph(rate = rateWith(rowsWithRest("capPerMin" to 3))), "/policy/REST/capPerMin")
        only(graph(rate = rateWith(rowsWithRest("quietGapS" to 90))), "/policy/REST/timerPhotoGapsMs/quietMs")
        only(graph(rate = rateWith(rowsWithRest("forceSettle" to false))), "/policy/REST/forceSettle")
        only(graph(rate = rateWith(rowsWithRest("walkGapS" to 45))), "/policy/REST/timerPhotoGapsMs/walkMs")
        only(graph(rate = rateWith(rowsWithRest("stillCapGapS" to 200))), "/policy/REST/timerPhotoGapsMs/stillCapMs")
        only(graph(rate = rateWith(rowsWithRest("nudges" to false))), "/policy/REST/nudges")
        only(graph(rate = rateWith(rowsWith("FLIGHT", "capPerMin" to 2))), "/policy/FLIGHT/capPerMin")
        only(graph(rate = rateWith(rowsWith("FLIGHT", "quietGapS" to 200))), "/policy/FLIGHT/timerPhotoGapsMs/quietMs")
        only(graph(rate = rateWith(rowsWith("FLIGHT", "nudges" to false))), "/policy/FLIGHT/nudges")
        only(graph(rate = rateWith(rowsWith("VEHICLE_PASSENGER", "capPerMin" to 3))), "/policy/VEHICLE_PASSENGER/capPerMin")
        only(graph(rate = rateWith(rowsWith("VEHICLE_PASSENGER", "walkGapS" to 40))), "/policy/VEHICLE_PASSENGER/timerPhotoGapsMs/walkMs")
        only(graph(rate = rateWith(rowsWith("VEHICLE_DRIVING", "settledGapS" to 20))), "/policy/VEHICLE_DRIVING/timerPhotoGapsMs/settledMs")
        only(graph(rate = rateWith(rowsWith("VEHICLE_DRIVING", "capPerMin" to 2))), "/policy/VEHICLE_DRIVING/capPerMin")
        // a changed param never moves the meta-independent hash of an unchanged config
        assertEquals(base.plan!!.configHash, ok(graph(name = "another name")).plan!!.configHash)
        assertFalse(base.plan!!.configHash == ok(graph(head = obj("bridgeMs" to 300))).plan!!.configHash)
    }

    // ---- operators (S78) ----

    @Test fun gyroPeakGteRefused() {
        val p = refused(graph(thresh = obj("feature" to "gyroPeak", "op" to ">=", "value" to 0.6)))
        assertEquals(listOf(">= is not supported for gyroPeak: onset is strict >"), p)
    }

    @Test fun boutMsGtRefused() {
        assertEquals(listOf("> is not supported for boutMs: bouts are inclusive >="), refused(graph(thresh = obj("feature" to "boutMs", "op" to ">", "value" to 300))))
        assertEquals(listOf("> is not supported for boutRad: bouts are inclusive >="), refused(graph(thresh = obj("feature" to "boutRad", "op" to ">", "value" to 0.3))))
    }

    @Test fun thresholdBoundaryExact() {
        // onset 0.6 with strict >: a 0.6 sample never starts a bout; 0.61 does (replay through the real detector)
        fun glances(r: CompileResult, mag: Float): Int {
            val g = r.plan!!.config.glance
            val d = GlanceDetector(GlanceParams(onsetRadS = g.onsetRadS, releaseRadS = g.releaseRadS, bridgeMs = g.bridgeMs, minBoutMs = g.minBoutMs,
                minBoutRad = g.minBoutRad, settleMs = g.settleMs))
            var t = 0L
            fun feed(ms: Long, m: Float) { val end = t + ms; while (t < end) { d.onGyro(m, t, 20, true); t += 20 } }
            feed(1_000, 0f); feed(500, mag); feed(4_000, 0f)
            return d.glances
        }
        val at06 = ok(graph())
        assertEquals(0, glances(at06, 0.6f))
        assertEquals(1, glances(at06, 0.61f))
        val at059 = ok(graph(thresh = obj("feature" to "gyroPeak", "op" to ">", "value" to 0.59)))
        assertEquals(1, glances(at059, 0.6f))
        assertEquals(300L, ok(graph(thresh = obj("feature" to "boutMs", "op" to ">=", "value" to 300))).plan!!.config.glance.minBoutMs)
    }

    /** A bout of [n] samples [dt] ms apart, the first with dt [firstDt] (its length is (n-1)*dt + firstDt), then 4 s of rest; the glance count. */
    private fun replayBout(r: CompileResult, n: Int, firstDt: Long, dt: Long, mag: Float): Int {
        val g = r.plan!!.config.glance
        val d = GlanceDetector(GlanceParams(onsetRadS = g.onsetRadS, releaseRadS = g.releaseRadS, bridgeMs = g.bridgeMs, minBoutMs = g.minBoutMs,
            minBoutRad = g.minBoutRad, settleMs = g.settleMs, maxDtMs = 250))
        var t = 1_000L
        d.onGyro(mag, t, firstDt, true)
        repeat(n - 1) { t += dt; d.onGyro(mag, t, dt, true) }
        val end = t + 4_000
        while (t < end) { t += 20; d.onGyro(0f, t, 20, true) }
        return d.glances
    }

    @Test fun boutMsBoundaryExactThroughTheDetector() {
        val r = ok(graph(thresh = obj("feature" to "boutMs", "op" to ">=", "value" to 350)))
        assertEquals(350L, r.plan!!.config.glance.minBoutMs)
        assertEquals(1, replayBout(r, 17, 30, 20, 1.5f)) // length 16*20+30 = 350 counts (inclusive)
        assertEquals(0, replayBout(r, 17, 29, 20, 1.5f)) // 349 does not
    }

    @Test fun boutRadBoundaryExactThroughTheDetector() {
        val r = ok(graph(thresh = obj("feature" to "boutRad", "op" to ">=", "value" to 0.5)))
        assertEquals(0.5f, r.plan!!.config.glance.minBoutRad, 0f)
        // dt 125 ms at 1 rad/s is 0.125 rad a sample (exact in float): four samples turn exactly 0.5 rad, three 0.375
        assertEquals(1, replayBout(r, 4, 125, 125, 1.0f))
        assertEquals(0, replayBout(r, 3, 125, 125, 1.0f))
    }

    @Test fun thresholdValueOutOfBoundsNamesTheNode() {
        assertEquals(listOf("node thresh: gyroPeak threshold must be between 0.4 and 1"), refused(graph(thresh = obj("feature" to "gyroPeak", "op" to ">", "value" to 0.3))))
        assertEquals(listOf("node thresh: boutMs threshold must be between 100 and 800"), refused(graph(thresh = obj("feature" to "boutMs", "op" to ">=", "value" to 900))))
        assertEquals(listOf("node thresh: boutMs threshold must be a whole number"), refused(graph(thresh = obj("feature" to "boutMs", "op" to ">=", "value" to 300.5))))
    }

    @Test fun conflictingThresholdsRefused() {
        val g = graph(head = obj("onsetRadS" to 0.5))
        assertEquals(listOf("node thresh: gyroPeak threshold conflicts with onsetRadS of node head"), refused(g))
        val two = graph(extra = listOf(node("thresh2", NodeTypes.GATE_THRESHOLD, obj("feature" to "gyroPeak", "op" to ">", "value" to 0.7))),
            edges = chainEdges.filter { it != PipelineEdge("thresh", "settle") } + PipelineEdge("thresh", "thresh2") + PipelineEdge("thresh2", "settle"))
        assertEquals(listOf("node thresh2: gyroPeak threshold conflicts with node thresh"), refused(two))
        // the same value twice is fine
        val same = graph(extra = listOf(node("thresh2", NodeTypes.GATE_THRESHOLD, obj("feature" to "gyroPeak", "op" to ">", "value" to 0.6))),
            edges = chainEdges.filter { it != PipelineEdge("thresh", "settle") } + PipelineEdge("thresh", "thresh2") + PipelineEdge("thresh2", "settle"))
        ok(same)
    }

    @Test fun releaseAboveFoldedOnsetRefused() {
        val g = graph(head = obj("releaseRadS" to 0.5), thresh = obj("feature" to "gyroPeak", "op" to ">", "value" to 0.45))
        assertEquals(listOf("node head: releaseRadS must not exceed the gyroPeak threshold"), refused(g))
    }

    @Test fun headReleaseAboveDefaultOnsetIsFineWhenTheFoldedOnsetIsHigher() {
        // not refused before the fold: head release 0.7 with a gyroPeak threshold of 0.8 is a valid plan
        val r = ok(graph(head = obj("releaseRadS" to 0.7), thresh = obj("feature" to "gyroPeak", "op" to ">", "value" to 0.8)))
        assertEquals(0.7f, r.plan!!.config.glance.releaseRadS, 0f)
        assertEquals(0.8f, r.plan!!.config.glance.onsetRadS, 0f)
    }

    @Test fun headReleaseAboveItsOwnOnsetRefusedWithoutThreshold() {
        val p = Pipeline(2, "p", "P", listOf(node("head", NodeTypes.IMU_HEAD_MOTION, obj("onsetRadS" to 0.5, "releaseRadS" to 0.6))), emptyList())
        assertEquals(listOf("node head: releaseRadS must not exceed onsetRadS"), refused(p))
    }

    // ---- topology (S78) ----

    @Test fun duplicatePolicyRefused() {
        val g = graph(extra = listOf(node("a_rate", NodeTypes.POLICY_CAPTURE_RATE, rateWith(obj("REST" to obj("capPerMin" to 4))))), edges = chainEdges + PipelineEdge("cin", "a_rate"))
        val p = refused(g)
        assertTrue(p.toString(), p.contains("unsupported wrapper order: policy.capture_rate before policy.capture_rate"))
        // a second node of any other chain type is refused too
        val twoCaps = graph(extra = listOf(node("cap2", NodeTypes.GATE_RATE_CAP)), edges = chainEdges + PipelineEdge("novel", "cap2"))
        assertTrue(refused(twoCaps).contains("unsupported wrapper order: gate.rate_cap before gate.rate_cap"))
    }

    @Test fun ctxMustHaveExactlyOneOutEdge() {
        val g = graph(edges = chainEdges + PipelineEdge("cin", "cap"))
        assertTrue(refused(g).contains("ctx binding must feed policy.capture_rate"))
    }

    @Test fun policyOffTheChainRefused() {
        // the policy is fed by ctx but is not between novelty and the cap
        val edges = listOf(
            PipelineEdge("head", "thresh"), PipelineEdge("thresh", "settle"), PipelineEdge("settle", "novel"), PipelineEdge("novel", "cap"),
            PipelineEdge("cin", "rate"), PipelineEdge("cap", "cam"), PipelineEdge("hub", "cout"),
        )
        val p = refused(graph(edges = edges))
        assertTrue(p.toString(), p.contains("unsupported wrapper order: policy.capture_rate is not on the chain"))
    }

    @Test fun reorderedPolicyBeforeNoveltyRefused() {
        val edges = listOf(
            PipelineEdge("head", "thresh"), PipelineEdge("thresh", "settle"), PipelineEdge("settle", "rate"), PipelineEdge("rate", "novel"),
            PipelineEdge("cin", "rate"), PipelineEdge("novel", "cap"), PipelineEdge("cap", "cam"), PipelineEdge("hub", "cout"),
        )
        assertTrue(refused(graph(edges = edges)).contains("unsupported wrapper order: policy.capture_rate before gate.novelty"))
    }

    @Test fun settleBeforeThresholdRefused() {
        val edges = listOf(
            PipelineEdge("head", "settle"), PipelineEdge("settle", "thresh"), PipelineEdge("thresh", "novel"), PipelineEdge("novel", "rate"),
            PipelineEdge("cin", "rate"), PipelineEdge("rate", "cap"), PipelineEdge("cap", "cam"), PipelineEdge("hub", "cout"),
        )
        assertTrue(refused(graph(edges = edges)).contains("unsupported wrapper order: imu.settle before gate.threshold"))
    }

    @Test fun branchRefused() {
        // novelty feeds both the policy and the rate cap
        val edges = chainEdges + PipelineEdge("novel", "cap")
        assertEquals(listOf("unsupported wrapper order: gate.novelty before gate.rate_cap"), refused(graph(edges = edges)))
        // two inputs into one chain node
        val join = chainEdges + PipelineEdge("settle", "rate")
        assertEquals(listOf("unsupported wrapper order: imu.settle before policy.capture_rate"), refused(graph(edges = join)))
    }

    @Test fun disconnectedBindingRefused() {
        val noCtxEdge = chainEdges.filter { it != PipelineEdge("cin", "rate") }
        assertEquals(listOf("ctx binding must feed policy.capture_rate"), refused(graph(edges = noCtxEdge)))
        val wrongTarget = noCtxEdge + PipelineEdge("cin", "cap")
        assertTrue(refused(graph(edges = wrongTarget)).contains("ctx binding must feed policy.capture_rate"))
        val noHub = chainEdges.filter { it != PipelineEdge("hub", "cout") }
        assertEquals(listOf("ctx binding must be sent from hub.context"), refused(graph(edges = noHub)))
    }

    @Test fun optionalNodesMayBeLeftOut() {
        val p = Pipeline(
            2, "p", "P",
            listOf(node("head", NodeTypes.IMU_HEAD_MOTION), node("settle", NodeTypes.IMU_SETTLE), node("novel", NodeTypes.GATE_NOVELTY), node("cam", NodeTypes.CAPTURE_IMAGE)),
            listOf(PipelineEdge("head", "settle"), PipelineEdge("settle", "novel"), PipelineEdge("novel", "cam")),
        )
        val r = ok(p)
        assertFalse(r.plan!!.config.ctxBound)
        assertEquals(PlanResolver.identity().copy(ctxBound = false), r.plan!!.config)
    }

    // ---- refusals ----

    @Test fun asyncNodeInTheRtHalfRefused() {
        val g = graph(extra = listOf(node("extract", NodeTypes.VISION_EXTRACT)), edges = chainEdges + PipelineEdge("cam", "extract"))
        assertTrue(refused(g).any { it.contains("crosses domains") })
        val g2 = graph(extra = listOf(node("photos", NodeTypes.INPUT_PHOTOS), node("extract", NodeTypes.VISION_EXTRACT)),
            edges = chainEdges + PipelineEdge("cam", "photos") + PipelineEdge("photos", "extract") + PipelineEdge("extract", "rate"))
        assertEquals(listOf("edge extract->rate: crosses domains", "cycle through cam"), refused(g2))
        // the photos binding itself and the ASYNC half are fine and do not change the plan
        val withAsync = graph(extra = listOf(node("photos", NodeTypes.INPUT_PHOTOS), node("extract", NodeTypes.VISION_EXTRACT)),
            edges = chainEdges + PipelineEdge("cam", "photos") + PipelineEdge("photos", "extract"))
        assertEquals(ok(graph()).planJson!!.substringBefore("\"meta\""), ok(withAsync).planJson!!.substringBefore("\"meta\""))
    }

    @Test fun asyncOnlyPipelineHasNoCaptureHalf() {
        val p = Pipeline(2, "p", "P", listOf(node("photos", NodeTypes.INPUT_PHOTOS), node("extract", NodeTypes.VISION_EXTRACT)), listOf(PipelineEdge("photos", "extract")))
        assertEquals(listOf("pipeline has no capture half"), refused(p))
    }

    @Test fun cycleRefused() {
        assertTrue(refused(graph(edges = chainEdges + PipelineEdge("cam", "cap"))).any { it.startsWith("cycle through ") })
    }

    @Test fun unknownParamAndOutOfBoundsNameTheNode() {
        assertTrue(refused(graph(head = obj("nope" to 1))).contains("node head: unknown param nope"))
        assertTrue(refused(graph(head = obj("onsetRadS" to 1.1))).contains("node head: onsetRadS must be between 0.4 and 1"))
        assertTrue(refused(graph(settle = obj("settleMs" to 999))).contains("node settle: settleMs must be between 1000 and 3000"))
        assertTrue(refused(graph(cam = obj("hamming" to 17))).contains("node cam: hamming must be between 6 and 16"))
        assertTrue(refused(graph(rate = rateWith(obj("WALKING" to obj("capPerMin" to 1))))).contains("node rate: unknown row WALKING"))
    }

    @Test fun hubParamsRefusedUntilLater() {
        assertEquals(listOf("hub fusion params arrive in slice 3a-LATER"), refused(graph(hub = obj("fastKmh" to 30))))
    }

    @Test fun sensingParamNeverCompiles() {
        val p = refused(graph(head = obj("sampleRateHz" to 100)))
        assertTrue(p.toString(), p.contains("node head: sensing and detection are not plan parameters"))
        assertTrue(refused(graph(settle = obj("detectorMode" to 1))).contains("node settle: sensing and detection are not plan parameters"))
    }

    @Test fun messagesNeverEchoPipelineText() {
        val marker = "LEAKMARKER"
        val g = graph(name = marker).let { p -> p.copy(nodes = p.nodes.map { it.copy(title = marker) }) }
        assertFalse(PlanCompiler.compile(g).problems.any { it.contains(marker) })
        val bad = g.copy(nodes = g.nodes.map { if (it.id == "head") it.copy(params = obj("onsetRadS" to 9.0)) else it })
        val r = PlanCompiler.compile(bad)
        assertTrue(r.problems.isNotEmpty()); assertFalse(r.problems.any { it.contains(marker) })
    }

    // ---- policy rows (S68, S83) ----

    @Test fun blankCapIsNull() {
        // only an explicit null is blank
        val explicit = ok(graph(rate = rateWith(rowsWithRest("capPerMin" to null))))
        assertNull(explicit.plan!!.config.policy.getValue(PlanState.REST).capPerMin)
        // an absent cap in a present row takes the identity row's cap
        val absent = ok(graph(rate = rateWith(obj("REST" to obj("settledGapS" to 30), "FLIGHT" to obj("forceSettle" to true))))).plan!!.config.policy
        assertEquals(2, absent.getValue(PlanState.REST).capPerMin)
        assertEquals(1, absent.getValue(PlanState.FLIGHT).capPerMin)
    }

    @Test fun blankGapIsZero() {
        val r = ok(graph(rate = rateWith(rowsWithRest("settledGapS" to null))))
        val gaps = r.plan!!.config.policy.getValue(PlanState.REST).timerPhotoGapsMs
        assertEquals(0L, gaps.settledMs)
        assertEquals(60_000L, gaps.quietMs)
        // absent gaps take the identity row's gaps
        val absent = ok(graph(rate = rateWith(obj("REST" to obj("capPerMin" to 1))))).plan!!.config.policy.getValue(PlanState.REST)
        assertEquals(listOf(30_000L, 60_000L, 30_000L, 120_000L), absent.timerPhotoGapsMs.all())
        assertEquals(1, absent.capPerMin)
    }

    @Test fun partialRowKeepsIdentityCells() {
        val id = PlanResolver.identity().policy
        for (s in PlanState.entries) {
            val r = ok(graph(rate = rateWith(obj(s.name to obj("nudges" to false)))))
            val row = r.plan!!.config.policy.getValue(s)
            val expected = if (s == PlanState.VEHICLE_DRIVING) id.getValue(s) else id.getValue(s).copy(nudges = false)
            assertEquals(s.name, expected, row)
        }
    }

    @Test fun allFourRowsAreAlwaysEmittedFromIdentity() {
        val id = PlanResolver.identity().policy
        for (g in listOf(graph(), graph(rate = rateWith(obj("REST" to obj("capPerMin" to 3)))), graph(rate = rateWith(obj())))) {
            val policy = ok(g).plan!!.config.policy
            assertEquals(PlanState.entries.toSet(), policy.keys)
            for (s in PlanState.entries) if (s != PlanState.REST || g == graph()) assertEquals(id.getValue(s), policy.getValue(s))
        }
        // the encoded json carries all four rows
        val json = ok(graph(rate = rateWith(obj("REST" to obj("capPerMin" to 3))))).planJson!!
        for (s in PlanState.entries) assertTrue(s.name, json.contains("\"${s.name}\""))
    }

    @Test fun gapBelowProfileWarns() {
        val w = "timer photo gap below the settings profile has no effect"
        // the settings profile (SETTLE, T0): settled 10 s, quiet 30 s, walk 10 s, still cap 60 s
        for ((k, v) in listOf("settledGapS" to 9, "quietGapS" to 29, "walkGapS" to 5, "stillCapGapS" to 59)) {
            val r = ok(graph(rate = rateWith(rowsWithRest(k to v))))
            assertEquals(k, listOf(w), r.warnings)
            assertNotNull(r.plan)
        }
        assertEquals(emptyList<String>(), ok(graph(rate = rateWith(rowsWithRest("settledGapS" to 10, "quietGapS" to 30, "walkGapS" to 10, "stillCapGapS" to 60)))).warnings)
        assertEquals(emptyList<String>(), ok(graph(rate = rateWith(rowsWithRest("settledGapS" to 0)))).warnings)
        // two low gaps still give one warning
        assertEquals(listOf(w), ok(graph(rate = rateWith(rowsWithRest("settledGapS" to 5, "quietGapS" to 5)))).warnings)
    }

    // ---- determinism, cost ----

    @Test fun compileIsDeterministicAndOrderIndependent() {
        val g = graph(rate = rateWith(rowsWithRest("capPerMin" to 3)), settle = obj("settleMs" to 2000))
        val a = ok(g)
        assertEquals(a.planJson, ok(g).planJson)
        val shuffled = g.copy(nodes = g.nodes.reversed(), edges = g.edges.reversed())
        assertEquals(a.planJson, ok(shuffled).planJson)
        val bad = graph(thresh = obj("feature" to "gyroPeak", "op" to ">=", "value" to 0.6), head = obj("nope" to 1), hub = obj("a" to 1))
        assertEquals(PlanCompiler.compile(bad).problems, PlanCompiler.compile(bad.copy(nodes = bad.nodes.reversed(), edges = bad.edges.reversed())).problems)
    }

    @Test fun staticCostIsTheSumOverTheCaptureHalf() {
        val c = ok(graph()).staticCost
        assertEquals(0, c.modelCalls)
        assertEquals(StaticCost(0, 1, "LOW"), c)
        // the ASYNC half (a vision node with model calls) is not counted
        val withAsync = graph(extra = listOf(node("photos", NodeTypes.INPUT_PHOTOS), node("extract", NodeTypes.VISION_EXTRACT)),
            edges = chainEdges + PipelineEdge("cam", "photos") + PipelineEdge("photos", "extract"))
        assertEquals(c, ok(withAsync).staticCost)
    }
}
