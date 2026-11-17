package com.rokid.context.phone.canvas.server

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** The browser's bearer sessions; pairing codes and their guess limits are the extracted [PairingLimiter]. Secrets are only ever compared in constant time. */
class CanvasAuth(
    private val clock: () -> Long,
    private val random: SecureRandom = SecureRandom(),
    /**
     * Developer sessions (roadmap row 7): how long a session paired NOW stays valid without any activity limit (the longest allowed is [MAX_DEV_LIFETIME_MS]); 0 = the normal rules
     * (30 min idle, 12 h). Read at every check, so switching it off or lowering it ends the longer sessions at once. Production passes `DeveloperModeStore.sessionLifetimeMs`
     * (0 unless Developer mode is on AND the user chose a lifetime).
     */
    private val devLifetimeMs: () -> Long = { 0L },
    /** Where the longer sessions' DIGESTS survive a process restart (never the token). Null = in memory only. */
    private val vault: SessionVault? = null,
) {
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
        /** The longest a developer session may live, whatever was stored or asked for: 7 days. */
        const val MAX_DEV_LIFETIME_MS = 7 * 24 * 3_600_000L
    }

    /** A longer session as stored: the SHA-256 of its token (hex), when it was paired and the lifetime it was granted. */
    data class StoredSession(val digestHex: String, val createdAt: Long, val lifeMs: Long)

    /** Persistence of the longer sessions' digests ([StoredSession]); implementations must never log or expose them. */
    interface SessionVault {
        fun read(): List<StoredSession>
        fun write(sessions: List<StoredSession>)
    }

    data class Code(val code: String, val expiresAt: Long)

    sealed interface Pair {
        data class Ok(val token: String) : Pair
        data class Wrong(val triesLeft: Int) : Pair
        data class Locked(val untilMs: Long) : Pair
        data object NoCode : Pair
    }

    /** Only the SHA-256 digest of the token is kept (a stored or in-memory session never holds the token itself); [lifeMs] > 0 = a developer session. */
    private class Session(val digest: ByteArray, val createdAt: Long, var lastSeen: Long, val lifeMs: Long = 0L)

    private val lock = Any()
    private val limiter = PairingLimiter(clock, random, lock = lock)
    private val sessions = ArrayList<Session>() // oldest first

    init {
        // developer sessions of an earlier process: only well-formed, unexpired ones within the cap come back
        try {
            val now = clock()
            for (st in vault?.read().orEmpty().take(MAX_SESSIONS)) {
                val digest = fromHex(st.digestHex) ?: continue
                if (st.lifeMs !in 1..MAX_DEV_LIFETIME_MS || st.createdAt > now) continue
                sessions += Session(digest, st.createdAt, st.createdAt, st.lifeMs)
            }
            purgeExpired(now)
        } catch (_: Exception) { /* an unreadable vault means no longer sessions */ }
    }

    /** The newest successful [check] (the service's idle rule); starts at construction time. */
    @Volatile var lastActivityMs: Long = clock()
        private set

    /** Test seam: runs right after a good code was consumed and before its session exists. */
    internal var afterCodeConsumed: () -> Unit = {}

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
    fun pair(code: String, ip: String? = null): Pair = synchronized(lock) { // one lock across the check and the session, as in v0.9.1: revokeAll() waits for it
        when (val r = limiter.attempt(code, ip)) {
            PairingLimiter.Result.Ok -> { afterCodeConsumed(); Pair.Ok(addSession(devLifetimeMs().coerceIn(0L, MAX_DEV_LIFETIME_MS))) }
            is PairingLimiter.Result.Wrong -> Pair.Wrong(r.triesLeft)
            is PairingLimiter.Result.Locked -> Pair.Locked(r.untilMs)
            PairingLimiter.Result.NoCode -> Pair.NoCode
        }
    }

    fun newWebViewToken(): String = synchronized(lock) { addSession() }

    fun check(token: String?): Boolean = synchronized(lock) {
        if (token.isNullOrEmpty()) return false
        val now = clock()
        purgeExpired(now)
        val given = digestOf(token)
        var hit: Session? = null
        for (s in sessions) { // no early exit: every session is compared
            if (constantTimeEquals(given, s.digest)) hit = s
        }
        val s = hit ?: return false
        s.lastSeen = now
        lastActivityMs = now
        true
    }

    fun logout(token: String) = synchronized(lock) {
        val given = digestOf(token)
        val had = sessions.any { it.lifeMs > 0 }
        sessions.removeAll { constantTimeEquals(given, it.digest) }
        if (had) persist()
        Unit
    }

    /** Ends every session, the longer ones included (their stored digests are cleared too). */
    fun revokeAll() = synchronized(lock) { val had = sessions.any { it.lifeMs > 0 }; sessions.clear(); if (had || vault != null) persist() }

    fun sessionCount(): Int = synchronized(lock) { purgeExpired(clock()); sessions.size }

    private fun addSession(lifeMs: Long = 0L): String {
        val now = clock()
        purgeExpired(now)
        while (sessions.size >= MAX_SESSIONS) sessions.removeAt(0)
        val bytes = ByteArray(32).also { random.nextBytes(it) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        sessions += Session(digestOf(token), now, now, lifeMs)
        if (lifeMs > 0 || vault != null) persist()
        return token
    }

    private fun purgeExpired(now: Long) {
        val dev = devLifetimeMs().coerceIn(0L, MAX_DEV_LIFETIME_MS)
        val before = sessions.count { it.lifeMs > 0 }
        sessions.removeAll {
            if (it.lifeMs > 0) now - it.createdAt >= minOf(it.lifeMs, dev) || dev == 0L || now < it.createdAt // the setting off or lowered ends them; so does a clock that went back
            else now - it.lastSeen >= IDLE_MS || now - it.createdAt >= MAX_AGE_MS
        }
        if (sessions.count { it.lifeMs > 0 } != before) persist()
    }

    private fun persist() {
        val v = vault ?: return
        try { v.write(sessions.filter { it.lifeMs > 0 }.map { StoredSession(toHex(it.digest), it.createdAt, it.lifeMs) }) } catch (_: Exception) { /* the session still works in memory; it just will not outlive the process */ }
    }

    private fun digestOf(token: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
    private fun toHex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun fromHex(h: String): ByteArray? = if (h.length != 64 || !h.all { it in '0'..'9' || it in 'a'..'f' }) null else ByteArray(32) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray) = MessageDigest.isEqual(a, b)
}
