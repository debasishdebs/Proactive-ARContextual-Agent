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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What may stop the phone from reopening the glasses app right now. Every answer is read fresh at the moment of the attempt. */
interface RelaunchGuards {
    /** No REAL call (user ruling 2026-10-28): AudioManager.mode is not IN_CALL / CALL_SCREENING, TelephonyManager.callState is IDLE when readable, and a call just ended has settled. A VoIP mode (IN_COMMUNICATION) is not a call. */
    fun callIdle(): Boolean
    /** No Rokid scene of ours to hijack: phone audio is not playing (the `mobile_music` scene). */
    fun mediaIdle(): Boolean
    /** The glasses were last known worn (CXR `wearingStatus`); null when unknown, which skips this guard. */
    fun worn(): Boolean?
    /** Fix wave I: the glasses app just closed (a scene event, [nowMs] on the relauncher's clock); a guard that cannot read the call state fails closed for a while after it. */
    fun onSceneEvent(nowMs: Long) {}
    /** Diagnostics only (never a guard input): the values behind the last [callIdle] answer as counts and buckets (`audioMode=.. callState=.. sinceNormal=.. sinceScene=..`), never a caller's number or name; null = none kept. */
    fun callInputs(): String? = null
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

/** v1.1 T5: a glasses close not yet answered by a reopen, kept across a phone process death. [why] is the last blocker (`closed` before any). */
data class PendingClose(val closedAtMs: Long, val why: String)

/** v1.1 T5: the pending close and the hourly reopen attempts, so a rebuilt relauncher keeps the 6/h cap and its open close. */
interface RelaunchStore {
    fun pending(): PendingClose?
    fun savePending(p: PendingClose)
    fun clearPending()
    /** Phone-clock times of our reopens, oldest first. */
    fun attempts(): List<Long>
    fun saveAttempts(a: List<Long>)
    /** Review hub-wp2 r1 P2: the attempts are on disk when this returns (blocking: call it off Main); the reopen that follows may be killed at once. True = durable. */
    fun saveAttemptsDurable(a: List<Long>): Boolean { saveAttempts(a); return true }
}

class InMemoryRelaunchStore : RelaunchStore {
    private var pending: PendingClose? = null
    private var attempts: List<Long> = emptyList()
    override fun pending() = pending
    override fun savePending(p: PendingClose) { pending = p }
    override fun clearPending() { pending = null }
    override fun attempts() = attempts
    override fun saveAttempts(a: List<Long>) { attempts = a }
}

/** [RelaunchStore] in the same prefs file as [PrefsStandDown] (`relaunch_state`). Times only, never a name or number. */
class PrefsRelaunchStore(private val prefs: android.content.SharedPreferences) : RelaunchStore {
    override fun pending(): PendingClose? =
        prefs.getLong(KEY_PENDING_AT, 0L).takeIf { it > 0L }?.let { PendingClose(it, prefs.getString(KEY_PENDING_WHY, null) ?: "closed") }
    override fun savePending(p: PendingClose) { prefs.edit().putLong(KEY_PENDING_AT, p.closedAtMs).putString(KEY_PENDING_WHY, p.why).apply() }
    override fun clearPending() { if (prefs.contains(KEY_PENDING_AT)) prefs.edit().remove(KEY_PENDING_AT).remove(KEY_PENDING_WHY).apply() }
    override fun attempts(): List<Long> = prefs.getString(KEY_ATTEMPTS, null)?.split(',')?.mapNotNull { it.toLongOrNull() }.orEmpty()
    override fun saveAttempts(a: List<Long>) { prefs.edit().putString(KEY_ATTEMPTS, a.joinToString(",")).apply() }
    override fun saveAttemptsDurable(a: List<Long>): Boolean = prefs.edit().putString(KEY_ATTEMPTS, a.joinToString(",")).commit()

