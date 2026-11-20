package com.rokid.context.phone.ui.settings.privacy.zones.online

import com.rokid.context.core.log.PcLog
import com.rokid.context.phone.ui.settings.privacy.zones.tiles.RasterTileSource
import com.rokid.context.phone.ui.settings.privacy.zones.tiles.TileKey
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Collections
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

/**
 * The ONLY place of the zones map that reaches the network, and only for the opt-in street map. A provider is a fixed host, a URL shape and the attribution its terms require.
 * [url] holds the key; never log it ([toString] omits it).
 */
class OnlineProvider private constructor(
    val host: String, val name: String, val cacheId: String, val attribution: String, val logoText: String?, private val template: String,
) {
    fun url(k: TileKey): String = template.replace("{z}", k.z.toString()).replace("{x}", k.x.toString()).replace("{y}", k.y.toString())
    override fun toString(): String = "OnlineProvider($name)"

    companion object {
        const val MAPTILER_HOST = "api.maptiler.com"
        const val OSM_HOST = "tile.openstreetmap.org"

        fun mapTiler(key: String) = OnlineProvider(
            MAPTILER_HOST, "MapTiler", "maptiler-streets-v2", "© MapTiler © OpenStreetMap contributors", "MapTiler",
            "https://$MAPTILER_HOST/maps/streets-v2/256/{z}/{x}/{y}.png?key=$key",
        )

        /** Debug builds only, and only without a key: the public OSM server under its tile usage policy (light, interactive, cached, identified). */
        fun osmDebug() = OnlineProvider(OSM_HOST, "OpenStreetMap", "osm-standard", "© OpenStreetMap contributors", null, "https://$OSM_HOST/{z}/{x}/{y}.png")

        /**
         * The build's provider. A key selects MapTiler. No key: OSM in a DEBUG build, and NOTHING in a release build (the zones map then shows the grid only).
         * The host allowlist is therefore {MapTiler}, or in an unkeyed debug build {tile.openstreetmap.org}; there is no other way to reach the network from the map.
         */
        fun select(key: String, debug: Boolean): OnlineProvider? = when {
            key.isNotBlank() -> mapTiler(key.trim())
            debug -> osmDebug()
            else -> null
        }
    }
}

/** One answer to one request. [body] is null when it exceeded the read cap (only the first cap+1 bytes were read). A redirect is NEVER followed by the transport; [location] is reported. */
class TileResponse(val code: Int, val location: String? = null, val retryAfterSec: Long? = null, val body: ByteArray? = null)

/** The network seam of the tile source: tests swap it for a fake. */
interface TileTransport {
    @Throws(IOException::class) fun get(url: String, userAgent: String): TileResponse
    /** Tears down every request in flight (the screen left the foreground). */
    fun cancelAll()
}

/** HTTPS only, no redirect following, connect and read timeouts, a hard read cap (counted on the bytes after Android's transparent gzip unpacking). */
class UrlConnectionTileTransport(
    private val maxBytes: Int = OnlineTileSource.MAX_TILE_BYTES,
    private val connectTimeoutMs: Int = 6_000,
    private val readTimeoutMs: Int = 10_000,
) : TileTransport {
    private val open: MutableSet<HttpURLConnection> = Collections.synchronizedSet(HashSet())

    override fun get(url: String, userAgent: String): TileResponse {
        val u = URL(url)
        if (u.protocol != "https") throw IOException("https only")
        val c = u.openConnection() as HttpURLConnection
        open += c
        try {
            c.instanceFollowRedirects = false
            c.connectTimeout = connectTimeoutMs; c.readTimeout = readTimeoutMs
            c.useCaches = false
            c.setRequestProperty("User-Agent", userAgent)
            c.setRequestProperty("Accept", "image/png,image/jpeg,image/webp")
            val code = c.responseCode
            val loc = c.getHeaderField("Location")
            val retry = c.getHeaderField("Retry-After")?.trim()?.toLongOrNull()
            if (code !in 200..299) return TileResponse(code, loc, retry)
            val out = java.io.ByteArrayOutputStream()
            c.inputStream.use { ins ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = ins.read(buf); if (n < 0) break
                    if (out.size() + n > maxBytes) return TileResponse(code, null, null, null)
                    out.write(buf, 0, n)
                }
            }
            return TileResponse(code, null, null, out.toByteArray())
        } finally { open -= c; c.disconnect() }
    }

    override fun cancelAll() { synchronized(open) { open.toList() }.forEach { runCatching { it.disconnect() } } }
}

/**
 * On-disk tile cache in the app cache folder: least recently used files go first once over [maxBytes] (50 MB). Each file is an expiry (Long) then the image bytes.
 * Names hold the tile numbers inside the app-private cache folder only (never logged); [purge] deletes the whole folder (Delete all, switch off).
 */
