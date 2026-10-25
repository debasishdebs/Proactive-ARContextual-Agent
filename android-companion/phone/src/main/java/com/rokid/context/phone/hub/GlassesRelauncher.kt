package com.rokid.context.phone.hub

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.rokid.context.core.log.PcLog
import com.rokid.context.core.model.LinkState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What may stop the phone from reopening the glasses app right now. Every answer is read fresh at the moment of the attempt. */
interface RelaunchGuards {
    /** No call is ringing or active (TelephonyManager.callState IDLE and AudioManager.mode NORMAL). */
    fun callIdle(): Boolean
    /** No Rokid scene of ours to hijack: phone audio is not playing (the `mobile_music` scene). */
    fun mediaIdle(): Boolean
    /** The glasses were last known worn (CXR `wearingStatus`); null when unknown, which skips this guard. */
    fun worn(): Boolean?
    /** Fix wave I: the glasses app just closed (a scene event, [nowMs] on the relauncher's clock); a guard that cannot read the call state fails closed for a while after it. */
    fun onSceneEvent(nowMs: Long) {}
}

/** Fix wave I: where the wearer's "I exited the app" is kept; the real one survives a phone process restart ([PrefsStandDown]). */
interface StandDown {
    val active: Boolean
    /** Phone clock of the last [clear], 0 when none: a hello that reports an older exit is not news. */
    val clearedAtMs: Long
    fun enter(atMs: Long)
    /** An explicit open: ends the stand-down and stamps [clearedAtMs]. */
    fun clear(atMs: Long)
    /** The app connected again (by anyone): ends the stand-down without stamping, so a hello repeating the exit that caused it still counts. */
    fun connected()
}

class InMemoryStandDown : StandDown {
    @Volatile override var active = false
        private set
    @Volatile override var clearedAtMs = 0L
        private set
    override fun enter(atMs: Long) { active = true }
    override fun clear(atMs: Long) { active = false; clearedAtMs = atMs }
    override fun connected() { active = false }
}

/** [StandDown] in its own prefs file (`relaunch_state`, excluded from backup: another phone's wearer exit means nothing here). */
class PrefsStandDown(private val prefs: android.content.SharedPreferences) : StandDown {
    override val active: Boolean get() = prefs.getBoolean(KEY_ACTIVE, false)
    override val clearedAtMs: Long get() = prefs.getLong(KEY_CLEARED, 0L)
    override fun enter(atMs: Long) { prefs.edit().putBoolean(KEY_ACTIVE, true).apply() }
    override fun clear(atMs: Long) { prefs.edit().putBoolean(KEY_ACTIVE, false).putLong(KEY_CLEARED, atMs).apply() }
    override fun connected() { if (active) prefs.edit().putBoolean(KEY_ACTIVE, false).apply() }

    companion object {
        const val PREFS = "relaunch_state"
        private const val KEY_ACTIVE = "stand_down"
        private const val KEY_CLEARED = "cleared_at"
    }
}

/**
 * Fix wave H2. Rokid's AssistServer force-stops the glasses app whenever it opens another scene (call, message, music,
 * glasses removed). After that the link ends in [LinkState.CLOSED_ON_GLASSES]; with "Reopen after Rokid closes it" on
 * (the same switch as "Keep running when nearby", [enabled]) the phone reopens the app by [reopen] (an explicit
 * connect) - but never while a call or media scene is active (we would hijack the Rokid UI) and never when the glasses
 * are known to be off the wearer's head. The wearer's own exit ([onWearerExit]) is never reopened.
 *
 * Timing: the first attempt 8 s after the close, then 30 s, 2 min, 5 min, 10 min for repeated closes that follow quickly
 * (the step resets once a session stayed up for [stableMs]); at most [maxPerHour] attempts per rolling hour. A guard that
 * blocks is polled every [pollMs] and the attempt goes ahead once it clears (a call ended). Everything runs on [scope].
 */
