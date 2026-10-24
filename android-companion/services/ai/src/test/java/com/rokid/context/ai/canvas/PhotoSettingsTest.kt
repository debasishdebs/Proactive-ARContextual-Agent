package com.rokid.context.ai.canvas

import com.rokid.context.ai.canvas.plan.PlanCompiler
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `input.photos` source and filter settings: every default is today's behaviour, so a pipeline saved before keeps its hash, its plan and its result. */
class PhotoSettingsTest {
    private val none = JsonObject(emptyMap())
    private fun photosWith(params: JsonObject) = Pipeline(
        1, "p", "P", listOf(PipelineNode("photos", NodeTypes.INPUT_PHOTOS, "Photo", params), PipelineNode("extract", NodeTypes.VISION_EXTRACT, "E", none)),
        listOf(PipelineEdge("photos", "extract")),
    )
    private fun problems(params: JsonObject) = PipelineValidator.validate(photosWith(params))
    private fun one(name: String, v: JsonElement) = problems(buildJsonObject { put(name, v) })

    @Test fun schemaListsTheFourSettingsWithTodaysDefaults() {
        val specs = NodeTypes.byType(NodeTypes.INPUT_PHOTOS)!!.params.specs.associateBy { it.name }
        assertEquals(setOf("source", "sinceHours", "markedOnly", "limit"), specs.keys)
        assertEquals(listOf("glasses", "all"), specs.getValue("source").options)
        assertEquals(JsonPrimitive("glasses"), specs.getValue("source").default)
        assertEquals(JsonPrimitive(0), specs.getValue("sinceHours").default) // 0 = any time
        assertEquals(JsonPrimitive(false), specs.getValue("markedOnly").default)
        assertEquals(JsonPrimitive(50), specs.getValue("limit").default) // the runner's own cap
        assertEquals(PhotoSettings(), PhotoSettings.of(none))
        assertTrue(PhotoSettings().isDefault)
    }

    @Test fun theDefaultsAreAcceptedAndNothingIsRequired() {
        assertEquals(emptyList<String>(), problems(none))
        assertEquals(emptyList<String>(), problems(buildJsonObject { put("source", "glasses"); put("sinceHours", 0); put("markedOnly", false); put("limit", 50) }))
        assertEquals(emptyList<String>(), problems(buildJsonObject { put("source", "all"); put("sinceHours", 24); put("markedOnly", true); put("limit", 5) }))
    }

    @Test fun onlyTheListedValuesAreAccepted() {
        assertTrue(one("source", JsonPrimitive("cloud")).toString(), one("source", JsonPrimitive("cloud")).any { it.contains("source must be one of glasses, all") })
        assertTrue(one("source", JsonPrimitive(1)).isNotEmpty())
        assertTrue(one("sinceHours", JsonPrimitive(-1)).any { it.contains("sinceHours must be between 0 and 720") })
        assertTrue(one("sinceHours", JsonPrimitive(721)).isNotEmpty())
        assertTrue(one("sinceHours", JsonPrimitive("3")).isNotEmpty())
        assertTrue(one("sinceHours", JsonPrimitive(1.5)).isNotEmpty())
        assertTrue(one("markedOnly", JsonPrimitive("yes")).any { it.contains("markedOnly must be true or false") })
        assertTrue(one("limit", JsonPrimitive(0)).any { it.contains("limit must be between 1 and 50") })
        assertTrue(one("limit", JsonPrimitive(51)).isNotEmpty())
        assertTrue(one("limit", JsonNull).isNotEmpty())
    }

    @Test fun aSavedPipelineWithOtherKeysStillValidates() {
        assertEquals(emptyList<String>(), problems(buildJsonObject { put("legacyNote", "kept") }))
    }

