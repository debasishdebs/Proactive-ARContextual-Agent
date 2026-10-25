package com.rokid.context.glasses.capture

import android.content.Context
import android.content.Intent
import com.rokid.context.core.log.PcLog
import com.rokid.context.glasses.MainActivity

/**
 * Whether our HUD activity may be brought forward by capture (spec §1.7: once the wearer leaves our app, nothing pulls
 * it back over another app). Allowed while it is started, or for [RECENT_MS] after it went to the background without
 * the wearer exiting it. Starts "never started", so a capture in a process the HUD never ran in is not allowed either.
 */
class HudPresence(private val clock: () -> Long = System::currentTimeMillis) {
    private enum class State { NEVER_STARTED, STARTED, STOPPED, EXITED }
    @Volatile private var state = State.NEVER_STARTED
    @Volatile private var leftAtMs = 0L
    /** The wearer minimised the HUD (spec 2026-10-14 §1.7 exception): only a Confirm may bring it forward. */
    @Volatile private var minimised = false

    fun onStarted() { state = State.STARTED; minimised = false }

    fun onMinimised() { minimised = true }

    /** [finishing]: the wearer exited (the activity is finishing), not merely backgrounded. */
    fun onStopped(finishing: Boolean) {
        if (state == State.EXITED) return
        leftAtMs = clock()
        state = if (finishing) State.EXITED else State.STOPPED
    }

    fun onExited() { state = State.EXITED }

    fun fallbackAllowed(nowMs: Long = clock()): Boolean = !minimised && when (state) {
        State.STARTED -> true
        State.STOPPED -> nowMs - leftAtMs in 0 until RECENT_MS
        State.NEVER_STARTED, State.EXITED -> false
    }

    /** For logs: the state name only. */
    fun describe(): String = if (minimised) "minimised" else state.name.lowercase()

    companion object { const val RECENT_MS = 60_000L }
}

/**
 * The background-camera fallback (PulseKeepAliveService behaviour): bring the HUD forward, where capture works, at most
 * every [COOLDOWN_MS], and only when [presence] allows it. Otherwise it logs one line and the next scheduled capture retries.
 * After [maxConsecutive] launches with no successful capture between them it stops (logging once) until
 * [onCaptureSucceeded], so a camera that fails for good never keeps pulling the HUD forward (R-07).
 */
class ForegroundFallback(
    private val presence: HudPresence,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = { PcLog.w(CaptureService.TAG, it) },
    private val maxConsecutive: Int = 3,
    /** Called just before the HUD is started (screen-wake attribution). */
    private val onLaunch: () -> Unit = {},
) {
    @Volatile private var lastAtMs: Long? = null
    @Volatile private var launchesSinceCapture = 0
    @Volatile private var gaveUpLogged = false

    /** A frame was buffered: the camera works again, so a later failure may bring the HUD forward again. */
    fun onCaptureSucceeded() {
        launchesSinceCapture = 0
        gaveUpLogged = false
    }

    /** True when the HUD was asked to come forward. */
    fun maybeLaunch(context: Context): Boolean {
        val now = clock()
        if (!presence.fallbackAllowed(now)) {
            log("camera restricted in background; HUD ${presence.describe()}: not reopening it, next capture retries")
            return false
        }
        if (launchesSinceCapture >= maxConsecutive) {
            if (!gaveUpLogged) { gaveUpLogged = true; log("camera keeps failing in background; not reopening the HUD until a capture works") }
            return false
        }
        val last = lastAtMs
        if (last != null && now - last in 0 until COOLDOWN_MS) return false
        lastAtMs = now
        launchesSinceCapture++
        log("camera restricted in background; opening the HUD to capture")
        onLaunch()
        return runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_CAPTURE_NOW)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }.isSuccess
    }

    companion object { const val COOLDOWN_MS = 20_000L }
}
