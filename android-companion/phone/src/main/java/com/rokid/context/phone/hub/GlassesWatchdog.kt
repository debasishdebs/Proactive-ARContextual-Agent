package com.rokid.context.phone.hub

import com.rokid.context.core.model.LinkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Phone-side watchdog (2026-10-22): Rokid force-stops the glasses app on calls and glasses removal, which kills its own alarms and
 * receivers, so the periodic trigger that brings it back lives on the phone (Rokid does not kill it). One [tick] every ~[TICK_MS]
 * (no wakelock, the period is not exact) decides, while the reopen setting is on and capture is active:
 *  - the hub service is dead (the process lived, the service did not): [startHub];
 *  - the wearer exited on purpose: nothing, only the note "stopped by you";
 *  - nothing heard from the glasses for [SILENT_MS] and the link is down: [reconnect] (the existing path, never a relaunch);
 *  - silent while the link says up: MINIMISED = [probeAlive]; otherwise [relaunch] = [GlassesRelauncher.attemptNow], which owns
 *    every guard (call idle with its post-call settle, media, worn, hourly cap, back-off). The watchdog itself never launches anything.
 * Every tick publishes the user-visible note (null = nothing to say) through [publish].
 */
class GlassesWatchdog(
    private val now: () -> Long,
    private val enabled: () -> Boolean,
    /** Capture is wanted: the pipeline is on and nothing is paused. */
    private val captureActive: () -> Boolean,
    private val hubRunning: () -> Boolean,
    private val link: () -> LinkState,
    /** Phone clock of the last message from the glasses app (hello, frame, command...), null = none yet. */
    private val lastHeardAtMs: () -> Long?,
    private val standingDown: () -> Boolean,
    private val startHub: () -> Unit,
    private val reconnect: () -> Unit,
    private val probeAlive: () -> Unit,
    /** Null = the relauncher reopened the app; otherwise why not. */
    private val relaunch: () -> String?,
    private val publish: (String?) -> Unit,
    private val silentMs: Long = SILENT_MS,
) {
    sealed interface Verdict {
        object Off : Verdict
        object Ok : Verdict
        object HubDown : Verdict
        data class LinkDown(val ageMs: Long) : Verdict
        data class StoppedByYou(val ageMs: Long) : Verdict
        data class Reopening(val ageMs: Long) : Verdict
        data class Waiting(val reason: String, val ageMs: Long) : Verdict
        data class Probing(val ageMs: Long) : Verdict
    }

    /** Where silence is measured from when nothing was heard yet: the first tick of this enabled window. */
    private var baselineMs: Long? = null

    fun start(scope: CoroutineScope, periodMs: Long = TICK_MS): Job = scope.launch {
        while (true) {
            try { tick() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { /* never end the loop */ }
            delay(periodMs)
        }
    }

    fun tick(): Verdict {
        val t = now()
        if (!enabled() || !captureActive()) { baselineMs = null; return finish(Verdict.Off) }
        if (!hubRunning()) { baselineMs = null; startHub(); return finish(Verdict.HubDown) }
        val base = baselineMs ?: t.also { baselineMs = it }
        val age = (t - (lastHeardAtMs() ?: base)).coerceAtLeast(0L)
        if (standingDown()) return finish(Verdict.StoppedByYou(age))
        if (age < silentMs) return finish(Verdict.Ok)
        val l = link()
        return finish(
            when (l) {
                LinkState.OFFLINE, LinkState.PAUSED_BT -> { reconnect(); Verdict.LinkDown(age) }
                LinkState.NOT_AUTHORIZED -> Verdict.LinkDown(age)
                LinkState.CONNECTING -> Verdict.Ok
                LinkState.MINIMISED -> { probeAlive(); Verdict.Probing(age) }
                LinkState.CONNECTED, LinkState.CLOSED_ON_GLASSES, LinkState.VERSION_MISMATCH -> when (val why = relaunch()) {
                    null, "pending" -> Verdict.Reopening(age)
                    "stood down" -> Verdict.StoppedByYou(age)
                    else -> Verdict.Waiting(why, age)
                }
            },
        )
    }

    private fun finish(v: Verdict): Verdict { publish(note(v)); return v }

    companion object {
        const val TICK_MS = 150_000L
        /** N: minutes of silence before the watchdog acts (3). */
        const val SILENT_MS = 3 * 60_000L

        fun note(v: Verdict): String? = when (v) {
            Verdict.Off, Verdict.Ok, Verdict.HubDown -> null
            is Verdict.LinkDown -> "Glasses not connected · ${heard(v.ageMs)}"
            is Verdict.StoppedByYou -> "Glasses app stopped by you · ${heard(v.ageMs)}"
            is Verdict.Reopening -> "Glasses app not running, reopening\u2026 \u00b7 ${heard(v.ageMs)}"
            is Verdict.Probing -> "Checking the glasses app\u2026 \u00b7 ${heard(v.ageMs)}"
            is Verdict.Waiting -> when (v.reason) {
                "hourly limit" -> "Glasses app not running · reopen limit reached, open it on the glasses · ${heard(v.ageMs)}"
                else -> "Glasses app not running, reopening… · ${heard(v.ageMs)}"
            }
        }

        private fun heard(ageMs: Long): String { val m = ageMs / 60_000L; return if (m < 1) "last heard just now" else "last heard $m min ago" }
    }
}
