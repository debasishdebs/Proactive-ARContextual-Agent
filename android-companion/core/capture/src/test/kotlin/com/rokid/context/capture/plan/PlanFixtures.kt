package com.rokid.context.capture.plan

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

object PlanFixtures {
    fun plan(config: PlanConfig = PlanResolver.identity(), name: String = "t", meta: PlanMeta = PlanMeta(name, 1)) =
        CapturePlan(PLAN_SCHEMA, PlanJson.configHash(config), config, meta)

    fun text(p: CapturePlan = plan()) = PlanJson.encode(p)

    /** A plan whose canonical text is exactly [target] UTF-8 bytes: ASCII padding, then a 2-byte char, so chars < bytes. */
    fun planWithBytes(target: Int): CapturePlan {
        val bytes0 = PlanJson.encode(plan(name = "é")).toByteArray(Charsets.UTF_8).size
        return plan(name = "a".repeat(target - bytes0) + "é")
    }

    fun raw(p: CapturePlan = plan()): JsonObject = Json.parseToJsonElement(text(p)) as JsonObject

    fun row(c: PlanConfig, s: PlanState, f: (PlanRow) -> PlanRow) = c.copy(policy = c.policy + (s to f(c.policy.getValue(s))))

    /** Replace one path in the raw JSON, e.g. set("config.policy.REST.capPerMin", "5"). */
    fun mutate(p: CapturePlan = plan(), f: (MutableMap<String, Any?>) -> Unit): JsonObject {
        @Suppress("UNCHECKED_CAST")
        fun toMap(e: JsonElement): Any? = when (e) {
            is JsonObject -> e.mapValues { toMap(it.value) }.toMutableMap<String, Any?>()
            else -> e
        }
        @Suppress("UNCHECKED_CAST")
        val m = toMap(raw(p)) as MutableMap<String, Any?>
        f(m)
        fun back(a: Any?): JsonElement = when (a) {
            is Map<*, *> -> JsonObject(a.entries.associate { it.key as String to back(it.value) })
            else -> a as JsonElement
        }
        return back(m) as JsonObject
    }
}
