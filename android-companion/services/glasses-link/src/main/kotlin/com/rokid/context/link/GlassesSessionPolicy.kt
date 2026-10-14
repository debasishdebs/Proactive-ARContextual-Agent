package com.rokid.context.link

import com.rokid.context.core.model.LinkState
import com.rokid.cxr.session.SessionErrorCode

sealed interface LinkEvent {
    data object DevicePresent : LinkEvent
    data object DeviceGone : LinkEvent
    data object Started : LinkEvent
    data object Paused : LinkEvent
    data object Resumed : LinkEvent
    data object Closed : LinkEvent
    data object ConnectFailed : LinkEvent
    /** A non-transient refusal (Hi Rokid missing, glasses app missing, BT off): no retry until an explicit action. */
    data object ConnectRefused : LinkEvent
    data object RetryTimer : LinkEvent
    /** The connect attempt produced no result in time; treated like [ConnectFailed] while connecting. */
    data object ConnectTimeout : LinkEvent
    /** The glasses left our app (user exit / app switch): do not auto-reconnect (spec §1.7). */
    data object GlassesExited : LinkEvent
    /** The user brought our app back to the foreground on the glasses ([GlassAppWatcher]). */
    data object GlassesAppOpened : LinkEvent
    /** Hi Rokid authorization failed or was declined: stop auto-relaunching it. */
    data object AuthFailed : LinkEvent
    /**
     * BUG-R2-4: the authorization never reported back (a blocked background start on a cold start). Not a verdict:
     * retried [MAX_AUTH_RETRIES] times with [AUTH_RETRY_STEPS_MS] backoff before it becomes [AuthFailed].
     */
    data object AuthTimedOut : LinkEvent
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
class GlassesSessionPolicy(
    private val backoff: Backoff = Backoff(),
    private val authBackoff: Backoff = Backoff(AUTH_RETRY_STEPS_MS),
) {
    /** ClosedOnGlasses is Idle after the user closed the app on the glasses: only their reopening it reconnects. */
    private enum class S { Idle, ClosedOnGlasses, Connecting, Open, Paused, WaitingRetry, WaitingAuthRetry }
    private var state = S.Idle
    private var present = false
    private var ceilingRetries = 0 // retries already scheduled at the backoff ceiling
    private var authRetries = 0 // automatic re-authorizations after a timeout since the last success or explicit action
    private val idle get() = state == S.Idle || state == S.ClosedOnGlasses

    /** True while the app was closed on the glasses and nothing else has happened since (the link watches for a reopen). */
    val closedOnGlasses: Boolean get() = state == S.ClosedOnGlasses

    /** True while an automatic re-authorization after a timeout is scheduled (BUG-R2-4). */
    val waitingAuthRetry: Boolean get() = state == S.WaitingAuthRetry

    /** The link was closed by its owner: forget everything, so the next [autoConnect] / [manualConnect] starts clean. */
    fun reset() { state = S.Idle; present = false; backoff.reset(); ceilingRetries = 0; resetAuthRetries() }

    private fun resetAuthRetries() { authBackoff.reset(); authRetries = 0 }

    fun onEvent(e: LinkEvent): List<LinkAction> = when (e) {
        LinkEvent.DevicePresent -> {
            present = true
            if (idle || state == S.WaitingRetry) { state = S.Connecting; listOf(LinkAction.MarkConnecting, LinkAction.Connect) } else emptyList()
        }
        LinkEvent.DeviceGone -> { present = false; state = S.Idle; listOf(LinkAction.MarkDisconnected) }
        LinkEvent.Started -> {
            state = S.Open; backoff.reset(); ceilingRetries = 0; resetAuthRetries()
            listOf(LinkAction.MarkConnected, LinkAction.SendSettings)
        }
        LinkEvent.Paused -> { state = S.Paused; listOf(LinkAction.MarkPaused) }
        LinkEvent.Resumed -> { state = S.Open; listOf(LinkAction.MarkConnected, LinkAction.SendSettings) }
        LinkEvent.GlassesExited -> { state = S.ClosedOnGlasses; listOf(LinkAction.MarkClosedOnGlasses) }
        LinkEvent.GlassesAppOpened ->
            if (state == S.ClosedOnGlasses) { state = S.Connecting; listOf(LinkAction.MarkConnecting, LinkAction.Connect) } else emptyList()
        LinkEvent.AuthFailed -> { state = S.Idle; listOf(LinkAction.MarkAuthNeeded) }
        LinkEvent.AuthTimedOut ->
            if (authRetries < MAX_AUTH_RETRIES) {
                authRetries++
                state = S.WaitingAuthRetry
                listOf(LinkAction.MarkConnecting, LinkAction.ScheduleRetry(authBackoff.next()))
            } else { state = S.Idle; listOf(LinkAction.MarkAuthNeeded) }
        LinkEvent.ConnectTimeout -> if (state == S.Connecting) onEvent(LinkEvent.ConnectFailed) else emptyList()
        LinkEvent.ConnectRefused -> { state = S.Idle; listOf(LinkAction.MarkDisconnected) }
        LinkEvent.Closed, LinkEvent.ConnectFailed -> {
            // Transient failures retry 2/5/10/30 s, then at most [MAX_CEILING_RETRIES] more 30 s retries, then wait for the user.
            if (present && ceilingRetries < MAX_CEILING_RETRIES) {
                val delay = backoff.next()
                if (delay >= backoff.ceilingMs) ceilingRetries++
                state = S.WaitingRetry
                listOf(LinkAction.MarkDisconnected, LinkAction.ScheduleRetry(delay))
            } else { state = S.Idle; listOf(LinkAction.MarkDisconnected) }
        }
        LinkEvent.RetryTimer -> when {
            present && state == S.WaitingRetry -> { state = S.Connecting; listOf(LinkAction.Connect) }
            // An auth retry does not need DevicePresent: a cold start's autoConnect never saw one.
            state == S.WaitingAuthRetry -> { state = S.Connecting; listOf(LinkAction.Connect) }
            else -> emptyList()
        }
    }

    /** Explicit connect (phone "Open on glasses", after authorization). Replaces a stuck attempt; leaves an Open/Paused session alone. */
    fun manualConnect(): List<LinkAction> {
        present = true
        backoff.reset(); ceilingRetries = 0; resetAuthRetries()
        return if (state == S.Open || state == S.Paused) emptyList()
        else { state = S.Connecting; listOf(LinkAction.MarkConnecting, LinkAction.Connect) }
    }

    /** Hub (re)start: one attempt from Idle; a failure ends Idle instead of entering the retry loop. */
    fun autoConnect(): List<LinkAction> =
        if (idle) { state = S.Connecting; listOf(LinkAction.MarkConnecting, LinkAction.Connect) } else emptyList()
}

const val MAX_CEILING_RETRIES = 5

/** BUG-R2-4: automatic re-authorizations after an auth timeout, and their delays. */
const val MAX_AUTH_RETRIES = 3
val AUTH_RETRY_STEPS_MS: List<Long> = listOf(2_000, 5_000, 10_000)

/** SDK connect failures a retry cannot fix; they wait for an explicit action or a phone-app resume. */
fun isTerminalConnectError(code: SessionErrorCode?): Boolean = when (code) {
    SessionErrorCode.ROKID_APP_NOT_INSTALLED, SessionErrorCode.ROKID_APP_VERSION_LOW, SessionErrorCode.BT_NOT_CONNECTED,
    SessionErrorCode.GLASSES_APP_NOT_FOUND, SessionErrorCode.GLASSES_APP_INSTALL_FAILED -> true
    else -> false
}

/**
 * The phone app came to the foreground: reconnect an OFFLINE link once when Hi Rokid authorized us before. Unlike
 * Live Fit, never a link closed on the glasses (spec §1.7: only the wearer reopening the app there, or an explicit
 * phone action, reconnects it), and never after a declined authorization (that would relaunch AuthActivity).
 * [authRetryPending] (BUG-R2-4): an automatic re-authorization is waiting; the foreground launch is tried at once.
 */
fun shouldReconnectOnResume(
    link: LinkState, hasToken: Boolean, authDeclined: Boolean, authTimedOut: Boolean = false, authRetryPending: Boolean = false,
): Boolean =
    hasToken && !authDeclined && (link == LinkState.OFFLINE || (link == LinkState.NOT_AUTHORIZED && authTimedOut) || authRetryPending)
