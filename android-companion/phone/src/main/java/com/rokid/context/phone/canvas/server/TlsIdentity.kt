package com.rokid.context.phone.canvas.server

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateEncodingException
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.X509ExtendedKeyManager
import javax.security.auth.x500.X500Principal

/**
 * The TLS identity of the canvas server on the LAN: one self-signed certificate whose private key never leaves its holder.
 * The user compares [fingerprint] with the browser's certificate details; the app's WebView proceeds past the certificate
 * warning only when [matches] says the presented certificate is this one.
 */
interface TlsIdentity {
    /** SHA-256 of the DER certificate, uppercase hex, grouped "ABCD EF01 ..." (16 groups); the app shows the first 8 large. */
    val fingerprint: String
    fun serverSocketFactory(): SSLServerSocketFactory
    /** True when SHA-256(DER) of [cert] equals ours, compared in constant time. */
    fun matches(cert: X509Certificate): Boolean
    /** Replaces the key and certificate. The caller restarts the server and revokes all sessions. */
    fun rotate()
}

object TlsFingerprint {
    /** `ABCD EF01 ...`: uppercase hex, 4 digits per group, one space between groups. */
    fun format(der: ByteArray): String = der.joinToString("") { "%02X".format(it) }.chunked(4).joinToString(" ")

    /** What a browser's certificate viewer shows: `AB:CD:EF:...` (spaces, colons and case of [fp] are ignored). */
    fun colonPairs(fp: String): String = fp.filter { it.isLetterOrDigit() }.uppercase().chunked(2).joinToString(":")

    fun digest(cert: X509Certificate): ByteArray = MessageDigest.getInstance("SHA-256").digest(cert.encoded)

    /** Constant-time: both sides are SHA-256 digests of equal length, compared with [MessageDigest.isEqual]. */
    fun sameCert(ours: ByteArray, cert: X509Certificate): Boolean =
        try { MessageDigest.isEqual(ours, digest(cert)) } catch (e: CertificateEncodingException) { false }
}

/** What a server needs from one key and certificate chain; shared by the keystore identity and the test fixture identity. */
internal class TlsMaterial(val fingerprint: String, val factory: SSLServerSocketFactory, val digest: ByteArray) {
    companion object {
        fun build(key: PrivateKey, chain: Array<X509Certificate>): TlsMaterial {
            val digest = TlsFingerprint.digest(chain[0])
            val ctx = SSLContext.getInstance("TLS").apply { init(arrayOf(PinnedKeyManager(key, chain)), null, null) }
            return TlsMaterial(TlsFingerprint.format(digest), ctx.serverSocketFactory, digest)
        }
    }
}

/**
 * Serves exactly one key and chain. The platform key manager would offer every private key of the app's AndroidKeyStore,
 * not only ours; this one cannot.
 */
internal class PinnedKeyManager(private val key: PrivateKey, private val chain: Array<X509Certificate>) : X509ExtendedKeyManager() {
    private fun aliasFor(keyType: String?): String? = ALIAS.takeIf { keyType == null || keyType.equals("EC", true) || keyType.startsWith("EC_", true) }

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = aliasFor(keyType)?.let { arrayOf(it) }
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = aliasFor(keyType)
    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?): String? = aliasFor(keyType)
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    override fun getCertificateChain(alias: String?): Array<X509Certificate>? = if (alias == ALIAS) chain.clone() else null
    override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == ALIAS) key else null

    private companion object { const val ALIAS = "pinned" }
}

/**
 * The certificate lives in the Android Keystore: an EC P-256 key generated there (never exported, never logged), a certificate
 * the keystore signs for it (no subject alternative name: the platform cannot set one without a new dependency, so browsers also
 * warn about the name; the WebView pins by fingerprint, not by name). JSSE signs through the keystore; the key bytes are never read.
 */
class KeystoreTlsIdentity(@Suppress("UNUSED_PARAMETER") context: Context, private val alias: String = ALIAS, private val clock: () -> Long = System::currentTimeMillis) : TlsIdentity {
    private val aliases = listOf(alias, alias + ALT_SUFFIX)

    /** The alias of the key in use; "New certificate" alternates between the two so the old key outlives a failed attempt. */
    @Volatile var activeAlias: String = alias
        private set
    @Volatile private var material: TlsMaterial = loadOrCreate()

