package com.rokid.context.glasses

import android.content.SharedPreferences

/**
 * Fix wave H4. Rokid's AssistServer force-stops this app whenever it opens another scene, and nothing survives a
 * force-stop, so the process cannot report its own death. Instead [heartbeat] (every 30 s, wall clock: elapsedRealtime
 * restarts nothing but is easy to misread across a reboot) stores "last alive"; at the next process start
 * [onProcessStart] logs `restart gap=<seconds> reason=<unknown|install|forcestop>` and counts the force-stops.
 * The gap is at most one heartbeat (30 s) longer than the real downtime. Counts only, never content.
 *
 * reason: install = the package was updated after the last heartbeat; forcestop = the system's last exit record for
 * this package says "user requested" (what `forceStopPackage` leaves); anything else (or no record) = unknown.
 */
class RestartDiagnostics(
    private val prefs: SharedPreferences,
    private val wallClock: () -> Long = System::currentTimeMillis,
    /** PackageInfo.lastUpdateTime. */
    private val installedAtMs: () -> Long = { 0L },
    /** The newest ApplicationExitInfo of the previous process: true = REASON_USER_REQUESTED; null = unavailable. */
    private val lastExitWasUserRequested: () -> Boolean? = { null },
    private val log: (String) -> Unit,
    /** What the wearer last saw before this process started: [UiModeStore] full | icon | home. */
    private val uiMode: () -> String = { UiModeStore.HOME },
) {
    /** True after a force-stop that hit us in icon mode: the app should come back into icon mode, not full screen. */
    @Volatile var restartedFromIcon = false
        private set

    /** One-shot: the activity asks once per process whether to go straight back to icon mode. */
    fun takeStartMinimised(): Boolean = restartedFromIcon.also { restartedFromIcon = false }

    /** Times the system force-stopped us (reason forcestop) since the app data was last cleared. */
    val closes: Int get() = prefs.getInt(KEY_CLOSES, 0)
    /** Seconds between the last heartbeat and the latest restart that followed a force-stop, or null. */
    val lastGapSec: Int? get() = if (prefs.contains(KEY_LAST_GAP)) prefs.getInt(KEY_LAST_GAP, 0) else null

    fun heartbeat() { prefs.edit().putLong(KEY_ALIVE, wallClock()).apply() }

    fun onProcessStart() {
        val lastAlive = prefs.getLong(KEY_ALIVE, 0L)
        val now = wallClock()
        val gap = if (lastAlive > 0L) ((now - lastAlive).coerceAtLeast(0L) / 1_000L).toInt() else -1
        val reason = when {
            lastAlive <= 0L -> "unknown"
            installedAtMs() > lastAlive -> "install"
            lastExitWasUserRequested() == true -> "forcestop"
            else -> "unknown"
        }
        val mode = uiMode()
        restartedFromIcon = reason == "forcestop" && mode == UiModeStore.ICON
        log("restart gap=$gap reason=$reason ui_mode=$mode")
        if (reason == "forcestop") prefs.edit().putInt(KEY_CLOSES, closes + 1).putInt(KEY_LAST_GAP, gap).apply()
        heartbeat() // a start is alive too: a second start right away must not read the old gap again
    }

    /** "Rokid closed the app N times (last gap Xs)", or null before the first one. */
    fun summary(): String? {
        val n = closes
        if (n == 0) return null
        return "Rokid closed the app $n time${if (n == 1) "" else "s"} (last gap ${lastGapSec ?: 0}s)"
    }

    private companion object {
        const val KEY_ALIVE = "last_alive_ms"
        const val KEY_CLOSES = "rokid_closes"
        const val KEY_LAST_GAP = "last_gap_sec"
    }
}

/** Fix wave H: which of full-screen HUD, minimised icon or neither the wearer last had; persisted on change, count-free text only. */
class UiModeStore(private val prefs: SharedPreferences) {
    fun get(): String = prefs.getString(KEY, HOME)?.takeIf { it == FULL || it == ICON || it == HOME } ?: HOME
    fun set(mode: String) { if (mode != get()) prefs.edit().putString(KEY, mode).apply() }

    companion object {
        const val FULL = "full"; const val ICON = "icon"; const val HOME = "home"
        private const val KEY = "ui_mode"
    }
}
