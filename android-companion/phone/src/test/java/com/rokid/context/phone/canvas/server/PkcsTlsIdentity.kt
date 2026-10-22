package com.rokid.context.phone.canvas.server

import okhttp3.OkHttpClient
import java.io.InputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Test-only [TlsIdentity] over a throwaway PKCS12 fixture (software key). The password lives here and nowhere else.
 * Never reuse the fixtures; they are not in `src/main`.
 */
class PkcsTlsIdentity private constructor(
    private val key: PrivateKey,
    private val chain: Array<X509Certificate>,
) : TlsIdentity {
    private val material = TlsMaterial.build(key, chain)
    val certificate: X509Certificate get() = chain[0]
    var rotations = 0
        private set

    override val fingerprint: String get() = material.fingerprint
    override fun serverSocketFactory(): SSLServerSocketFactory = material.factory
    override fun matches(cert: X509Certificate): Boolean = TlsFingerprint.sameCert(material.digest, cert)
    override fun rotate() { rotations++ }

    /** An OkHttp client that trusts exactly this certificate, and uses OkHttp's default hostname verifier. */
    fun client(): OkHttpClient {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null); setCertificateEntry("pin", certificate) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
        val tm = tmf.trustManagers.single() as X509TrustManager
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        return OkHttpClient.Builder().sslSocketFactory(ctx.socketFactory, tm).callTimeout(10, TimeUnit.SECONDS).build()
    }

    companion object {
        private const val PASS = "test-only"

        fun load(resource: String = "/canvas-tls-test.p12"): PkcsTlsIdentity {
            val stream: InputStream = PkcsTlsIdentity::class.java.getResourceAsStream(resource) ?: error("missing fixture $resource")
            val ks = KeyStore.getInstance("PKCS12").apply { stream.use { load(it, PASS.toCharArray()) } }
            val alias = ks.aliases().toList().single()
            val key = ks.getKey(alias, PASS.toCharArray()) as PrivateKey
            val chain = ks.getCertificateChain(alias).map { it as X509Certificate }.toTypedArray()
            return PkcsTlsIdentity(key, chain)
        }

        fun other(): PkcsTlsIdentity = load("/canvas-tls-other.p12")
    }
}
