package com.rokid.context.orchestration

import com.rokid.context.ai.embed.LocalModelPower
import com.rokid.context.ai.embed.PowerState

/**
 * Helpers for the on-device memory-index log lines (PcLog, files/logs/pca.log). Pure: no Android, no I/O, clock injected.
 * Only counts, durations, ids of spaces/models and fixed reasons are ever formatted here: never memory text, captions, queries or photo content.
 */

/**
 * Decides when a progress line is worth writing. A key (one build and kind, e.g. "text:space_x") logs its first report, then at most one line
 * per [minIntervalMs] or whenever another [stepPct] percent bucket is crossed (so a fast build writes about ten lines, a slow one one per interval).
 */
class ProgressLimiter(
    private val clock: () -> Long,
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
    private val stepPct: Int = STEP_PCT,
) {
    private class State(var at: Long, var bucket: Int)
    private val states = HashMap<String, State>()

    /** True when [done] of [total] for [key] should be logged now; records that it was. */
    @Synchronized fun due(key: String, done: Int, total: Int): Boolean {
        if (total <= 0) return false
        val now = clock()
        val bucket = IndexLogFormat.percent(done, total) / stepPct
        val s = states[key]
        if (s == null || bucket > s.bucket || now - s.at >= minIntervalMs || now < s.at) {
            states[key] = State(now, bucket)
            return true
        }
        return false
    }

    /** Forgets every key that starts with [prefix] (a build ended: its keys are not needed any more). */
    @Synchronized fun forget(prefix: String) { states.keys.removeAll { it.startsWith(prefix) } }

    companion object {
        const val MIN_INTERVAL_MS = 30_000L
        const val STEP_PCT = 10
    }
}

/**
 * Process-wide memory of what was already said, so a state is logged when it changes and not at every run: the index health, an open pause,
 * and the "semantic search ready" line (once per space and level of completeness).
 */
class IndexLogState(private val clock: () -> Long) {
    private var health: String? = null
    private val paused = HashMap<String, Pair<String, Long>>()
    private val ready = HashSet<String>()

    /** The line for a health change ("NEEDED -> RUNNING (reason)"), or null when [state] is already the logged one. */
    @Synchronized fun health(state: String, reason: String): String? {
        val before = health
        if (before == state) return null
        health = state
        return if (before == null) "index health: $state ($reason)" else "index health: $before -> $state ($reason)"
    }

    /** True when [key] was not paused yet (the caller logs the pause); a repeated pause is silent. */
    @Synchronized fun pause(key: String, reason: String): Boolean {
        if (paused.containsKey(key)) return false
        paused[key] = reason to clock()
        return true
    }

    /** The reason and how long [key] was paused, or null when it was not paused (the caller logs the resume). */
    @Synchronized fun resume(key: String): Pair<String, Long>? =
        paused.remove(key)?.let { (reason, at) -> reason to (clock() - at).coerceAtLeast(0L) }

    /** True the first time [key] is offered (the caller logs the ready line). */
    @Synchronized fun firstReady(key: String): Boolean = ready.add(key)

    @Synchronized fun resetReady(prefix: String) { ready.removeAll { it.startsWith(prefix) } }
}

internal object IndexLogFormat {
    /** 0..100, clamped; 100 when [total] is 0 and nothing is left. */
    fun percent(done: Int, total: Int): Int = if (total <= 0) 100 else (done.coerceIn(0, total).toLong() * 100 / total).toInt()

    /** "text index (building): 1200/3400 embedded (35%) space=space_x". */
    fun progress(kind: String, role: String, done: Int, total: Int, spaceId: String): String =
        "$kind index ($role): ${done.coerceAtLeast(0)}/$total embedded (${percent(done, total)}%) space=$spaceId"

    /** "850 ms", "42 s", "3 min 05 s", "2 h 07 min". */
    fun duration(ms: Long): String {
        val v = ms.coerceAtLeast(0L)
        val s = (v + 500) / 1000
        return when {
            v < 1000 -> "$v ms"
            s < 60 -> "$s s"
            s < 3600 -> "${s / 60} min ${"%02d".format(s % 60)} s"
            else -> "${s / 3600} h ${"%02d".format((s % 3600) / 60)} min"
        }
    }

    /** A model id fit for a log: the nomic-style id as is (bounded); a hash fingerprint cut to 12 characters. */
    fun model(fingerprint: String): String =
        if (fingerprint.length == 64 && fingerprint.all { it in '0'..'9' || it in 'a'..'f' }) fingerprint.take(12) else fingerprint.take(MODEL_MAX)

    private const val MODEL_MAX = 96
}

/** Why bulk index work is not allowed right now, as a fixed phrase (battery numbers are not content). */
internal object BulkGateReason {
    fun of(power: PowerState, backgroundAllowed: Boolean): String = when {
        !LocalModelPower.bulkAllowed(power) ->
            "battery ${if (power.batteryPct >= 0) "${power.batteryPct}%" else "unknown"} and not charging (needs a charger or battery above ${LocalModelPower.MIN_BATTERY_PCT}%)"
        !backgroundAllowed -> "thermal/background policy (phone too warm)"
        else -> "bulk gate closed"
    }
}

/** The process-wide instances the workers and [IndexMaintenance] log through (one rate limit and one "already said" memory per process). */
internal object IndexLogShared {
    val progress = ProgressLimiter(System::currentTimeMillis)
    val state = IndexLogState(System::currentTimeMillis)
}
