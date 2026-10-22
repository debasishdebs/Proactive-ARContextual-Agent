package com.rokid.context.phone.canvas.server

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URL
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Real AndroidKeyStore, real handshakes (S36): a fresh [KeystoreTlsIdentity] under a test alias (deleted afterwards) serves a loopback
 * [CanvasServer] over TLS 1.2 and TLS 1.3. The client trusts, and verifies the host name of, exactly the pinned certificate.
 * Run on the phone (Task 14); Robolectric has no AndroidKeyStore. Nothing here prints a key, a certificate or a fingerprint.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreTlsDeviceTest {
    private val alias = "pc_canvas_tls_devtest"
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private var identity: KeystoreTlsIdentity? = null
    private var server: CanvasServer? = null
    private var port = 0

    @After fun cleanUp() {
        runCatching { server?.stop() }
        runCatching { identity?.delete() }
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) }
    }

    private fun serve(): KeystoreTlsIdentity {
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) } // a leftover of an earlier failed run
        val id = KeystoreTlsIdentity(context, alias).also { identity = it }
        val auth = CanvasAuth(System::currentTimeMillis)
        val hosts = HostPolicy(port = { port }, lanIp = { null }, secure = { true })
        val router = CanvasRouter(auth, hosts, { HttpResponse.json(200, buildJsonObject { put("ok", true) }) }, { if (it == "index.html") "hi".toByteArray() else null })
        server = CanvasServer(router, true, id)
        port = server!!.start()
        return id
    }

    /** Accepts exactly the pinned certificate, whatever its name or issuer. */
    private fun pinned(id: TlsIdentity) = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = throw java.security.cert.CertificateException("no client auth")
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {
            if (chain.isNullOrEmpty() || !id.matches(chain[0])) throw java.security.cert.CertificateException("not the pinned certificate")
        }
        override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
    }

    private fun get(id: TlsIdentity, protocol: String): Int {
        val ctx = SSLContext.getInstance(protocol).apply { init(null, arrayOf(pinned(id)), null) }
        val c = URL("https://127.0.0.1:$port/").openConnection() as HttpsURLConnection
        c.sslSocketFactory = ctx.socketFactory
        c.hostnameVerifier = HostnameVerifier { _, session -> id.matches(session.peerCertificates[0] as X509Certificate) }
        c.connectTimeout = 5000; c.readTimeout = 5000
        return try { c.responseCode } finally { c.disconnect() }
    }

    @Test fun tls13AndTls12HandshakesSucceedAgainstTheKeystoreKey() {
        val id = serve()
        assertEquals(200, get(id, "TLSv1.3"))
        assertEquals(200, get(id, "TLSv1.2"))
    }

    @Test fun negotiatedProtocolIsTheOneAskedFor() {
        val id = serve()
        for (p in listOf("TLSv1.3", "TLSv1.2")) {
            val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(pinned(id)), null) }
            (ctx.socketFactory.createSocket("127.0.0.1", port) as SSLSocket).use {
                it.enabledProtocols = arrayOf(p); it.soTimeout = 5000; it.startHandshake()
                assertEquals(p, it.session.protocol)
            }
        }
    }

    @Test fun oldProtocolsAreRefused() {
        serve()
        for (p in listOf("TLSv1.1", "TLSv1")) {
            val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(object : X509TrustManager {
                override fun checkClientTrusted(c: Array<X509Certificate>?, a: String?) {}
                override fun checkServerTrusted(c: Array<X509Certificate>?, a: String?) {}
                override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
            }), null) }
            val ok = runCatching {
                (ctx.socketFactory.createSocket("127.0.0.1", port) as SSLSocket).use { it.enabledProtocols = arrayOf(p); it.soTimeout = 5000; it.startHandshake() }
            }.isSuccess
            assertFalse("handshake with $p must fail", ok)
        }
    }

    @Test fun anotherCertificateIsNotAccepted() {
        val id = serve()
        // a second identity under another alias is not "ours": the pinned client must refuse the first server when pinned to it
        val other = KeystoreTlsIdentity(context, alias + "_other")
        try {
            assertTrue(runCatching { get(other, "TLSv1.3") }.isFailure)
            assertEquals(200, get(id, "TLSv1.3"))
        } finally { other.delete() }
    }

    @Test fun keyIsNotExportableAndRotationChangesTheCertificate() {
        val id = serve()
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertNull("the private key must not be exportable", (ks.getKey(alias, null) as PrivateKey).encoded)
        val before = id.fingerprint
        id.rotate()
        assertNotEquals(before, id.fingerprint)
        // a restarted server serves the new certificate under the same identity object
        server!!.stop()
        val router = CanvasRouter(CanvasAuth(System::currentTimeMillis), HostPolicy({ port }, { null }, { true }), { HttpResponse.error(404, "not_found", "x") }, { null })
        server = CanvasServer(router, true, id)
        port = server!!.start()
        assertEquals(404, get(id, "TLSv1.3"))
    }
}
