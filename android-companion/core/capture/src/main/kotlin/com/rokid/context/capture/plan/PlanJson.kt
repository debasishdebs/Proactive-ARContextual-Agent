package com.rokid.context.capture.plan

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Canonical encode, strict decode and the 12-hex [configHash] over `config` only (S60). */
object PlanJson {
    fun encode(p: CapturePlan): String = CanonicalJson.encode(toJson(p))

    fun toJson(p: CapturePlan): JsonObject = JsonObject(
        mapOf(
            "planSchema" to JsonPrimitive(p.planSchema),
            "configHash" to JsonPrimitive(p.configHash),
            "config" to configJson(p.config),
            "meta" to JsonObject(
                buildMap {
                    put("name", JsonPrimitive(p.meta.name))
                    put("settingsRev", JsonPrimitive(p.meta.settingsRev))
                    p.meta.expectOpensP95PerMin?.let { m -> put("expectOpensP95PerMin", JsonObject(m.mapValues { JsonPrimitive(it.value) })) }
                },
            ),
        ),
    )

    fun configJson(c: PlanConfig): JsonObject = JsonObject(
        buildMap {
            putAll(baseConfig(c))
            // Only schema-2 plans carry these keys: a schema-1 config keeps its exact bytes and hash (F2).
            if (c.exprs.isNotEmpty()) {
                put("exprs", kotlinx.serialization.json.JsonArray(c.exprs))
                put("sigSchema", JsonPrimitive(c.sigSchema))
            }
        },
    )

    private fun baseConfig(c: PlanConfig): Map<String, kotlinx.serialization.json.JsonElement> =
        mapOf(
            "glance" to c.glance.let { g ->
                JsonObject(
                    mapOf(
                        "onsetRadS" to JsonPrimitive(g.onsetRadS), "releaseRadS" to JsonPrimitive(g.releaseRadS),
                        "bridgeMs" to JsonPrimitive(g.bridgeMs), "minBoutMs" to JsonPrimitive(g.minBoutMs),
                        "minBoutRad" to JsonPrimitive(g.minBoutRad), "settleMs" to JsonPrimitive(g.settleMs),
                        "noveltyWindowMs" to JsonPrimitive(g.noveltyWindowMs), "noveltyMinRad" to JsonPrimitive(g.noveltyMinRad),
                        "bigTurnRad" to JsonPrimitive(g.bigTurnRad),
                    ),
                )
            },
            "policy" to JsonObject(
                c.policy.entries.associate { (s, r) ->
                    s.name to JsonObject(
                        mapOf(
                            "capPerMin" to (r.capPerMin?.let { JsonPrimitive(it) } ?: JsonNull),
                            "timerPhotoGapsMs" to JsonObject(
                                mapOf(
                                    "settledMs" to JsonPrimitive(r.timerPhotoGapsMs.settledMs),
                                    "quietMs" to JsonPrimitive(r.timerPhotoGapsMs.quietMs),
                                    "walkMs" to JsonPrimitive(r.timerPhotoGapsMs.walkMs),
                                    "stillCapMs" to JsonPrimitive(r.timerPhotoGapsMs.stillCapMs),
                                ),
                            ),
                            "forceSettle" to JsonPrimitive(r.forceSettle),
                            "nudges" to JsonPrimitive(r.nudges),
                        ),
                    )
                },
            ),
            "gate" to JsonObject(
                mapOf(
                    "bigChange" to JsonPrimitive(c.gate.bigChange), "hamming" to JsonPrimitive(c.gate.hamming),
                    "ringCompare" to JsonPrimitive(c.gate.ringCompare),
                ),
            ),
            "ctxBound" to JsonPrimitive(c.ctxBound),
            "phoneFusion" to JsonObject(c.phoneFusion.mapValues { JsonPrimitive(it.value) }),
        )

