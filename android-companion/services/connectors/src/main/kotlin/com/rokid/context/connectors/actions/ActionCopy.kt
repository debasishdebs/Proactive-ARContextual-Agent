package com.rokid.context.connectors.actions

import com.rokid.context.connectors.actions.executors.FailCode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Fixed user-facing copy for actions (never model or exception text beyond the proposal's own params). */
object ActionCopy {
    const val MAX_LINE = 60
    private const val ELLIPSIS = "\u2026"

    /** One line for a card or notification: "Call mom · Tue 18:00", "milk, eggs +2". */
    fun summary(p: ActionParams, zone: ZoneId = ZoneId.systemDefault()): String = when (p) {
        is ActionParams.Reminder -> "${p.title} \u00b7 ${whenText(p.dueAtMs, zone)}"
        is ActionParams.Calendar -> "${p.title} \u00b7 ${whenText(p.startAtMs, zone)}"
        is ActionParams.Shopping -> shoppingDetail(p)
    }

    /** The first two names, then "+N" for the rest. */
    fun shoppingDetail(p: ActionParams.Shopping): String {
        val names = p.items.map { it.name.trim() }
        val head = names.take(2).joinToString(", ")
        return if (names.size > 2) "$head +${names.size - 2}" else head
    }

    fun confirmTitle(t: ActionType): String = when (t) {
        ActionType.REMINDER -> "Remind me?"
        ActionType.SHOPPING_ADD -> "Add to shopping list?"
        ActionType.CALENDAR_EVENT -> "Add to calendar?"
    }

    fun failureTitle(code: String?): String = when (FailCode.entries.firstOrNull { it.name == code }) {
        FailCode.NO_PERMISSION -> "Calendar permission needed"
        FailCode.NO_CALENDAR -> "No calendar on this phone"
        FailCode.NOTIFICATIONS_OFF -> "Notifications are off"
        else -> "Couldn't add that \u2014 try again"
    }

    /** An executed action that can't reach the user as is (ExecResult.Done.warning); null = nothing to say. */
    fun warningTitle(code: String?): String? = when (FailCode.entries.firstOrNull { it.name == code }) {
        FailCode.NOTIFICATIONS_OFF -> "Added, but notifications are off"
        else -> null
    }

    /** Ellipsised to [max] chars (the last char becomes "…"). */
    fun clip(s: String, max: Int = MAX_LINE): String = if (s.length <= max) s else s.take(max - 1).trimEnd() + ELLIPSIS

    fun whenText(ms: Long, zone: ZoneId): String = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH).format(Instant.ofEpochMilli(ms).atZone(zone))
}
