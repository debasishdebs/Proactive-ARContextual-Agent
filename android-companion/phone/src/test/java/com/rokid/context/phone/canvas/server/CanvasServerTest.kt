package com.rokid.context.phone.canvas.server

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

class CanvasServerTest {
    private val auth = CanvasAuth(clock = { System.currentTimeMillis() })
    private var port = 0
    private val hosts = HostPolicy(port = { port }, lanIp = { null })
    private val api = ApiHandler { req ->
        HttpResponse.json(200, buildJsonObject { put("method", req.method); put("path", req.path); put("q", req.query["a"] ?: ""); put("len", req.body.size) })
    }
    private val assets = AssetSource { if (it == "index.html") "<html>hi</html>".toByteArray() else null }
    private val client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
    private val servers = mutableListOf<CanvasServer>()
    private val sockets = mutableListOf<ServerSocket>()

    private fun server(lan: Boolean = false) =
        CanvasServer(CanvasRouter(auth, hosts, api, assets), lan).also { servers += it }

    /** Listens on [p] so the server sees it busy. Reuse is on so TIME_WAIT left by earlier tests cannot block the test itself. */
    private fun occupy(p: Int): ServerSocket {
        val ss = ServerSocket().also { it.reuseAddress = true; sockets += it }
        try { ss.bind(InetSocketAddress("127.0.0.1", p)) } catch (e: java.io.IOException) {
            // acceptable only when some other process is already listening there
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", p), 500) }
        }
        return ss
    }

    @After fun tearDown() {
        servers.forEach { runCatching { it.stop() } }
        sockets.forEach { runCatching { it.close() } }
    }

