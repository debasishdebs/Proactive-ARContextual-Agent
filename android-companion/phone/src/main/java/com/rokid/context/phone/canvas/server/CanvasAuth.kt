package com.rokid.context.phone.canvas.server

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Pairing codes and bearer sessions. Secrets are only ever compared in constant time. */
class CanvasAuth(private val clock: () -> Long, private val random: SecureRandom = SecureRandom()) {
    companion object {
        const val CODE_TTL_MS = 5 * 60_000L
        const val MAX_TRIES = 5
        const val GLOBAL_MAX_WRONG = 20
        const val MAX_TRACKED_IPS = 64
        const val LOCK_MS = 30_000L
        /** Lockout lengths per process: each lockout takes the next step, then the last one for good; a successful pair starts over. */
        val LOCK_STEPS_MS = longArrayOf(LOCK_MS, 120_000L, 600_000L)
        const val IDLE_MS = 30 * 60_000L
        const val MAX_AGE_MS = 12 * 3_600_000L
        const val MAX_SESSIONS = 3
        private const val NO_IP = "?"
    }

    data class Code(val code: String, val expiresAt: Long)

    sealed interface Pair {
        data class Ok(val token: String) : Pair
        data class Wrong(val triesLeft: Int) : Pair
        data class Locked(val untilMs: Long) : Pair
        data object NoCode : Pair
    }

    private class Session(val token: ByteArray, val createdAt: Long, var lastSeen: Long)

    /** Per remote device: wrong guesses against the current code, and its own escalating lock. */
    private class IpState { var tries = 0; var lockedUntil = 0L; var lockouts = 0; var lastSeen = 0L }

    private val lock = Any()
    private var code: Code? = null
    /** Wrong guesses from every device since the code was minted (the global back-stop). */
    private var wrongTotal = 0
    /** The global lock (back-stop) and its own escalation count. */
    private var lockedUntil = 0L
    private var lockouts = 0
    /** Set by the global lock, cleared only by an explicit [newCode]: nothing mints a code on its own after it. */
    private var awaitingNew = false
    private val ips = LinkedHashMap<String, IpState>() // least recently seen first
    private val sessions = ArrayList<Session>() // oldest first

    /** The newest successful [check] (the service's idle rule); starts at construction time. */
    @Volatile var lastActivityMs: Long = clock()
        private set

    /**
     * Mints a code. The tap is the rate limiter, so it clears every current lock (global and per device) but keeps the escalation
     * counts: a device gets MAX_TRIES guesses per tap and never more, and the next lock it earns is still the next, longer step.
     */
    fun newCode(): Code = synchronized(lock) {
        val c = Code("%06d".format(random.nextInt(1_000_000)), clock() + CODE_TTL_MS)
        code = c
        wrongTotal = 0
        lockedUntil = 0L
        awaitingNew = false
        for (st in ips.values) { st.tries = 0; st.lockedUntil = 0L }
        c
    }

    /** True after the global lock until [newCode] is called. */
    fun awaitingNewCode(): Boolean = synchronized(lock) { awaitingNew }

    /** Wrong pairing guesses, from any device, since the current code was minted. */
    fun wrongAttempts(): Int = synchronized(lock) { wrongTotal }

    internal fun trackedIps(): Int = synchronized(lock) { ips.size }

    /** Seconds until pairing opens again for [ip] (the longer of the global lock and its own), rounded up; 0 when not locked. */
    fun retryAfterSeconds(ip: String? = null): Long = synchronized(lock) {
        val until = maxOf(lockedUntil, ips[ip ?: NO_IP]?.lockedUntil ?: 0L)
        ((until - clock() + 999) / 1000).coerceAtLeast(0)
    }

    fun currentCode(): Code? = synchronized(lock) { liveCode() }

    private fun liveCode(): Code? {
        val c = code ?: return null
        if (clock() >= c.expiresAt) { code = null; return null }
        return c
    }

    private fun stateFor(ip: String?, now: Long): IpState {
        val key = ip ?: NO_IP
        val st = ips.remove(key) ?: IpState() // re-inserted last: most recently seen
        st.lastSeen = now
        ips[key] = st
        // bounded: a device with nothing to remember goes first, then the least recently seen
        while (ips.size > MAX_TRACKED_IPS) {
            val idle = ips.entries.firstOrNull { it.key != key && it.value.tries == 0 && it.value.lockedUntil <= now && it.value.lockouts == 0 }
                ?: ips.entries.first { it.key != key }
            ips.remove(idle.key)
        }
        return st
    }

    /**
     * Pairing is limited per remote device ([ip]): MAX_TRIES wrong guesses lock that device for 30 s, 120 s, then 600 s each time
     * (a good pairing from it resets that). One noisy device therefore cannot lock the user's own laptop out. Across all devices
     * GLOBAL_MAX_WRONG wrong guesses against one code void it and lock everyone (same steps, own count), until "New code".
     * 5 per device and 20 in all bound the chance of guessing a 6-digit code at about 2e-5 per code.
     */
    fun pair(code: String, ip: String? = null): Pair = synchronized(lock) {
        val now = clock()
        if (now < lockedUntil) return Pair.Locked(lockedUntil)
        val st = stateFor(ip, now)
        if (now < st.lockedUntil) return Pair.Locked(st.lockedUntil)
        val current = liveCode() ?: return Pair.NoCode
        if (!constantTimeEquals(code.toByteArray(Charsets.UTF_8), current.code.toByteArray(Charsets.UTF_8))) {
            st.tries++
            wrongTotal++
            if (wrongTotal >= GLOBAL_MAX_WRONG) {
                this.code = null
                lockedUntil = now + LOCK_STEPS_MS[minOf(lockouts, LOCK_STEPS_MS.size - 1)]
                lockouts++
                awaitingNew = true
                return Pair.Locked(lockedUntil)
            }
            if (st.tries >= MAX_TRIES) {
                st.tries = 0
                st.lockedUntil = now + LOCK_STEPS_MS[minOf(st.lockouts, LOCK_STEPS_MS.size - 1)]
                st.lockouts++
                return Pair.Locked(st.lockedUntil)
            }
            return Pair.Wrong(MAX_TRIES - st.tries)
        }
        this.code = null
        wrongTotal = 0
        st.tries = 0
        st.lockouts = 0
        lockouts = 0
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
