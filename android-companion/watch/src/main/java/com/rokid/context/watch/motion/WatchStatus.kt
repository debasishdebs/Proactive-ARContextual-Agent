package com.rokid.context.watch.motion

import com.rokid.context.core.model.context.WatchState

enum class LinkStatus { OFF, PERMISSION_NEEDED, SENDING, PHONE_NOT_REACHABLE, WAITING }

/** Pure UI reducer: what the status chip says, from the persisted memory (the UI computes nothing else). */
object WatchStatus {
    fun link(enabled: Boolean, permissionGranted: Boolean, m: MotionMemory): LinkStatus = when {
        !enabled -> LinkStatus.OFF
        !permissionGranted -> LinkStatus.PERMISSION_NEEDED
        m.reported == WatchState.UNKNOWN -> LinkStatus.WAITING
        m.unsent -> LinkStatus.PHONE_NOT_REACHABLE
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

    /** "sent 2 min ago", or "not sent yet" when [lastSentMs] is 0. */
    fun sentAgo(lastSentMs: Long, nowMs: Long): String {
        if (lastSentMs <= 0L) return "not sent yet"
        val min = ((nowMs - lastSentMs).coerceAtLeast(0L) / 60_000L)
        return if (min < 1) "sent just now" else "sent $min min ago"
    }
}