    /** 12 hex of SHA-256 over the canonical `config` JSON. The meta block never changes it. */
    fun configHash(c: PlanConfig): String =
        MessageDigest.getInstance("SHA-256").digest(CanonicalJson.encode(configJson(c)).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(12)

    /** Strict: unknown keys, missing required keys, wrong types, unknown policy rows and non-finite numbers all fail. */
    fun decodeStrict(s: String): Result<CapturePlan> = runCatching {
        require(!hasDuplicateKeys(s)) { "duplicate key" }
        decodeElement(Json.parseToJsonElement(s))
    }

    private val JSON_NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

    /** True when [content] is a number token in the JSON grammar (no `0.6f`, `1d`, hex floats, `+1`, `.5`, `01`). */
    fun isJsonNumber(content: String): Boolean = JSON_NUMBER.matches(content)

    /**
     * True when any object in [s] repeats a key (compared after unescaping, so `"a"` and `"\u0061"` clash). kotlinx keeps the last
     * duplicate silently; the plan is closed and canonical, so a repeat is refused. Iterative: no recursion on nesting.
     */
    fun hasDuplicateKeys(s: String): Boolean {
        val keys = ArrayList<HashSet<String>?>()   // null = an array
        var expectKey = false
        var i = 0
        while (i < s.length) {
            when (s[i]) {
                '{' -> { keys.add(HashSet()); expectKey = true }
                '[' -> { keys.add(null); expectKey = false }
                '}', ']' -> { if (keys.isNotEmpty()) keys.removeAt(keys.size - 1); expectKey = false }
                ',' -> expectKey = keys.lastOrNull() != null
                '"' -> {
                    val sb = StringBuilder()
                    i++
                    while (i < s.length && s[i] != '"') {
                        if (s[i] == '\\' && i + 1 < s.length) {
                            i++
                            when (s[i]) {
                                'u' -> {
                                    val cp = s.substring(i + 1, minOf(i + 5, s.length)).toIntOrNull(16)
                                    if (cp != null && i + 5 <= s.length) { sb.append(cp.toChar()); i += 4 } else sb.append('u')
                                }
                                'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                                else -> sb.append(s[i])
                            }
                        } else sb.append(s[i])
                        i++
                    }
                    if (expectKey) {
                        if (!keys.last()!!.add(sb.toString())) return true
                        expectKey = false
                    }
                }
            }
            i++
        }
        return false
    }

    fun decodeElement(e: JsonElement): CapturePlan {
        val o = Obj(e, "plan")
        val schema = o.int("planSchema")
        val hash = o.str("configHash")
        val config = decodeConfig(o.take("config"), "config")
        val meta = o.obj("meta").let { m ->
            val name = m.str("name")
            val rev = m.long("settingsRev")
            val expect = m.takeOpt("expectOpensP95PerMin")?.let { x ->
                Obj(x, "meta.expectOpensP95PerMin").also { it.markOpen() }.entries().mapValues { num(it.value, "meta.expectOpensP95PerMin.${it.key}") }
            }
            m.done()
            PlanMeta(name, rev, expect)
        }
        o.done()
        return CapturePlan(schema, hash, config, meta)
    }

    /** Decodes a `config` object (used by the resolver too). */
    fun decodeConfig(e: JsonElement, path: String = "config"): PlanConfig {
        val c = Obj(e, path)
        val g = c.obj("glance")
        val glance = GlanceBlock(
            g.float("onsetRadS"), g.float("releaseRadS"), g.long("bridgeMs"), g.long("minBoutMs"), g.float("minBoutRad"),
            g.long("settleMs"), g.long("noveltyWindowMs"), g.float("noveltyMinRad"), g.float("bigTurnRad"),
        ).also { g.done() }
        val pol = c.obj("policy")
        val policy = LinkedHashMap<PlanState, PlanRow>()
        for ((k, v) in pol.entries()) {
            val st = PlanState.entries.firstOrNull { it.name == k } ?: throw IllegalArgumentException("unknown policy row $k")
            val r = Obj(v, "policy.$k")
            val cap = r.takeOpt("capPerMin")?.let { if (it is JsonNull) null else intOf(it, "policy.$k.capPerMin") }
            val gp = r.obj("timerPhotoGapsMs")
            val gaps = TimerPhotoGaps(gp.long("settledMs"), gp.long("quietMs"), gp.long("walkMs"), gp.long("stillCapMs")).also { gp.done() }
            policy[st] = PlanRow(cap, gaps, r.bool("forceSettle"), r.bool("nudges")).also { r.done() }
        }
        pol.markOpen()
        val gt = c.obj("gate")
        val gate = GateBlock(gt.int("bigChange"), gt.int("hamming"), gt.str("ringCompare")).also { gt.done() }
        val ctxBound = c.bool("ctxBound")
        val fusion = c.obj("phoneFusion").let { f -> f.markOpen(); f.entries().mapValues { num(it.value, "phoneFusion.${it.key}") } }
        val exprs = c.takeOpt("exprs")?.let { x ->
            require(x is kotlinx.serialization.json.JsonArray) { "$path.exprs is not an array" }
            x.mapIndexed { i, r -> r as? JsonObject ?: throw IllegalArgumentException("$path.exprs[$i] is not an object") }
        } ?: emptyList()
        val sig = c.takeOpt("sigSchema")?.let { intOf(it, "$path.sigSchema") } ?: 0
        require(exprs.isEmpty() == (sig == 0)) { "$path.sigSchema must be present exactly when exprs is" }
        c.done()
        return PlanConfig(glance, policy, gate, ctxBound, fusion, exprs, sig)
    }

    internal fun num(e: JsonElement, path: String): Double {
        require(e is JsonPrimitive && e !is JsonNull && !e.isString) { "$path is not a number" }
        require(isJsonNumber(e.content)) { "$path is not a finite number" }
        val d = e.content.toDoubleOrNull()
        require(d != null && d.isFinite()) { "$path is not a finite number" }
        return d
    }

    private fun intOf(e: JsonElement, path: String): Int = longOf(e, path).also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) { "$path out of range" } }.toInt()

    private fun longOf(e: JsonElement, path: String): Long {
        num(e, path)
        return (e as JsonPrimitive).content.toLongOrNull() ?: throw IllegalArgumentException("$path is not an integer")
    }

    /** Reads an object key by key and fails on leftovers. */
    internal class Obj(e: JsonElement, private val path: String) {
        private val o: JsonObject = e as? JsonObject ?: throw IllegalArgumentException("$path is not an object")
        private val used = HashSet<String>()
        private var open = false

        fun markOpen() { open = true; used.addAll(o.keys) }
        fun entries(): Map<String, JsonElement> = o
        fun take(k: String): JsonElement { used += k; return o[k] ?: throw IllegalArgumentException("$path.$k is missing") }
        fun takeOpt(k: String): JsonElement? { used += k; return o[k] }
        fun obj(k: String) = Obj(take(k), "$path.$k")
        fun str(k: String): String = take(k).let { require(it is JsonPrimitive && it.isString) { "$path.$k is not a string" }; (it as JsonPrimitive).content }
        fun bool(k: String): Boolean = take(k).let { require(it is JsonPrimitive && !it.isString && (it.content == "true" || it.content == "false")) { "$path.$k is not a boolean" }; it.toString() == "true" }
        fun int(k: String): Int = intOf(take(k), "$path.$k")
        fun long(k: String): Long = longOf(take(k), "$path.$k")
        fun float(k: String): Float {
            val e = take(k)
            num(e, "$path.$k")
            return (e as JsonPrimitive).content.toFloat().also { require(it.isFinite()) { "$path.$k is not a finite number" } }
        }
        fun done() {
            if (open) return
            val extra = o.keys - used
            require(extra.isEmpty()) { "unknown key $path.${extra.sorted().first()}" }
        }
    }
}
