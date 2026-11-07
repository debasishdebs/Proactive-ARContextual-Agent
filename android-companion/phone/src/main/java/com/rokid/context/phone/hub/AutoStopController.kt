package com.rokid.context.phone.hub

import com.rokid.context.core.log.PcLog
import com.rokid.context.core.model.LinkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext

/**
 * Drives [BackgroundPolicy.autoStop] (user ruling 2026-11-07): while the hub runs and the glasses are off-head or disconnected, it counts the time away and, once
 * the configured minutes have passed (and nothing is in flight, the app was not in front lately, no run is active), stops the hub by itself: [stopHub] ends the service
 * (the hub's own stop cancels the link, probe, reconnect and relaunch attempts; the service releases what it holds). It logs one content-free line (`auto_stop_not_worn`
 * or `auto_stop_disconnected`) and keeps the reason, so the status can say "Stopped: glasses not worn since HH:mm. Tap to start".
 *
 * It sleeps until the exact due time (no polling): [poke] re-evaluates at once (link or wear change, app in front, a setting change). While the hub is stopped
 * it waits for the next [BackgroundPolicy.wakeDue] time, which needs the battery-optimization exemption ([exempt]); without it nothing here ever starts the hub.
 * Review fix 2026-11-07: a stop or a refused wake never waits for a poke (a link that was already OFFLINE emits nothing more): it sleeps to the next rung and
 * also hands that time to [scheduleWake] (a WorkManager wake in production) so a process that dies meanwhile is started again for it.
 * Review fix 2 (2026-11-07, Codex): the wake itself is [AutoStopWake.claim], the one locked owner shared with the WorkManager path, so the two can never both consume
 * a rung or start twice; the blocking commits (the stop record, the claim) run on [io], never on the main thread, and still finish BEFORE the stop / start they guard.
 * One [evaluateNow] at a time ([Mutex]): consecutive decisions never overlap across the IO hop.
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
    /** The next wake rung is in this many ms (a WorkManager wake in production, see [AutoStopWake.schedule]); called after a stop and after a refused wake. */
    private val scheduleWake: (Long) -> Unit = {},
    /** The hub service is up ([ContextHubService.isRunning] in production): tells "already running" from a refused start inside [AutoStopWake.claim]. */
    private val serviceRunning: () -> Boolean = hubRunning,
    /** Where the blocking commits run (Dispatchers.IO; tests may pass their own). */
    private val io: CoroutineContext = Dispatchers.IO,
) {
    private var awaySince: Long? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val decide = Mutex()

    fun poke() { wake.trySend(Unit) }

    /** [evaluateNow] from a plain thread (tests). Never on the main thread: it blocks for the IO hop. */
    internal fun evaluate(): Long? = runBlocking { evaluateNow() }

    /** One decision; returns how long to sleep (null = until the next [poke]). */
    suspend fun evaluateNow(): Long? = decide.withLock { decideOnce() }

    private suspend fun decideOnce(): Long? {
        val t = now()
        publishNote(note())
        if (!hubRunning()) {
            awaySince = null
            if (store.autoStoppedAtMs() == 0L) return null
            val due = store.nextWakeAtMs()
            if (BackgroundPolicy.wakeDue(exempt(), true, store.manualStop(), minutes(), false, linkAllowed(), t, due)) {
                // Re-checked under the shared lock: a WorkManager wake that won the race leaves NONE / NOT_DUE here, and the probe flag untouched.
                when (withContext(io) { AutoStopWake.claim(t, store, exempt, minutes, linkAllowed, serviceRunning, wakeHub, via = "controller", log = log, warn = log) }) {
                    AutoStopWake.Claim.STARTED, AutoStopWake.Claim.ALREADY_RUNNING -> { publishNote(null); return BackgroundPolicy.BUSY_RECHECK_MS } // look again once the hub runs
                    // re-armed: the next rung (the claim moved it), never a poke that may not come
                    AutoStopWake.Claim.REFUSED -> { val next = untilNextWake(t); scheduleWake(next); return next }
                    AutoStopWake.Claim.NOT_DUE -> return untilNextWake(t)
                    AutoStopWake.Claim.NONE -> { publishNote(note()); return BackgroundPolicy.BUSY_RECHECK_MS } // lost the race (or a manual stop came): decide again soon
                }
            }
            // Waiting for the ladder: only with the exemption and the setting on is there anything to wait for.
            return if (BackgroundPolicy.wakeAllowed(exempt(), true, store.manualStop(), minutes(), false, linkAllowed())) untilNextWake(t) else null
        }
        val away = BackgroundPolicy.away(link(), worn())
        if (away == null) {
            awaySince = null; if (store.wakeProbe()) store.setWakeProbe(false)
            if (store.wakeStep() != 0 || store.nextWakeAtMs() != 0L) store.resetWake() // the glasses were seen present: the wake ladder starts again
            return null
        }
        val n = BackgroundPolicy.cleanAutoStopMin(minutes())
        val since = awaySince ?: (if (store.wakeProbe() && n != BackgroundPolicy.AUTO_STOP_NEVER) t - maxOf(0L, n * 60_000L - BackgroundPolicy.WAKE_PROBE_MS) else t).also { awaySince = it }
        val d = BackgroundPolicy.autoStop(BackgroundPolicy.AutoStopInput(t, minutes(), since, away, lastFrontMs(), inFront(), busy(), runActive()))
        if (d.stop && d.reason != null) {
            when (d.reason) { BackgroundPolicy.Away.NOT_WORN -> log("auto_stop_not_worn"); BackgroundPolicy.Away.DISCONNECTED -> log("auto_stop_disconnected") }
            val reason = d.reason; val nextAt = t + BackgroundPolicy.wakeDelayMs(store.wakeStep())
            withContext(io) { store.markAutoStopped(t, reason, since, nextAt) } // committed off the main thread, before the stop
            awaySince = null; if (store.wakeProbe()) store.setWakeProbe(false)
            publishNote(note())
            stopHub()
            // The link may already be OFFLINE (no further state change, no poke): sleep to the first rung and schedule it, never wait for a poke.
            val next = BackgroundPolicy.wakeDelayMs(store.wakeStep()); scheduleWake(next); return next
        }
        return d.recheckInMs
    }

    /** Until the stored next rung, at least [BackgroundPolicy.BUSY_RECHECK_MS]; a clock set back counts as due ([BackgroundPolicy.deadlinePassed]). */
    private fun untilNextWake(t: Long): Long =
        BackgroundPolicy.remainingMs(t, store.nextWakeAtMs(), BackgroundPolicy.WAKE_LADDER_MS.last()).coerceAtLeast(BackgroundPolicy.BUSY_RECHECK_MS)

    private fun note(): String? {
        if (store.autoStoppedAtMs() == 0L) return null
        val why = store.reason() ?: return null
        return BackgroundPolicy.stoppedNote(why, timeText(store.awaySinceMs().takeIf { it > 0 } ?: store.autoStoppedAtMs()))
    }

    fun start(scope: CoroutineScope): Job = scope.launch {
        while (true) {
            val sleep = try { evaluateNow() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { PcLog.w(TAG, "auto stop check failed: ${e.javaClass.simpleName}"); BackgroundPolicy.IDLE_RECHECK_MS }
            if (sleep == null) wake.receive() else withTimeoutOrNull(sleep) { wake.receive() }
        }
    }

    companion object { const val TAG = "PcAutoStop" }
}