    private companion object {
        const val KEY_PENDING_AT = "pending_closed_at"
        const val KEY_PENDING_WHY = "pending_why"
        const val KEY_ATTEMPTS = "attempts"
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
    /** v1.1 T5: the pending close and the hourly attempts, kept across a phone restart. */
    private val store: RelaunchStore = InMemoryRelaunchStore(),
    /** Where the durable attempt write runs before a launch (never Main); tests pass Dispatchers.Unconfined. */
    private val io: kotlin.coroutines.CoroutineContext = kotlinx.coroutines.Dispatchers.IO,
) {
    /** A saved attempt older than an hour no longer counts, and one from the future (a clock change) is dropped. */
    private val attempts = ArrayDeque(store.attempts().filter { it > now() - HOUR_MS && it <= now() })
    private var step = 0
    private var connectedSince: Long? = null
    private var job: Job? = null
    /** Phone clock of our last reopen while its outcome is open (the link neither connected nor failed yet). */
    private var awaitingSince: Long? = null
    private var lastReopenAt: Long? = null
    /** The pending attempt follows a failed reopen, so it may run while the link is OFFLINE. */
    private var afterFailure = false
    /** Reopens in a row that failed (the glasses are away): the retry then follows [BackgroundPolicy.BACKOFF_MS] (5, 15, 30, 60 min) instead of the close-time steps; reset by a data link or a positive signal. */
    private var failures = 0
    /** Diagnostics for a long block (2026-10-28: 29 silent minutes of "call active"): what was last logged, when, and since when the guard has been blocking. */
    private var blockSig: String? = null
    private var blockLoggedAt = 0L
    private var blockedSince: Long? = null
    private var blockedWhy = ""

    /** Observes the link until the returned job is cancelled (the hub's lifetime). */
    fun start(): Job {
        resumePending()
        return scope.launch { observe() }
    }

    /**
     * v1.1 T5: a close still open when the phone process died. It resumes through the same guards (a call still blocks, with its 15 s settle),
     * and an expired or future one is dropped. A link that comes back as CLOSED_ON_GLASSES takes the normal path; one that is OFFLINE gets a timed attempt now.
     */
    private fun resumePending() {
        val p = store.pending() ?: return
        val age = now() - p.closedAtMs
        if (age < 0 || age > PENDING_MAX_AGE_MS || standDown.active) { store.clearPending(); log("relaunch: dropped a stale pending close (age ${age / 1000} s)"); return }
        log("relaunch: resumed pending close (age ${age / 1000} s, why=${p.why})")
        guards.onSceneEvent(now()) // a guard that cannot read the call state holds for a while, as after a fresh close
        if (state.value == LinkState.OFFLINE) scheduleNext(afterFailure = true)
    }

    private suspend fun observe() {
        var prev: LinkState? = null
        state.collect { s ->
            if (prev != null && prev != s) log("relaunch: link $prev->$s")
            if (s == LinkState.CONNECTED) { if (connectedSince == null) connectedSince = now(); standDown.connected() }
            if (s == LinkState.CONNECTED || s == LinkState.MINIMISED) { failures = 0; store.clearPending() }
            if (s == LinkState.CONNECTED || s == LinkState.MINIMISED || s == LinkState.PAUSED_BT) awaitingSince = null
            if (s == LinkState.CLOSED_ON_GLASSES && prev != LinkState.CLOSED_ON_GLASSES) onClosed()
            // Fix wave I4: our own reopen failed (the link is OFFLINE again soon after it): the next try follows the backoff and the cap.
            else if (s == LinkState.OFFLINE && awaitingSince?.let { now() - it < RESULT_WINDOW_MS } == true) {
                awaitingSince = null
                if (standDown.active) log("relaunch: reopen failed, standing down") else { failures++; log("relaunch: reopen failed, trying again later"); scheduleNext(afterFailure = true) }
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
    fun onUserExit() { standDown.enter(now()); store.clearPending(); job?.cancel(); job = null; log("relaunch: user exit, standing down") }

    /** The user asked to open the glasses app (phone "Open on glasses"): the stand-down is over. */
    fun onExplicitOpen() { if (standDown.active) log("relaunch: explicit open, stand-down cleared"); standDown.clear(now()); store.clearPending(); failures = 0 }

    /**
     * A positive signal (a wear event, the app in front, the setting switched on): the failed-reopen back-off starts again, and a close that
     * is still open with no attempt pending (the loop ended because the setting was off) is attempted again through the guards.
     */
    fun onPositiveSignal() {
        failures = 0
        if (state.value == LinkState.CLOSED_ON_GLASSES && job?.isActive != true && !standDown.active) scheduleNext()
    }

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
        store.savePending(PendingClose(now(), "closed"))
        scheduleNext()
    }

    private fun scheduleNext(afterFailure: Boolean = false) {
        job?.cancel()
        this.afterFailure = afterFailure
        // After a failed reopen the glasses are away: BackgroundPolicy's ladder; a fresh close (the glasses are there) keeps its own steps.
        val wait = if (afterFailure && failures > 0) BackgroundPolicy.delayMs(failures - 1) else stepsMs[step.coerceAtMost(stepsMs.lastIndex)]
        log("relaunch: attempt in ${wait / 1000} s (step $step${if (afterFailure) ", after a failed reopen" else ""})")
        job = scope.launch { attemptLoop(wait) }
    }

    private suspend fun attemptLoop(firstDelayMs: Long) {
        delay(firstDelayMs)
        var lastWhy = ""
        while (state.value == LinkState.CLOSED_ON_GLASSES || (afterFailure && state.value == LinkState.OFFLINE)) {
            if (standDown.active) { store.clearPending(); return }
            val why = blocker()
            if (why == null) { noteCleared(); doReopen(); return }
            if (why != lastWhy) { log("relaunch: waiting ($why)"); lastWhy = why }
            noteBlocked(why)
            // No polling for what only a person changes (the setting) and no 10 s poll for the hourly cap: sleep until its oldest attempt ages out.
            if (why == SETTING_OFF) return
            delay(if (why == HOURLY_LIMIT) (attempts.firstOrNull()?.let { it + HOUR_MS - now() }?.coerceAtLeast(pollMs) ?: pollMs) else pollMs)
        }
    }

    /** The attempt is durable (committed on [io]) BEFORE the launch: a kill right after it can never let a rebuilt relauncher allow a 7th reopen in the hour. */
    private suspend fun doReopen() {
        attempts.addLast(now()); val saved = attempts.toList(); step++; awaitingSince = now(); lastReopenAt = now()
        if (!withContext(NonCancellable + io) { store.saveAttemptsDurable(saved) }) log("relaunch: attempt record not durable")
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
        if (why != null) { log("relaunch: watchdog waiting ($why)"); noteBlocked(why); return why }
        noteCleared()
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) { doReopen() } // the durable write is off Main; the launch follows it
        return null
    }

    /**
     * v1.1 hub recovery (review hub-wp2 r1 P1): the lost hub's automatic connect is a glasses launch like any reopen: it waits for every guard (a call and its
     * settle, the setting, the hourly cap) through the same loop and counts as an attempt. Never connects during a call; retries when the blocker clears.
     */
    fun connectAfterRecovery() {
        if (standDown.active) return
        job?.cancel()
        afterFailure = true
        log("relaunch: recovery connect through the guards")
        job = scope.launch { attemptLoop(0L) }
    }

    /**
     * Diagnostics only (the guard logic is untouched): one `blocked` line when the blocker or its inputs change (at most every [BLOCK_CHANGE_MIN_MS], so a flapping mode cannot grow the log) and a heartbeat every
     * [BLOCK_HEARTBEAT_MS] while it holds. Values and buckets only. [noteCleared] says when the block ended and how long it lasted.
     */
    private fun noteBlocked(why: String) {
        val t = now()
        if (blockedSince == null || blockedWhy != why) {
            blockedSince = t; blockedWhy = why
            store.pending()?.let { store.savePending(it.copy(why = why)) }
        }
        val inputs = if (why == CALL_ACTIVE) guards.callInputs() else null
        val sig = "$why|$inputs"
        val since = t - blockLoggedAt
        val changed = sig != blockSig && since >= BLOCK_CHANGE_MIN_MS
        if (blockSig != null && !changed && since < BLOCK_HEARTBEAT_MS) return
        blockSig = sig; blockLoggedAt = t
        log("relaunch: blocked ($why)${inputs?.let { " $it" } ?: ""} poll=${if (job?.isActive == true) "alive" else "idle"} for=${(t - (blockedSince ?: t)) / 1000} s")
    }

    private fun noteCleared() {
        val since = blockedSince ?: return
        log("relaunch: block cleared (was $blockedWhy, ${(now() - since) / 1000} s)")
        blockedSince = null; blockSig = null; blockedWhy = ""
    }

    /** The first reason not to reopen now, or null. */
    private fun blocker(): String? {
        if (!enabled()) return SETTING_OFF
        val cutoff = now() - HOUR_MS
        val before = attempts.size
        while (attempts.isNotEmpty() && attempts.first() <= cutoff) attempts.removeFirst()
        if (attempts.size != before) store.saveAttempts(attempts.toList())
        if (attempts.size >= maxPerHour) return HOURLY_LIMIT
        if (!guards.callIdle()) return CALL_ACTIVE
        if (reopenIsFullScreen() && !guards.mediaIdle()) return "media active"
        if (guards.worn() == false) return "glasses not worn"
        return null
    }

    companion object {
        const val TAG = "PcRelaunch"
        val STEPS_MS = listOf(8_000L, 30_000L, 120_000L, 300_000L, 600_000L)
        const val MAX_PER_HOUR = 6
        const val POLL_MS = 10_000L
        private const val CALL_ACTIVE = "call active"
        private const val SETTING_OFF = "setting off"
        private const val HOURLY_LIMIT = "hourly limit"
        /** Diagnostics: a changed blocker or input is logged at most this often; an unchanged block repeats this often. */
        const val BLOCK_CHANGE_MIN_MS = 30_000L
        const val BLOCK_HEARTBEAT_MS = 300_000L
        const val STABLE_MS = 60_000L
        const val HOUR_MS = 3_600_000L
        /** A pending close older than this (the glasses were away or the wearer moved on) is not resumed. */
        const val PENDING_MAX_AGE_MS = 2 * HOUR_MS
        /** A reopen whose link is OFFLINE again within this long failed. */
        const val RESULT_WINDOW_MS = 60_000L
        const val AUTO_OPEN_WINDOW_MS = 45_000L
    }
}

/**
 * The real guards. A call is TelephonyManager.callState != IDLE or an AudioManager mode other than NORMAL. READ_PHONE_STATE is declared and optional
 * (asked in Settings > Phone camera & mic; supersedes ruling R12); callState is read only when it was granted. Without it the audio mode is all
 * we have, so the call state is treated as unknown, and unknown counts as "in a call" for [UNKNOWN_CALL_HOLD_MS] after a scene event ([callIdle] with a
 * null call state). User ruling 2026-10-28: only a REAL call blocks: audio mode IN_CALL, RINGTONE (a ringing call) or CALL_SCREENING, or a readable call state that is not IDLE; the other
 * non-NORMAL modes (IN_COMMUNICATION of VoIP and meeting apps, ...) do not (risk: a VoIP call no longer defers the reopen; the diagnostics line still logs the mode).
 * A call just ended is not idle either: the guard stamps `normalSinceMs` on the transition OUT OF a call (the mode listener on API 31+, or the poll that first sees no call
 * after a call sample), on the monotonic clock [elapsed], and [callIdle] needs [CALL_END_SETTLE_MS] since then (S12). [wearing] is the last CXR `wearingStatus`.
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
    @Volatile private var lastInputs: String? = null
    /** The last READABLE telephony state: the mode listener cannot read it (null), so it must not start the settle while the last known state is a call. */
    private var lastCallState: Int? = null

    init {
        // API 31+: the listener sees a hang-up the moment it happens, between two polls.
        if (watchModeChanges && Build.VERSION.SDK_INT >= 31) {
            try { audio?.addOnModeChangedListener(ContextCompat.getMainExecutor(app)) { mode -> onModeChanged(mode) } } catch (e: Exception) { PcLog.w(GlassesRelauncher.TAG, "mode listener unavailable: ${e.javaClass.simpleName}") }
        }
    }

    override fun onSceneEvent(nowMs: Long) { sceneAtMs = nowMs }

    /** A sample (the mode listener, or a poll). The end of a call is stamped once; a call sample (a call mode, or a readable non-idle [callState]) clears it. */
    internal fun onModeChanged(mode: Int, callState: Int? = null) {
        synchronized(lock) {
            if (callState != null) lastCallState = callState
            if (callBusy(mode, callState ?: lastCallState)) normalSinceMs = null
            else if (normalSinceMs == null) normalSinceMs = elapsed()
        }
    }

    override fun callIdle(): Boolean {
        val mode = modeSource?.invoke() ?: try { audio?.mode ?: AudioManager.MODE_NORMAL } catch (e: Exception) { AudioManager.MODE_NORMAL }
        val state = callStateSource?.invoke() ?: if (callStateSource != null) null else readCallState()
        onModeChanged(mode, state) // every poll samples the mode too
        val sinceNormal = synchronized(lock) { normalSinceMs }?.let { elapsed() - it } ?: 0L
        val sinceScene = clock() - sceneAtMs
        lastInputs = "audioMode=$mode callState=${state ?: "unreadable"} sinceNormal=${bucket(sinceNormal)} sinceScene=${bucket(sinceScene)}"
        return callIdle(mode, state, sinceScene, sinceNormal)
    }

    override fun callInputs(): String? = lastInputs

    private fun readCallState(): Int? {
        val permitted = app.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        return if (!permitted) null else try { telephony?.callState } catch (e: SecurityException) { null }
    }

    override fun mediaIdle(): Boolean = try { audio?.isMusicActive != true } catch (e: Exception) { true }

    override fun worn(): Boolean? = wearing()

    companion object {
        /** Diagnostics: a duration as a coarse bucket (never a time of day). */
        internal fun bucket(ms: Long): String = when {
            ms < 15_000L -> "<15s"
            ms < 60_000L -> "<1m"
            ms < 300_000L -> "<5m"
            ms < 1_800_000L -> "<30m"
            else -> ">=30m"
        }

        const val UNKNOWN_CALL_HOLD_MS = 30_000L
        /** After the mode returns to NORMAL the call is treated as still ending for this long (a VoIP app may flip the mode back right away). */
        const val CALL_END_SETTLE_MS = 15_000L

        /** A real call in progress: the call audio modes (not VoIP's IN_COMMUNICATION), or a readable telephony state that is not idle. */
        internal fun callBusy(audioMode: Int, callState: Int?): Boolean =
            audioMode == AudioManager.MODE_IN_CALL || audioMode == AudioManager.MODE_RINGTONE || audioMode == AudioManager.MODE_CALL_SCREENING || (callState != null && callState != TelephonyManager.CALL_STATE_IDLE)

        /** Pure: [callState] null = unreadable (no permission). Unknown holds the reopen back until [UNKNOWN_CALL_HOLD_MS] after the scene event. [sinceNormalMs] = time since the last call sample ended. */
        fun callIdle(audioMode: Int, callState: Int?, sinceSceneMs: Long, sinceNormalMs: Long): Boolean = when {
            callBusy(audioMode, callState) -> false
            sinceNormalMs < CALL_END_SETTLE_MS -> false
            callState != null -> callState == TelephonyManager.CALL_STATE_IDLE
            else -> sinceSceneMs >= UNKNOWN_CALL_HOLD_MS
        }
    }
}
