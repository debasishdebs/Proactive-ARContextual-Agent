package com.rokid.context.link

import com.rokid.context.core.model.LinkState

sealed interface LinkEvent {
    data object DevicePresent : LinkEvent
    data object DeviceGone : LinkEvent
    data object Started : LinkEvent
    data object Paused : LinkEvent
    data object Resumed : LinkEvent
    data object Closed : LinkEvent
    data object ConnectFailed : LinkEvent
    data object RetryTimer : LinkEvent
    /** The connect attempt produced no result in time; treated like [ConnectFailed] while connecting. */
    data object ConnectTimeout : LinkEvent
    /** The glasses left our app (user exit / app switch): do not auto-reconnect (spec §1.7). */
    data object GlassesExited : LinkEvent
    /** The user brought our app back to the foreground on the glasses ([GlassAppWatcher]). */
    data object GlassesAppOpened : LinkEvent
    /** Hi Rokid authorization failed or was declined: stop auto-relaunching it. */
    data object AuthFailed : LinkEvent
}

sealed interface LinkAction {
    data object Connect : LinkAction
    /** The session (re)opened: the hub pushes `pc_settings` and `pc_state` now. */
    data object SendSettings : LinkAction
    data class ScheduleRetry(val delayMs: Long) : LinkAction
    data object MarkConnected : LinkAction
    data object MarkConnecting : LinkAction
    /** BT dropped: CXR pauses the session and resumes it by itself ~2 s after BT returns (phase 0). */
    data object MarkPaused : LinkAction
    data object MarkDisconnected : LinkAction
    data object MarkClosedOnGlasses : LinkAction
    data object MarkAuthNeeded : LinkAction
}

/** The [LinkState] a Mark* action sets; null for actions that do not change it. */
fun LinkAction.linkState(): LinkState? = when (this) {
    LinkAction.MarkConnected -> LinkState.CONNECTED
    LinkAction.MarkConnecting -> LinkState.CONNECTING
    LinkAction.MarkPaused -> LinkState.PAUSED_BT
    LinkAction.MarkDisconnected -> LinkState.OFFLINE
    LinkAction.MarkClosedOnGlasses -> LinkState.CLOSED_ON_GLASSES
    LinkAction.MarkAuthNeeded -> LinkState.NOT_AUTHORIZED
    LinkAction.Connect, LinkAction.SendSettings, is LinkAction.ScheduleRetry -> null
}

/** Pure session lifecycle rules for the CXR-L link (ported from Live Fit; spec §1.7). */
class GlassesSessionPolicy(private val backoff: Backoff = Backoff()) {
    /** ClosedOnGlasses is Idle after the user closed the app on the glasses: only their reopening it reconnects. */
    private enum class S { Idle, ClosedOnGlasses, Connecting, Open, Paused, WaitingRetry }
    private var state = S.Idle
    private var present = false
    private val idle get() = state == S.Idle || state == S.ClosedOnGlasses

    /** True while the app was closed on the glasses and nothing else has happened since (the link watches for a reopen). */
    val closedOnGlasses: Boolean get() = state == S.ClosedOnGlasses

    fun onEvent(e: LinkEvent): List<LinkAction> = when (e) {
        LinkEvent.DevicePresent -> {
            present = true
            if (idle || state == S.WaitingRetry) { state = S.Connecting; listOf(LinkAction.MarkConnecting, LinkAction.Connect) } else emptyList()
        }
        LinkEvent.DeviceGone -> { present = false; state = S.Idle; listOf(LinkAction.MarkDisconnected) }
        LinkEvent.Started -> { state = S.Open; backoff.reset(); listOf(LinkAction.MarkConnected, LinkAction.SendSettings) }
        LinkEvent.Paused -> { state = S.Paused; listOf(LinkAction.MarkPaused) }
        LinkEvent.Resumed -> { state = S.Open; listOf(LinkAction.MarkConnected, LinkAction.SendSettings) }
        LinkEvent.GlassesExited -> { state = S.ClosedOnGlasses; listOf(LinkAction.MarkClosedOnGlasses) }
        LinkEvent.GlassesAppOpened ->
            if (state == S.ClosedOnGlasses) { state = S.Connecting; listOf(LinkAction.MarkConnecting, LinkAction.Connect) } else emptyList()
        LinkEvent.AuthFailed -> { state = S.Idle; listOf(LinkAction.MarkAuthNeeded) }
        LinkEvent.ConnectTimeout -> if (state == S.Connecting) onEvent(LinkEvent.ConnectFailed) else emptyList()
        LinkEvent.Closed, LinkEvent.ConnectFailed -> {
            if (present) { state = S.WaitingRetry; listOf(LinkAction.MarkDisconnected, LinkAction.ScheduleRetry(backoff.next())) }
            else { state = S.Idle; listOf(LinkAction.MarkDisconnected) }
        }
        LinkEvent.RetryTimer -> if (present && state == S.WaitingRetry) { state = S.Connecting; listOf(LinkAction.Connect) } else emptyList()
    }

    /** Explicit connect (phone "Open on glasses", after authorization). Replaces a stuck attempt; leaves an Open/Paused session alone. */
    fun manualConnect(): List<LinkAction> {
        present = true
        return if (state == S.Open || state == S.Paused) emptyList()
        else { state = S.Connecting; listOf(LinkAction.MarkConnecting, LinkAction.Connect) }
    }

    /** Hub (re)start: one attempt from Idle; a failure ends Idle instead of entering the retry loop. */
    fun autoConnect(): List<LinkAction> =
        if (idle) { state = S.Connecting; listOf(LinkAction.MarkConnecting, LinkAction.Connect) } else emptyList()
}

/**
 * The phone app came to the foreground: reconnect an OFFLINE link once when Hi Rokid authorized us before. Unlike
 * Live Fit, never a link closed on the glasses (spec §1.7: only the wearer reopening the app there, or an explicit
 * phone action, reconnects it), and never after a declined authorization (that would relaunch AuthActivity).
 */
fun shouldReconnectOnResume(link: LinkState, hasToken: Boolean, authDeclined: Boolean): Boolean =
    link == LinkState.OFFLINE && hasToken && !authDeclined
