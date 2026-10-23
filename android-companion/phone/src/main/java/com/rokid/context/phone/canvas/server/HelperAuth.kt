package com.rokid.context.phone.canvas.server

import com.rokid.context.canvas.helper.CanvasLogSink
import com.rokid.context.canvas.helper.HelperProtocol
import com.rokid.context.phone.canvas.helper.HelperGrants
import com.rokid.context.phone.canvas.helper.helperLine
import com.rokid.context.phone.log.PhoneLogging
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The laptop helper's identity (3b WP2): ONE helper at a time, paired by a code that only a native tap on the phone opens ([acceptPairing]); there is no HTTP route
 * that accepts a pairing. The bearer token is memory-only (never persisted: a restart forgets it and the user pairs again) and is a different secret, with a
 * different limiter, from every browser session ([CanvasAuth]); [principalFor] keeps each token on its own routes. Secrets are only ever compared in constant time.
 *
 * [sessionGen] counts pairings in this process (1 for the first). [grantOn] says whether a grant is on, for the `caps` the pair answer reports (default: none).
 */
class HelperAuth(
    private val clock: () -> Long,
    private val random: SecureRandom = SecureRandom(),
    private val log: CanvasLogSink = PhoneLogging.canvasPort(),
    private val grantOn: (String) -> Boolean = { false },
) {
    data class Session(val helperId: String, val name: String, val sessionGen: Long, val pairedAtMs: Long)

    sealed interface PairResult {
        data class Ok(val token: String, val session: Session) : PairResult
        data class Wrong(val triesLeft: Int) : PairResult
        data class Locked(val untilMs: Long) : PairResult
        data object NoCode : PairResult
    }

    private val lock = Any()
    private val limiter = PairingLimiter(clock, random, codeTtlMs = HelperProtocol.PAIR_CODE_TTL_MS, lock = lock)
    private var token: ByteArray? = null
    private var session: Session? = null
    private var gen = 0L

    /** Test seam: runs right after a good code was consumed and before its token exists. */
    internal var afterCodeConsumed: () -> Unit = {}

    /** Native only (the card's "Accept pairing"): opens a pairing window of `pairCodeTtlMs` with a fresh code. */
    fun acceptPairing(): PairingLimiter.Code = synchronized(lock) { limiter.newCode() }

    fun pairingCode(): PairingLimiter.Code? = limiter.currentCode()

    fun retryAfterSeconds(ip: String? = null): Long = limiter.retryAfterSeconds(ip)

    /** The `caps` of the pair answer: each known grant and whether it is on right now. */
    fun caps(): Map<String, Boolean> = HelperGrants.KNOWN.associateWith { grantOn(it) }

    /**
     * One pairing guess ([name] was checked by the route). A good one replaces any earlier helper's token. The whole attempt, from the code check to the token, is one
     * step under the same lock as [acceptPairing] and [revoke] (the limiter shares it), so a disconnect can never complete between the code being consumed and the
     * token being installed: it waits, then revokes that token.
     */
    fun pair(code: String, name: String, ip: String?): PairResult = synchronized(lock) {
        when (val r = limiter.attempt(code, ip)) {
            PairingLimiter.Result.Ok -> {
                afterCodeConsumed()
                val bytes = ByteArray(32).also { random.nextBytes(it) }
                val t = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
                val s = Session(helperId = hex(ByteArray(8).also { random.nextBytes(it) }), name = name, sessionGen = ++gen, pairedAtMs = clock())
                token = t.toByteArray(Charsets.UTF_8)
                session = s
                log.helperLine("helper pair ok")
                PairResult.Ok(t, s)
            }
            is PairingLimiter.Result.Wrong -> { log.helperLine("helper pair wrong"); PairResult.Wrong(r.triesLeft) }
            is PairingLimiter.Result.Locked -> { log.helperLine("helper pair locked"); PairResult.Locked(r.untilMs) }
            PairingLimiter.Result.NoCode -> { log.helperLine("helper pair nocode"); PairResult.NoCode }
        }
    }

    fun check(token: String?): Boolean = synchronized(lock) {
        val mine = this.token ?: return false
        if (token.isNullOrEmpty()) return false
        MessageDigest.isEqual(token.toByteArray(Charsets.UTF_8), mine)
    }

    fun session(): Session? = synchronized(lock) { session }

    fun sessionGen(): Long = synchronized(lock) { gen }

    /** Disconnect: the token stops working at once and the pairing window closes. The pairing count keeps going. */
    fun revoke() {
        synchronized(lock) { token = null; session = null; limiter.closeCode() }
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
