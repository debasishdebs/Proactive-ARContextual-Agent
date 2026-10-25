package com.rokid.context.ai.canvas

import com.rokid.context.core.model.context.WatchSample
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `src.watch_motion` (Source, WATCH lane): the watch motion values the phone's context hub already received, as run items. One item per value, oldest first: `tsMs`, the watch `state`
 * and its `confidence`, the motion `score` 0..1 (see `WatchMotionScore`), and `sensor.watchMotion` (the same score, where `agg.window` reads it). A value with no score (UNKNOWN) is not
 * a measurement and is left out. No value in the window at all gives ONE item that is an error, NO_DATA: absence is never a zero. Pure: no clock, no model, no I/O.
 */
object WatchMotion {
    const val KEY_PREFIX = "watch:"
    /** The key of the single item of an empty window; its node result is the error [NO_DATA]. */
    const val NO_DATA_KEY = KEY_PREFIX + "none"
    const val NO_DATA = "NO_DATA"

    data class Item(val key: String, val json: JsonObject)

    fun keyOf(s: WatchSample) = KEY_PREFIX + s.atMs

    /** The items of [samples] (any order in: time order out; one per instant, the first wins); empty when none has a score. */
    fun items(samples: List<WatchSample>): List<Item> = samples.sortedBy { it.atMs }.distinctBy { it.atMs }.mapNotNull { s ->
        val score = s.score ?: return@mapNotNull null
        Item(keyOf(s), buildJsonObject {
            put("itemType", "watch"); put("tsMs", s.atMs); put("state", s.state.name); put("confidence", s.confidence.toDouble()); put("score", score)
            put("sensor", buildJsonObject { put("watchMotion", score) })
        })
    }

    /** What a token binds: every key with its score, so a value that arrived or changed since the estimate refuses the start. */
    fun binding(items: List<Item>): String =
        if (items.isEmpty()) NO_DATA_KEY else items.joinToString("\n") { it.key + "=" + ((it.json["score"] as JsonPrimitive).content) }
}
