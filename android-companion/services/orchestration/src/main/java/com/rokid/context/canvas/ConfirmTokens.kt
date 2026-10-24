package com.rokid.context.canvas

import java.security.SecureRandom

/** Single-use run confirmations, bound to a pipeline hash and a set of frame ids. Held in memory only. */
class ConfirmTokens(private val clock: () -> Long, private val random: SecureRandom = SecureRandom()) {
    private class Entry(val hash: String, val frames: Set<String>, val expiresAt: Long, val binding: String?)

    private val live = LinkedHashMap<String, Entry>()

    /** 16 random bytes as hex, valid for 5 minutes. */
    fun issue(hash: String, frameIds: List<String>, binding: String? = null): String = synchronized(live) {
        val now = clock()
        live.values.removeAll { it.expiresAt <= now }
        val bytes = ByteArray(16).also { random.nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        live[token] = Entry(hash, frameIds.toSet(), now + TTL_MS, binding)
        token
    }

    /** True once per token, and only for the same hash and frame-id set. Any attempt uses the token up. */
    fun consume(token: String, hash: String, frameIds: List<String>): Boolean = synchronized(live) {
        val e = live.remove(token) ?: return false
        e.expiresAt > clock() && e.hash == hash && e.frames == frameIds.toSet()
    }

    enum class Verdict { OK, BAD, MOVED }

    /**
     * [consume] for a token that also carries a content [binding] (Moment runs: the sorted id+rev+hash of every Moment). [Verdict.MOVED] = the token was otherwise valid
     * (live, same pipeline hash, same ids) but the content it bound is not what it is now. Single use either way.
     */
    fun consumeBound(token: String, hash: String, frameIds: List<String>, binding: String): Verdict = synchronized(live) {
        val e = live.remove(token) ?: return Verdict.BAD
        if (e.expiresAt <= clock() || e.hash != hash || e.frames != frameIds.toSet() || e.binding == null) return Verdict.BAD
        if (e.binding == binding) Verdict.OK else Verdict.MOVED
    }

    companion object { const val TTL_MS = 5 * 60 * 1000L }
}