    @Test fun settingsReadTheParamsAndFallBackToTheDefaults() {
        val s = PhotoSettings.of(buildJsonObject { put("source", "all"); put("sinceHours", 6); put("markedOnly", true); put("limit", 3) })
        assertEquals(PhotoSettings("all", 6, true, 3), s)
        assertFalse(s.isDefault)
        assertEquals(PhotoSettings(), PhotoSettings.of(buildJsonObject { put("source", "nope"); put("sinceHours", -4); put("markedOnly", "x"); put("limit", 999) }))
    }

    // ---- golden: a pipeline saved before this change

    @Test fun savedPipelinesKeepTheirHashAndPlan() {
        // pinned from v0.9.3 e9c0dc4b (the live hash was read before the schema existed and did not move)
        assertEquals(
            listOf("0f331f401992", "df5b4cbe2fa3"),
            listOf(DefaultPipelines.liveExtract(), DefaultPipelines.intentDraft("SEED {{recent_context}}")).map { PipelineHash.short(PipelineHash.of(it)) },
        )
        assertEquals("f5782e0d8610", PlanCompiler.compile(DefaultPipelines.defaultCapture()).plan!!.configHash)
        assertFalse("the built-ins carry no photo param", DefaultPipelines.liveExtract().nodes.first { it.type == NodeTypes.INPUT_PHOTOS }.params.isNotEmpty())
    }

    // ---- the filter

    private val h = 3_600_000L
    private val now = 1_000 * h
    private fun fact(id: String, ageH: Double, source: String = "rokid_glasses", marked: Boolean = false) = FrameFact(id, now - (ageH * h).toLong(), source, marked)
    private val facts = listOf(fact("a", 1.0), fact("b", 5.0, marked = true), fact("c", 30.0, marked = true), fact("d", 2.0, source = "import"), fact("e", 3.0)).associateBy { it.id }
    private val ids = listOf("a", "b", "c", "d", "e")

    @Test fun defaultsKeepEveryChosenIdInItsOrderWhateverTheFacts() {
        assertEquals(ids, PhotoScope.apply(PhotoSettings(), ids, emptyMap(), now))
        assertEquals(ids + "gone", PhotoScope.apply(PhotoSettings(), ids + "gone", emptyMap(), now))
    }

    @Test fun glassesSourceKeepsOnlyGlassesCaptures() {
        assertEquals(listOf("a", "b", "c", "e"), PhotoScope.apply(PhotoSettings(markedOnly = false, limit = 49), ids, facts, now))
    }

    @Test fun allSourceKeepsEverySource() {
        assertEquals(ids, PhotoScope.apply(PhotoSettings(source = "all", limit = 49), ids, facts, now))
    }

    @Test fun sinceHoursDropsOlderFrames() {
        assertEquals(listOf("a", "b", "e"), PhotoScope.apply(PhotoSettings(sinceHours = 6), ids, facts, now))
        assertEquals(listOf("a"), PhotoScope.apply(PhotoSettings(sinceHours = 1), ids, facts, now)) // exactly 1 h old is kept
    }

    @Test fun markedOnlyKeepsMarkedFrames() {
        assertEquals(listOf("b", "c"), PhotoScope.apply(PhotoSettings(markedOnly = true), ids, facts, now))
    }

    @Test fun limitKeepsTheNewestAndTheChosenOrder() {
        assertEquals(listOf("a", "e"), PhotoScope.apply(PhotoSettings(limit = 2), ids, facts, now)) // newest two among the glasses captures: a (1 h), e (3 h)
    }

    @Test fun anIdWithNoFactIsDroppedWhenAnySettingIsSet() {
        assertEquals(listOf("a"), PhotoScope.apply(PhotoSettings(limit = 49), listOf("a", "gone"), facts, now))
    }

    @Test fun theNodeOfAPipelineIsFoundOrDefault() {
        assertEquals(PhotoSettings("all", 0, false, 50), PhotoScope.settingsOf(photosWith(buildJsonObject { put("source", "all") })))
        assertEquals(PhotoSettings(), PhotoScope.settingsOf(Pipeline(2, "p", "P", emptyList(), emptyList())))
    }
}
