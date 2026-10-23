package com.rokid.context.phone.canvas.server

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.Socket

/**
 * Review 1 #1 (Important): the body is sized BEFORE Host or token are checked, so the 2 MiB limit of the helper `result` route must be granted only to an allowed Host that carries
 * the LIVE helper token, and the body must be read in bounded chunks, never allocated from the client's Content-Length. Driven through a REAL server on a real socket.
 */
class HelperBodyLimitServerTest {
    private var now = 1_000_000L
    private val browser = CanvasAuth({ now })
    private val helper = HelperAuth({ now })
    private var port = 0
    private var seenLen = -1
    private val api = ApiHandler { HttpResponse.json(200, kotlinx.serialization.json.JsonObject(emptyMap())) }
    private val helperApi = ApiHandler { seenLen = it.body.size; HttpResponse.json(200, kotlinx.serialization.json.JsonObject(emptyMap())) }
    private lateinit var server: CanvasServer
    private lateinit var helperToken: String
    private lateinit var browserToken: String

    @Before fun setUp() {
        val router = CanvasRouter(browser, HostPolicy({ port }, { null }), api, AssetSource { null }, helperAuth = helper, helperApi = helperApi)
        server = CanvasServer(router, false)
        port = server.start()
        helper.acceptPairing()
        helperToken = (helper.pair(helper.pairingCode()!!.code, "laptop", "10.0.0.1") as HelperAuth.PairResult.Ok).token
        browserToken = (browser.pair(browser.newCode().code) as CanvasAuth.Pair.Ok).token
    }
    @After fun tearDown() { server.stop() }

    private val resultPath = "/api/helper/jobs/job1/result"

    /** Sends the headers with [claimed] as Content-Length and [send] body bytes; returns the status line's code, or -1 when no answer came within 3 s (the server was waiting for a body). */
    private fun post(path: String, token: String?, claimed: Long, send: Int, host: String = "127.0.0.1:$port"): Int {
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 3_000
            val head = "POST $path HTTP/1.1\r\nHost: $host\r\n" + (if (token != null) "Authorization: Bearer $token\r\n" else "") + "Content-Type: application/json\r\nContent-Length: $claimed\r\n\r\n"
            s.getOutputStream().write(head.toByteArray())
            val chunk = ByteArray(64 * 1024) { ' '.code.toByte() }
            var left = send
            while (left > 0) { val n = minOf(left, chunk.size); s.getOutputStream().write(chunk, 0, n); left -= n }
            s.getOutputStream().flush()
            return try {
                val line = s.getInputStream().bufferedReader().readLine() ?: return -1
                line.split(' ')[1].toInt()
            } catch (_: java.net.SocketTimeoutException) { -1 }
        }
    }

    @Test fun anUnauthenticatedPeerClaimingTwoMiBIsRefusedWithoutTheServerWaitingForBytes() {
        assertEquals(413, post(resultPath, null, 2_101_248, 0))
    }

    @Test fun aBrowserTokenDoesNotGetTheLargeLimit() {
        assertEquals(413, post(resultPath, browserToken, 2_101_248, 0))
        assertEquals(413, post(resultPath, browserToken, 300 * 1024L, 0))
    }

    @Test fun aWrongHostDoesNotGetTheLargeLimitEvenWithTheHelperToken() {
        assertEquals(421, post(resultPath, helperToken, 300 * 1024L, 0, host = "evil.example"))
    }

    @Test fun theLiveHelperTokenGetsTheLargeLimitOnThatRouteOnly() {
        assertEquals(200, post(resultPath, helperToken, 300 * 1024L, 300 * 1024))
        assertEquals(300 * 1024, seenLen)
        assertEquals(413, post("/api/helper/jobs/job1/heartbeat", helperToken, 300 * 1024L, 0))
        assertEquals(413, post(resultPath, helperToken, 2_101_249, 0))
    }

    @Test fun aRevokedHelperTokenLosesTheLargeLimit() {
        helper.revoke()
        assertEquals(413, post(resultPath, helperToken, 300 * 1024L, 0))
    }

    @Test fun theBodyIsReadInBoundedChunksNeverAllocatedFromContentLength() {
        val asked = mutableListOf<Int>()
        val src = object : InputStream() {
            private val inner = ByteArrayInputStream(ByteArray(10) { 1 })
            override fun read(): Int = inner.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int { asked += len; return inner.read(b, off, len) }
        }
        val body = CanvasServer.readBody(src, 2_101_248L)
        assertEquals("only what the peer sent", 10, body.size)
        assertTrue("no single read asked for more than 64 KiB: $asked", asked.all { it <= 64 * 1024 })
    }
}
