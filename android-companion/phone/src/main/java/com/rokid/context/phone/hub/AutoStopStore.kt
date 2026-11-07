package com.rokid.context.phone.hub

import android.content.Context
import android.content.SharedPreferences

/**
 * Device state of the AutoStop policy ([BackgroundPolicy.autoStop]), persisted so a process death does not forget it. Never backed up (per-device, see BackupRulesTest).
 * - manual stop: the user stopped the hub by hand; remembered until the user starts it again.
 * - auto stop: the hub stopped itself at [autoStoppedAtMs] with [reason]; the "Stopped: ... Tap to start" status reads it. Cleared by any start.
 * - wake ladder: how many background wakes ran since the glasses were last seen present, and when the next may run ([BackgroundPolicy.WAKE_LADDER_MS]).
 * - wake probe: a background wake started the hub, so its next away window is the short probe ([BackgroundPolicy.WAKE_PROBE_MS]), also when the wake came from a
 *   cold process whose controller did not exist yet.
 * Actor rewrite (Codex review 4): this is ONE immutable snapshot, written whole by the AutoStop state machine ([AutoStopEngine]) after each step that changed it.
 */
data class AutoStopSnapshot(
    val manualStop: Boolean = false,
    val autoStoppedAtMs: Long = 0L,
    val reason: BackgroundPolicy.Away? = null,
    /** The away-since time the stop was measured from (shown in the status). */
    val awaySinceMs: Long = 0L,
    val wakeStep: Int = 0,
    val nextWakeAtMs: Long = 0L,
    val wakeProbe: Boolean = false,
)

/** Where the [AutoStopSnapshot] lives. [save] is durable when it returns (it runs on the state machine's IO context, before the effects it guards). */
interface AutoStopStore {
    fun load(): AutoStopSnapshot
    fun save(s: AutoStopSnapshot)

    // Read-only conveniences (tests, diagnostics): what is persisted now.
    fun manualStop() = load().manualStop
    fun autoStoppedAtMs() = load().autoStoppedAtMs
    fun reason() = load().reason
    fun awaySinceMs() = load().awaySinceMs
    fun wakeStep() = load().wakeStep
    fun nextWakeAtMs() = load().nextWakeAtMs
    fun wakeProbe() = load().wakeProbe
}

class InMemoryAutoStopStore(initial: AutoStopSnapshot = AutoStopSnapshot()) : AutoStopStore {
    @Volatile private var s = initial
    override fun load() = s
    override fun save(s: AutoStopSnapshot) { this.s = s }
}

/** One prefs file, one commit per snapshot (the keys are the ones the earlier per-field store wrote, so an upgrade keeps the state). */
class PrefsAutoStopStore(private val prefs: () -> SharedPreferences) : AutoStopStore {
    override fun load(): AutoStopSnapshot = prefs().let { p ->
        AutoStopSnapshot(
            manualStop = p.getBoolean("manual_stop", false), autoStoppedAtMs = p.getLong("auto_stopped_at", 0L),
            reason = BackgroundPolicy.Away.values().firstOrNull { it.token == p.getString("reason", null) }, awaySinceMs = p.getLong("away_since", 0L),
            wakeStep = p.getInt("wake_step", 0), nextWakeAtMs = p.getLong("wake_next_at", 0L), wakeProbe = p.getBoolean("wake_probe", false),
        )
    }

    override fun save(s: AutoStopSnapshot) {
        prefs().edit().putBoolean("manual_stop", s.manualStop).putLong("auto_stopped_at", s.autoStoppedAtMs).putString("reason", s.reason?.token)
            .putLong("away_since", s.awaySinceMs).putInt("wake_step", s.wakeStep).putLong("wake_next_at", s.nextWakeAtMs).putBoolean("wake_probe", s.wakeProbe).commit()
    }

    companion object {
        const val PREFS = "hub_auto_stop"
        @Volatile private var instance: AutoStopStore? = null
        fun of(context: Context): AutoStopStore = instance ?: synchronized(this) {
            instance ?: PrefsAutoStopStore { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }.also { instance = it }
        }
    }
}
