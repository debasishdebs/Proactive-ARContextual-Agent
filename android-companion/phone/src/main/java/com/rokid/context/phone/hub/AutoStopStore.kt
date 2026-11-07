package com.rokid.context.phone.hub

import android.content.Context
import android.content.SharedPreferences

/**
 * Device state of the AutoStop policy ([BackgroundPolicy.autoStop]), persisted so a process death does not forget it. Never backed up (per-device, see BackupRulesTest).
 * - manual stop: the user stopped the hub by hand; remembered until the user starts it again.
 * - auto stop: the hub stopped itself at [autoStoppedAtMs] with [reason]; the "Stopped: ... Tap to start" status reads it. Cleared by any start.
 * - wake ladder: how many background wakes ran since the glasses were last seen present, and when the next may run ([BackgroundPolicy.WAKE_LADDER_MS]).
 * - wake probe: a background wake started the hub, so its next away window is the short probe ([BackgroundPolicy.WAKE_PROBE_MS]), also when the wake came from a
 *   cold process ([AutoStopWake.onProcessStart]) whose controller did not exist yet.
 */
interface AutoStopStore {
    fun manualStop(): Boolean
    fun setManualStop(on: Boolean)
    fun autoStoppedAtMs(): Long
    fun reason(): BackgroundPolicy.Away?
    /** The away-since time the stop was measured from (shown in the status). */
    fun awaySinceMs(): Long
    fun markAutoStopped(nowMs: Long, reason: BackgroundPolicy.Away, awaySinceMs: Long, nextWakeAtMs: Long)
    fun clearAutoStopped()
    fun wakeStep(): Int
    fun nextWakeAtMs(): Long
    fun wakeAttempted(nextAtMs: Long)
    fun resetWake()
    fun wakeProbe(): Boolean
    fun setWakeProbe(on: Boolean)

    /** A user start (app opened, Reconnect tapped): the manual stop, the auto stop, the wake ladder and a pending probe are all over. */
    fun userStarted() { setManualStop(false); clearAutoStopped(); resetWake(); setWakeProbe(false) }
}

class InMemoryAutoStopStore : AutoStopStore {
    // volatile: the controller (IO hop) and the wake worker read it from different threads; writes that matter happen under AutoStopWake.claim's lock
    @Volatile private var manual = false; @Volatile private var at = 0L; @Volatile private var why: BackgroundPolicy.Away? = null; @Volatile private var since = 0L
    @Volatile private var step = 0; @Volatile private var next = 0L; @Volatile private var probe = false
    override fun manualStop() = manual
    override fun setManualStop(on: Boolean) { manual = on }
    override fun autoStoppedAtMs() = at
    override fun reason() = why
    override fun awaySinceMs() = since
    override fun markAutoStopped(nowMs: Long, reason: BackgroundPolicy.Away, awaySinceMs: Long, nextWakeAtMs: Long) { at = nowMs; why = reason; since = awaySinceMs; next = nextWakeAtMs }
    override fun clearAutoStopped() { at = 0L; why = null; since = 0L }
    override fun wakeStep() = step
    override fun nextWakeAtMs() = next
    override fun wakeAttempted(nextAtMs: Long) { step++; next = nextAtMs }
    override fun resetWake() { step = 0; next = 0L }
    override fun wakeProbe() = probe
    override fun setWakeProbe(on: Boolean) { probe = on }
}

class PrefsAutoStopStore(private val prefs: () -> SharedPreferences) : AutoStopStore {
    override fun manualStop() = prefs().getBoolean("manual_stop", false)
    override fun setManualStop(on: Boolean) { prefs().edit().putBoolean("manual_stop", on).apply() }
    override fun autoStoppedAtMs() = prefs().getLong("auto_stopped_at", 0L)
    override fun reason() = BackgroundPolicy.Away.values().firstOrNull { it.token == prefs().getString("reason", null) }
    override fun awaySinceMs() = prefs().getLong("away_since", 0L)
    override fun markAutoStopped(nowMs: Long, reason: BackgroundPolicy.Away, awaySinceMs: Long, nextWakeAtMs: Long) {
        prefs().edit().putLong("auto_stopped_at", nowMs).putString("reason", reason.token).putLong("away_since", awaySinceMs).putLong("wake_next_at", nextWakeAtMs).commit()
    }
    override fun clearAutoStopped() { prefs().edit().remove("auto_stopped_at").remove("reason").remove("away_since").apply() }
    override fun wakeStep() = prefs().getInt("wake_step", 0)
    override fun nextWakeAtMs() = prefs().getLong("wake_next_at", 0L)
    /** Committed before the start it guards: a process that dies in the start cannot run the same rung again. */
    override fun wakeAttempted(nextAtMs: Long) { prefs().edit().putInt("wake_step", wakeStep() + 1).putLong("wake_next_at", nextAtMs).commit() }
    override fun resetWake() { prefs().edit().putInt("wake_step", 0).putLong("wake_next_at", 0L).apply() }
    override fun wakeProbe() = prefs().getBoolean("wake_probe", false)
    override fun setWakeProbe(on: Boolean) { prefs().edit().putBoolean("wake_probe", on).let { if (on) it.commit() else it.apply() } }

    companion object {
        const val PREFS = "hub_auto_stop"
        @Volatile private var instance: AutoStopStore? = null
        fun of(context: Context): AutoStopStore = instance ?: synchronized(this) {
            instance ?: PrefsAutoStopStore { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }.also { instance = it }
        }
    }
}
