package com.rokid.context.phone.canvas.server

import fi.iki.elonen.NanoHTTPD
import java.io.IOException

/** Thin NanoHTTPD adapter: converts sessions to [HttpRequest] and [HttpResponse] back. All policy is in [CanvasRouter]. */
class CanvasServer(private val router: CanvasRouter, private val bindLan: Boolean) {
    companion object {
        const val PORT = 8765
        val FALLBACK = 8766..8770
        const val MAX_BODY = 256 * 1024
    }

    private class Status(private val code: Int) : NanoHTTPD.Response.IStatus {
        override fun getRequestStatus() = code
        override fun getDescription() = "$code"
    }

    private inner class Daemon(hostname: String?, port: Int) : NanoHTTPD(hostname, port) {
        override fun serve(session: IHTTPSession): Response = try {
            val req = toRequest(session)
            // An unread (oversize) or chunked body would otherwise be parsed as the next request on a kept-alive connection.
            val unread = req.bodyTooLarge || req.headers.containsKey("transfer-encoding")
            toResponse(router.handle(req)).also { if (unread) it.closeConnection(true) }
        } catch (e: Exception) {
            toResponse(HttpResponse.error(500, "internal", "Something went wrong."))
        }
    }

    @Volatile private var daemon: Daemon? = null
    @Volatile var port: Int? = null
        private set
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
