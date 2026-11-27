package com.rokid.context.work.frames.place

import com.rokid.context.memory.place.CardDelivery
import com.rokid.context.memory.retrieval.PhotoConfirm
import com.rokid.context.memory.retrieval.RefKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceEvalTest {
    private fun input(kind: HistoryKind, delivery: String? = null, l1: PlaceEval.L1? = null, cand: List<String> = listOf("wd:Q1"), l2: String = "-") =
        PlaceEval.Input(kind, delivery, "AUTOMATIC", 1, l1, cand, l2, null, null, "-")

    @Test fun layerAndShownPerKind() {
        assertEquals("L1", PlaceEval.layerOf(input(HistoryKind.IMAGE)))
        assertEquals("L2", PlaceEval.layerOf(input(HistoryKind.PHOTO)))
        assertEquals("L2", PlaceEval.layerOf(input(HistoryKind.POSSIBLE)))
        assertEquals("L3", PlaceEval.layerOf(input(HistoryKind.MAYBE)))
        assertEquals("PIN", PlaceEval.layerOf(input(HistoryKind.PIN)))
        assertEquals("PIN", PlaceEval.layerOf(input(HistoryKind.PIN_CLUSTER)))
        assertEquals("LOC", PlaceEval.layerOf(input(HistoryKind.NEARBY)))
        assertEquals("NONE", PlaceEval.layerOf(input(HistoryKind.NEARBY, CardDelivery.HELD_NO_REFERENCE)))
        assertEquals("NONE", PlaceEval.layerOf(input(HistoryKind.NO_MATCH, CardDelivery.HELD_NOT_CONFIRMED)))
        assertEquals("L2", PlaceEval.layerOf(input(HistoryKind.NO_MATCH, CardDelivery.HELD_NOT_CONFIRMED, l2 = "none")))
        assertTrue(PlaceEval.shown(HistoryKind.PHOTO, null)); assertTrue(PlaceEval.shown(HistoryKind.NEARBY, CardDelivery.PENDING))
        assertFalse(PlaceEval.shown(HistoryKind.NEARBY, CardDelivery.HELD_DATA_SAVER)); assertFalse(PlaceEval.shown(HistoryKind.POSSIBLE, CardDelivery.NOT_SENT_POSSIBLE)); assertFalse(PlaceEval.shown(HistoryKind.NO_MATCH, null))
    }

    @Test fun neverMoreThan2KbAndAlwaysValidJson() {
        val scores = (1..40).map { PhotoConfirm.PlaceScore("osm:node/${1_000_000_000L + it}${"x".repeat(30)}", 900 - it, RefKind.WEB, PhotoConfirm.Level.MID) }
        val l1 = PlaceEval.L1("medium", 4, scores, 12, null)
        val s = PlaceEval.build(input(HistoryKind.PHOTO, l1 = l1, cand = scores.map { it.placeId }))
        assertTrue("${s.length}", s.toByteArray().size <= PlaceEval.MAX_BYTES)
        val o = Json.parseToJsonElement(s) as JsonObject
        assertEquals(1, (o["v"] as kotlinx.serialization.json.JsonPrimitive).content.toInt())
        assertTrue((((o["l1"] as JsonObject)["scores"]) as JsonArray).size <= 8)
    }

    @Test fun carriesOnlyNumbersIdsAndTokens() {
        val l1 = PlaceEval.L1("accept", 2, listOf(PhotoConfirm.PlaceScore("user:abc", 970, RefKind.OWN, PhotoConfirm.Level.HIGH), PhotoConfirm.PlaceScore("wd:Q9", null, null, PhotoConfirm.Level.NO_REF)), 33, null)
        val s = PlaceEval.build(input(HistoryKind.IMAGE, l1 = l1, cand = listOf("user:abc", "wd:Q9")))
        val o = Json.parseToJsonElement(s) as JsonObject
        assertEquals(setOf("v", "layer", "shown", "held", "mode", "thr", "cand", "l1", "l2", "l3", "ref"), o.keys)
        val l = o["l1"] as JsonObject
        assertEquals(setOf("state", "frames", "margin", "top", "scores"), l.keys)
        assertTrue(s.contains("\"milli\":970") && s.contains("\"ref\":\"OWN\"") && s.contains("\"level\":\"HIGH\""))
    }
}
