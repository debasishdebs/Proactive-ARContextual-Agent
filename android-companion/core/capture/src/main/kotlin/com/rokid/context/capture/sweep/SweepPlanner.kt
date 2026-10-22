package com.rokid.context.capture.sweep

import com.rokid.context.capture.plan.CapturePlan
import com.rokid.context.capture.plan.PlanConfig
import com.rokid.context.capture.plan.PlanJson
import com.rokid.context.capture.plan.PlanMeta
import com.rokid.context.capture.plan.PlanResolver
import com.rokid.context.capture.plan.PlanState
import com.rokid.context.capture.plan.PlanValidator
import com.rokid.context.capture.replay.ReplayCandidate
import com.rokid.context.glasses.capture.keyframe.GateParams
import com.rokid.context.glasses.capture.motion.GlanceParams
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.util.Random

/** One swept param: a plan path and the values it takes (a `null` capPerMin means "unchanged"). */
class SweepParam(val path: String, val values: List<JsonElement>)

enum class SweepMode { GRID, RANDOM }

/** [samples] and [seed] apply to [SweepMode.RANDOM] only. */
class SweepSpec(val mode: SweepMode, val params: List<SweepParam>, val samples: Int = 0, val seed: Long = 0L)

/** One config of a sweep: [assignments] are the swept values by path, [config] the full plan config they give (already validated). */
class SweepPoint(val index: Int, val configHash: String, val config: PlanConfig, val assignments: Map<String, JsonElement>)

/**
 * Expands a sweep into validated configs (S16, S75; slice 3 section 6.3). A sweep varies the RT params of a [PlanConfig] over replay:
 * `glance.*`, `gate.*` and per editable policy row `capPerMin` and `timerPhotoGapsMs.*` (a timer photo gap is 0 = inherit, or 5 to 300 s; S83, S84).
 * Sensing keys (S84), fusion (not sweepable in 3a, S30) and the locked rows are refused. Every point is checked by [PlanValidator] (the publish rules),
 * so a refused point refuses the whole request before anything runs. At most [MAX_PARAMS] params and [MAX_CONFIGS] configs.
 */
object SweepPlanner {
    const val MAX_PARAMS = 4
    const val MAX_CONFIGS = 200
    const val MAX_VALUES = 200

    private val GLANCE = setOf("onsetRadS", "releaseRadS", "bridgeMs", "minBoutMs", "minBoutRad", "settleMs", "noveltyWindowMs", "noveltyMinRad", "bigTurnRad")
    private val GATE = setOf("bigChange", "hamming", "ringCompare")
    private val GAPS = setOf("settledMs", "quietMs", "walkMs", "stillCapMs")
    private val EDITABLE = PlanState.entries.map { it.name }.toSet()

    /** [min]..[max] by [step], inclusive, without floating-point drift. */
    fun range(min: Double, max: Double, step: Double): List<JsonPrimitive> {
        require(min.isFinite() && max.isFinite() && step.isFinite() && step > 0.0 && max >= min) { "bad range" }
        val lo = BigDecimal(min.toString()); val hi = BigDecimal(max.toString()); val st = BigDecimal(step.toString())
        require(hi.subtract(lo).divide(st, 0, java.math.RoundingMode.FLOOR) < BigDecimal(MAX_VALUES)) { "a range has at most $MAX_VALUES values" }
        val out = ArrayList<JsonPrimitive>()
        var v = lo
        while (v <= hi) {
            out += if (v.scale() <= 0 || v.stripTrailingZeros().scale() <= 0) JsonPrimitive(v.toBigInteger().toLong()) else JsonPrimitive(v.stripTrailingZeros().toDouble())
            v = v.add(st)
        }
        return out
    }

