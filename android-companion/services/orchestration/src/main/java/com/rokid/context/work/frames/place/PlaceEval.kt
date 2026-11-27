package com.rokid.context.work.frames.place

import com.rokid.context.memory.place.CardDelivery
import com.rokid.context.memory.retrieval.PhotoConfirm
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The per-attempt layer record of a place card history row (`place_card_log.eval`, context.db v19): which layer decided, the Layer 1 scores and margin as numbers, the candidate place ids, the Layer 2 answer token and
 * confidence, the Layer 3 token, the held reason token, the check mode and the thresholds version. Built from the sampler's decision trace only: place ids, numbers and fixed tokens. NO name, caption, coordinate, photo or frame id.
 * At most [MAX_BYTES]: when it would be larger the score list is shortened, then dropped.
 */
object PlaceEval {
    const val MAX_BYTES = 2048
    const val VERSION = 1
    private const val MAX_SCORES = 8
    private const val MAX_CAND = 12

    /** Layer 1 as the sampler saw it: [state] is the outcome token (accept, medium, below, no_ref, skip, off). */
    class L1(val state: String, val frames: Int, val scores: List<PhotoConfirm.PlaceScore>, val margin: Int?, val skip: String?)

    class Input(
        val kind: HistoryKind, val delivery: String?, val checkMode: String, val thresholdsVersion: Int, val l1: L1?, val candidates: List<String>,
        val l2: String, val l2Conf: Int?, val l2Pick: String?, val l3: String, val glanceRef: Boolean = false,
    )

    /** The layer that produced the row: L1, L2, L3, PIN (a pin-first card), LOC (a location-only nearby card) or NONE (nothing decided: a held row, an unmatched attempt). */
    fun layerOf(i: Input): String = when {
        i.kind == HistoryKind.IMAGE -> "L1"
        i.kind == HistoryKind.PHOTO || i.kind == HistoryKind.POSSIBLE -> "L2"
        i.kind == HistoryKind.MAYBE -> "L3"
        isHeld(i.delivery) -> if (i.l2 == "none" || i.l2 == "contract") "L2" else "NONE"
        i.kind == HistoryKind.NO_MATCH -> "NONE"
        i.kind == HistoryKind.PIN || i.kind == HistoryKind.PIN_CLUSTER -> "PIN"
        else -> "LOC"
    }

    fun isHeld(delivery: String?): Boolean = delivery != null && delivery.startsWith("HELD_")

    /** A card the glasses were (or were to be) sent: not held, not a POSSIBLE row, not a NO_MATCH row. */
    fun shown(kind: HistoryKind?, delivery: String?): Boolean = !isHeld(delivery) && kind != HistoryKind.POSSIBLE && kind != HistoryKind.NO_MATCH

    fun build(i: Input): String {
        fun json(withScores: Int): String = buildJsonObject {
            put("v", VERSION)
            put("layer", layerOf(i))
            put("shown", shown(i.kind, i.delivery))
            if (isHeld(i.delivery)) put("held", i.delivery) else put("held", JsonNull)
            put("mode", i.checkMode)
            put("thr", i.thresholdsVersion)
            put("cand", JsonArray(i.candidates.take(MAX_CAND).map { JsonPrimitive(it) }))
            val l1 = i.l1
            if (l1 == null) put("l1", JsonNull) else put("l1", buildJsonObject {
                put("state", l1.state); put("frames", l1.frames)
                l1.skip?.let { put("skip", it) }
                put("margin", l1.margin?.let { JsonPrimitive(it) } ?: JsonNull)
                put("top", l1.scores.mapNotNull { it.milli }.maxOrNull()?.let { JsonPrimitive(it) } ?: JsonNull)
                put("scores", JsonArray(l1.scores.sortedByDescending { it.milli ?: -1 }.take(withScores).map { s ->
                    buildJsonObject { put("id", s.placeId); put("milli", s.milli?.let { JsonPrimitive(it) } ?: JsonNull); put("ref", s.kind?.name); put("level", s.level.name) }
                }))
            })
            put("l2", buildJsonObject {
                put("answer", i.l2); put("conf", i.l2Conf?.let { JsonPrimitive(it) } ?: JsonNull); put("pick", i.l2Pick)
            })
            put("l3", i.l3)
            put("ref", i.glanceRef)
        }.toString()
        var s = json(MAX_SCORES)
        var n = MAX_SCORES
        while (s.toByteArray().size > MAX_BYTES && n > 0) { n /= 2; s = json(n) }
        return s
    }
}