    override val fingerprint: String get() = material.fingerprint
    override fun serverSocketFactory(): SSLServerSocketFactory = material.factory
    override fun matches(cert: X509Certificate): Boolean = TlsFingerprint.sameCert(material.digest, cert)

    /**
     * Failure-safe: the new key is generated under the other alias and must load completely before anything is swapped; the old key
     * is deleted only afterwards. Any failure leaves the old key and certificate in place and is thrown to the caller.
     */
    @Synchronized
    override fun rotate() {
        val old = activeAlias
        val target = aliases.first { it != old }
        val ks = keystore()
        runCatching { ks.deleteEntry(target) } // a stale half-made entry of an earlier attempt
        try {
            generate(target)
            val fresh = materialFor(keystore(), target)
            material = fresh
            activeAlias = target
        } catch (e: Exception) {
            runCatching { keystore().deleteEntry(target) }
            throw e
        }
        runCatching { keystore().deleteEntry(old) }
    }

    /** Removes both entries (test aliases only; the app never calls it). */
    fun delete() { val ks = keystore(); aliases.forEach { runCatching { ks.deleteEntry(it) } } }

    private fun keystore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun generate(target: String) {
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).apply { initialize(keySpec(target, clock())) }.generateKeyPair()
    }

    private fun materialFor(ks: KeyStore, a: String): TlsMaterial {
        val key = ks.getKey(a, null) as PrivateKey
        val chain = ks.getCertificateChain(a).map { it as X509Certificate }.toTypedArray()
        return TlsMaterial.build(key, chain)
    }

    @Synchronized
    private fun loadOrCreate(): TlsMaterial {
        val ks = keystore()
        // after a crash between "new key made" and "old key deleted" both can exist: the newer certificate wins
        val best = aliases.filter { usable(ks, it) }.maxByOrNull { (ks.getCertificate(it) as X509Certificate).notBefore.time }
        if (best != null) {
            aliases.filter { it != best }.forEach { runCatching { ks.deleteEntry(it) } }
            activeAlias = best
            return materialFor(ks, best)
        }
        aliases.forEach { runCatching { ks.deleteEntry(it) } }
        generate(alias)
        activeAlias = alias
        return materialFor(keystore(), alias)
    }

    /** A stored key whose certificate is valid for at least a week more; anything else is replaced. */
    private fun usable(ks: KeyStore, a: String): Boolean {
        val chain = ks.getCertificateChain(a) ?: return false
        if (ks.getKey(a, null) !is PrivateKey) return false
        val cert = chain.firstOrNull() as? X509Certificate ?: return false
        return try { cert.checkValidity(Date(clock() + 7 * DAY_MS)); true } catch (e: java.security.cert.CertificateException) { false }
    }

    companion object {
        const val ALIAS = "pc_canvas_tls"
        private const val ALT_SUFFIX = "_b"
        private const val PROVIDER = "AndroidKeyStore"
        private const val DAY_MS = 86_400_000L
        /** 824 days: under the 825-day cap that Apple's TLS policy applies. */
        private const val VALID_DAYS = 824L
        private const val SUBJECT = "CN=Proactive Context canvas"

        /**
         * The key's use is signing only. TLS 1.2 and 1.3 sign through the provider with whatever digest the handshake picks, and some
         * stacks pre-hash and ask for no digest at all, so NONE is authorised along with the SHA-2 family (S36).
         */
        fun keySpec(alias: String, nowMs: Long): KeyGenParameterSpec =
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                .setCertificateSubject(X500Principal(SUBJECT))
                // A fresh serial per certificate: a browser refuses two different keys under one issuer and serial after "New certificate".
                .setCertificateSerialNumber(BigInteger(63, SecureRandom()).add(BigInteger.ONE))
                .setCertificateNotBefore(Date(nowMs - DAY_MS))
                .setCertificateNotAfter(Date(nowMs + VALID_DAYS * DAY_MS))
                .setUserAuthenticationRequired(false)
                .build()
    }
}

/** What the WebView does about a certificate error: proceed only on a match with our pinned certificate. */
object SslDecision {
    fun decide(matches: Boolean): Boolean = matches
}
