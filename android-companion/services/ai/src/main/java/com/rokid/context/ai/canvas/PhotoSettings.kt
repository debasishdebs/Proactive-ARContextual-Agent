package com.rokid.context.ai.canvas

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** One stored frame as the Photo filter sees it. [marked]: a mark names this frame or was made within [PhotoScope.MARK_LINK_MS] of its capture. */
data class FrameFact(val id: String, val ts: Long, val source: String, val marked: Boolean)

/**
 * The settings of an `input.photos` node. Every default is today's behaviour, so an absent param (every pipeline saved before) selects exactly what it
 * selected before: glasses captures, any age, marked or not, up to the runner's own cap of 50. 0 hours means any time.
 */
data class PhotoSettings(val source: String = SOURCE_GLASSES, val sinceHours: Int = 0, val markedOnly: Boolean = false, val limit: Int = MAX_LIMIT) {
    val isDefault: Boolean get() = this == PhotoSettings()

    companion object {
        const val SOURCE_GLASSES = "glasses"
        const val SOURCE_ALL = "all"
        val SOURCES = listOf(SOURCE_GLASSES, SOURCE_ALL)
        const val MAX_LIMIT = 50
        const val MAX_HOURS = 720

        val schema = ParamSchema(listOf(
            ParamSpec("source", ParamKind.ENUM, label = "Photos from", options = SOURCES, default = JsonPrimitive(SOURCE_GLASSES)),
            ParamSpec("sinceHours", ParamKind.INT, label = "Only the last hours (0 = any time)", min = 0.0, max = MAX_HOURS.toDouble(), step = 1.0, default = JsonPrimitive(0)),
            ParamSpec("markedOnly", ParamKind.BOOL, label = "Only marked moments", default = JsonPrimitive(false)),
            ParamSpec("limit", ParamKind.INT, label = "At most (newest first)", min = 1.0, max = MAX_LIMIT.toDouble(), step = 1.0, default = JsonPrimitive(MAX_LIMIT)),
        ), strict = false) // strict stays off: a saved pipeline may carry other keys, and the type had no schema before

        /** The params of a node; an absent or invalid one falls back to its default (the validator reports invalid ones). */
        fun of(params: JsonObject): PhotoSettings {
            fun int(k: String, range: IntRange) = (params[k] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it in range }
            val src = (params["source"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it in SOURCES }
            val marked = (params["markedOnly"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.let { it == "true" }
            return PhotoSettings(src ?: SOURCE_GLASSES, int("sinceHours", 0..MAX_HOURS) ?: 0, marked ?: false, int("limit", 1..MAX_LIMIT) ?: MAX_LIMIT)
        }
    }
}

/** Narrows the photos a run was started over by the Photo node's settings. Pure: the frame facts come from the caller, only when [PhotoSettings.isDefault] is false. */
object PhotoScope {
    /** A mark within this long of a capture belongs to it (the Scenes forget radius, 10 s). */
    const val MARK_LINK_MS = 10_000L
    private const val HOUR_MS = 3_600_000L

    /** The settings of a pipeline's Photo node; the defaults when it has none. */
    fun settingsOf(p: Pipeline): PhotoSettings = p.nodes.firstOrNull { it.type == NodeTypes.INPUT_PHOTOS }?.let { PhotoSettings.of(it.params) } ?: PhotoSettings()

    /** [ids] in their order, kept when the frame passes every setting; with the defaults, [ids] untouched. A frame with no fact is dropped (it is gone). */
    fun apply(s: PhotoSettings, ids: List<String>, facts: Map<String, FrameFact>, now: Long): List<String> {
        if (s.isDefault) return ids
        val fit = ids.filter { id ->
            val f = facts[id] ?: return@filter false
            (s.source == PhotoSettings.SOURCE_ALL || f.source.startsWith("rokid", ignoreCase = true)) &&
                (s.sinceHours == 0 || f.ts >= now - s.sinceHours * HOUR_MS) && (!s.markedOnly || f.marked)
        }
        if (fit.size <= s.limit) return fit
        val newest = fit.sortedByDescending { facts.getValue(it).ts }.take(s.limit).toSet()
        return fit.filter { it in newest }
    }
}
