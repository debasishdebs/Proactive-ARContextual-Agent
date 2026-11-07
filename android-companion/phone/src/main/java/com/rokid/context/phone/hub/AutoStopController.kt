package com.rokid.context.phone.hub

import com.rokid.context.core.log.PcLog
import com.rokid.context.core.model.LinkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Drives [BackgroundPolicy.autoStop] (user ruling 2026-11-07): while the hub runs and the glasses are off-head or disconnected, it counts the time away and, once
 * the configured minutes have passed (and nothing is in flight, the app was not in front lately, no run is active), stops the hub by itself: [stopHub] ends the service
 * (the hub's own stop cancels the link, probe, reconnect and relaunch attempts; the service releases what it holds). It logs one content-free line (`auto_stop_not_worn`
 * or `auto_stop_disconnected`) and keeps the reason, so the status can say "Stopped: glasses not worn since HH:mm. Tap to start".
 *
 * It sleeps until the exact due time (no polling): [poke] re-evaluates at once (link or wear change, app in front, a setting change). While the hub is stopped
 * it waits for the next [BackgroundPolicy.wakeDue] time, which needs the battery-optimization exemption ([exempt]); without it nothing here ever starts the hub.
 */
class AutoStopController(
    private val now: () -> Long,
    private val minutes: () -> Int,
    private val store: AutoStopStore,
    private val hubRunning: () -> Boolean,
    private val link: () -> LinkState,
    private val worn: () -> Boolean?,
    private val inFront: () -> Boolean,
    private val lastFrontMs: () -> Long,
    private val busy: () -> Boolean,
    private val runActive: () -> Boolean,
    private val stopHub: () -> Unit,
    /** Starts the hub from the background; true = the start was issued (Android may still refuse it, the caller logs that). */
    private val wakeHub: () -> Boolean,
    private val exempt: () -> Boolean,
    private val linkAllowed: () -> Boolean,
    /** The user-visible line ("Stopped: ... Tap to start"), null = none. */
    private val publishNote: (String?) -> Unit,
    private val timeText: (Long) -> String = { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it)) },
    private val log: (String) -> Unit = { PcLog.i(TAG, it) },
) {
    private var awaySince: Long? = null
    private var wakeProbe = false
    private val wake = Channel<Unit>(Channel.CONFLATED)

    fun poke() { wake.trySend(Unit) }

    /** One decision; returns how long to sleep (null = until the next [poke]). */
    fun evaluate(): Long? {
        val t = now()
        publishNote(note())
        if (!hubRunning()) {
            awaySince = null
            if (store.autoStoppedAtMs() == 0L) return null
            val due = store.nextWakeAtMs()
            if (BackgroundPolicy.wakeDue(exempt(), true, store.manualStop(), minutes(), false, linkAllowed(), t, due)) {
                store.wakeAttempted(t + BackgroundPolicy.wakeDelayMs(store.wakeStep() + 1))
                log("auto_stop wake attempt")
                if (wakeHub()) { wakeProbe = true; store.clearAutoStopped(); publishNote(null) } else log("auto_stop wake refused")
                return null
            }
            // Waiting for the ladder: only with the exemption and the setting on is there anything to wait for.
            return if (exempt() && !store.manualStop() && minutes() != BackgroundPolicy.AUTO_STOP_NEVER && linkAllowed()) (due - t).coerceAtLeast(BackgroundPolicy.BUSY_RECHECK_MS) else null
        }
        val away = BackgroundPolicy.away(link(), worn())
        if (away == null) {
            awaySince = null; wakeProbe = false
            if (store.wakeStep() != 0 || store.nextWakeAtMs() != 0L) store.resetWake() // the glasses were seen present: the wake ladder starts again
            return null
        }
        val n = BackgroundPolicy.cleanAutoStopMin(minutes())
        val since = awaySince ?: (if (wakeProbe && n != BackgroundPolicy.AUTO_STOP_NEVER) t - maxOf(0L, n * 60_000L - BackgroundPolicy.WAKE_PROBE_MS) else t).also { awaySince = it }
        val d = BackgroundPolicy.autoStop(BackgroundPolicy.AutoStopInput(t, minutes(), since, away, lastFrontMs(), inFront(), busy(), runActive()))
        if (d.stop && d.reason != null) {
            when (d.reason) { BackgroundPolicy.Away.NOT_WORN -> log("auto_stop_not_worn"); BackgroundPolicy.Away.DISCONNECTED -> log("auto_stop_disconnected") }
            store.markAutoStopped(t, d.reason, since, t + BackgroundPolicy.wakeDelayMs(store.wakeStep()))
            awaySince = null; wakeProbe = false
            publishNote(note())
            stopHub()
            return null
        }
        return d.recheckInMs
    }

    private fun note(): String? {
        if (store.autoStoppedAtMs() == 0L) return null
        val why = store.reason() ?: return null
        return BackgroundPolicy.stoppedNote(why, timeText(store.awaySinceMs().takeIf { it > 0 } ?: store.autoStoppedAtMs()))
    }

    fun start(scope: CoroutineScope): Job = scope.launch {
        while (true) {
            val sleep = try { evaluate() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { PcLog.w(TAG, "auto stop check failed: ${e.javaClass.simpleName}"); BackgroundPolicy.IDLE_RECHECK_MS }
            if (sleep == null) wake.receive() else withTimeoutOrNull(sleep) { wake.receive() }
        }
    }

    companion object { const val TAG = "PcAutoStop" }
}
