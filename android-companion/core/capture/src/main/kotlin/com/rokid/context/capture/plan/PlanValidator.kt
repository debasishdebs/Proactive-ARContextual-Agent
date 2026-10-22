package com.rokid.context.capture.plan

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Refuses plans that break the non-removable invariants or bounds (S3, S32, S83, S84). Texts are exact; the en dash is U+2013. */
object PlanValidator {
    private const val DASH = "\u2013"
    internal val SENSING = Regex("^(sampleRate.*|sensor.*|imu.*Hz|tick.*|fusionCadence.*|evaluate.*|detector.*|classifier.*)$", RegexOption.IGNORE_CASE)
    private val EDITABLE = PlanState.entries.map { it.name }.toSet()

    /** [raw] is the parsed plan JSON, [p] its strict decode (null when that failed), [bytes] the UTF-8 length of the text. */
    fun problems(raw: JsonObject, p: CapturePlan?, bytes: Int): List<String> {
        val out = ArrayList<String>()
        // The walkers below recurse: refuse deep nesting first (a real plan is about 6 levels), and never let one throw.
        if (tooDeep(raw, 0)) return listOf("plan is nested too deep (max $MAX_DEPTH)")
        return runCatching { walk(raw, p, bytes, out) }.getOrElse { listOf("plan could not be checked") }
    }

    private const val MAX_DEPTH = 32

    private fun tooDeep(e: JsonElement, depth: Int): Boolean = when {
        depth > MAX_DEPTH -> true
        e is JsonObject -> e.values.any { tooDeep(it, depth + 1) }
        e is JsonArray -> e.any { tooDeep(it, depth + 1) }
        else -> false
    }