    /** The path's JSON nesting inside `config`, or throws the fixed refusal. */
    private fun segments(path: String): List<String> {
        val seg = path.split('.')
        require(seg.none { it.isEmpty() }) { "$path: not a sweepable param" }
        seg.firstOrNull { PlanValidator.SENSING.matches(it) }?.let { throw IllegalArgumentException("$it: sensing and detection are not plan parameters") }
        if (seg.first() == "phoneFusion" || seg.first() == "fusion") throw IllegalArgumentException("$path: fusion is not sweepable")
        val ok = when (seg[0]) {
            "glance" -> seg.size == 2 && seg[1] in GLANCE
            "gate" -> seg.size == 2 && seg[1] in GATE
            "policy" -> {
                if (seg.size >= 2 && seg[1] !in EDITABLE) {
                    if (seg[1] == "WALKING" || seg[1] == "UNKNOWN") throw IllegalArgumentException("policy row ${seg[1]} is locked")
                    throw IllegalArgumentException("policy row ${seg[1]} is not supported")
                }
                (seg.size == 3 && seg[2] == "capPerMin") || (seg.size == 4 && seg[2] == "timerPhotoGapsMs" && seg[3] in GAPS)
            }
            else -> false
        }
        require(ok) { "$path: not a sweepable param" }
        return seg
    }

    private fun nest(seg: List<String>, v: JsonElement): JsonObject = seg.foldRight(v) { k, acc -> JsonObject(mapOf(k to acc)) } as JsonObject

    private fun merge(a: JsonObject, b: JsonObject): JsonObject = JsonObject(a + b.mapValues { (k, v) -> val x = a[k]; if (x is JsonObject && v is JsonObject) merge(x, v) else v })

    /** Expands [spec] against [base]: validated points, duplicates (same hash) dropped, in order. Throws [IllegalArgumentException] with a fixed text. */
    fun points(base: PlanConfig, spec: SweepSpec): List<SweepPoint> {
        require(spec.params.isNotEmpty()) { "no params" }
        require(spec.params.size <= MAX_PARAMS) { "at most $MAX_PARAMS params" }
        require(spec.params.map { it.path }.distinct().size == spec.params.size) { "duplicate param" }
        val segs = spec.params.map { segments(it.path) }
        spec.params.forEach { require(it.values.isNotEmpty()) { "a param needs at least one value" } }
        val choices: List<List<Int>> = when (spec.mode) {
            SweepMode.GRID -> {
                var n = 1L
                spec.params.forEach { n *= it.values.size; require(n <= MAX_CONFIGS) { "at most $MAX_CONFIGS configs" } }
                var acc: List<List<Int>> = listOf(emptyList())
                spec.params.forEach { p -> acc = acc.flatMap { prefix -> p.values.indices.map { prefix + it } } }
                acc
            }
            SweepMode.RANDOM -> {
                require(spec.samples in 1..MAX_CONFIGS) { "samples must be 1 to $MAX_CONFIGS" }
                val rnd = Random(spec.seed)
                List(spec.samples) { spec.params.map { p -> rnd.nextInt(p.values.size) } }
            }
        }
        val seen = HashSet<String>()
        val out = ArrayList<SweepPoint>()
        for (pick in choices) {
            val assign = LinkedHashMap<String, JsonElement>()
            var partial = JsonObject(emptyMap())
            pick.forEachIndexed { i, vi -> val v = spec.params[i].values[vi]; assign[spec.params[i].path] = v; partial = merge(partial, nest(segs[i], v)) }
            val cfg = PlanResolver.resolve(partial, base).getOrElse { throw IllegalArgumentException("${assign.keys.first()}: not a valid value") }
            val hash = PlanJson.configHash(cfg)
            val problems = PlanValidator.check(PlanJson.encode(CapturePlan(configHash = hash, config = cfg, meta = PlanMeta("sweep", 0)))).first
            require(problems.isEmpty()) { problems.first() }
            if (seen.add(hash)) out += SweepPoint(out.size, hash, cfg, assign)
        }
        return out
    }

    /** The replay candidate of a point: what a LIVE plan of this config would apply (`ActiveConfig.of`). */
    fun candidate(p: SweepPoint): ReplayCandidate {
        val g = p.config.glance
        val gt = p.config.gate
        return ReplayCandidate(
            p.configHash,
            glance = GlanceParams(
                onsetRadS = g.onsetRadS, releaseRadS = g.releaseRadS, bridgeMs = g.bridgeMs, minBoutMs = g.minBoutMs, minBoutRad = g.minBoutRad,
                settleMs = g.settleMs, noveltyWindowMs = g.noveltyWindowMs, noveltyMinRad = g.noveltyMinRad, bigTurnRad = g.bigTurnRad,
            ),
            gate = GateParams(gt.hamming, gt.bigChange, gt.ringCompare == "all"),
            table = ReplayCandidate.policyTable(p.config.policy),
            ctxBound = p.config.ctxBound,
        )
    }

}
