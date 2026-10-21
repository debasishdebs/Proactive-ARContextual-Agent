package com.rokid.context.phone.canvas.server

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Pairing codes and bearer sessions. Secrets are only ever compared in constant time. */
class CanvasAuth(private val clock: () -> Long, private val random: SecureRandom = SecureRandom()) {
    companion object {
        const val CODE_TTL_MS = 5 * 60_000L
        const val MAX_TRIES = 5
        const val LOCK_MS = 30_000L
        const val IDLE_MS = 30 * 60_000L
        const val MAX_AGE_MS = 12 * 3_600_000L
        const val MAX_SESSIONS = 3
    }

    data class Code(val code: String, val expiresAt: Long)

    sealed interface Pair {
        data class Ok(val token: String) : Pair
        data class Wrong(val triesLeft: Int) : Pair
        data class Locked(val untilMs: Long) : Pair
        data object NoCode : Pair
    }

    private class Session(val token: ByteArray, val createdAt: Long, var lastSeen: Long)

    private val lock = Any()
    private var code: Code? = null
    private var tries = 0
    private var lockedUntil = 0L
    private val sessions = ArrayList<Session>() // oldest first

    /** The newest successful [check] (the service's idle rule); starts at construction time. */
    @Volatile var lastActivityMs: Long = clock()
        private set

    fun newCode(): Code = synchronized(lock) {
        val c = Code("%06d".format(random.nextInt(1_000_000)), clock() + CODE_TTL_MS)
        code = c
        tries = 0
        c
    }

    fun currentCode(): Code? = synchronized(lock) { liveCode() }

    private fun liveCode(): Code? {
        val c = code ?: return null
        if (clock() >= c.expiresAt) { code = null; return null }
        return c
    }

    fun pair(code: String): Pair = synchronized(lock) {
        val now = clock()
        if (now < lockedUntil) return Pair.Locked(lockedUntil)
        val current = liveCode() ?: return Pair.NoCode
        if (!constantTimeEquals(code.toByteArray(Charsets.UTF_8), current.code.toByteArray(Charsets.UTF_8))) {
            tries++
            if (tries >= MAX_TRIES) {
                this.code = null
                tries = 0
                lockedUntil = now + LOCK_MS
                return Pair.Locked(lockedUntil)
            }
            return Pair.Wrong(MAX_TRIES - tries)
        }
        this.code = null
        tries = 0
        Pair.Ok(addSession())
    }

    fun newWebViewToken(): String = synchronized(lock) { addSession() }

    fun check(token: String?): Boolean = synchronized(lock) {
        if (token.isNullOrEmpty()) return false
        val now = clock()
        purgeExpired(now)
        val given = token.toByteArray(Charsets.UTF_8)
        var hit: Session? = null
        for (s in sessions) { // no early exit: every session is compared
            if (constantTimeEquals(given, s.token)) hit = s
        }
        val s = hit ?: return false
        s.lastSeen = now
        lastActivityMs = now
        true
    }

    fun logout(token: String) = synchronized(lock) {
        val given = token.toByteArray(Charsets.UTF_8)
        sessions.removeAll { constantTimeEquals(given, it.token) }
        Unit
    }

    fun revokeAll() = synchronized(lock) { sessions.clear() }

    fun sessionCount(): Int = synchronized(lock) { purgeExpired(clock()); sessions.size }

    private fun addSession(): String {
        val now = clock()
        purgeExpired(now)
        while (sessions.size >= MAX_SESSIONS) sessions.removeAt(0)
        val bytes = ByteArray(32).also { random.nextBytes(it) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        sessions += Session(token.toByteArray(Charsets.UTF_8), now, now)
        return token
    }

    private fun purgeExpired(now: Long) {
        sessions.removeAll { now - it.lastSeen >= IDLE_MS || now - it.createdAt >= MAX_AGE_MS }
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray) = MessageDigest.isEqual(a, b)
}