    private fun walk(raw: JsonObject, p: CapturePlan?, bytes: Int, out: MutableList<String>): List<String> {
        if (bytes > PLAN_MAX_BYTES) out += "plan is $bytes bytes (max $PLAN_MAX_BYTES)"
        val schema = (raw["planSchema"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()
        if (schema != PLAN_SCHEMA.toLong()) out += "plan schema ${schema ?: (raw["planSchema"] as? JsonPrimitive)?.content ?: "missing"} is not supported"
        finite(raw, "", out)
        sensing(raw, out)
        rawRules(raw, out)
        if (p != null) bounds(p, out)
        return out.distinct()
    }

    /** Convenience for text: size, parse, strict decode, then [problems]. Returns the decoded plan when it decoded. */
    fun check(text: String): Pair<List<String>, CapturePlan?> {
        val bytes = text.toByteArray(Charsets.UTF_8).size
        val raw = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: return listOf("plan is not a JSON object") to null
        val plan = PlanJson.decodeStrict(text).getOrNull()
        return problems(raw, plan, bytes) to plan
    }

    private fun finite(e: JsonElement, path: String, out: MutableList<String>) {
        when (e) {
            is JsonNull -> {}
            is JsonPrimitive -> if (!e.isString && e.content != "true" && e.content != "false") {
                val d = e.content.takeIf { PlanJson.isJsonNumber(it) }?.toDoubleOrNull()
                if (d == null || !d.isFinite()) out += "${path.ifEmpty { "plan" }} is not a finite number"
            }
            is JsonArray -> e.forEachIndexed { i, x -> finite(x, "$path[$i]", out) }
            is JsonObject -> e.forEach { (k, v) -> finite(v, if (path.isEmpty()) k else "$path.$k", out) }
        }
    }

    /** S84: any key under (or named like) a sensing or detection block. */
    private fun sensing(e: JsonElement, out: MutableList<String>) {
        if (e is JsonObject) {
            for ((k, v) in e) {
                if (SENSING.matches(k)) {
                    if (v is JsonObject) v.keys.forEach { out += "$it: sensing and detection are not plan parameters" }
                    else out += "$k: sensing and detection are not plan parameters"
                }
                sensing(v, out)
            }
        } else if (e is JsonArray) e.forEach { sensing(it, out) }
    }

    private fun rawRules(raw: JsonObject, out: MutableList<String>) {
        val config = raw["config"] as? JsonObject ?: return
        val policy = config["policy"] as? JsonObject
        if (policy != null) {
            for ((k, v) in policy) {
                if (k !in EDITABLE) out += if (k == "WALKING" || k == "UNKNOWN") "policy row $k is locked" else "policy row $k is not supported"
                if (v is JsonObject && "pause" in v) out += "pause is not allowed"
            }
        }
        val fusion = config["phoneFusion"] as? JsonObject
        fusion?.keys?.filter { it !in PlanBounds.FUSION_DEFAULTS }?.sorted()?.forEach { out += "fusion.$it is not tunable" }
        if (wakeMotion(raw)) out += "MOTION wake is settings-only"
    }

    private fun wakeMotion(e: JsonElement): Boolean = when (e) {
        is JsonObject -> e.any { (k, v) ->
            (k.equals("cameraWake", true) || k.equals("wake", true)) && (v as? JsonPrimitive)?.content.equals("MOTION", true) || wakeMotion(v)
        }
        is JsonArray -> e.any { wakeMotion(it) }
        else -> false
    }

    private fun bounds(p: CapturePlan, out: MutableList<String>) {
        val g = p.config.glance
        fun f(name: String, v: Float, r: ClosedFloatingPointRange<Float>) {
            if (!(v >= r.start && v <= r.endInclusive)) out += "glance.$name out of bounds ${PlanBounds.fmt(r.start)}$DASH${PlanBounds.fmt(r.endInclusive)}"
        }
        fun l(name: String, v: Long, r: LongRange) {
            if (v !in r) out += "glance.$name out of bounds ${r.first}$DASH${r.last}"
        }
        f("onsetRadS", g.onsetRadS, PlanBounds.ONSET)
        if (!(g.releaseRadS >= PlanBounds.RELEASE_LO && g.releaseRadS <= g.onsetRadS)) {
            out += "glance.releaseRadS out of bounds ${PlanBounds.fmt(PlanBounds.RELEASE_LO)}$DASH${PlanBounds.fmt(g.onsetRadS)}"
        }
        l("bridgeMs", g.bridgeMs, PlanBounds.BRIDGE_MS)
        l("minBoutMs", g.minBoutMs, PlanBounds.MIN_BOUT_MS)
        f("minBoutRad", g.minBoutRad, PlanBounds.MIN_BOUT_RAD)
        l("settleMs", g.settleMs, PlanBounds.SETTLE_MS)
        l("noveltyWindowMs", g.noveltyWindowMs, PlanBounds.NOVELTY_WINDOW_MS)
        f("noveltyMinRad", g.noveltyMinRad, PlanBounds.NOVELTY_MIN_RAD)
        f("bigTurnRad", g.bigTurnRad, PlanBounds.BIG_TURN_RAD)
        val gt = p.config.gate
        if (gt.bigChange !in PlanBounds.BIG_CHANGE) out += "gate.bigChange out of bounds ${PlanBounds.BIG_CHANGE.first}$DASH${PlanBounds.BIG_CHANGE.last}"
        if (gt.hamming !in PlanBounds.HAMMING) out += "gate.hamming out of bounds ${PlanBounds.HAMMING.first}$DASH${PlanBounds.HAMMING.last}"
        if (gt.ringCompare != "last" && gt.ringCompare != "all") out += "gate.ringCompare out of bounds last|all"
        for ((s, r) in p.config.policy) {
            if (r.capPerMin != null && r.capPerMin !in PlanBounds.CAP_PER_MIN) out += "policy row $s: capPerMin must be 1${DASH}4 or unchanged"
            if (r.timerPhotoGapsMs.all().any { it != 0L && (it < PlanBounds.GAP_MIN_MS || it > PlanBounds.GAP_MAX_MS) }) {
                out += "policy row $s: timer photo gaps must be 0 (inherit) or 5${DASH}300 s"
            }
            if (s == PlanState.VEHICLE_DRIVING && r.nudges) out += "VEHICLE_DRIVING nudges are always off"
        }
        for ((k, v) in p.config.phoneFusion) {
            val r = PlanBounds.fusionRange(k) ?: continue   // untunable keys are reported from the raw JSON
            if (!v.isFinite()) out += "fusion.$k is not a finite number"
            else if (v < r.start || v > r.endInclusive) out += "fusion.$k out of bounds ${PlanBounds.fmt(r.start)}$DASH${PlanBounds.fmt(r.endInclusive)}"
        }
    }
}
