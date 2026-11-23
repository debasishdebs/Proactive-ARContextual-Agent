package com.rokid.context.connectors.place

import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PlaceHttpDeadlineTest {
    /** A mirror that keeps streaming one byte every 20 ms and only stops when the connection is disconnected. */
    private class SlowConn(u: URL) : HttpURLConnection(u) {
        val disconnected = AtomicBoolean(false)
        override fun connect() {}
        override fun disconnect() { disconnected.set(true) }
        override fun usingProxy() = false
        override fun getResponseCode() = 200
        override fun getContentEncoding(): String? = null
        override fun getOutputStream() = java.io.ByteArrayOutputStream()
        override fun getInputStream(): InputStream = object : InputStream() {
            override fun read(): Int { if (disconnected.get()) throw IOException("closed"); Thread.sleep(20); return 'a'.code }
        }
    }

    @Test fun aSlowlyStreamingMirrorIsAbortedAtTheDeadlineSoFailoverIsNotHeld() {
        val conns = java.util.concurrent.CopyOnWriteArrayList<SlowConn>()
        val http = UrlConnectionPlaceHttp(minGapMs = 0, open = { SlowConn(it).also { c -> conns.add(c) } })
        val src = OverpassMirrorSource(listOf(OverpassHttpSource(http, "https://a.example/x"), OverpassHttpSource(http, "https://b.example/x")), perEndpointTimeoutMs = 300, pause = {})
        val t0 = System.nanoTime()
        assertFailsWith<IOException> { runBlocking { src.elements(com.rokid.context.memory.place.GeoBox(17.0, 78.0, 17.04, 78.04)) } }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(ms < 5_000, "took $ms ms")
        assertTrue(conns.size == 2 && conns.all { it.disconnected.get() }, "both slow connections were disconnected")
    }
}
