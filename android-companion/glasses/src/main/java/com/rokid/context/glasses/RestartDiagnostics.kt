package com.rokid.context.glasses

import android.content.SharedPreferences

/**
 * Fix wave H4. Rokid's AssistServer force-stops this app whenever it opens another scene, and nothing survives a
 * force-stop, so the process cannot report its own death. Instead [heartbeat] (every 30 s, wall clock: elapsedRealtime
 * restarts nothing but is easy to misread across a reboot) stores "last alive"; at the next process start
 * [onProcessStart] logs `restart rokid=N unknown=M other=K gap=<seconds> reason=<unknown|install|forcestop_rokid|forcestop_unverified|forcestop_other>`
 * and counts the force-stops by who made them (see [ExitClassifier]).
 * The gap is at most one heartbeat (30 s) longer than the real downtime. Counts only, never content.
 *
 * reason: install = the package was updated after the last heartbeat; forcestop_* = a fresh "user requested" exit record
 * (what `forceStopPackage` leaves), by caller: rokid, other (a verified non-Rokid process) or unverified (caller hidden or reused);
 * anything else (or no record, or a stale one) = unknown.
 *
 * Honesty (fix wave I): REASON_USER_REQUESTED is also what a force-stop from Settings or adb leaves, so "forcestop" is the
 * best Android offers, not proof that Rokid did it. Only a forcestop_rokid exit is counted as "Rokid closed"; an update, a
 * deliberate exit by the wearer (the alive stamp is cleared then) and any other death are not. The heartbeat is written
 * only while the app is meant to be running (ui_mode full or icon), so the gap never includes time the wearer had it closed.
 */
