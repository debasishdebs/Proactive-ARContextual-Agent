package com.rokid.context.phone.hub

import com.rokid.context.core.log.PcLog
import com.rokid.context.core.model.LinkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
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
 *
 * Logging (2026-10-25, a 21 min dead gap had no phone line): one counts-only line per verdict CHANGE and every [LOG_EVERY]th tick otherwise (`verdict`, `why` or the
 * relaunch result, the link state, seconds silent), a warning for a late tick (the phone process was suspended: the proof for Doze) and for a dead hub.
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
    /** The silence threshold, read at EVERY tick (Settings > Capture; default [SILENT_MS]), so a changed setting applies on the next tick. */
    private val silentMs: () -> Long = { SILENT_MS },
    private val log: (String) -> Unit = { PcLog.i(TAG, it) },
    private val warn: (String) -> Unit = { PcLog.w(TAG, it) },
    /** The tick period, read before EVERY sleep (Settings > Capture; default [TICK_MS]). */
    private val periodMs: () -> Long = { TICK_MS },
    /** A pair exists ([com.rokid.context.link.CxrGlassesLink.isPaired]); without one nothing here runs ([BackgroundPolicy]). */
    private val paired: () -> Boolean = { true },
    /** The hub foreground service is wanted ([BackgroundPolicy.hubNeeded]); when it is not and the service runs, [stopHub] ends it. */
    private val hubNeeded: () -> Boolean = { true },
    private val stopHub: () -> Unit = {},
    /** The hub restart ladder and last start time; persisted in production so process death does not reset them ([PrefsHubRestartStore]). */
    private val hubStore: HubRestartStore = InMemoryHubRestartStore(),
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
    private var lastTickAt: Long? = null
    private var lastLogged: String? = null
    private var quietTicks = 0
    /** Back-off of the automatic attempts while the glasses are away; reset by [onPositiveSignal]. */
    @Volatile private var backoff = BackgroundPolicy.reset()
    /** Own back-off for HUB restarts (a start refused by Android 12+ must not repeat every tick); reset when the hub runs and by every positive signal. */
    @Volatile private var hubBackoff = hubStore.loadBackoff()
    private var gate = BackgroundPolicy.Gate.ACTIVE
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /**
     * A positive signal (link up, wear event, app in front, a tapped Reconnect, the setting switched on): the back-off starts again and the loop
     * ticks now instead of finishing its sleep. Thread-safe.
     */
    fun onPositiveSignal() { backoff = BackgroundPolicy.reset(); resetHubBackoff(); wake.trySend(Unit) }

    /** A phone context source was switched on (the hub is wanted now): the hub restart ladder starts again and the loop ticks now. */
    fun onHubWanted() { resetHubBackoff(); wake.trySend(Unit) }

    /** Resets the ladder step only: the minimum interval between hub start attempts ([MIN_HUB_START_MS]) is independent and survives every reset signal. */
    private fun resetHubBackoff() { hubBackoff = BackgroundPolicy.reset(); hubStore.saveBackoff(hubBackoff) }

    private fun hubStartAllowed(t: Long): Boolean {
        val d = t - hubStore.lastStartAtMs()
        return hubBackoff.due(t) && (d < 0 || d >= MIN_HUB_START_MS) // a clock set back must not block for ever
    }

    /** The attempt is saved (committed) BEFORE the start: a start that crashes the process cannot bypass the back-off on the next process. */
    private fun hubStarted(t: Long) { hubBackoff = BackgroundPolicy.attempted(hubBackoff, t); hubStore.saveStart(hubBackoff, t); startHub() }

    /** [periodMs] null = the configured one, re-read before every sleep, and the policy's sleep (long with no pair or the setting off, the back-off while away); a number fixes it (tests). */
    fun start(scope: CoroutineScope, periodMs: Long? = null): Job = scope.launch {
        while (true) {
            try { tick() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { /* never end the loop */ }
            val sleep = periodMs ?: BackgroundPolicy.sleepMs(gate, this@GlassesWatchdog.periodMs(), backoff, now())
            withTimeoutOrNull(sleep) { wake.receive() }
        }
    }

    fun tick(): Verdict {
        val t = now()
        val period = periodMs()
        lastTickAt?.let { prev -> if (t - prev > 2 * period) warn("watchdog late tick gap_s=${(t - prev) / 1000} period_s=${period / 1000}") }
        lastTickAt = t
        val on = enabled(); val pair = paired()
        gate = BackgroundPolicy.gate(on, pair, link())
        // The hub service stays only while something needs it; the watchdog never restarts one that is not needed.
        if (!hubNeeded()) { baselineMs = null; if (hubRunning()) { log("watchdog hub not needed, stopping it"); stopHub() }; return finish(Verdict.Off, if (!pair) "why=no_pair" else if (!on) "why=setting_off" else "why=not_needed") }
        // Hub recovery is separate from permission to run glasses machinery: a needed hub (a context source, an ask) is restarted whatever the glasses gate says; no reconnect, no relaunch.
        fun recoverHub() { if (!hubRunning() && hubStartAllowed(t)) { warn("watchdog hub down, starting it"); hubStarted(t) } }
        if (hubRunning() && hubBackoff != BackgroundPolicy.reset()) resetHubBackoff()
        if (!pair) { baselineMs = null; recoverHub(); return finish(Verdict.Off, "why=no_pair") }
        if (!on) { baselineMs = null; recoverHub(); return finish(Verdict.Off, "why=setting_off") }
        if (!captureActive()) { baselineMs = null; recoverHub(); return finish(Verdict.Off, "why=capture_inactive") }
        if (gate == BackgroundPolicy.Gate.ACTIVE) backoff = BackgroundPolicy.reset()
        if (!hubRunning()) {
            // Away: the restart waits for its back-off; the status line still says what is going on (silence counts from the last message, or from this window's first tick).
            if (!hubStartAllowed(t)) {
                val first = baselineMs ?: t.also { baselineMs = it }
                return finish(Verdict.LinkDown((t - (lastHeardAtMs() ?: first)).coerceAtLeast(0L)), "why=hub_down action=backoff")
            }
            baselineMs = null
            warn("watchdog hub down, starting it"); hubStarted(t)
            return finish(Verdict.HubDown, "why=hub_down")
        }
        val base = baselineMs ?: t.also { baselineMs = it }
        val age = (t - (lastHeardAtMs() ?: base)).coerceAtLeast(0L)
        val silentS = "silent_s=${age / 1000}"
        if (standingDown()) return finish(Verdict.StoppedByYou(age), "why=stood_down $silentS")
        if (age < silentMs()) return finish(Verdict.Ok, silentS)
        val l = link()
        var detail = "link=$l $silentS"
        return finish(
            when (l) {
                LinkState.OFFLINE, LinkState.PAUSED_BT ->
                    if (backoff.due(t)) { reconnect(); backoff = BackgroundPolicy.attempted(backoff, t); detail += " action=reconnect"; Verdict.LinkDown(age) }
                    else { detail += " action=backoff"; Verdict.LinkDown(age) }
                LinkState.NOT_AUTHORIZED -> Verdict.LinkDown(age)
                LinkState.CONNECTING -> Verdict.Ok
                LinkState.MINIMISED -> { probeAlive(); detail += " action=probe"; Verdict.Probing(age) }
                LinkState.CONNECTED, LinkState.CLOSED_ON_GLASSES, LinkState.VERSION_MISMATCH -> when (val why = relaunch()) {
                    null -> { detail += " relaunch=reopened"; Verdict.Reopening(age) }
                    "pending" -> { detail += " relaunch=pending"; Verdict.Reopening(age) }
                    "stood down" -> { detail += " relaunch=stood down"; Verdict.StoppedByYou(age) }
                    else -> { detail += " relaunch=$why"; Verdict.Waiting(why, age) }
                }
            },
            detail,
        )
    }

    /** Logs the decision: at once when the verdict or its detail changed, else every [LOG_EVERY]th tick. */
    private fun finish(v: Verdict, detail: String = ""): Verdict {
        publish(note(v))
        val key = v.javaClass.simpleName + " " + detail.replace(Regex("silent_s=\\d+"), "")
        if (key != lastLogged || ++quietTicks >= LOG_EVERY) { log("watchdog tick verdict=${v.javaClass.simpleName}${if (detail.isBlank()) "" else " $detail"}"); lastLogged = key; quietTicks = 0 }
        return v
    }

    companion object {
        const val TAG = "PcWatchdog"
        const val LOG_EVERY = 10
        const val TICK_MS = 150_000L
        /** No two hub start attempts closer than this, whatever resets the ladder (foreground, a source toggled, a positive signal). */
        const val MIN_HUB_START_MS = 60_000L
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

/** Persisted state of the hub restart attempts: the ladder (step, next allowed time) and the time of the last start attempt. */
interface HubRestartStore {
    fun loadBackoff(): BackgroundPolicy.Backoff
    fun lastStartAtMs(): Long
    /** A reset of the ladder; the last start time is untouched. */
    fun saveBackoff(b: BackgroundPolicy.Backoff)
    /** A start attempt at [atMs] and the ladder after it; durable when it returns (it is saved before the start it guards). */
    fun saveStart(b: BackgroundPolicy.Backoff, atMs: Long)
}

class InMemoryHubRestartStore : HubRestartStore {
    private var b = BackgroundPolicy.reset(); private var last = 0L
    override fun loadBackoff() = b
    override fun lastStartAtMs() = last
    override fun saveBackoff(b: BackgroundPolicy.Backoff) { this.b = b }
    override fun saveStart(b: BackgroundPolicy.Backoff, atMs: Long) { this.b = b; last = atMs }
}

class PrefsHubRestartStore(private val prefs: () -> android.content.SharedPreferences) : HubRestartStore {
    override fun loadBackoff() = BackgroundPolicy.Backoff(prefs().getInt("step", 0), prefs().getLong("next_at", 0L))
    override fun lastStartAtMs() = prefs().getLong("last_start", 0L)
    override fun saveBackoff(b: BackgroundPolicy.Backoff) { prefs().edit().putInt("step", b.step).putLong("next_at", b.nextAtMs).apply() }
    override fun saveStart(b: BackgroundPolicy.Backoff, atMs: Long) { prefs().edit().putInt("step", b.step).putLong("next_at", b.nextAtMs).putLong("last_start", atMs).commit() }
}