class GlassesRelauncher(
    private val scope: CoroutineScope,
    private val state: StateFlow<LinkState>,
    private val enabled: () -> Boolean,
    private val guards: RelaunchGuards,
    private val reopen: () -> Unit,
    private val now: () -> Long,
    private val log: (String) -> Unit = { PcLog.i(TAG, it) },
    private val stepsMs: List<Long> = STEPS_MS,
    private val maxPerHour: Int = MAX_PER_HOUR,
    private val pollMs: Long = POLL_MS,
    private val stableMs: Long = STABLE_MS,
    /** Fix wave I: the wearer's exit, kept across a phone restart. */
    private val standDown: StandDown = InMemoryStandDown(),
    /** Fix wave I: true only when the reopen would put the HUD full screen (the media guard then applies); the glasses reopen into icon mode, so the default is false. */
    private val reopenIsFullScreen: () -> Boolean = { false },
) {
    private val attempts = ArrayDeque<Long>()
    private var step = 0
    private var connectedSince: Long? = null
    private var job: Job? = null
    /** Phone clock of our last reopen while its outcome is open (the link neither connected nor failed yet). */
    private var awaitingSince: Long? = null
    private var lastReopenAt: Long? = null
    /** The pending attempt follows a failed reopen, so it may run while the link is OFFLINE. */
    private var afterFailure = false

    /** Observes the link until the returned job is cancelled (the hub's lifetime). */
    fun start(): Job = scope.launch {
        var prev: LinkState? = null
        state.collect { s ->
            if (prev != null && prev != s) log("relaunch: link $prev->$s")
            if (s == LinkState.CONNECTED) { if (connectedSince == null) connectedSince = now(); standDown.connected() }
            if (s == LinkState.CONNECTED || s == LinkState.MINIMISED || s == LinkState.PAUSED_BT) awaitingSince = null
            if (s == LinkState.CLOSED_ON_GLASSES && prev != LinkState.CLOSED_ON_GLASSES) onClosed()
            // Fix wave I4: our own reopen failed (the link is OFFLINE again soon after it): the next try follows the backoff and the cap.
            else if (s == LinkState.OFFLINE && awaitingSince?.let { now() - it < RESULT_WINDOW_MS } == true) {
                awaitingSince = null
                if (standDown.active) log("relaunch: reopen failed, standing down") else { log("relaunch: reopen failed, trying again later"); scheduleNext(afterFailure = true) }
            }
            if (s != LinkState.CLOSED_ON_GLASSES && !(s == LinkState.OFFLINE && afterFailure)) { job?.cancel(); job = null }
            prev = s
        }
    }

    /** The hub stops: forget any pending attempt. */
    fun stop() { job?.cancel(); job = null }

    /**
     * The wearer exited the app on purpose (`pc_user_exit`): this close is not Rokid's, so nothing reopens it automatically.
     * No timer: an explicit open ([onExplicitOpen]) or the app connecting again ends the stand-down at once.
     */
    fun onUserExit() { standDown.enter(now()); job?.cancel(); job = null; log("relaunch: user exit, standing down") }

    /** The user asked to open the glasses app (phone "Open on glasses"): the stand-down is over. */
    fun onExplicitOpen() { if (standDown.active) log("relaunch: explicit open, stand-down cleared"); standDown.clear(now()) }

    /** True while an automatic reopen is stood down because the wearer exited on purpose. */
    val standingDown: Boolean get() = standDown.active

    /** True for [AUTO_OPEN_WINDOW_MS] after our own reopen: the glasses are told ("pc_auto_open") that this start was automatic. */
    fun reopenedRecently(): Boolean = lastReopenAt?.let { now() - it in 0..AUTO_OPEN_WINDOW_MS } == true

    private fun onClosed() {
        val since = connectedSince
        connectedSince = null
        if (since != null && now() - since >= stableMs) step = 0
        guards.onSceneEvent(now())
        if (standDown.active) { log("relaunch: closed by the wearer, not reopening"); return }
        scheduleNext()
    }

    private fun scheduleNext(afterFailure: Boolean = false) {
        job?.cancel()
        this.afterFailure = afterFailure
        val wait = stepsMs[step.coerceAtMost(stepsMs.lastIndex)]
        log("relaunch: attempt in ${wait / 1000} s (step $step${if (afterFailure) ", after a failed reopen" else ""})")
        job = scope.launch { attemptLoop(wait) }
    }

    private suspend fun attemptLoop(firstDelayMs: Long) {
        delay(firstDelayMs)
        var lastWhy = ""
        while (state.value == LinkState.CLOSED_ON_GLASSES || (afterFailure && state.value == LinkState.OFFLINE)) {
            if (standDown.active) return
            val why = blocker()
            if (why == null) { doReopen(); return }
            if (why != lastWhy) { log("relaunch: waiting ($why)"); lastWhy = why }
            delay(pollMs)
        }
    }

    private fun doReopen() {
        attempts.addLast(now()); step++; awaitingSince = now(); lastReopenAt = now()
        log("relaunch: reopening the glasses app (attempt ${attempts.size}/h, next step $step)")
        try { reopen() } catch (e: CancellationException) { throw e } catch (e: Exception) { log("relaunch: reopen failed (${e.javaClass.simpleName})") }
    }

    /**
     * The watchdog's periodic trigger: one attempt now through every guard of the event-driven path (setting, hourly cap, call idle
     * including its post-call settle, media, worn) plus the stand-down. Null = reopened; else why not ("pending" = a timed attempt is queued).
     */
    fun attemptNow(): String? {
        if (standDown.active) return "stood down"
        if (job?.isActive == true) return "pending"
        val why = blocker()
        if (why != null) { log("relaunch: watchdog waiting ($why)"); return why }
        doReopen()
        return null
    }

    /** The first reason not to reopen now, or null. */
    private fun blocker(): String? {
        if (!enabled()) return "setting off"
        val cutoff = now() - HOUR_MS
        while (attempts.isNotEmpty() && attempts.first() <= cutoff) attempts.removeFirst()
        if (attempts.size >= maxPerHour) return "hourly limit"
        if (!guards.callIdle()) return "call active"
        if (reopenIsFullScreen() && !guards.mediaIdle()) return "media active"
        if (guards.worn() == false) return "glasses not worn"
        return null
    }

    companion object {
        const val TAG = "PcRelaunch"
        val STEPS_MS = listOf(8_000L, 30_000L, 120_000L, 300_000L, 600_000L)
        const val MAX_PER_HOUR = 6
        const val POLL_MS = 10_000L
        const val STABLE_MS = 60_000L
        const val HOUR_MS = 3_600_000L
        /** A reopen whose link is OFFLINE again within this long failed. */
        const val RESULT_WINDOW_MS = 60_000L
        const val AUTO_OPEN_WINDOW_MS = 45_000L
    }
}

