package com.rokid.context.phone.canvas.server

import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Thin NanoHTTPD adapter: converts sessions to [HttpRequest] and [HttpResponse] back. All policy is in [CanvasRouter].
 *
 * With [bindLan] the server listens on 0.0.0.0 and speaks TLS 1.2 and 1.3 only, through [tls] (required then); there is no plain-HTTP
 * LAN mode, and a plain request to the TLS port fails its handshake and is closed. Without it, plain HTTP on 127.0.0.1.
 * [onStart] runs once after a successful bind (trace pruning); a failure there never fails the start.
 */
class CanvasServer(
    private val router: CanvasRouter,
    private val bindLan: Boolean,
    private val tls: TlsIdentity? = null,
    private val log: (String) -> Unit = {},
    private val maxClients: Int = MAX_CLIENTS,
    private val deadlineMs: Long = DEADLINE_MS,
    private val onStart: () -> Unit = {},
) {
    init { require(!bindLan || tls != null) { "LAN needs a TLS identity" } }

    companion object {
        const val MAX_CLIENTS = 16
        const val DEADLINE_MS = 15_000L
        const val PORT = 8765
        val FALLBACK = 8766..8770
        const val MAX_BODY = 256 * 1024
        /** The helper's `result` post (a Python experiment's answers, at most 2 MiB of result plus its envelope). */
        const val MAX_RESULT_BODY = 2 * 1024 * 1024 + 4096
        /** Developer IMPORT (`POST /api/import`): up to 50 items with photos (a photo is at most 12 MB decoded, 16 MB as base64). Only a paired session may send this much, see [CanvasRouter.mayReadLargeBody]. */
        const val MAX_IMPORT_BODY = 20 * 1024 * 1024
        const val IMPORT_PATH = "/api/import"
        private val RESULT_ROUTE = Regex("^/api/helper/jobs/[A-Za-z0-9:_-]{1,64}/result$")

        /** Reads up to [len] bytes in chunks of at most 64 KiB: a peer that claims much and sends little costs only what it sent, never a buffer sized from its Content-Length. */
        internal fun readBody(input: java.io.InputStream, len: Long): ByteArray {
            val out = java.io.ByteArrayOutputStream(minOf(len, 64L * 1024).toInt())
            val buf = ByteArray(64 * 1024)
            var left = len
            while (left > 0) {
                val n = input.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                left -= n
            }
            return out.toByteArray()
        }

        /** The largest request body for [method] [path]: [MAX_BODY] everywhere except the one helper `result` route, which alone is raised to [MAX_RESULT_BODY]. */
        fun bodyLimit(method: String, path: String): Int =
            if (method.equals("POST", true) && RESULT_ROUTE.matches(path)) MAX_RESULT_BODY else if (method.equals("POST", true) && path == IMPORT_PATH) MAX_IMPORT_BODY else MAX_BODY
        internal val TLS_PROTOCOLS = arrayOf("TLSv1.3", "TLSv1.2")

        // held strongly: java.util.logging keeps loggers weakly, and a collected one forgets its level
        private val nanoLogger = java.util.logging.Logger.getLogger(NanoHTTPD::class.java.name)

        init {
            // NanoHTTPD logs every failed handshake (a port scan, or plain HTTP sent to the TLS port) at SEVERE with a stack trace.
            // Our own log is count-only; the library's is silenced.
            nanoLogger.level = java.util.logging.Level.OFF
        }
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

        /** Set by [stop][CanvasServer.stop] and [closeAll]: a connection accepted in the race with the stop is closed at once. */
        @Volatile var stopping = false

        /** The log is only ever called through here: a throwing log must not stop the watchdog or a connection. */
        private fun say(line: String) { runCatching { log(line) } }

        init {
            val tick = maxOf(20L, minOf(1_000L, deadlineMs / 4))
            watchdog.scheduleWithFixedDelay({
                // One bad connection or log call must never end the periodic task (it would silently stop all deadlines).
                runCatching {
                    val now = System.currentTimeMillis()
                    for (c in running.values) {
                        val deadline = c.deadline // read once: a disarm may land between the comparison and the reset
                        if (now >= deadline) {
                            c.deadline = Long.MAX_VALUE
                            say("CONN deadline")
                            runCatching { c.handler.close() }
                        }
                    }
                }
            }, tick, tick, TimeUnit.MILLISECONDS)
        }

        override fun exec(handler: NanoHTTPD.ClientHandler) {
            if (stopping) { handler.close(); return }
            if (!permits.tryAcquire()) { say("CONN over-cap"); handler.close(); return }
            val conn = Conn(handler).also { it.deadline = System.currentTimeMillis() + deadlineMs }
            running[handler] = conn
            try {
                pool.execute {
                    current.set(conn)
                    try { handler.run() } catch (t: Throwable) { say("CONN error"); handler.close() } finally { current.remove(); release(handler) }
                }
            } catch (e: Exception) { release(handler); handler.close() }
        }

        override fun closed(handler: NanoHTTPD.ClientHandler) = release(handler)

        private fun release(handler: NanoHTTPD.ClientHandler) { if (running.remove(handler) != null) permits.release() }

        override fun closeAll() {
            stopping = true
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

    /** The protocols the bound TLS server socket is limited to (a fresh, unbound socket from the same factory), or null without TLS. */
    internal fun probeProtocols(): List<String>? = daemon?.let { d ->
        if (!bindLan) null else d.serverSocketFactory.create().use { (it as? javax.net.ssl.SSLServerSocket)?.enabledProtocols?.toList() }
    }

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
                if (bindLan) d.makeSecure(tls!!.serverSocketFactory(), TLS_PROTOCOLS)
                d.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
            } catch (e: IOException) {
                // This attempt's runner already started its watchdog thread; a busy port must not leave one behind.
                runCatching { d.runner.closeAll() }
                last = e
                continue
            }
            daemon = d
            port = p
            bindHost = if (bindLan) "0.0.0.0" else "127.0.0.1"
            runCatching { onStart() }
            return p
        }
        throw IOException("No free port for the canvas server", last)
    }

    @Synchronized
    fun stop() {
        daemon?.let { it.runner.stopping = true; it.stop() }
        daemon = null
        port = null
        bindHost = null
    }

    private fun toRequest(s: NanoHTTPD.IHTTPSession): HttpRequest {
        val headers = s.headers.entries.associate { it.key.lowercase() to it.value }
        val query = s.parameters.mapNotNull { (k, v) -> v.firstOrNull()?.let { k to it } }.toMap()
        val method = s.method?.name ?: "GET"
        val len = headers["content-length"]?.trim()?.toLongOrNull()?.coerceAtLeast(0) ?: 0L
        val path = s.uri.substringBefore('?')
        // The size is decided before a byte is read: the larger limit only for an allowed Host with the live helper token; a claim above the limit is refused unread.
        val limit = if (len > MAX_BODY && router.mayReadLargeBody(method, path, headers)) bodyLimit(method, path) else MAX_BODY
        if (len > limit) return HttpRequest(method, s.uri, query, headers, ByteArray(0), bodyTooLarge = true, remoteIp = s.remoteIpAddress)
        return HttpRequest(method, s.uri, query, headers, readBody(s.inputStream, len), remoteIp = s.remoteIpAddress)
    }

    private fun toResponse(r: HttpResponse): NanoHTTPD.Response {
        val out = NanoHTTPD.newFixedLengthResponse(Status(r.status), r.contentType, r.body.inputStream(), r.body.size.toLong())
        out.setGzipEncoding(false)
        r.headers.forEach { (k, v) -> out.addHeader(k, v) }
        return out
    }
}
