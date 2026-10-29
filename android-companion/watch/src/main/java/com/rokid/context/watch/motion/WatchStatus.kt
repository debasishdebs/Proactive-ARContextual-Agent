package com.rokid.context.watch.motion

import com.rokid.context.core.model.context.WatchState

enum class LinkStatus { OFF, PERMISSION_NEEDED, SENDING, PHONE_NOT_REACHABLE, WAITING }

/** Pure UI reducer: what the status chip says, from the persisted memory (the UI computes nothing else). */
object WatchStatus {
    fun link(enabled: Boolean, permissionGranted: Boolean, m: MotionMemory): LinkStatus = when {
        !enabled -> LinkStatus.OFF
        !permissionGranted -> LinkStatus.PERMISSION_NEEDED
        m.unsent -> LinkStatus.PHONE_NOT_REACHABLE
        m.reported == WatchState.UNKNOWN && m.lastSentMs <= 0L -> LinkStatus.WAITING
        else -> LinkStatus.SENDING
    }

    fun stateLabel(s: WatchState): String = when (s) {
        WatchState.STILL -> "Still"
        WatchState.WALKING -> "Walking"
        WatchState.RUNNING -> "Running"
        WatchState.CYCLING -> "Cycling"
        WatchState.IN_VEHICLE -> "In vehicle"
        WatchState.EXERCISE -> "Exercise"
        WatchState.ASLEEP -> "Asleep"
        WatchState.UNKNOWN -> "Unknown"
    }

    /** "Last sent: WALKING 14:05" after a successful send, or null before the first one. [timeText] formats the send time as HH:mm. */
    fun lastSent(m: MotionMemory, timeText: (Long) -> String): String? =
        if (m.lastSentMs <= 0L) null else "Last sent: ${m.lastSentState.name} ${timeText(m.lastSentMs)}"

    /** "sent 2 min ago", or "not sent yet" when [lastSentMs] is 0. */
    fun sentAgo(lastSentMs: Long, nowMs: Long): String {
        if (lastSentMs <= 0L) return "not sent yet"
        val min = ((nowMs - lastSentMs).coerceAtLeast(0L) / 60_000L)
        return if (min < 1) "sent just now" else "sent $min min ago"
    }
}
