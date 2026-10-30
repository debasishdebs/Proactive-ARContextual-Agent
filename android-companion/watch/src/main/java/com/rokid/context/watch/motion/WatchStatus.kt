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
        WatchState.IN_VEHICLE -> "Driving"
        WatchState.EXERCISE -> "Exercising"
        WatchState.ASLEEP -> "Asleep"
        WatchState.UNKNOWN -> "Unknown"
    }

    /** The link line shown under the state, and what its one-tap fix is (null = no action). */
    fun linkText(l: LinkStatus): String = when (l) {
        LinkStatus.OFF -> "Not sending"
        LinkStatus.PERMISSION_NEEDED -> "Permission needed"
        LinkStatus.SENDING -> "Sending to phone"
        LinkStatus.PHONE_NOT_REACHABLE -> "Phone not reachable"
        LinkStatus.WAITING -> "Waiting for motion"
    }

    enum class Fix { GRANT_PERMISSION, TURN_ON }
    fun fix(l: LinkStatus): Fix? = when (l) {
        LinkStatus.PERMISSION_NEEDED -> Fix.GRANT_PERMISSION
        LinkStatus.OFF -> Fix.TURN_ON
        else -> null
    }
    fun fixLabel(f: Fix): String = when (f) { Fix.GRANT_PERMISSION -> "Allow motion"; Fix.TURN_ON -> "Turn on" }

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

/** Which drawable a state uses (resolved to a resource in the UI; kept as a key so the mapping is testable without Android). */
enum class StateIcon { STILL, WALK, RUN, CYCLE, CAR, EXERCISE, SLEEP, UNKNOWN }

/** Accent colours are 0xAARRGGBB on a dark background; [onAccent] is the text colour that reads on top of the accent. */
data class StateStyle(val accent: Long, val icon: StateIcon)

/** Pure state to look mapping: one accent and icon per motion state. */
object WatchLook {
    const val BACKGROUND = 0xFF000000L
    const val SURFACE = 0xFF1B1F24L
    const val TEXT = 0xFFF2F4F7L
    const val TEXT_DIM = 0xFFAAB1BAL
    const val WARN = 0xFFFFB74DL

    fun style(s: WatchState): StateStyle = when (s) {
        WatchState.STILL -> StateStyle(0xFF7FA6D9L, StateIcon.STILL)       // calm blue/slate
        WatchState.WALKING -> StateStyle(0xFF4CD964L, StateIcon.WALK)       // green
        WatchState.RUNNING -> StateStyle(0xFFFF8A3DL, StateIcon.RUN)        // orange
        WatchState.CYCLING -> StateStyle(0xFF26C6B0L, StateIcon.CYCLE)      // teal
        WatchState.IN_VEHICLE -> StateStyle(0xFFFFC107L, StateIcon.CAR)     // amber
        WatchState.EXERCISE -> StateStyle(0xFFFF4D4DL, StateIcon.EXERCISE)  // red
        WatchState.ASLEEP -> StateStyle(0xFF8C8CF0L, StateIcon.SLEEP)       // indigo
        WatchState.UNKNOWN -> StateStyle(0xFF9AA0A6L, StateIcon.UNKNOWN)    // grey
    }
}