/**
 * The real guards. A call is TelephonyManager.callState != IDLE or an AudioManager mode other than NORMAL. READ_PHONE_STATE is deliberately not
 * requested (2026-10-21 ruling R12); callState is read only if a merged library declared it and it was granted. Without it the audio mode is all
 * we have, and a VoIP call that still rings has mode NORMAL: so the call state is treated as unknown, and unknown counts as "in a call" for
 * [UNKNOWN_CALL_HOLD_MS] after a scene event ([callIdle] with a null call state). A call just ended is not idle either: the guard stamps
 * `normalSinceMs` on the transition INTO NORMAL (the mode listener on API 31+, or the poll that first sees NORMAL after a non-NORMAL sample), on the
 * monotonic clock [elapsed], and [callIdle] needs [CALL_END_SETTLE_MS] since then (S12). [wearing] is the last CXR `wearingStatus`.
 * [modeSource] and [callStateSource] are test seams; null = the real AudioManager and TelephonyManager.
 */
class AndroidRelaunchGuards(
    context: Context,
    private val wearing: () -> Boolean?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val modeSource: (() -> Int)? = null,
    private val callStateSource: (() -> Int?)? = null,
    watchModeChanges: Boolean = true,
) : RelaunchGuards {
    private val app = context.applicationContext
    private val telephony = app.getSystemService(TelephonyManager::class.java)
    private val audio = app.getSystemService(AudioManager::class.java)
    @Volatile private var sceneAtMs = Long.MIN_VALUE / 2
    /** Monotonic time we last entered NORMAL; null while the mode is not NORMAL; "long ago" until a non-NORMAL mode was seen. */
    private var normalSinceMs: Long? = Long.MIN_VALUE / 2
    private val lock = Any()

    init {
        // API 31+: the listener sees a hang-up the moment it happens, between two polls.
        if (watchModeChanges && Build.VERSION.SDK_INT >= 31) {
            try { audio?.addOnModeChangedListener(ContextCompat.getMainExecutor(app)) { mode -> onModeChanged(mode) } } catch (e: Exception) { PcLog.w(GlassesRelauncher.TAG, "mode listener unavailable: ${e.javaClass.simpleName}") }
        }
    }

    override fun onSceneEvent(nowMs: Long) { sceneAtMs = nowMs }

    /** A mode sample (the listener, or a poll). The transition into NORMAL is stamped once; any non-NORMAL mode clears it. */
    internal fun onModeChanged(mode: Int) {
        synchronized(lock) {
            if (mode != AudioManager.MODE_NORMAL) normalSinceMs = null
            else if (normalSinceMs == null) normalSinceMs = elapsed()
        }
    }

    override fun callIdle(): Boolean {
        val mode = modeSource?.invoke() ?: try { audio?.mode ?: AudioManager.MODE_NORMAL } catch (e: Exception) { AudioManager.MODE_NORMAL }
        onModeChanged(mode) // every poll samples the mode too
        val state = callStateSource?.invoke() ?: if (callStateSource != null) null else readCallState()
        val sinceNormal = synchronized(lock) { normalSinceMs }?.let { elapsed() - it } ?: 0L
        return callIdle(mode, state, clock() - sceneAtMs, sinceNormal)
    }

    private fun readCallState(): Int? {
        val permitted = app.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        return if (!permitted) null else try { telephony?.callState } catch (e: SecurityException) { null }
    }

    override fun mediaIdle(): Boolean = try { audio?.isMusicActive != true } catch (e: Exception) { true }

    override fun worn(): Boolean? = wearing()

    companion object {
        const val UNKNOWN_CALL_HOLD_MS = 30_000L
        /** After the mode returns to NORMAL the call is treated as still ending for this long (a VoIP app may flip the mode back right away). */
        const val CALL_END_SETTLE_MS = 15_000L

        /** Pure: [callState] null = unreadable (no permission). Unknown holds the reopen back until [UNKNOWN_CALL_HOLD_MS] after the scene event. */
        fun callIdle(audioMode: Int, callState: Int?, sinceSceneMs: Long, sinceNormalMs: Long): Boolean = when {
            audioMode != AudioManager.MODE_NORMAL -> false
            sinceNormalMs < CALL_END_SETTLE_MS -> false
            callState != null -> callState == TelephonyManager.CALL_STATE_IDLE
            else -> sinceSceneMs >= UNKNOWN_CALL_HOLD_MS
        }
    }
}
