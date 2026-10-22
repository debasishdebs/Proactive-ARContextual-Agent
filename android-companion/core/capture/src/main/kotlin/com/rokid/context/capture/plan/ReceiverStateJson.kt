package com.rokid.context.capture.plan

import com.rokid.context.core.model.PlanMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The persisted form of [ReceiverState] (one string in the `capture_plan` prefs). Hand-written so a field added later decodes with its
 * default from an older text; anything unreadable decodes to null, which the controller treats as "no plan" (the settings path).
 */
object ReceiverStateJson {
    fun encode(s: ReceiverState): String = Json.encodeToString(JsonObject.serializer(), toJson(s))

    fun decode(text: String?): ReceiverState? {
        if (text.isNullOrBlank()) return null
        return runCatching { fromJson(Json.parseToJsonElement(text) as JsonObject) }.getOrNull()
    }

    private fun toJson(s: ReceiverState): JsonObject = buildJsonObject {
        put("appliedGen", s.appliedGen); put("phoneInstall", s.phoneInstall); put("mode", s.mode.name)
        put("planJson", s.planJson); put("configHash", s.configHash)
        put("trialStartElapsed", s.trialStartElapsed); put("trialMaxMs", s.trialMaxMs)
        put("bootId", s.bootId); put("effective", s.effective); put("publishId", s.publishId)
        s.previous?.let { put("previous", toJson(it)) }
        put("terminal", JsonObject(s.terminal.mapKeys { it.key.toString() }.mapValues { JsonPrimitive(it.value) }))
        put("terminalMeta", JsonObject(s.terminalMeta.mapKeys { it.key.toString() }.mapValues { (_, m) ->
            buildJsonObject { put("mode", m.mode.name); put("publishId", m.publishId) }
        }))
    }

    private fun str(o: JsonObject, k: String): String? = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun lng(o: JsonObject, k: String): Long? = (o[k] as? JsonPrimitive)?.longOrNull

    private fun fromJson(o: JsonObject): ReceiverState {
        val terminal = (o["terminal"] as? JsonObject).orEmpty().entries.associate { (k, v) -> k.toLong() to (v as JsonPrimitive).content }
        val meta = (o["terminalMeta"] as? JsonObject).orEmpty().entries.associate { (k, v) ->
            val m = v as JsonObject
            k.toLong() to TerminalMeta(PlanMode.valueOf(str(m, "mode")!!), str(m, "publishId"))
        }
        return ReceiverState(
            appliedGen = lng(o, "appliedGen")!!, phoneInstall = str(o, "phoneInstall")!!, mode = PlanMode.valueOf(str(o, "mode")!!),
            planJson = str(o, "planJson"), configHash = str(o, "configHash"),
            trialStartElapsed = lng(o, "trialStartElapsed"), trialMaxMs = lng(o, "trialMaxMs"), bootId = str(o, "bootId")!!,
            previous = (o["previous"] as? JsonObject)?.let { fromJson(it) }, terminal = terminal,
            effective = (o["effective"] as? JsonPrimitive)?.booleanOrNull ?: false,
            publishId = str(o, "publishId"), terminalMeta = meta,
        )
    }
}

/**
 * The boot marker for [PlanReceiver]: `Settings.Global.BOOT_COUNT` (persisted by the OS, +1 per boot), so a reboot is visible even
 * when `elapsedRealtime` has already passed the old trial start. With no readable count the boot instant (wall minus elapsed, minute
 * resolution) stands in; a drifting clock can only flip it spuriously, which ends a TRIAL early (the safe side).
 */
object BootId {
    fun of(bootCount: Int, wallMs: Long, elapsedMs: Long): String =
        if (bootCount >= 0) "bc:$bootCount" else "bt:${(wallMs - elapsedMs) / 60_000L}"
}
