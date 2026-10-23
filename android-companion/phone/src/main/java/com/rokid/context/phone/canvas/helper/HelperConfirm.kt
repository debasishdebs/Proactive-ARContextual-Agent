package com.rokid.context.phone.canvas.helper

import com.rokid.context.canvas.helper.CanvasLogSink
import com.rokid.context.memory.canvas.ConfirmBinding
import com.rokid.context.phone.log.PhoneLogging
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The per-run confirmation binding (user decision 2026-10-23 evening): HMAC-SHA256 over `jobId | bundleHash | sessionGen | expiresAtMs`
 * ([ConfirmBinding.message]), so one native tap binds one job, one immutable payload hash, one session and one expiry.
 *
 * The key is made when this object is made (the dev session start) and by [rotate]; it lives in this object's memory only: never persisted, never in a body, a header,
 * a log line or a backup, and no member hands it out. The browser and the helper principals have no route to this class. The orchestration service never sees it:
 * the `:phone` adapter (WP6) mints and verifies, then calls the job port's `confirm`.
 */
class HelperConfirm private constructor(initialKey: ByteArray, private val random: SecureRandom, private val log: CanvasLogSink) {
    constructor(random: SecureRandom = SecureRandom(), log: CanvasLogSink = PhoneLogging.canvasPort()) : this(newKey(random), random, log)

    companion object {
        private const val ALG = "HmacSHA256"
        private fun newKey(random: SecureRandom) = ByteArray(32).also { random.nextBytes(it) }
        private fun isLowerHex64(s: String) = s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' }

        /** Test seam: a fixed key (the shared vectors). Production never calls it. */
        internal fun forTest(key: ByteArray, log: CanvasLogSink = PhoneLogging.canvasPort()) = HelperConfirm(key.copyOf(), SecureRandom(), log)
    }

    @Volatile private var key = SecretKeySpec(initialKey, ALG)

    private fun mac(b: ConfirmBinding): ByteArray = Mac.getInstance(ALG).run { init(key); doFinal(b.message().toByteArray(Charsets.UTF_8)) }

    /** The lower-case hex tag of [b]. */
    fun tag(b: ConfirmBinding): String = mac(b).joinToString("") { "%02x".format(it) }

    /** True only for the tag of exactly [b] made with the current key, while [nowMs] is before `b.expiresAtMs`. Constant-time compare; never throws. */
    fun verify(b: ConfirmBinding, tag: String, nowMs: Long): Boolean {
        val macOk = isLowerHex64(tag) && MessageDigest.isEqual(mac(b), ByteArray(32) { tag.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
        if (!macOk) { log.helperLine("helper confirm refused"); return false }
        if (nowMs >= b.expiresAtMs) { log.helperLine("helper confirm expired"); return false }
        log.helperLine("helper confirm verified")
        return true
    }

    /** A new session: every earlier tag stops verifying. */
    fun rotate() {
        key = SecretKeySpec(newKey(random), ALG)
        log.helperLine("helper key rotated")
    }

    override fun toString(): String = "HelperConfirm"
}
