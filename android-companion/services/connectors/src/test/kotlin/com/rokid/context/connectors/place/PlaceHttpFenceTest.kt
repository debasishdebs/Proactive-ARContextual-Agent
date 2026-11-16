package com.rokid.context.connectors.place

import com.rokid.context.memory.place.ZoneFence
import com.rokid.context.memory.place.ZoneFenced
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Codex r5 P1-2: the cancel hook is registered synchronously (no watcher coroutine), and registering is the admission check. */
class PlaceHttpFenceTest {
    /** A TCP listener that accepts and never answers (a TLS handshake blocks on it); counts accepted connections and bytes read. */
    private class Silent : AutoCloseable {
        val server = ServerSocket(0)
        val accepted = AtomicInteger(); val accepts = CountDownLatch(1)
        init { Thread { try { while (true) { val s = server.accept(); accepted.incrementAndGet(); accepts.countDown(); s.getInputStream().let { i -> Thread { runCatching { while (i.read() >= 0) { } } }.start() } } } catch (_: Exception) { } }.apply { isDaemon = true }.start() }
        val url get() = "https://127.0.0.1:${server.localPort}/x"
        override fun close() { server.close() }
    }

    // trip between the first check and the connection: pause() runs after ticket.check() and before the dispatch hop
    @Test fun aTripBetweenTheCheckAndTheConnectNeverConnects() = Silent().use { s ->
        val fence = ZoneFence()
        val http = UrlConnectionPlaceHttp(minGapMs = 10_000, clock = { 0L }, pause = { fence.trip() })
        runCatching { runBlocking { http.get("https://127.0.0.1:1/warm") } } // refused at once; records the host so the next call must wait
        val r = runCatching { runBlocking { withContext(fence.ticket()) { http.get(s.url) } } }
        assertTrue(r.exceptionOrNull() is ZoneFenced, "was ${r.exceptionOrNull()}")
        Thread.sleep(200)
        assertEquals(0, s.accepted.get(), "no connection was opened after the trip")
    }

    // trip while the connect/handshake is blocked: the hook disconnects at once, long before the read timeout
    @Test fun aTripWhileBlockedOnTheConnectDisconnectsAtOnce() = Silent().use { s ->
        val fence = ZoneFence()
        val http = UrlConnectionPlaceHttp(connectTimeoutMs = 60_000, readTimeoutMs = 60_000)
        val t0 = System.nanoTime()
        Thread { s.accepts.await(10, TimeUnit.SECONDS); Thread.sleep(100); fence.trip() }.start()
        val r = runCatching { runBlocking { withContext(fence.ticket()) { http.get(s.url) } } }
        assertTrue(r.exceptionOrNull() is ZoneFenced, "was ${r.exceptionOrNull()}")
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - t0) < 20)
    }

    // the hook is a registration, not a coroutine: a trip that lands before it runs is refused by the registration itself
    @Test fun registrationAfterATripIsRefusedAndOneBeforeItIsRun() {
        val fence = ZoneFence(); val t = fence.ticket()
        var ran = 0
        val reg = t.onClose { ran++ }
        fence.trip()
        assertEquals(1, ran)
        reg.close()
        assertTrue(runCatching { t.onClose { ran++ } }.exceptionOrNull() is ZoneFenced)
        assertEquals(1, ran)
    }
}
