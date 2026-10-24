package com.rokid.context.capture.plan

import com.rokid.context.core.model.context.ContextState
import com.rokid.context.core.model.context.graph.WindowRing
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Signal catalogue `sigSchema 1` (expression nodes, F2): only what the glasses already compute (`SecondFeatures`, `ContextGate`).
 * Per-glance and per-keep signals (`boutMs`, `msSinceKeep`, ...) and `ctxConf` are not computed yet, so they are not here; adding one
 * later is a new signal, never a redefinition. `headSpeed` is a catalogue alias of `gyroMax` (Q8), not a new signal.
 */
object SignalCatalog {
    const val SCHEMA = SIG_SCHEMA

    enum class Type { NUM, ENUM }

    /** [index] is the position in `SecondFeatures.toArray()` for the per-second features, else -1. [default] applies when stale. */
    data class Signal(
        val name: String, val type: Type, val unit: String, val index: Int, val default: Any,
        val staleAfterMs: Long?, val enumValues: List<String> = emptyList(),
    )

    private const val STALE_MS = 3_000L

    private val features = listOf(
        "gyroMax" to "rad/s", "gyroMean" to "rad/s", "gyroRms" to "rad/s", "aboveOnFrac" to "0-1",
        "accVar" to "(m/s^2)^2", "stepHz" to "Hz", "rotRad" to "rad",
    )

    /** In `SecondFeatures.toArray()` order. */
    val featureNames: List<String> = features.map { it.first }

    val all: List<Signal> = features.mapIndexed { i, (n, u) -> Signal(n, Type.NUM, u, i, 0.0, STALE_MS) } + listOf(
        Signal("ctx", Type.ENUM, "", -1, ContextState.UNKNOWN.name, null, ContextState.entries.map { it.name }),
        Signal("ctxAgeS", Type.NUM, "s", -1, 0.0, null),
    )

    private val aliases = mapOf("headSpeed" to "gyroMax")
    private val byName = all.associateBy { it.name }

    /** The canonical signal name for [name] (resolving aliases), or null when unknown. */
    fun canonical(name: String): String? = (aliases[name] ?: name).takeIf { it in byName }

    fun byName(name: String): Signal? = canonical(name)?.let { byName[it] }

    /** Index into `SecondFeatures.toArray()`, or null when [name] is not a per-second feature. */
    fun featureIndex(name: String): Int? = byName(name)?.index?.takeIf { it >= 0 }

    /** The catalogue as served to the canvas (node-type route wiring comes with the inspector rows). */
    fun toJson(): JsonObject = buildJsonObject {
        put("sigSchema", SCHEMA)
        put("aliases", JsonObject(aliases.mapValues { JsonPrimitive(it.value) }))
        // 3c E2a: the window ops of catalogue 2 (`win` over a one-second feature); a plan that uses them carries sigSchema 2
        put(
            "windows",
            buildJsonObject {
                put("sigSchema", SIG_SCHEMA_WIN)
                put("aggs", JsonArray(listOf("max", "mean", "frac").map { JsonPrimitive(it) }))
                put("minSeconds", WindowRing.MIN_SECONDS); put("maxSeconds", WindowRing.MAX_SECONDS); put("maxTerms", WindowRing.MAX_TERMS)
                put("signals", JsonArray(featureNames.map { JsonPrimitive(it) }))
                put("fracNeedsThreshold", true)
            },
        )
        put(
            "signals",
            JsonArray(
                all.map { s ->
                    buildJsonObject {
                        put("name", s.name); put("type", s.type.name.lowercase()); put("unit", s.unit)
                        when (val d = s.default) { is Number -> put("default", d); else -> put("default", d.toString()) }
                        s.staleAfterMs?.let { put("staleAfterMs", it) }
                        if (s.enumValues.isNotEmpty()) put("values", JsonArray(s.enumValues.map { JsonPrimitive(it) }))
                    }
                },
            ),
        )
    }
}
