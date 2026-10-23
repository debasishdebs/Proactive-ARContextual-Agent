package com.rokid.context.phone.canvas.server

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * One pairing code and the guess limits around it, extracted from [CanvasAuth] so the browser and the laptop helper each own an independent instance
 * (3b WP2: one rule, two principals; a lockout of one never touches the other). Secrets are only ever compared in constant time. Not a session store.
 *
 * Pairing is limited per remote device (`ip`): MAX_TRIES wrong guesses lock that device for 30 s, 120 s, then 600 s each time (a good pairing from it resets
 * that). One noisy device therefore cannot lock the user's own laptop out. Across all devices GLOBAL_MAX_WRONG wrong guesses against one code void it and lock
 * everyone (same steps, own count), until [newCode]. 5 per device and 20 in all bound the chance of guessing a 6-digit code at about 2e-5 per code.
 */
class PairingLimiter(
    private val clock: () -> Long,
    private val random: SecureRandom = SecureRandom(),
    private val codeTtlMs: Long = CODE_TTL_MS,
) {
    companion object {
        const val CODE_TTL_MS = 5 * 60_000L
        const val MAX_TRIES = 5
        const val GLOBAL_MAX_WRONG = 20
        const val MAX_TRACKED_IPS = 64
        const val LOCK_MS = 30_000L
        /** Lockout lengths per process: each lockout takes the next step, then the last one for good; a successful pair starts over. */
        val LOCK_STEPS_MS = longArrayOf(LOCK_MS, 120_000L, 600_000L)
        private const val NO_IP = "?"
    }

    data class Code(val code: String, val expiresAt: Long)

    sealed interface Result {
        data object Ok : Result
        data class Wrong(val triesLeft: Int) : Result
        data class Locked(val untilMs: Long) : Result
        data object NoCode : Result
    }

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

    /**
     * Mints a code. The tap is the rate limiter, so it clears every current lock (global and per device) but keeps the escalation
     * counts: a device gets MAX_TRIES guesses per tap and never more, and the next lock it earns is still the next, longer step.
     */
    fun newCode(): Code = synchronized(lock) {
        val c = Code("%06d".format(random.nextInt(1_000_000)), clock() + codeTtlMs)
        code = c
        wrongTotal = 0
        lockedUntil = 0L
        awaitingNew = false
        for (st in ips.values) { st.tries = 0; st.lockedUntil = 0L }
        c
    }

    /** Closes the pairing window without a pairing (a Disconnect). Keeps the escalation counts. */
    fun closeCode() = synchronized(lock) { code = null }

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

    /** One guess from [ip]. [Result.Ok] consumes the code (single use). */
    fun attempt(code: String, ip: String? = null): Result = synchronized(lock) {
        val now = clock()
        if (now < lockedUntil) return Result.Locked(lockedUntil)
        val st = stateFor(ip, now)
        if (now < st.lockedUntil) return Result.Locked(st.lockedUntil)
        val current = liveCode() ?: return Result.NoCode
        if (!constantTimeEquals(code.toByteArray(Charsets.UTF_8), current.code.toByteArray(Charsets.UTF_8))) {
            st.tries++
            wrongTotal++
            if (wrongTotal >= GLOBAL_MAX_WRONG) {
                this.code = null
                lockedUntil = now + LOCK_STEPS_MS[minOf(lockouts, LOCK_STEPS_MS.size - 1)]
                lockouts++
                awaitingNew = true
                return Result.Locked(lockedUntil)
            }
            if (st.tries >= MAX_TRIES) {
                st.tries = 0
                st.lockedUntil = now + LOCK_STEPS_MS[minOf(st.lockouts, LOCK_STEPS_MS.size - 1)]
                st.lockouts++
                return Result.Locked(st.lockedUntil)
            }
            return Result.Wrong(MAX_TRIES - st.tries)
        }
        this.code = null
        wrongTotal = 0
        st.tries = 0
        st.lockouts = 0
        lockouts = 0
        Result.Ok
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray) = MessageDigest.isEqual(a, b)
}
