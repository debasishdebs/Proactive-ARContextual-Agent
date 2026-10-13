package com.rokid.context.connectors.actions

import com.rokid.context.ai.contract.ProposedAction
import com.rokid.context.ai.contract.ShoppingLine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * A proposal's normalised params (`proposal.paramsJson`, canonical key order). Local times from the model are resolved to
 * instants in the phone's zone at receipt: a DST-gap time shifts forward (java.time's gap rule), an overlap takes the
 * earlier offset. A calendar end that is not after the start becomes start + 30 min; shopping names dedupe per proposal.
 */
sealed interface ActionParams {
    val type: ActionType

    data class Reminder(val title: String, val dueAtMs: Long, val note: String?) : ActionParams { override val type get() = ActionType.REMINDER }
    data class Shopping(val items: List<ShoppingLine>) : ActionParams { override val type get() = ActionType.SHOPPING_ADD }
    data class Calendar(val title: String, val startAtMs: Long, val endAtMs: Long, val location: String?) : ActionParams {
        override val type get() = ActionType.CALENDAR_EVENT
    }

    /** The instant that must still be in the future at receipt, if any. */
    val firesAtMs: Long? get() = when (this) { is Reminder -> dueAtMs; is Calendar -> startAtMs; is Shopping -> null }

    companion object {
        const val DEFAULT_EVENT_MS = 30 * 60_000L

        fun from(a: ProposedAction, zone: ZoneId): ActionParams = when (a) {
            is ProposedAction.Reminder -> Reminder(a.title, instant(a.dueAt, zone), a.note)
            is ProposedAction.ShoppingAdd -> Shopping(a.items.distinctBy { it.name.trim().lowercase() })
            is ProposedAction.CalendarEvent -> {
                val start = instant(a.startAt, zone)
                val end = instant(a.endAt, zone).takeIf { it > start } ?: (start + DEFAULT_EVENT_MS)
                Calendar(a.title, start, end, a.location)
            }
        }

        fun encode(p: ActionParams): String = when (p) {
            is Reminder -> buildJsonObject { put("title", p.title); put("dueAtMs", p.dueAtMs); put("note", p.note) }
            is Shopping -> buildJsonObject {
                put("items", buildJsonArray {
                    p.items.forEach { add(buildJsonObject { put("name", it.name); put("qty", it.qty); put("unit", it.unit) }) }
                })
            }
            is Calendar -> buildJsonObject {
                put("title", p.title); put("startAtMs", p.startAtMs); put("endAtMs", p.endAtMs); put("location", p.location)
            }
        }.toString()

        /** Null when [json] is not a valid encoding for [type] (a corrupted row is never executed). */
        fun decode(type: ActionType, json: String): ActionParams? = runCatching {
            val o = Json.parseToJsonElement(json).jsonObject
            when (type) {
                ActionType.REMINDER -> Reminder(o.str("title")!!, o.getValue("dueAtMs").jsonPrimitive.long, o.str("note"))
                ActionType.SHOPPING_ADD -> Shopping(o.getValue("items").jsonArray.map {
                    val i = it.jsonObject
                    ShoppingLine(i.str("name")!!, (i["qty"] as? JsonPrimitive)?.doubleOrNull, i.str("unit"))
                })
                ActionType.CALENDAR_EVENT -> Calendar(
                    o.str("title")!!, o.getValue("startAtMs").jsonPrimitive.long, o.getValue("endAtMs").jsonPrimitive.long, o.str("location"),
                )
            }
        }.getOrNull()

        fun typeOf(name: String): ActionType? = ActionType.entries.firstOrNull { it.name == name }

        private fun instant(t: LocalDateTime, zone: ZoneId): Long = t.atZone(zone).toInstant().toEpochMilli()

        private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content
    }
}
