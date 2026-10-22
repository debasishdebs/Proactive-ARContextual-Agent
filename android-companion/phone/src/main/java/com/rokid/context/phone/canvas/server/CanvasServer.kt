package com.rokid.context.phone.canvas.server

import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Thin NanoHTTPD adapter: converts sessions to [HttpRequest] and [HttpResponse] back. All policy is in [CanvasRouter]. */
class CanvasServer(
    private val router: CanvasRouter,
    private val bindLan: Boolean,
    private val log: (String) -> Unit = {},
    private val maxClients: Int = MAX_CLIENTS,
    private val deadlineMs: Long = DEADLINE_MS,
) {
    companion object {
        const val MAX_CLIENTS = 16
        const val DEADLINE_MS = 15_000L
        const val PORT = 8765
        val FALLBACK = 8766..8770
        const val MAX_BODY = 256 * 1024
    }

    private class Status(private val code: Int) : NanoHTTPD.Response.IStatus {
        override fun getRequestStatus() = code
        override fun getDescription() = "$code"
    }

    /**
     * At most [max] concurrent connections; the rest are closed at once. Every connection has a deadline
     * ([deadlineMs]) for each phase in which the client controls the pace: request line, headers and body, the
     * response write, and the idle wait for the next keep-alive request. It is disarmed only while our handler runs
     * (the API bounds a read itself; a mutation runs to completion). A client that drips bytes is closed when the deadline passes.
     */
    internal class BoundedRunner(private val max: Int, private val deadlineMs: Long, private val log: (String) -> Unit) : NanoHTTPD.AsyncRunner {
        private class Conn(val handler: NanoHTTPD.ClientHandler) { @Volatile var deadline = Long.MAX_VALUE }

        private val permits = Semaphore(max)
        private val running = ConcurrentHashMap<NanoHTTPD.ClientHandler, Conn>()
        private val pool = Executors.newCachedThreadPool { r -> Thread(r, "canvas-conn").apply { isDaemon = true } }
        private val watchdog: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "canvas-watchdog").apply { isDaemon = true } }
        private val current = ThreadLocal<Conn>()

        val active: Int get() = running.size

        init {
            val tick = maxOf(20L, minOf(1_000L, deadlineMs / 4))
            watchdog.scheduleWithFixedDelay({
                val now = System.currentTimeMillis()
                for (c in running.values) if (now >= c.deadline) { c.deadline = Long.MAX_VALUE; log("CONN deadline"); c.handler.close() }
            }, tick, tick, TimeUnit.MILLISECONDS)
        }

        override fun exec(handler: NanoHTTPD.ClientHandler) {
            if (!permits.tryAcquire()) { log("CONN over-cap"); handler.close(); return }
            val conn = Conn(handler).also { it.deadline = System.currentTimeMillis() + deadlineMs }
            running[handler] = conn
            try {
                pool.execute {
                    current.set(conn)
                    try { handler.run() } catch (t: Throwable) { log("CONN error"); handler.close() } finally { current.remove(); release(handler) }
                }
            } catch (e: Exception) { release(handler); handler.close() }
        }

        override fun closed(handler: NanoHTTPD.ClientHandler) = release(handler)

        private fun release(handler: NanoHTTPD.ClientHandler) { if (running.remove(handler) != null) permits.release() }

        override fun closeAll() {
            watchdog.shutdownNow()
            for (h in running.keys.toList()) h.close()
            pool.shutdown()
        }

        fun arm() { current.get()?.deadline = System.currentTimeMillis() + deadlineMs }
        fun disarm() { current.get()?.deadline = Long.MAX_VALUE }
    }

    internal inner class Daemon(hostname: String?, port: Int) : NanoHTTPD(hostname, port) {
        val runner = BoundedRunner(maxClients, deadlineMs, log)
        init { setAsyncRunner(runner) }

        override fun serve(session: IHTTPSession): Response = try {
            runner.arm() // the body is read next
            val req = toRequest(session)
            runner.disarm() // our handler runs; the response write is re-armed below
            // An unread (oversize) or chunked body would otherwise be parsed as the next request on a kept-alive connection.
            val unread = req.bodyTooLarge || req.headers.containsKey("transfer-encoding")
            toResponse(router.handle(req)).also { if (unread) it.closeConnection(true) }.also { runner.arm() }
        } catch (e: Throwable) {
            runner.arm()
            toResponse(router.withSecurityHeaders(HttpResponse.error(500, "internal", "Something went wrong.")))
        }
    }

    @Volatile private var daemon: Daemon? = null
    @Volatile var port: Int? = null
        private set
    /** Connections currently held (for tests and diagnostics). */
    val activeClients: Int get() = daemon?.runner?.active ?: 0

    @Volatile var bindHost: String? = null
        private set

    /** Binds 127.0.0.1 (0.0.0.0 with LAN on) on [PORT], then [FALLBACK]; throws [IOException] when all are busy. */
    @Synchronized
    fun start(): Int {
        daemon?.let { return port!! }
        var last: IOException? = null
        for (p in listOf(PORT) + FALLBACK) {
            val d = Daemon(if (bindLan) null else "127.0.0.1", p)
            try {
                d.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
            } catch (e: IOException) {
                last = e
                continue
            }
            daemon = d
            port = p
            bindHost = if (bindLan) "0.0.0.0" else "127.0.0.1"
            return p
        }
        throw IOException("No free port for the canvas server", last)
    }

    @Synchronized
    fun stop() {
        daemon?.stop()
        daemon = null
        port = null
        bindHost = null
    }

    private fun toRequest(s: NanoHTTPD.IHTTPSession): HttpRequest {
        val headers = s.headers.entries.associate { it.key.lowercase() to it.value }
        val query = s.parameters.mapNotNull { (k, v) -> v.firstOrNull()?.let { k to it } }.toMap()
        val method = s.method?.name ?: "GET"
        val len = headers["content-length"]?.trim()?.toLongOrNull()?.coerceAtLeast(0) ?: 0L
        if (len > MAX_BODY) return HttpRequest(method, s.uri, query, headers, ByteArray(0), bodyTooLarge = true)
        val body = ByteArray(len.toInt())
        var read = 0
        val input = s.inputStream
        while (read < body.size) {
            val n = input.read(body, read, body.size - read)
            if (n < 0) break
            read += n
        }
        return HttpRequest(method, s.uri, query, headers, if (read == body.size) body else body.copyOf(read))
    }

    private fun toResponse(r: HttpResponse): NanoHTTPD.Response {
        val out = NanoHTTPD.newFixedLengthResponse(Status(r.status), r.contentType, r.body.inputStream(), r.body.size.toLong())
        out.setGzipEncoding(false)
        r.headers.forEach { (k, v) -> out.addHeader(k, v) }
        return out
    }
}
