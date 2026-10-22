package com.rokid.context.canvas.trace

import android.content.Context
import android.content.SharedPreferences
import com.rokid.context.core.model.TraceControl
import com.rokid.context.memory.db.DeletionEpochStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * "Collect for tuning" (S73, S75): a time-boxed session in which the glasses record IMU traces, and the phone's request that they send them (the glasses check
 * their own charging, so [TraceControl.send] is always true). The session (start and end time) is persisted in the `canvas_collect` prefs, so a process death
 * neither loses nor extends it, and every [TraceControl] carries the PERSISTED epoch (`installId` and `deletionCounter` of [DeletionEpochStore]), never a cached
 * one: after a Delete all the glasses see the new counter, drop their buffer and adopt it. [flow] is the value to publish on `Channels.TRACE_CTL`; call [refresh]
 * after a Delete all and when the session may have expired. Tombstones and a forget range ride one control message once ([control] with arguments); they are not
 * stored here.
 */
class CollectSession(
    private val prefs: SharedPreferences,
    private val epoch: DeletionEpochStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val state = MutableStateFlow(control())

    /** The control to publish now (re-emitted by [start], [stop] and [refresh]). */
    val flow: StateFlow<TraceControl> get() = state

    /** Whether a session is running now. */
    fun active(): Boolean = until()?.let { it > clock() } == true

    /** Starts (or restarts) a session of [durationMs] (1 min to [MAX_DURATION_MS]). Returns the control published. */
    fun start(durationMs: Long = DEFAULT_DURATION_MS): TraceControl {
        val now = clock()
        val d = durationMs.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
        check(prefs.edit().putLong(KEY_START, now).putLong(KEY_UNTIL, now + d).commit()) { "collect session not persisted" }
        return refresh()
    }

    fun stop(): TraceControl {
        check(prefs.edit().remove(KEY_START).remove(KEY_UNTIL).commit()) { "collect session not persisted" }
        return refresh()
    }

    /** Rebuilds the control from the persisted session and the persisted epoch and publishes it on [flow]. */
    fun refresh(): TraceControl = control().also { state.value = it }

    /**
     * The control for NOW: collect while the session lasts, always the persisted epoch, `send = true`. [tombstones] and the forget range are for a one-off
     * message (deletion); they do not change the session.
     */
    fun control(tombstones: List<String> = emptyList(), forgetFromMs: Long? = null, forgetToMs: Long? = null): TraceControl {
        val now = clock()
        val e = epoch.read()
        val until = until()
        val on = until != null && until > now
        return TraceControl(
            collect = on, collectUntilMs = if (on) until else null, installId = e.installId, deletionCounter = e.counter,
            tombstones = tombstones, forgetFromMs = forgetFromMs, forgetToMs = forgetToMs, send = true, atMs = now,
            sessionStartMs = if (on) prefs.getLong(KEY_START, 0L) else 0L,
        )
    }

    private fun until(): Long? = if (prefs.contains(KEY_UNTIL)) prefs.getLong(KEY_UNTIL, 0L) else null

    companion object {
        const val PREFS = "canvas_collect"
        private const val KEY_START = "sessionStartMs"
        private const val KEY_UNTIL = "collectUntilMs"
        const val MIN_DURATION_MS = 60_000L
        const val DEFAULT_DURATION_MS = 20 * 60_000L
        const val MAX_DURATION_MS = 12 * 3_600_000L

        fun of(context: Context): CollectSession {
            val app = context.applicationContext
            return CollectSession(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE), DeletionEpochStore.of(app))
        }
    }
}