class RestartDiagnostics(
    private val prefs: SharedPreferences,
    private val wallClock: () -> Long = System::currentTimeMillis,
    /** PackageInfo.lastUpdateTime. */
    private val installedAtMs: () -> Long = { 0L },
    /** The newest ApplicationExitInfo of the previous process; null = unavailable. */
    private val lastExit: () -> ExitFacts? = { null },
    private val log: (String) -> Unit,
    /** What the wearer last saw before this process started: [UiModeStore] full | icon | home. */
    private val uiMode: () -> String = { UiModeStore.HOME },
    /** (exit, lastAliveMs, nowMs) -> its kind, or null when it is not a counted force-stop (see [ExitClassifier]). */
    private val classify: (ExitFacts, Long, Long) -> ExitKind? = { f, _, _ -> if (f.userRequested) ExitKind.UNKNOWN else null },
) {
    /**
     * True after a restart that follows a death while the app was meant to be running (a heartbeat was stored, no update and
     * no wearer exit since, ui_mode full or icon): the app comes back into icon mode, never full screen over a call or scene.
     */
    @Volatile var killedWhileRunning = false
        private set

    /** One-shot: the activity asks once per process whether to go straight back to icon mode. */
    fun takeStartMinimised(): Boolean = killedWhileRunning.also { killedWhileRunning = false }

    /** Times the system force-stopped us (reason forcestop) since the app data was last cleared. */
    val closes: Int get() = prefs.getInt(KEY_CLOSES, 0)
    /** Force-stops whose caller could not be verified (hidden or reused pid): their own category, not added to [closes]. */
    val unverifiedCloses: Int get() = prefs.getInt(KEY_UNVERIFIED, 0)
    /** Force-stops by a verified non-Rokid caller (Settings, adb). */
    val otherCloses: Int get() = prefs.getInt(KEY_OTHER, 0)
    /** Seconds between the last heartbeat and the latest restart that followed a force-stop, or null. */
    val lastGapSec: Int? get() = if (prefs.contains(KEY_LAST_GAP)) prefs.getInt(KEY_LAST_GAP, 0) else null

    /** Not while the app is deliberately closed (ui_mode home): a stamp then would make the next start read a gap the wearer chose. */
    fun heartbeat() { if (uiMode() != UiModeStore.HOME) prefs.edit().putLong(KEY_ALIVE, wallClock()).apply() }

    /** The wearer's exit (or any finish of the activity): forget the stamp; the next start is a fresh one, not a restart. */
    fun onExit() { prefs.edit().remove(KEY_ALIVE).apply() }

    /** The wearer exited on purpose at [atMs] (glasses clock): repeated in every hello until the phone has heard it. */
    fun onUserExit(atMs: Long) { prefs.edit().remove(KEY_ALIVE).putLong(KEY_EXIT_AT, atMs).apply() }

    /** The wearer opened the app themselves: the exit they made is over and must never be repeated in a hello again. */
    fun clearUserExit() { if (userExitAtMs != null) prefs.edit().remove(KEY_EXIT_AT).apply() }

    /** Glasses clock of the wearer's last deliberate exit, or null. */
    val userExitAtMs: Long? get() = prefs.getLong(KEY_EXIT_AT, 0L).takeIf { it > 0L }

    fun onProcessStart() {
        // Before wave 3 `rokid_closes` counted every force-stop, whoever made it: drop that mixed count once (diagnostics only).
        if (!prefs.contains(KEY_COUNTS_V2)) prefs.edit().remove(KEY_CLOSES).remove(KEY_LAST_GAP).putBoolean(KEY_COUNTS_V2, true).apply()
        val lastAlive = prefs.getLong(KEY_ALIVE, 0L)
        val now = wallClock()
        val exit = lastExit()
        // The exit's own timestamp is exact; the heartbeat overstates the gap by up to 30 s.
        val gap = when {
            lastAlive <= 0L -> -1
            exit != null && exit.timestampMs in (lastAlive + 1)..now -> ((now - exit.timestampMs) / 1_000L).toInt()
            else -> ((now - lastAlive).coerceAtLeast(0L) / 1_000L).toInt()
        }
        val kind = if (lastAlive > 0L && installedAtMs() <= lastAlive && exit != null) classify(exit, lastAlive, now) else null
        val reason = when {
            lastAlive <= 0L -> "unknown"
            installedAtMs() > lastAlive -> "install"
            kind == ExitKind.ROKID -> "forcestop_rokid"
            kind == ExitKind.UNKNOWN -> "forcestop_unverified"
            kind == ExitKind.OTHER -> "forcestop_other"
            else -> "unknown"
        }
        val mode = uiMode()
        killedWhileRunning = lastAlive > 0L && reason != "install" && mode != UiModeStore.HOME
        val e = prefs.edit()
        when (kind) {
            ExitKind.ROKID -> e.putInt(KEY_CLOSES, closes + 1).putInt(KEY_LAST_GAP, gap)
            ExitKind.UNKNOWN -> e.putInt(KEY_UNVERIFIED, unverifiedCloses + 1)
            ExitKind.OTHER -> e.putInt(KEY_OTHER, otherCloses + 1)
            null -> {}
        }
        e.apply()
        // Counts only (S20): never the exit description, a pid or a package name.
        log("restart rokid=$closes unknown=$unverifiedCloses other=$otherCloses gap=$gap reason=$reason ui_mode=$mode")
        if (mode == UiModeStore.HOME) onExit() else heartbeat() // a start is alive too: a second start right away must not read the old gap again
    }

    /** "Rokid closed the app N times (last gap Xs; M unverified; K other force-stops)", or null before the first force-stop of any kind. */
    fun summary(): String? = com.rokid.context.core.model.closesSummary(closes, lastGapSec, unverifiedCloses, otherCloses)

    private companion object {
        const val KEY_ALIVE = "last_alive_ms"
        const val KEY_CLOSES = "rokid_closes"
        const val KEY_COUNTS_V2 = "closes_v2"
        const val KEY_UNVERIFIED = "unknown_closes"
        const val KEY_OTHER = "other_closes"
        const val KEY_LAST_GAP = "last_gap_sec"
        const val KEY_EXIT_AT = "user_exit_at_ms"
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