class OnlineTileDiskCache(
    private val root: File, cacheId: String, private val maxBytes: Long = MAX_BYTES,
    private val ttlMs: Long = 7L * 24 * 3600 * 1000, private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dir = File(root, cacheId)

    @Synchronized fun get(k: TileKey): ByteArray? {
        val f = file(k)
        if (!f.isFile) return null
        return try {
            val raw = f.readBytes()
            if (raw.size <= 8) { f.delete(); return null }
            val exp = java.nio.ByteBuffer.wrap(raw, 0, 8).long
            if (clock() >= exp) { f.delete(); return null }
            f.setLastModified(clock())
            raw.copyOfRange(8, raw.size)
        } catch (e: IOException) { f.delete(); null }
    }

    @Synchronized fun put(k: TileKey, bytes: ByteArray) {
        if (!dir.isDirectory && !dir.mkdirs()) return
        val f = file(k); val tmp = File(dir, f.name + ".tmp")
        try {
            tmp.outputStream().use { it.write(java.nio.ByteBuffer.allocate(8).putLong(clock() + ttlMs).array()); it.write(bytes) }
            if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) { tmp.delete(); return } }
            f.setLastModified(clock())
        } catch (e: IOException) { tmp.delete(); return }
        trim()
    }

    fun sizeBytes(): Long = files().sumOf { it.length() }

    /** Deletes every cached tile of every provider. */
    @Synchronized fun purge() { root.deleteRecursively() }

    private fun trim() {
        var total = sizeBytes()
        if (total <= maxBytes) return
        for (f in files().sortedWith(compareBy({ it.lastModified() }, { it.name }))) {
            if (total <= maxBytes) break
            total -= f.length(); f.delete()
        }
    }

    private fun files(): List<File> = dir.listFiles { f -> f.name.endsWith(".tile") }.orEmpty().toList()
    private fun file(k: TileKey) = File(dir, "${k.z}_${k.x}_${k.y}.tile")

    companion object { const val MAX_BYTES = 50L * 1024 * 1024 }
}

/**
 * Raster z/x/y tiles from [provider] for the zones map, feeding the same compositor as the PMTiles archive. Blocking: call [tile] from a background dispatcher.
 *
 * Fences: HTTPS and the provider's one host only (every request AND every redirect target is checked; a redirect off the host is refused); at most [MAX_TILE_BYTES] per tile and only a
 * real image (PNG, JPEG, WebP header); zoom 0..[MAX_ZOOM]; at most [sessionCap] requests per visit to the screen ([newVisit]; a pause and resume within a visit keeps the count) and [maxConcurrent] at once; a 429 or 5xx backs the whole source off
 * (Retry-After honoured, capped, doubling); nothing is requested until [start] and nothing after [stop], which also cancels the requests in flight.
 * Logs: fixed event names only. A tile number, a URL and the key are never logged (tile numbers reveal where the user is looking).
 */
