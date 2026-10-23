package com.rokid.context.phone.canvas.server

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** The browser's bearer sessions; pairing codes and their guess limits are the extracted [PairingLimiter]. Secrets are only ever compared in constant time. */
class CanvasAuth(private val clock: () -> Long, private val random: SecureRandom = SecureRandom()) {
    companion object {
        const val CODE_TTL_MS = PairingLimiter.CODE_TTL_MS
        const val MAX_TRIES = PairingLimiter.MAX_TRIES
        const val GLOBAL_MAX_WRONG = PairingLimiter.GLOBAL_MAX_WRONG
        const val MAX_TRACKED_IPS = PairingLimiter.MAX_TRACKED_IPS
        const val LOCK_MS = PairingLimiter.LOCK_MS
        val LOCK_STEPS_MS = PairingLimiter.LOCK_STEPS_MS
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
    private val limiter = PairingLimiter(clock, random)
    private val sessions = ArrayList<Session>() // oldest first

    /** The newest successful [check] (the service's idle rule); starts at construction time. */
    @Volatile var lastActivityMs: Long = clock()
        private set

    /** Mints a code (see [PairingLimiter.newCode]). */
    fun newCode(): Code = limiter.newCode().let { Code(it.code, it.expiresAt) }

    /** True after the global lock until [newCode] is called. */
    fun awaitingNewCode(): Boolean = limiter.awaitingNewCode()

    /** Wrong pairing guesses, from any device, since the current code was minted. */
    fun wrongAttempts(): Int = limiter.wrongAttempts()

    internal fun trackedIps(): Int = limiter.trackedIps()

    /** Seconds until pairing opens again for [ip] (the longer of the global lock and its own), rounded up; 0 when not locked. */
    fun retryAfterSeconds(ip: String? = null): Long = limiter.retryAfterSeconds(ip)

    fun currentCode(): Code? = limiter.currentCode()?.let { Code(it.code, it.expiresAt) }

    /** One pairing guess; the rules (per device lock, global back-stop, single use) are [PairingLimiter]'s. A good guess opens a bearer session. */
    fun pair(code: String, ip: String? = null): Pair = when (val r = limiter.attempt(code, ip)) {
        PairingLimiter.Result.Ok -> Pair.Ok(newWebViewToken())
        is PairingLimiter.Result.Wrong -> Pair.Wrong(r.triesLeft)
        is PairingLimiter.Result.Locked -> Pair.Locked(r.untilMs)
        PairingLimiter.Result.NoCode -> Pair.NoCode
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
