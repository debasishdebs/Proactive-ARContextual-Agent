package com.rokid.context.phone.canvas

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.canvas.Pipeline
import com.rokid.context.canvas.CanvasPublisher
import com.rokid.context.phone.canvas.server.ApiHandler
import com.rokid.context.phone.canvas.server.AssetSource
import com.rokid.context.phone.canvas.server.CanvasAuth
import com.rokid.context.phone.canvas.server.CanvasServer
import com.rokid.context.phone.canvas.server.HttpResponse
import com.rokid.context.phone.canvas.server.PkcsTlsIdentity
import com.rokid.context.phone.canvas.server.TlsIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class CanvasGraphTlsTest {
    private val core = object : CanvasCore {
        override val api = ApiHandler { HttpResponse.error(404, "not_found", "x") }
        override suspend fun start() {}
        override fun runActive() = false
        override suspend fun cancelActive() {}
        override suspend fun home() = HomeData(emptyList(), emptyList(), false)
        override suspend fun rollback(expectedId: Long): CanvasPublisher.Outcome = CanvasPublisher.Outcome.Blocked(emptyList())
        override suspend fun pipeline(id: String): Pipeline? = null
    }
    private var now = 1_000L
    private val auth = CanvasAuth({ now })
    private val tls = PkcsTlsIdentity.load()
    private var pruned = 0
    private var servers = 0
    private val graphs = mutableListOf<CanvasGraph>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    /** [counting] swaps in a server factory that counts starts; the prune test leaves the production factory alone. */
    private fun graph(tlsFactory: () -> TlsIdentity? = { tls }, counting: Boolean = true): CanvasGraph =
        if (counting) CanvasGraph(
            auth, core, AssetSource { null }, MutableStateFlow(true), wifiIp = { "192.168.1.20" },
            scope = scope, clock = { now }, tlsFactory = tlsFactory, prune = { pruned++ },
            newServer = { r, lan, t -> servers++; CanvasServer(r, lan, t) },
        ).also { graphs += it }
        else CanvasGraph(
            auth, core, AssetSource { null }, MutableStateFlow(true), wifiIp = { "192.168.1.20" },
            scope = scope, clock = { now }, tlsFactory = tlsFactory, prune = { pruned++ },
        ).also { graphs += it }

    /** Rotation that fails the way a keystore error would. */
    private class FailingRotation(private val d: TlsIdentity) : TlsIdentity by d {
        override fun rotate() { throw IllegalStateException("keystore said no") }
    }

    @After fun tearDown() { graphs.forEach { runCatching { it.stop() } } }

    @Test fun lanUnavailableWithoutTls() {
        val g = graph(tlsFactory = { null })
        assertFalse(g.status.value.lanAvailable)
        g.setLan(true)
        assertFalse(g.status.value.lan)
        assertFalse("no plain-HTTP LAN fallback", g.status.value.on)
        g.start(true)
        assertFalse(g.status.value.on)
        assertNull(g.status.value.fingerprint)
    }

    @Test fun lanServesHttpsWithTheFingerprint() {
        val g = graph()
        g.setLan(true)
        val s = g.status.value
        assertTrue(s.on && s.lan)
        assertEquals(tls.fingerprint, s.fingerprint)
        assertEquals("https://192.168.1.20:${s.port}", s.lanUrl)
        assertEquals("https://127.0.0.1:${s.port}", s.localUrl) // loopback is https too while LAN is on
        tls.client().newCall(Request.Builder().url("https://127.0.0.1:${s.port}/").build()).execute().use { assertEquals(404, it.code) }
        g.setLan(false)
        assertEquals("http://127.0.0.1:${g.status.value.port}", g.status.value.localUrl)
        assertNull(g.status.value.lanUrl)
    }

    @Test fun rotateRevokesRestartsLanAndNeverTouchesOffState() {
        val g = graph()
        g.setLan(true)
        val before = servers
        auth.newWebViewToken()
        g.rotateCertificate()
        assertEquals(1, tls.rotations)
        assertEquals(0, auth.sessionCount())
        assertEquals("LAN restarted", before + 1, servers)
        assertTrue(g.status.value.on && g.status.value.lan)
        tls.client().newCall(Request.Builder().url("https://127.0.0.1:${g.status.value.port}/").build()).execute().use { assertEquals(404, it.code) }
        g.setLan(false)
        val b2 = servers
        g.rotateCertificate()
        assertEquals(2, tls.rotations)
        assertEquals("loopback is not restarted", b2, servers)
    }

    @Test fun lockoutNeedsAnExplicitNewCode() {
        val g = graph()
        g.start(false)
        val code = g.newPairingCode().code
        val wrong = if (code == "000000") "000001" else "000000"
        repeat(CanvasAuth.GLOBAL_MAX_WRONG) { auth.pair(wrong, "10.0.0.${it / CanvasAuth.MAX_TRIES}") } // the global back-stop
        assertNull(g.pairingCode())
        assertTrue(g.status.value.pairLocked)
        assertNull(g.status.value.code)
        assertNotNull(g.newPairingCode())
        assertFalse(g.status.value.pairLocked)
        assertNotNull(g.status.value.code)
    }

    @Test fun pruneAtInitAndOnServerStart() {
        val g = graph(counting = false) // the production newServer: its onStart hook must reach prune()
        assertEquals("one background prune at graph init", 1, pruned)
        g.start(false)
        assertEquals("and one on the server start", 2, pruned)
    }

    @Test fun aFailedRotationIsFailureSafe() {
        val g = graph({ FailingRotation(tls) })
        g.setLan(true)
        assertTrue(g.status.value.lan)
        auth.newWebViewToken()
        g.rotateCertificate() // must not throw
        val st = g.status.value
        assertEquals("every session was revoked", 0, auth.sessionCount())
        assertFalse("LAN is stopped", st.lan)
        assertNull(st.fingerprint)
        assertEquals("Secure LAN isn't available on this phone", st.notice)
        assertFalse(st.lanAvailable)
        assertTrue("loopback stays up over plain http", st.on && st.localUrl!!.startsWith("http://"))
    }

    @Test fun aKeystoreFailureIsNotRememberedForTheWholeProcess() {
        var attempts = 0
        val g = graph({ if (attempts++ == 0) throw IllegalStateException("transient") else tls })
        assertFalse(g.status.value.lanAvailable)
        g.setLan(true)
        assertFalse(g.status.value.lan)
        g.refreshNetwork() // too soon: no retry storm
        assertEquals(1, attempts)
        now += 31_000L
        g.refreshNetwork()
        assertEquals(2, attempts)
        assertTrue(g.status.value.lanAvailable)
        g.setLan(true)
        assertTrue(g.status.value.lan)
    }

    @Test fun endingAWebViewSessionRevokesOnlyThatToken() {
        val g = graph()
        val old = g.webViewToken()
        val other = g.webViewToken()
        g.endWebViewSession(old)
        assertFalse(auth.check(old))
        assertTrue(auth.check(other))
    }

    @Test fun wrongAttemptsReachTheStatus() {
        val g = graph()
        g.start(false)
        val code = g.newPairingCode().code
        val wrong = if (code == "000000") "000001" else "000000"
        auth.pair(wrong, "192.168.1.50"); auth.pair(wrong, "192.168.1.50")
        g.refreshNetwork()
        assertEquals(2, g.status.value.wrongAttempts)
    }
}
