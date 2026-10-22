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

    private fun graph(tlsFactory: () -> PkcsTlsIdentity? = { tls }) = CanvasGraph(
        auth, core, AssetSource { null }, MutableStateFlow(true), wifiIp = { "192.168.1.20" },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), clock = { now }, tlsFactory = tlsFactory, prune = { pruned++ },
        newServer = { r, lan, t -> servers++; CanvasServer(r, lan, t, onStart = { pruned++ }) },
    ).also { graphs += it }

    @After fun tearDown() { graphs.forEach { runCatching { it.stop() } } }

    @Test fun lanUnavailableWithoutTls() {
        val g = graph { null }
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
        repeat(CanvasAuth.MAX_TRIES) { auth.pair(wrong) }
        assertNull(g.pairingCode())
        assertTrue(g.status.value.pairLocked)
        assertNull(g.status.value.code)
        assertNotNull(g.newPairingCode())
        assertFalse(g.status.value.pairLocked)
        assertNotNull(g.status.value.code)
    }

    @Test fun pruneAtInitAndOnServerStart() {
        val g = graph()
        assertEquals("one background prune at graph init", 1, pruned)
        g.start(false)
        assertEquals("and one on the server start", 2, pruned)
    }
}
