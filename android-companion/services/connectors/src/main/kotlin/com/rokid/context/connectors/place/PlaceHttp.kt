package com.rokid.context.connectors.place

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.rokid.context.memory.place.currentZoneTicket
import kotlinx.coroutines.withContext
import com.rokid.context.core.net.DataGate
import com.rokid.context.core.net.DataKind
import com.rokid.context.core.net.ModelPrivacy
import com.rokid.context.memory.place.PlaceNetGuard
import com.rokid.context.memory.place.PlaceOfflineOnly
import com.rokid.context.memory.place.SeedAttempt
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A non-2xx answer. The body is never kept (it may echo the request); only the code. */
class PlaceHttpException(val code: Int, /** The numeric Retry-After in seconds, when the server sent one (an HTTP-date is ignored). */ val retryAfterS: Int? = null) : IOException("HTTP $code")

/** The one network seam of the place fetchers: tests swap it for recorded fixtures. Public data only, GET or a form POST; never a key, never a cookie. */
interface PlaceHttp {
    suspend fun get(url: String): String
    suspend fun postForm(url: String, form: Map<String, String>): String
}

/** Counts the bytes read from the wire (the compressed bytes when the answer is gzip). */
internal class CountingInputStream(`in`: java.io.InputStream, private val count: java.util.concurrent.atomic.AtomicLong) : java.io.FilterInputStream(`in`) {
    override fun read(): Int { val b = super.read(); if (b >= 0) count.incrementAndGet(); return b }
    override fun read(b: ByteArray, off: Int, len: Int): Int { val n = super.read(b, off, len); if (n > 0) count.addAndGet(n.toLong()); return n }
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
    /**
     * The data saver's byte ledger: a cell fetch is a small text request (class SMALL). The network class is read when the request STARTS (before the connection opens) and the bytes actually received
     * (on the wire, before gzip is unpacked) are added to today's mobile-data count, only when that class was costly (mobile, roaming, unknown). Counts only, never a URL or content.
     */
    private val gate: DataGate = DataGate.ALLOW_ALL,
    /** The connection factory (tests swap it for a fake slow stream). */
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    /**
     * The live Model privacy mode, asked before EVERY request (a cell fetch is up to a dozen requests, and Overpass carries the cell's bounding box): anything but AUTO refuses with
     * [PlaceOfflineOnly], and a reader that throws counts as Offline only (fail closed). Production passes the store; the default is AUTO only so tests of the other rules need no store.
     */
    private val privacy: () -> ModelPrivacy = { ModelPrivacy.AUTO },
    /** Where the blocking connection work runs (tests pass a dispatcher they control to flip the mode while the request is queued). */
    private val io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    /** The shared request limiter and diagnostics: after a 429/503 every request waits (see [PlaceNetGuard]). Production passes the process-wide one; the default is private to this client. */
    private val guard: PlaceNetGuard = PlaceNetGuard(),
) : PlaceHttp {
    private val lock = Mutex()
    private val lastByHost = HashMap<String, Long>()

    override suspend fun get(url: String): String = send(url, null)

    override suspend fun postForm(url: String, form: Map<String, String>): String = send(url, formEncode(form))

    private fun privacyGate() {
        val m = try { privacy() } catch (e: Exception) { ModelPrivacy.OFFLINE_ONLY }
        if (m != ModelPrivacy.AUTO) throw PlaceOfflineOnly()
    }

    /** The service token of a request, from the host and the action only (never logged with the URL). */
    private fun serviceOf(u: URL): String = when {
        u.host.contains("overpass") -> "overpass"
        u.host.contains("wikidata") -> "wikidata"
        u.query?.contains("list=geosearch") == true -> "wiki_geosearch"
        else -> "wiki_extract"
    }

    private fun failClass(e: Exception): String = when (e) {
        is java.net.SocketTimeoutException -> "timeout"
        is java.net.UnknownHostException -> "dns"
        is javax.net.ssl.SSLException -> "tls"
        else -> "io"
    }

    /** Waits for the global pause, sends, and records the outcome (tokens and numbers only) in the shared guard. */
    private suspend fun send(url: String, body: String?): String {
        val u = URL(url)
        require(u.protocol == "https") { "place fetch is https only" }
        val svc = serviceOf(u)
        guard.await(svc) // a 429/503 pauses every request of the same server group (Wikimedia or Overpass), whichever cell asks
        val t0 = clock()
        try {
            val r = sendNow(u, url, body, svc)
            guard.ok(svc, clock() - t0)
            return r
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: PlaceOfflineOnly) { throw e
        } catch (e: com.rokid.context.memory.place.ZoneFenced) { throw e
        } catch (e: PlaceHttpException) {
            guard.failed(svc, e.code, "http", e.retryAfterS, clock() - t0, kotlin.coroutines.coroutineContext[SeedAttempt]?.n ?: 1); throw e
        } catch (e: IOException) {
            guard.failed(svc, null, failClass(e), null, clock() - t0, kotlin.coroutines.coroutineContext[SeedAttempt]?.n ?: 1); throw e
        }
    }

    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class) // invokeOnCompletion(onCancelling = true): the only hook that runs when cancellation STARTS, while the blocking read is still stuck
    private suspend fun sendNow(u: URL, url: String, body: String?, svc: String): String {
        privacyGate()
        val ticket = currentZoneTicket()
        ticket?.check()
        lock.withLock {
            val wait = (lastByHost[u.host] ?: Long.MIN_VALUE).let { if (it == Long.MIN_VALUE) 0L else it + minGapMs - clock() }
            if (wait > 0) pause(wait)
            lastByHost[u.host] = clock()
        }
        guard.await(svc) // a 429/503 that another request recorded while this one waited for the host gap still pauses it (checked again right before dispatch)
        privacyGate() // asked again after any wait for the host gap: the mode may have changed while this request queued
        com.rokid.context.memory.place.seedAdmitCheck() // a seed's request: privacy, free Wi-Fi and the data saver are asked again before EVERY request (a no-op outside a seed)
        return withContext(io) {
            privacyGate() // the dispatch above may have queued: the mode is read once more on the IO thread, immediately before the connection is made (fail closed)
            val costlyAtStart = try { gate.costlyNow() } catch (e: Exception) { false } // the network class when the request starts
            val received = java.util.concurrent.atomic.AtomicLong(0)
            val c = open(u) // not connected yet: no byte leaves before connect()
            // A real call deadline: when the caller's coroutine is cancelled (a withTimeout around the call fires) the connection is disconnected at once, so a blocking read of a slowly streaming answer ends now instead of when the server stops.
            val cancelHook = coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion(onCancelling = true) { runCatching { c.disconnect() } }
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
                if (code !in 200..299) throw PlaceHttpException(code, c.getHeaderField("Retry-After")?.trim()?.toIntOrNull()?.takeIf { it >= 0 })
                val wire = CountingInputStream(c.inputStream, received)
                val raw = if (c.contentEncoding.equals("gzip", true)) java.util.zip.GZIPInputStream(wire) else wire
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
            } finally {
                cancelHook?.dispose(); reg?.close(); c.disconnect()
                val rx = received.get()
                if (rx > 0) try { gate.sentAt(DataKind.SMALL, 0L, rx, 0, costlyAtStart) } catch (e: Exception) { /* counting never breaks a fetch */ }
            }
        }
    }

    companion object {
        /**
         * Wikimedia asks for an app name, a version and a way to reach the operator: the public repository is the contact. No personal data (no email, no name).
         */
        const val DEFAULT_USER_AGENT = "ProactiveContextAgent/1.1 (https://github.com/debasishdebs/Proactive-ARContextual-Agent; contact: see repository)"
    }
}
