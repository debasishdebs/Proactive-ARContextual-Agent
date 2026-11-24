package com.rokid.context.ai.resolve

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.Capability
import com.rokid.context.ai.Endpoint
import com.rokid.context.ai.EndpointKind
import com.rokid.context.ai.EndpointReaches
import com.rokid.context.ai.Http
import com.rokid.context.ai.OfflineOnlyNet
import com.rokid.context.ai.OfflineOnlyRefusedException
import com.rokid.context.core.net.EndpointReach
import com.rokid.context.core.net.ModelPrivacy
import okhttp3.Address
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.ConnectionSpec
import okhttp3.Connection
import okhttp3.Dns
import okhttp3.Handshake
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/** The connection half of Offline only: no system proxy, a direct own-network route, and no old pooled connection. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class OfflineOnlyNetTest {
    private val address = Address("box.local", 11434, Dns.SYSTEM, SocketFactory.getDefault(), null, null, null, Authenticator.NONE, null, listOf(Protocol.HTTP_1_1), listOf(ConnectionSpec.CLEARTEXT), object : ProxySelector() {
        override fun select(uri: URI?) = listOf(Proxy.NO_PROXY); override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: java.io.IOException?) {}
    })
    private fun route(ip: String, proxy: Proxy = Proxy.NO_PROXY, port: Int = 11434) = Route(address, proxy, InetSocketAddress(InetAddress.getByName(ip), port))
    private fun refused(m: ModelPrivacy, r: Route?) = runCatching { OfflineOnlyNet.check(m, r) }.exceptionOrNull() is OfflineOnlyRefusedException

    // ---- the pure rule ----

    @Test fun aDirectRouteToAnOwnNetworkAddressPassesAndAnythingElseIsRefused() {
        for (ip in listOf("192.168.1.5", "10.0.0.2", "127.0.0.1", "fd12::1", "fe80::1")) assertFalse(ip, refused(ModelPrivacy.OFFLINE_ONLY, route(ip)))
        for (ip in listOf("8.8.8.8", "93.184.216.34", "2606:4700::1111")) assertTrue(ip, refused(ModelPrivacy.OFFLINE_ONLY, route(ip))) // a name that resolved publicly (e.g. a pooled connection opened under Automatic)
    }

    @Test fun aProxyRouteIsRefusedEvenWhenTheProxyItselfIsOnTheLanAndEvenWhenTheTargetIsLocal() {
        val http = Proxy(Proxy.Type.HTTP, InetSocketAddress("203.0.113.9", 3128))
        val socks = Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("proxy.example", 1080))
        assertTrue(refused(ModelPrivacy.OFFLINE_ONLY, route("203.0.113.9", http, 3128))) // the proxy at a public address, the request is for 192.168.1.5
        assertTrue(refused(ModelPrivacy.OFFLINE_ONLY, route("192.168.1.9", http)))
        assertTrue(refused(ModelPrivacy.OFFLINE_ONLY, Route(address, socks, InetSocketAddress.createUnresolved("box.local", 11434))))
    }

    /** mp-r2 P1-2: a private address behind a VPN may be a remote host: only loopback passes while one is up; Automatic never checks. */
    @Test fun whileAVpnIsUpOnlyLoopbackPassesUnderOfflineOnly() {
        fun vpnRefused(ip: String) = runCatching { OfflineOnlyNet.check(ModelPrivacy.OFFLINE_ONLY, route(ip), vpn = true) }.exceptionOrNull() is OfflineOnlyRefusedException
        for (ip in listOf("192.168.1.5", "10.0.0.2", "172.16.0.9", "fd12::1", "fe80::1", "169.254.1.1")) assertTrue(ip, vpnRefused(ip))
        for (ip in listOf("127.0.0.1", "::1")) assertFalse(ip, vpnRefused(ip))
        OfflineOnlyNet.check(ModelPrivacy.AUTO, route("192.168.1.5"), vpn = true) // Automatic: unchanged
        OfflineOnlyNet.check(ModelPrivacy.OFFLINE_ONLY, route("192.168.1.5"), vpn = false)
    }

    @Test fun theGuardAsksTheVpnProbeAndAProbeThatThrowsCountsAsVpn() {
        val before = OfflineOnlyNet.vpnProbe
        try {
            val guard = OfflineOnlyNet.Guard { ModelPrivacy.OFFLINE_ONLY }
            OfflineOnlyNet.vpnProbe = { false }
            assertEquals(200, guard.intercept(FakeChain(FakeConnection(route("192.168.1.5")))).code)
            OfflineOnlyNet.vpnProbe = { true }
            val lan = FakeChain(FakeConnection(route("192.168.1.5")))
            assertTrue(runCatching { guard.intercept(lan) }.exceptionOrNull() is OfflineOnlyRefusedException); assertEquals(0, lan.proceeded)
            assertEquals(200, guard.intercept(FakeChain(FakeConnection(route("127.0.0.1")))).code)
            OfflineOnlyNet.vpnProbe = { error("no connectivity service") }
            assertTrue(runCatching { guard.intercept(FakeChain(FakeConnection(route("192.168.1.5")))) }.exceptionOrNull() is OfflineOnlyRefusedException)
        } finally { OfflineOnlyNet.vpnProbe = before }
    }

    @Test fun anEndpointOnALanAddressIsRefusedByTheGuardWhileAVpnIsUpButALoopbackOneIsNot() {
        val before = OfflineOnlyNet.vpnProbe
        try {
            OfflineOnlyNet.vpnProbe = { true }
            fun refusedEp(url: String) = runCatching { com.rokid.context.ai.OfflineOnlyGuard.require({ ModelPrivacy.OFFLINE_ONLY }, ep(EndpointKind.OLLAMA, url)) }.exceptionOrNull() is OfflineOnlyRefusedException
            assertTrue(refusedEp("http://192.168.1.5:11434"))
            assertFalse(refusedEp("http://127.0.0.1:11434"))
            OfflineOnlyNet.vpnProbe = { false }
            assertFalse(refusedEp("http://192.168.1.5:11434"))
        } finally { OfflineOnlyNet.vpnProbe = before }
    }

    @Test fun noRouteIsRefusedAndAutoNeverChecks() {
        assertTrue(refused(ModelPrivacy.OFFLINE_ONLY, null))
        assertFalse(refused(ModelPrivacy.AUTO, route("8.8.8.8")))
        assertFalse(refused(ModelPrivacy.AUTO, null))
    }

    // ---- the interceptor with a fake connection ----

    private class FakeConnection(private val r: Route) : Connection {
        override fun route() = r; override fun socket(): Socket = Socket(); override fun handshake(): Handshake? = null; override fun protocol() = Protocol.HTTP_1_1
    }
    private class FakeChain(private val conn: Connection?) : Interceptor.Chain {
        val request = Request.Builder().url("http://box.local:11434/api").build(); var proceeded = 0
        override fun request() = request
        override fun proceed(request: Request): Response { proceeded++; return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("ok").build() }
        override fun connection() = conn
        override fun call(): Call = throw UnsupportedOperationException()
        override fun connectTimeoutMillis() = 0; override fun withConnectTimeout(timeout: Int, unit: TimeUnit) = this
        override fun readTimeoutMillis() = 0; override fun withReadTimeout(timeout: Int, unit: TimeUnit) = this
        override fun writeTimeoutMillis() = 0; override fun withWriteTimeout(timeout: Int, unit: TimeUnit) = this
    }

    @Test fun theGuardRefusesBeforeTheRequestIsWrittenAndPassesAnOwnNetworkConnection() {
        var mode = ModelPrivacy.OFFLINE_ONLY
        val guard = OfflineOnlyNet.Guard { mode }
        val bad = FakeChain(FakeConnection(route("8.8.8.8")))
        assertTrue(runCatching { guard.intercept(bad) }.exceptionOrNull() is OfflineOnlyRefusedException); assertEquals("nothing proceeded: the request was never written", 0, bad.proceeded)
        val good = FakeChain(FakeConnection(route("192.168.1.5")))
        assertEquals(200, guard.intercept(good).code); assertEquals(1, good.proceeded)
        assertTrue(runCatching { guard.intercept(FakeChain(null)) }.exceptionOrNull() is OfflineOnlyRefusedException)
        mode = ModelPrivacy.AUTO // the same connection that was refused is fine under Automatic
        assertEquals(200, guard.intercept(FakeChain(FakeConnection(route("8.8.8.8")))).code)
    }

    @Test fun aModeReaderThatThrowsRefuses() {
        assertTrue(runCatching { OfflineOnlyNet.Guard { error("prefs") }.intercept(FakeChain(FakeConnection(route("192.168.1.5", Proxy(Proxy.Type.HTTP, InetSocketAddress("1.2.3.4", 3128)))))) }.exceptionOrNull() is OfflineOnlyRefusedException)
    }

    // ---- the proxy selector ----

    @Test fun underOfflineOnlyTheSystemProxyIsNeverUsedAndUnderAutoItIs() {
        val sys = Proxy(Proxy.Type.HTTP, InetSocketAddress("203.0.113.9", 3128))
        val delegate = object : ProxySelector() { override fun select(uri: URI?) = listOf(sys); override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: java.io.IOException?) {} }
        var mode = ModelPrivacy.OFFLINE_ONLY
        val sel = OfflineOnlyNet.OfflineOnlySelector({ mode }) { delegate }
        assertEquals(listOf(Proxy.NO_PROXY), sel.select(URI("http://192.168.1.5:11434")))
        mode = ModelPrivacy.AUTO
        assertEquals(listOf(sys), sel.select(URI("https://example.com")))
        assertEquals(listOf(Proxy.NO_PROXY), OfflineOnlyNet.OfflineOnlySelector({ error("x") }) { delegate }.select(URI("https://example.com")))
    }

    @Test fun everyModelClientCarriesTheSelectorAndTheGuard() {
        val c = Http.modelClient()
        assertTrue(c.proxySelector === OfflineOnlyNet.selector)
        assertTrue(c.networkInterceptors.any { it === OfflineOnlyNet.shared })
    }

    // ---- pools ----

    @Test fun evictPoolsClosesTheIdleConnectionsOfTheModelClients() {
        val server = MockWebServer().apply { enqueue(MockResponse().setBody("ok")); start() }
        try {
            val client = Http.modelClient()
            client.newCall(Request.Builder().url(server.url("/")).build()).execute().use { it.body?.string() }
            assertEquals(1, client.connectionPool.connectionCount())
            OfflineOnlyNet.evictPools()
            assertEquals(0, client.connectionPool.connectionCount())
        } finally { server.shutdown() }
    }

    // ---- photoReach (P2-b) ----

    private fun ep(kind: EndpointKind, url: String, vararg caps: Capability) = Endpoint("e", "e", kind, url, null, listOf("m"), caps.toSet(), 1)

    @Test fun aRemotePrimaryWithAnOnDeviceOrLanTextFallbackIsStillRemoteForPhotos() {
        val remoteVision = ep(EndpointKind.OLLAMA, "https://example.com:1", Capability.CHAT, Capability.VISION)
        val deviceText = ep(EndpointKind.LLAMACPP_LOCAL, "model.gguf", Capability.CHAT)
        val lanTextOnly = ep(EndpointKind.OLLAMA, "http://box.local:11434", Capability.CHAT)
        assertEquals(EndpointReach.REMOTE, EndpointReaches.photoReach(listOf(remoteVision, deviceText)))
        assertEquals(EndpointReach.REMOTE, EndpointReaches.photoReach(listOf(remoteVision, lanTextOnly)))
    }

    @Test fun aFallbackThatCanTakeThePhotoCountsAndTheBestCapableOneWins() {
        val remoteVision = ep(EndpointKind.OLLAMA, "https://example.com:1", Capability.CHAT, Capability.VISION)
        val lanVision = ep(EndpointKind.OLLAMA, "http://box.local:11434", Capability.CHAT, Capability.VISION)
        assertEquals(EndpointReach.LAN, EndpointReaches.photoReach(listOf(remoteVision, lanVision)))
        assertEquals(EndpointReach.LAN, EndpointReaches.photoReach(listOf(lanVision)))
    }

    @Test fun whenNoneCanTakeThePhotoThePrimarysReachDecidesAsBeforeAndNothingIsRemote() {
        val lanText = ep(EndpointKind.OLLAMA, "http://box.local:11434", Capability.CHAT)
        assertEquals(EndpointReach.LAN, EndpointReaches.photoReach(listOf(lanText)))
        assertEquals(EndpointReach.REMOTE, EndpointReaches.photoReach(emptyList()))
    }
}