    /** Drives Daemon.serve() with a session that throws during request conversion (D26, S16). */
    @Test fun serveCatchPathHasSecurityHeaders() {
        val session = java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(fi.iki.elonen.NanoHTTPD.IHTTPSession::class.java),
        ) { _, _, _ -> throw IllegalStateException("boom") } as fi.iki.elonen.NanoHTTPD.IHTTPSession
        val s = server()
        val res = s.Daemon(null, 0).serve(session)
        assertEquals(500, res.status.requestStatus)
        assertEquals("nosniff", res.getHeader("X-Content-Type-Options"))
        assertEquals("no-store", res.getHeader("Cache-Control"))
        assertEquals("no-referrer", res.getHeader("Referrer-Policy"))
        assertNotNull(res.getHeader("Content-Security-Policy"))
    }

    @Test fun servesIndexAndApi() {
        val s = server()
        port = s.start()
        assertEquals(port, s.port)
        val base = "http://127.0.0.1:$port"
        client.newCall(Request.Builder().url("$base/").build()).execute().use { r ->
            assertEquals(200, r.code)
            assertEquals("<html>hi</html>", r.body!!.string())
            assertEquals("nosniff", r.header("X-Content-Type-Options"))
            assertEquals("no-store", r.header("Cache-Control"))
            assertNotNull(r.header("Content-Security-Policy"))
        }
        client.newCall(Request.Builder().url("$base/api/session").build()).execute().use { r ->
            assertEquals(401, r.code)
            assertTrue(r.body!!.string().contains("unauthorized"))
        }
        val token = auth.newWebViewToken()
        client.newCall(Request.Builder().url("$base/api/frames/x?a=7").header("Authorization", "Bearer $token").build()).execute().use { r ->
            assertEquals(200, r.code)
            val b = r.body!!.string()
            assertTrue(b, b.contains("\"path\":\"/api/frames/x\"") && b.contains("\"q\":\"7\"") && b.contains("\"method\":\"GET\""))
        }
        client.newCall(
            Request.Builder().url("$base/api/pipelines").header("Authorization", "Bearer $token")
                .post("{\"a\":1}".toRequestBody()).build(),
        ).execute().use { r ->
            assertEquals(200, r.code)
            assertTrue(r.body!!.string().contains("\"len\":7"))
        }
        // a rebinding Host is refused even on the real socket
        client.newCall(Request.Builder().url("$base/").header("Host", "evil.example:$port").build()).execute().use { r ->
            assertEquals(421, r.code)
        }
        // a foreign Origin on POST is refused
        client.newCall(
            Request.Builder().url("$base/api/pipelines").header("Authorization", "Bearer $token")
                .header("Origin", "http://evil.example").post("{}".toRequestBody()).build(),
        ).execute().use { r -> assertEquals(403, r.code) }
    }

    @Test fun oversizeBodyIs413OnTheWire() {
        val s = server()
        port = s.start()
        val token = auth.newWebViewToken()
        val big = ByteArray(CanvasServer.MAX_BODY + 1) { 'a'.code.toByte() }.toRequestBody()
        client.newCall(Request.Builder().url("http://127.0.0.1:$port/api/x").header("Authorization", "Bearer $token").post(big).build())
            .execute().use { r -> assertEquals(413, r.code) }
        val ok = ByteArray(CanvasServer.MAX_BODY) { 'a'.code.toByte() }.toRequestBody()
        client.newCall(Request.Builder().url("http://127.0.0.1:$port/api/x").header("Authorization", "Bearer $token").post(ok).build())
            .execute().use { r ->
                val b = r.body!!.string()
                assertEquals(b, 200, r.code)
                assertTrue(b.contains("\"len\":${CanvasServer.MAX_BODY}"))
            }
    }

    @Test fun fallsBackWhenPortBusy() {
        val occupied = occupy(CanvasServer.PORT)
        val s = server()
        port = s.start()
        assertTrue("port=$port", port in CanvasServer.FALLBACK)
        assertNotEquals(occupied.localPort, port)
        client.newCall(Request.Builder().url("http://127.0.0.1:$port/").build()).execute().use { assertEquals(200, it.code) }
    }

    @Test fun throwsWhenAllPortsBusy() {
        val all = listOf(CanvasServer.PORT) + CanvasServer.FALLBACK
        for (p in all) occupy(p)
        val s = server()
        val failed = runCatching { s.start() }.exceptionOrNull()
        assertTrue("$failed ${s.port}", failed is java.io.IOException)
        assertEquals(null, s.port)
    }

    @Test fun lanOffBindsLoopbackOnly() {
        val s = server(lan = false)
        port = s.start()
        assertEquals("127.0.0.1", s.bindHost)
        // and really: no other local IPv4 address of this machine accepts a connection
        val others = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .filter { it is java.net.Inet4Address && !it.isLoopbackAddress }
        for (a in others) {
            val refused = runCatching { Socket().use { it.connect(InetSocketAddress(a, port), 500) } }.isFailure
            assertTrue("reachable on ${a.hostAddress}", refused)
        }
    }

    @Test fun lanOnBindsAllInterfaces() {
        val s = server(lan = true)
        port = s.start()
        assertEquals("0.0.0.0", s.bindHost)
        client.newCall(Request.Builder().url("http://127.0.0.1:$port/").build()).execute().use { assertEquals(200, it.code) }
    }

    @Test fun stopFreesPort() {
        val s = server()
        port = s.start()
        s.stop()
        assertEquals(null, s.port)
        ServerSocket().use { it.reuseAddress = true; it.bind(InetSocketAddress("127.0.0.1", port)) }
        assertFalse(runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) } }.isSuccess)
    }

    private fun awaitTrue(ms: Long = 5000, c: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (c()) return true; Thread.sleep(25) }
        return c()
    }

    /** True when the peer closed (EOF or reset) within [ms]. */
    private fun closedByPeer(sock: Socket, ms: Int): Boolean {
        sock.soTimeout = ms
        return try { sock.getInputStream().read() == -1 } catch (e: java.net.SocketTimeoutException) { false } catch (e: java.io.IOException) { true }
    }

    @Test fun seventeenthConcurrentConnectionIsClosedAndThreadsAreReleased() {
        val logs = java.util.Collections.synchronizedList(mutableListOf<String>())
        val s = CanvasServer(CanvasRouter(auth, hosts, api, assets), false, log = { logs += it }).also { servers += it }
        port = s.start()
        val held = (1..CanvasServer.MAX_CLIENTS).map { Socket("127.0.0.1", port) }
        try {
            assertTrue(awaitTrue { s.activeClients == CanvasServer.MAX_CLIENTS })
            Socket("127.0.0.1", port).use { extra -> assertTrue("17th stayed open", closedByPeer(extra, 2000)) }
            assertEquals(CanvasServer.MAX_CLIENTS, s.activeClients)
            assertTrue(logs.toString(), logs.any { it == "CONN over-cap" })
        } finally { held.forEach { it.close() } }
        assertTrue("threads not released: ${s.activeClients}", awaitTrue { s.activeClients == 0 })
        client.newCall(Request.Builder().url("http://127.0.0.1:$port/").build()).execute().use { assertEquals(200, it.code) }
    }

    @Test fun slowDripClientIsClosedAfterTheDeadline() {
        val s = CanvasServer(CanvasRouter(auth, hosts, api, assets), false, deadlineMs = 800).also { servers += it }
        port = s.start()
        val drip = Socket("127.0.0.1", port)
        val out = drip.getOutputStream()
        out.write("GET / HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nX-A: ".toByteArray()); out.flush()
        val started = System.currentTimeMillis()
        var closed = false
        try {
            while (System.currentTimeMillis() - started < 4000 && !closed) {
                try { out.write('a'.code); out.flush() } catch (e: java.io.IOException) { closed = true }
                Thread.sleep(150)
            }
        } finally { drip.close() }
        assertTrue("slow client was not cut off", closed)
        assertTrue(System.currentTimeMillis() - started < 3500)
        assertTrue(awaitTrue { s.activeClients == 0 })
        // a normal, fast request still works and keep-alive reuse is within the deadline
        client.newCall(Request.Builder().url("http://127.0.0.1:$port/").build()).execute().use { assertEquals(200, it.code) }
    }
}
