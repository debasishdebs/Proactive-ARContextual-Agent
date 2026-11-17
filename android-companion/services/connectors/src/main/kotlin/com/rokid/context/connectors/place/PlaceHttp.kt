package com.rokid.context.connectors.place

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.rokid.context.memory.place.currentZoneTicket
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A non-2xx answer. The body is never kept (it may echo the request); only the code. */
class PlaceHttpException(val code: Int) : IOException("HTTP $code")

/** The one network seam of the place fetchers: tests swap it for recorded fixtures. Public data only, GET or a form POST; never a key, never a cookie. */
interface PlaceHttp {
    suspend fun get(url: String): String
    suspend fun postForm(url: String, form: Map<String, String>): String
}

fun formEncode(form: Map<String, String>): String = form.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }

fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

/**
 * HTTPS only, with a polite User-Agent, connect and read timeouts, a response cap and a minimum gap between requests to the same host (the public Wikimedia and Overpass servers
 * ask clients to go slowly). No API key exists anywhere in the place fetchers.
 */
class UrlConnectionPlaceHttp(
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 25_000,
    private val minGapMs: Long = 400,
    private val maxBytes: Int = 4 * 1024 * 1024,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) : PlaceHttp {
    private val lock = Mutex()
    private val lastByHost = HashMap<String, Long>()

    override suspend fun get(url: String): String = send(url, null)

    override suspend fun postForm(url: String, form: Map<String, String>): String = send(url, formEncode(form))

    private suspend fun send(url: String, body: String?): String {
        val u = URL(url)
        require(u.protocol == "https") { "place fetch is https only" }
        val ticket = currentZoneTicket()
        ticket?.check()
        lock.withLock {
            val wait = (lastByHost[u.host] ?: Long.MIN_VALUE).let { if (it == Long.MIN_VALUE) 0L else it + minGapMs - clock() }
            if (wait > 0) pause(wait)
            lastByHost[u.host] = clock()
        }
        return withContext(Dispatchers.IO) {
            val c = u.openConnection() as HttpURLConnection // not connected yet: no byte leaves before connect()
            val tripped = java.util.concurrent.atomic.AtomicBoolean(false)
            // Registered synchronously, and the registration IS the admission check (one step under the fence lock): a close before it throws, a close after it runs this hook on the
            // closing thread, so a blocked connect/read is torn down at once. No watcher coroutine whose start could be cancelled away.
            val reg = ticket?.onClose { tripped.set(true); runCatching { c.disconnect() } }
            try {
                c.connectTimeout = connectTimeoutMs; c.readTimeout = readTimeoutMs
                c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", userAgent)
                c.setRequestProperty("Accept", "application/json")
                c.setRequestProperty("Accept-Encoding", "gzip")
                if (body != null) {
                    c.requestMethod = "POST"; c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                }
                c.connect() // handshake only; the request line (with the cell) and the body go out after this
                if (tripped.get()) throw com.rokid.context.memory.place.ZoneFenced()
                if (body != null) {
                    c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = c.responseCode
                if (code !in 200..299) throw PlaceHttpException(code)
                val raw = if (c.contentEncoding.equals("gzip", true)) java.util.zip.GZIPInputStream(c.inputStream) else c.inputStream
                raw.use { ins ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = ins.read(buf); if (n < 0) break
                        if (out.size() + n > maxBytes) throw IOException("response too large")
                        out.write(buf, 0, n)
                    }
                    out.toString("UTF-8")
                }
            } catch (e: IOException) {
                if (tripped.get()) throw com.rokid.context.memory.place.ZoneFenced() else throw e
            } finally { reg?.close(); c.disconnect() }
        }
    }

    companion object {
        /**
         * Wikimedia asks for an app name, a version and a way to reach the operator: the repository is the contact. No personal data (no email, no name). The URL is a placeholder until the
         * repository is public: replace `https://github.com/` with the repository URL then.
         */
        const val DEFAULT_USER_AGENT = "ProactiveContextAgent/1.1 (https://github.com/; contact: see repository)"
    }
}
