package com.rokid.context.orchestration.proactive

/**
 * The per-record publication state machine (Codex v3.1 r1 P1-4): CLAIMED -> PUBLISHED, and WITHDRAWN as the terminal state, all under ONE lock. A delivery claims a record on a surface and publishes it through [publish];
 * every withdrawal (dismiss, snooze, never, pin delete, Forget, Delete all) calls [withdraw] BEFORE it removes the record and clears the surfaces, so a withdrawal that lands between the claim and the publication wins and
 * the publication is skipped. [publish] runs the (synchronous, cheap) surface call while holding the lock, so a withdrawal cannot interleave with it either: it waits, then clears what was just drawn.
 *
 * [withdrawAll] and [withdrawSurface] move an epoch: every claim taken before is dead. A queued delivery (Nexus) asks [isLive] again at its own publish point. Holds ids and counts only; bounded.
 */
class SuggestionPublications {
    enum class State { CLAIMED, PUBLISHED, WITHDRAWN }

    /** A claim of one record on one surface, with the epochs it was taken under. */
    class Claim internal constructor(val id: String, val surface: Surface, internal val all: Long, internal val surfaceEpoch: Long)

    private val lock = Any()
    private val states = object : LinkedHashMap<String, State>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, State>?) = size > MAX
    }
    private var allEpoch = 0L
    private val surfaceEpochs = HashMap<Surface, Long>()
    private fun sEpoch(s: Surface) = surfaceEpochs[s] ?: 0L

    private fun liveLocked(c: Claim) = states[c.id] != State.WITHDRAWN && c.all == allEpoch && c.surfaceEpoch == sEpoch(c.surface)

    /** Claims [id] on [surface]; null when the record was already withdrawn. */
    fun claim(id: String, surface: Surface): Claim? = synchronized(lock) {
        if (states[id] == State.WITHDRAWN) null else { if (states[id] == null) states[id] = State.CLAIMED; Claim(id, surface, allEpoch, sEpoch(surface)) }
    }

    /** True while the claim may still publish: not withdrawn and no [withdrawAll] / [withdrawSurface] since it was taken. */
    fun isLive(c: Claim): Boolean = synchronized(lock) { liveLocked(c) }

    /** The state of [id] (null = never seen), for tests and logs. */
    fun state(id: String): State? = synchronized(lock) { states[id] }

    /** True when [id] was withdrawn. */
    fun isWithdrawn(id: String): Boolean = synchronized(lock) { states[id] == State.WITHDRAWN }

    /** Runs [action] (the surface publication; it must not suspend or call back here) if the claim is still live; true = it ran and returned true. */
    fun publish(c: Claim, action: () -> Boolean): Boolean = synchronized(lock) {
        if (!liveLocked(c)) return false
        val ok = action()
        if (ok) states[c.id] = State.PUBLISHED
        ok
    }

    /** Terminal: nothing of these records is claimed or published any more. */
    fun withdraw(ids: Collection<String>) { synchronized(lock) { ids.forEach { states[it] = State.WITHDRAWN } } }
    fun withdrawAll() { synchronized(lock) { allEpoch++ } }
    fun withdrawSurface(s: Surface) { synchronized(lock) { surfaceEpochs[s] = sEpoch(s) + 1 } }

    companion object {
        const val MAX = 512
        /** The process-wide instance every production path shares. */
        val shared = SuggestionPublications()
    }
}
