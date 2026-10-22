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
    @Volatile private var material: TlsMaterial = loadOrCreate()

    override val fingerprint: String get() = material.fingerprint
    override fun serverSocketFactory(): SSLServerSocketFactory = material.factory
    override fun matches(cert: X509Certificate): Boolean = TlsFingerprint.sameCert(material.digest, cert)

    @Synchronized
    override fun rotate() {
        keystore().deleteEntry(alias)
        material = loadOrCreate()
    }

    /** Removes our entry (test aliases only; the app never calls it). */
    fun delete() { keystore().deleteEntry(alias) }

    private fun keystore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    @Synchronized
    private fun loadOrCreate(): TlsMaterial {
        var ks = keystore()
        if (!usable(ks)) {
            ks.deleteEntry(alias)
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).apply { initialize(keySpec(alias, clock())) }.generateKeyPair()
            ks = keystore()
        }
        val key = ks.getKey(alias, null) as PrivateKey
        val chain = ks.getCertificateChain(alias).map { it as X509Certificate }.toTypedArray()
        return TlsMaterial.build(key, chain)
    }

    /** A stored key whose certificate is valid for at least a week more; anything else is replaced. */
    private fun usable(ks: KeyStore): Boolean {
        val chain = ks.getCertificateChain(alias) ?: return false
        if (ks.getKey(alias, null) !is PrivateKey) return false
        val cert = chain.firstOrNull() as? X509Certificate ?: return false
        return try { cert.checkValidity(Date(clock() + 7 * DAY_MS)); true } catch (e: java.security.cert.CertificateException) { false }
    }

    companion object {
        const val ALIAS = "pc_canvas_tls"
        private const val PROVIDER = "AndroidKeyStore"
        private const val DAY_MS = 86_400_000L
        private const val VALID_DAYS = 825L
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

        /** The identity, or null when this phone's keystore cannot make or serve the key: LAN is then unavailable (S16). */
        fun createOrNull(context: Context): TlsIdentity? = try { KeystoreTlsIdentity(context) } catch (e: Exception) { null }
    }
}

/** What the WebView does about a certificate error: proceed only on a match with our pinned certificate. */
object SslDecision {
    fun decide(matches: Boolean): Boolean = matches
}
