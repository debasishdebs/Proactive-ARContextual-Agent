package com.rokid.context.work

import java.util.concurrent.ConcurrentHashMap

/**
 * Offline only refuses a remote primary and tries the on-device/LAN fallback; while that box is merely asleep the work parks without using an attempt. A box that is reachable but fails every time would park the same job or
 * frame forever, so each key (a queue job id or a frame id) may be parked [CAP] times; the next one counts as an ordinary transient failure (attempt budget, then the normal end). In memory only (a restart gives the work a fresh
 * allowance, which is harmless); bounded; keys are ids and never logged.
 */
internal object FallbackParkCap {
    const val CAP = 6
    private const val MAX_KEYS = 2_000
    private val parks = ConcurrentHashMap<String, Int>()

    /** Records one park for each of [keys]; true when ANY of them has now been parked more than [CAP] times (its counter is then reset, so the attempt it uses starts a fresh allowance). */
    fun over(keys: Collection<String>): Boolean {
        if (parks.size > MAX_KEYS) parks.clear()
        var over = false
        for (k in keys) {
            val n = parks.merge(k, 1, Int::plus) ?: 1
            if (n > CAP) { over = true; parks.remove(k) }
        }
        return over
    }

    fun resetForTests() = parks.clear()
}
