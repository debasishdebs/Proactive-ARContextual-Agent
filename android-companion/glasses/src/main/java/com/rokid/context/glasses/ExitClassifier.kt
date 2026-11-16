package com.rokid.context.glasses

/** The newest ApplicationExitInfo of the previous process, reduced to what is classified. [description] is never logged. */
data class ExitFacts(val userRequested: Boolean, val description: String?, val timestampMs: Long)

/** The process that called force-stop: its name, and whether it was already running at the exit (rules out pid reuse). */
data class CallerInfo(val name: String, val startedBeforeExit: Boolean)

enum class ExitKind { ROKID, OTHER, UNKNOWN }

/** Why a force-stop caller could not be verified: [why] is no_pid | proc_hidden | pid_reused; [pid] is the number from the description, if any. */
data class UnverifiedWhy(val why: String, val pid: Int?)

object ExitClassifier {
    private val PID = Regex("""from pid (\d+)""")
    private const val FRESH_SLACK_MS = 5_000L

    /**
     * Null = not counted at all: not a force-stop, or a stale record that does not belong to the run that just ended
     * (its timestamp is not in `(lastAliveMs - 5 s, nowMs]`). ROKID/OTHER need a readable caller that started before the
     * exit; absent, hidden (Android hides other uids' /proc) or reused pids are UNKNOWN.
     */
    fun classify(f: ExitFacts, lastAliveMs: Long, nowMs: Long, caller: (Int) -> CallerInfo?): ExitKind? {
        if (!f.userRequested) return null
        if (f.timestampMs <= lastAliveMs - FRESH_SLACK_MS || f.timestampMs > nowMs) return null
        val pid = PID.find(f.description.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: return ExitKind.UNKNOWN
        val c = caller(pid) ?: return ExitKind.UNKNOWN
        if (!c.startedBeforeExit) return ExitKind.UNKNOWN
        return if (c.name.startsWith("com.rokid.")) ExitKind.ROKID else ExitKind.OTHER
    }

    /** The reason [classify] answers UNKNOWN for a force-stop (null = the caller is verified). The pid is a number, never text from the description. */
    fun unverifiedWhy(f: ExitFacts, caller: (Int) -> CallerInfo?): UnverifiedWhy? {
        val pid = PID.find(f.description.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: return UnverifiedWhy("no_pid", null)
        val c = caller(pid) ?: return UnverifiedWhy("proc_hidden", pid)
        return if (c.startedBeforeExit) null else UnverifiedWhy("pid_reused", pid)
    }
}

/** Reads a caller from /proc; null on any failure. Not unit tested (no /proc on the JVM host): covered by the device checklist. */
object ProcCaller {
    fun read(pid: Int, exitTimestampMs: Long, nowWallMs: Long = System.currentTimeMillis(), elapsedMs: Long = android.os.SystemClock.elapsedRealtime()): CallerInfo? = runCatching {
        val name = java.io.File("/proc/$pid/cmdline").readBytes().takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8)
        if (name.isBlank()) return null
        // stat: "pid (comm) state ppid ..."; starttime is field 22, counted from after the last ')' (state is field 3).
        val stat = java.io.File("/proc/$pid/stat").readText()
        val fields = stat.substring(stat.lastIndexOf(')') + 2).split(' ')
        val startTicks = fields[22 - 3].toLong()
        val hz = android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK).takeIf { it > 0 } ?: 100L
        val startedAtWallMs = (nowWallMs - elapsedMs) + startTicks * 1000L / hz
        CallerInfo(name, startedBeforeExit = startedAtWallMs <= exitTimestampMs)
    }.getOrNull()
}
