package com.rokid.context.work.frames

import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * A PARTIAL_WAKE_LOCK around one extraction (review I5): without it the CPU may sleep inside a long provider call or a budget
 * wait, the uptime-based timers stall while the wall-clock claim stamp keeps running, and the claim looks abandoned. Reference
 * counted (the ask and the loop may extract at once) and timeout-bounded, so a stuck call can never pin the CPU awake. The
 * log carries counts only.
 */
class ExtractWakeLock(private val app: Context, private val timeoutMs: Long = HOLD_MS) {
    private val lock: PowerManager.WakeLock? by lazy {
        (app.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)?.apply { setReferenceCounted(true) }
    }

    suspend fun <T> hold(block: suspend () -> T): T {
        val l = lock
        val acquired = l != null && try { l.acquire(timeoutMs); true } catch (e: Exception) { Log.w(TAG, "wake lock refused: ${e.javaClass.simpleName}"); false }
        try {
            return block()
        } finally {
            if (acquired) try { if (l!!.isHeld) l.release() } catch (_: RuntimeException) { /* already timed out */ }
        }
    }

    companion object {
        const val TAG = "pca:keyframe-extract"
        /** Longer than a worst-case extraction (see [FrameRecovery.CLAIM_TIMEOUT_MS]) is never useful: the claim would be abandoned anyway. */
        const val HOLD_MS = 10 * 60_000L
    }
}
