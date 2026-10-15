package com.rokid.context.phone.hub

import android.content.Context
import android.media.AudioManager
import android.telephony.TelephonyManager
import android.util.Log
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
    private val log: (String) -> Unit = { Log.i(TAG, it) },
    private val stepsMs: List<Long> = STEPS_MS,
    private val maxPerHour: Int = MAX_PER_HOUR,
    private val pollMs: Long = POLL_MS,
    private val stableMs: Long = STABLE_MS,
) {
    private val attempts = ArrayDeque<Long>()
    private var step = 0
    private var connectedSince: Long? = null
    private var wearerExited = false
    private var job: Job? = null

    /** Observes the link until the returned job is cancelled (the hub's lifetime). */
    fun start(): Job = scope.launch {
        var prev: LinkState? = null
        state.collect { s ->
            if (s == LinkState.CONNECTED) { if (connectedSince == null) connectedSince = now(); wearerExited = false }
            if (s == LinkState.CLOSED_ON_GLASSES && prev != LinkState.CLOSED_ON_GLASSES) onClosed()
            if (s != LinkState.CLOSED_ON_GLASSES) { job?.cancel(); job = null }
            prev = s
        }
    }

    /** The hub stops: forget any pending attempt. */
    fun stop() { job?.cancel(); job = null }

    /**
     * The wearer exited the app on purpose (`pc_user_exit`): this close is not Rokid's, so nothing reopens it automatically.
     * No timer: an explicit open ([onExplicitOpen]) or the app connecting again ends the stand-down at once.
     */
    fun onUserExit() { wearerExited = true; job?.cancel(); job = null; log("relaunch: user exit, standing down") }

    /** The user asked to open the glasses app (phone "Open on glasses"): the stand-down is over. */
    fun onExplicitOpen() { if (wearerExited) log("relaunch: explicit open, stand-down cleared"); wearerExited = false }

    /** True while an automatic reopen is stood down because the wearer exited on purpose. */
    val standingDown: Boolean get() = wearerExited

    private fun onClosed() {
        val since = connectedSince
        connectedSince = null
        if (since != null && now() - since >= stableMs) step = 0
        if (wearerExited) { log("relaunch: closed by the wearer, not reopening"); return }
        job?.cancel()
        job = scope.launch { attemptLoop(stepsMs[step.coerceAtMost(stepsMs.lastIndex)]) }
    }

    private suspend fun attemptLoop(firstDelayMs: Long) {
        delay(firstDelayMs)
        var lastWhy = ""
        while (state.value == LinkState.CLOSED_ON_GLASSES) {
            val why = blocker()
            if (why == null) {
                attempts.addLast(now()); step++
                log("relaunch: reopening the glasses app (attempt ${attempts.size}/h, next step $step)")
                try { reopen() } catch (e: CancellationException) { throw e } catch (e: Exception) { log("relaunch: reopen failed (${e.javaClass.simpleName})") }
                return
            }
            if (why != lastWhy) { log("relaunch: waiting ($why)"); lastWhy = why }
            delay(pollMs)
        }
    }

    /** The first reason not to reopen now, or null. */
    private fun blocker(): String? {
        if (!enabled()) return "setting off"
        val cutoff = now() - HOUR_MS
        while (attempts.isNotEmpty() && attempts.first() <= cutoff) attempts.removeFirst()
        if (attempts.size >= maxPerHour) return "hourly limit"
        if (!guards.callIdle()) return "call active"
        if (!guards.mediaIdle()) return "media active"
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
    }
}

/**
 * The real guards. A call is TelephonyManager.callState != IDLE or an AudioManager mode other than NORMAL (the mode alone
 * covers a ringing or active call without READ_PHONE_STATE, which Android 12+ would refuse; a SecurityException on
 * callState falls back to the mode). [wearing] is the last CXR `wearingStatus`, null until one was seen.
 */
class AndroidRelaunchGuards(context: Context, private val wearing: () -> Boolean?) : RelaunchGuards {
    private val telephony = context.applicationContext.getSystemService(TelephonyManager::class.java)
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)

    override fun callIdle(): Boolean {
        val mode = try { audio?.mode ?: AudioManager.MODE_NORMAL } catch (e: Exception) { AudioManager.MODE_NORMAL }
        if (mode != AudioManager.MODE_NORMAL) return false
        // callState needs READ_PHONE_STATE on API 31+; without it the audio mode above is the whole answer.
        return try { (telephony?.callState ?: TelephonyManager.CALL_STATE_IDLE) == TelephonyManager.CALL_STATE_IDLE } catch (e: SecurityException) { true }
    }

    override fun mediaIdle(): Boolean = try { audio?.isMusicActive != true } catch (e: Exception) { true }

    override fun worn(): Boolean? = wearing()
}