class OnlineTileSource(
    private val provider: OnlineProvider,
    private val transport: TileTransport,
    private val cache: OnlineTileDiskCache,
    private val userAgent: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sessionCap: Int = SESSION_CAP,
    maxConcurrent: Int = MAX_CONCURRENT,
    /** Model privacy "Offline only": no tile is requested from the internet (tiles already on the phone are still shown). Read at every request; the default allows. */
    private val offlineOnly: () -> Boolean = { false },
) : RasterTileSource {
    override val minZoom = 1
    override val maxZoom = MAX_ZOOM
    override val attribution: String = provider.attribution
    override val logoText: String? = provider.logoText

    private val gate = Semaphore(maxConcurrent)
    private val requests = AtomicInteger(0)
    @Volatile private var running = false
    @Volatile private var epoch = 0
    private val lock = Any()
    private var backoffUntil = 0L
    private var backoffMs = 0L
    private val failedUntil = HashMap<TileKey, Long>()
    private var capLogged = false

    /** Network requests made in this visit to the screen. */
    fun requestCount(): Int = requests.get()

    override fun start() { synchronized(lock) { running = true; epoch++ } }

    /** A new visit to the screen: the request cap starts again. A pause and resume inside the same visit keeps the count. */
    override fun newVisit() { synchronized(lock) { requests.set(0); capLogged = false } }

    override fun stop() { synchronized(lock) { running = false; epoch++ }; transport.cancelAll() }

    override fun tile(k: TileKey): ByteArray? {
        if (k.z !in minZoom..maxZoom) return null
        val n = 1L shl k.z
        if (k.x < 0 || k.y < 0 || k.x >= n || k.y >= n) return null
        cache.get(k)?.let { return it }
        if (!running) return null
        if (offline()) return null // fail closed: an unreadable privacy setting is Offline only
        val e0 = epoch
        val now = clock()
        synchronized(lock) { if (now < backoffUntil || (failedUntil[k] ?: 0L) > now) return null }
        gate.acquire()
        try {
            if (!running || epoch != e0 || offline()) return null // a request that waited for a slot is judged again: the switch may have come meanwhile
            return download(k, e0)
        } finally { gate.release() }
    }

    private fun offline(): Boolean = try { offlineOnly() } catch (e: Exception) { true }

    private fun download(k: TileKey, e0: Int): ByteArray? {
        var url = provider.url(k)
        for (hop in 0..MAX_REDIRECTS) {
            if (!permitted(url)) { PcLog.i(TAG, "online tiles refused a url"); return fail(k) }
            if (!takeSlot()) return null
            if (offline()) return null // immediately before the request (and before every redirect hop)
            val r = try { transport.get(url, userAgent) } catch (e: IOException) {
                if (!running || epoch != e0) return null
                PcLog.i(TAG, "online tiles request failed"); return fail(k)
            }
            if (!running || epoch != e0) return null
            val code = r.code
            when {
                code in 200..299 -> {
                    val b = r.body
                    if (b == null || b.size > MAX_TILE_BYTES) { PcLog.i(TAG, "online tiles response too large"); return fail(k) }
                    if (!looksLikeImage(b)) { PcLog.i(TAG, "online tiles response not an image"); return fail(k) }
                    // Under the same lock as stop(): a stop that came first (switch off, pause) wins and nothing is written; one that comes after waits, and its purge then removes the tile.
                    synchronized(lock) {
                        if (!running || epoch != e0) return null
                        backoffMs = 0L; failedUntil.remove(k)
                        cache.put(k, b)
                    }
                    return b
                }
                code == 301 || code == 302 || code == 303 || code == 307 || code == 308 -> {
                    url = resolve(url, r.location) ?: run { PcLog.i(TAG, "online tiles refused a redirect"); return fail(k) }
                }
                code == 429 || code in 500..599 -> { backOff(r.retryAfterSec); return null }
                else -> { PcLog.i(TAG, "online tiles request refused by server"); return fail(k) }
            }
        }
        PcLog.i(TAG, "online tiles too many redirects")
        return fail(k)
    }

    private fun takeSlot(): Boolean {
        if (requests.incrementAndGet() <= sessionCap) return true
        requests.decrementAndGet()
        synchronized(lock) { if (!capLogged) { capLogged = true; PcLog.i(TAG, "online tiles session cap reached") } }
        return false
    }

    private fun fail(k: TileKey): ByteArray? { synchronized(lock) { failedUntil[k] = clock() + FAIL_RETRY_MS }; return null }

    private fun backOff(retryAfterSec: Long?) {
        val now = clock()
        synchronized(lock) {
            backoffMs = if (backoffMs == 0L) INITIAL_BACKOFF_MS else minOf(backoffMs * 2, MAX_BACKOFF_MS)
            val wait = retryAfterSec?.let { (it * 1000).coerceIn(backoffMs, MAX_BACKOFF_MS) } ?: backoffMs
            backoffUntil = now + wait
        }
        PcLog.i(TAG, "online tiles backing off")
    }

    /** https, the provider's host (exact), the default port, no credentials in the URL. */
    private fun permitted(url: String): Boolean {
        val u = try { URI(url) } catch (e: Exception) { return false }
        return u.scheme.equals("https", ignoreCase = true) && u.host.equals(provider.host, ignoreCase = true) && (u.port == -1 || u.port == 443) && u.userInfo == null
    }

    private fun resolve(base: String, location: String?): String? {
        if (location.isNullOrBlank()) return null
        val next = try { URI(base).resolve(location).toString() } catch (e: Exception) { return null }
        return next.takeIf { permitted(it) }
    }

    companion object {
        private const val TAG = "OnlineTiles"
        const val MAX_TILE_BYTES = 512 * 1024
        const val MAX_ZOOM = 19
        const val SESSION_CAP = 400
        const val MAX_CONCURRENT = 4
        const val MAX_REDIRECTS = 2
        const val INITIAL_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 60_000L
        const val FAIL_RETRY_MS = 30_000L

        /** PNG, JPEG or WebP magic bytes: the decoder is only ever handed an image. */
        fun looksLikeImage(b: ByteArray): Boolean {
            if (b.size < 12) return false
            val png = b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()
            val jpg = b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()
            val webp = b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte() && b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() && b[10] == 'B'.code.toByte() && b[11] == 'P'.code.toByte()
            return png || jpg || webp
        }
    }
}
