package com.rokid.context.work.frames.place

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/** Why a reference photo was not taken. Fixed tokens: nothing here carries a link, a host or a byte of the image. */
enum class PhotoFault(val text: String) {
    OFFLINE_ONLY("Offline only is on: nothing is fetched from the internet."),
    NO_NETWORK("No network."),
    NOT_FREE_WIFI("Not on free Wi-Fi (the Wi-Fi only rule is on)."),
    DATA_SAVER("The data saver does not allow this download now."),
    BAD_LINK("That is not an acceptable link. Use a full https:// address of an image."),
    BLOCKED_HOST("That address is not allowed (it is not a public internet address)."),
    REDIRECT("The link redirects somewhere that is not allowed."),
    HTTP("The server did not give the image."),
    TOO_BIG("The photo is too big (up to 12 MB)."),
    NOT_AN_IMAGE("That is not a JPEG, PNG or WebP image."),
    DIMENSIONS("The photo is too large in pixels (up to 8000 by 8000, 48 megapixels)."),
    TIMEOUT("The download took too long."),
    UNREADABLE("The photo could not be read."),
    FAILED("The download failed."),
}

/** A photo that passed every check, as it will be stored: re-encoded JPEG without metadata, capped in size. */
class PreparedPhoto(val jpeg: ByteArray, val width: Int, val height: Int) {
    val size: Int get() = jpeg.size
}

/** [bytes] set = the raw, sniffed, size-capped bytes; else [fault]. */
class RawPhoto(val bytes: ByteArray?, val fault: PhotoFault?) {
    companion object {
        fun ok(b: ByteArray) = RawPhoto(b, null)
        fun fail(f: PhotoFault) = RawPhoto(null, f)
    }
}

/** The checks every reference photo passes, whether it came from a link or from the file picker. */
object RefPhoto {
    const val MAX_BYTES = 12 * 1024 * 1024
    const val MAX_SIDE = 8000
    const val MAX_PIXELS = 48_000_000L

    /** The format by magic bytes only (the Content-Type header and the name are never read): "jpeg", "png", "webp" or null. */
    fun sniff(b: ByteArray): String? = when {
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "jpeg"
        b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() && b[4] == 0x0D.toByte() && b[5] == 0x0A.toByte() && b[6] == 0x1A.toByte() && b[7] == 0x0A.toByte() -> "png"
        b.size >= 12 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "webp"
        else -> null
    }

    /** Reads at most [cap] bytes of [ins]; null = it has more (aborted at once, the rest is never read). */
    @Throws(IOException::class)
    fun readCapped(ins: InputStream, cap: Int = MAX_BYTES, expired: () -> Boolean = { false }): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            if (expired()) throw java.net.SocketTimeoutException("deadline")
            val n = ins.read(buf); if (n < 0) break
            if (out.size() + n > cap) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** The first fault of [bytes] (format, then pixels from [bounds], which is BitmapFactory's inJustDecodeBounds on the phone), or null. */
    fun validate(bytes: ByteArray, bounds: (ByteArray) -> Pair<Int, Int>?): PhotoFault? {
        if (bytes.isEmpty()) return PhotoFault.NOT_AN_IMAGE
        if (bytes.size > MAX_BYTES) return PhotoFault.TOO_BIG
        if (sniff(bytes) == null) return PhotoFault.NOT_AN_IMAGE
        val (w, h) = (try { bounds(bytes) } catch (e: Exception) { null }) ?: return PhotoFault.UNREADABLE
        if (w <= 0 || h <= 0) return PhotoFault.UNREADABLE
        if (w > MAX_SIDE || h > MAX_SIDE || w.toLong() * h > MAX_PIXELS) return PhotoFault.DIMENSIONS
        return null
    }
}

/** What an acceptable link is, and which addresses are public. Pure. */
object SafeUrl {
    private val HOST = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")
    private val TLD = Regex("^([a-z]{2,63}|xn--[a-z0-9-]{1,59})$")
    private val BLOCKED_TLD = setOf("local", "localhost", "internal", "lan", "home", "corp", "intranet", "private", "arpa", "test", "invalid", "example")
    const val MAX_LEN = 2000

    /** The lower-case host of an acceptable https link, or null: https only, no user info, port 443 only, a real DNS name (no IP literal in any notation, no single label, no local-looking suffix). */
    fun host(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty() || s.length > MAX_LEN || s.any { it.code < 0x21 || it.code > 0x7e }) return null
        val u = try { URI(s) } catch (e: Exception) { return null }
        if (!"https".equals(u.scheme, ignoreCase = true)) return null
        if (u.rawUserInfo != null || u.rawAuthority?.contains('@') == true) return null
        if (u.port != -1 && u.port != 443) return null
        val h = u.host?.lowercase()?.trimEnd('.') ?: return null
        if (h.startsWith("[") || h.contains(':') || !HOST.matches(h)) return null
        val tld = h.substringAfterLast('.')
        if (!TLD.matches(tld) || tld in BLOCKED_TLD) return null
        return h
    }

    /** True only for a unicast address on the public internet. Every address a name resolves to must pass. */
    fun isPublic(a: InetAddress): Boolean = when (a) {
        is Inet4Address -> v4(a.address)
        is Inet6Address -> v6(a.address)
        else -> false
    }

    private fun v4(b: ByteArray): Boolean {
        val o = IntArray(4) { b[it].toInt() and 0xFF }
        val a = o[0]; val c = o[1]
        return when {
            a == 0 || a == 10 || a == 127 || a >= 224 -> false // this network, private, loopback, multicast, reserved
            a == 100 && c in 64..127 -> false // carrier-grade NAT
            a == 169 && c == 254 -> false // link-local
            a == 172 && c in 16..31 -> false
            a == 192 && c == 168 -> false
            a == 192 && c == 0 && (o[2] == 0 || o[2] == 2) -> false
            a == 198 && c in 18..19 -> false // benchmarking
            a == 198 && c == 51 && o[2] == 100 -> false
            a == 203 && c == 0 && o[2] == 113 -> false
            else -> true
        }
    }

    private fun v6(b: ByteArray): Boolean {
        if (b.size != 16) return false
        val first10Zero = (0 until 10).all { b[it].toInt() == 0 }
        if (first10Zero && b[10] == 0xFF.toByte() && b[11] == 0xFF.toByte()) return v4(b.copyOfRange(12, 16)) // ::ffff:a.b.c.d
        if ((0 until 12).all { b[it].toInt() == 0 }) return false // ::, ::1, ::a.b.c.d (deprecated compatible)
        val b0 = b[0].toInt() and 0xFF; val b1 = b[1].toInt() and 0xFF; val b2 = b[2].toInt() and 0xFF; val b3 = b[3].toInt() and 0xFF
        return when {
            (b0 and 0xFE) == 0xFC -> false // unique local fc00::/7
            b0 == 0xFE && (b1 and 0xC0) == 0x80 -> false // link-local fe80::/10
            b0 == 0xFE && (b1 and 0xC0) == 0xC0 -> false // site-local fec0::/10
            b0 == 0xFF -> false // multicast
            b0 == 0x00 && b1 == 0x64 && b2 == 0xFF && b3 == 0x9B && (4 until 12).all { b[it].toInt() == 0 } -> v4(b.copyOfRange(12, 16)) // 64:ff9b::/96 NAT64
            b0 == 0x20 && b1 == 0x02 -> v4(b.copyOfRange(2, 6)) // 6to4
            b0 == 0x20 && b1 == 0x01 && b2 == 0 && b3 == 0 -> false // Teredo
            b0 == 0x20 && b1 == 0x01 && b2 == 0x0D && b3 == 0xB8 -> false // documentation
            else -> true
        }
    }
}

/** One answer to one request of [ImageHop]. A redirect is never followed by the transport. */
class HopResponse(val code: Int, val location: String?, val contentLength: Long, val body: InputStream?, private val onClose: () -> Unit = {}) : Closeable {
    override fun close() { try { body?.close() } catch (_: Exception) { }; try { onClose() } catch (_: Exception) { } }
}

/** The network seam: ONE https GET to exactly the addresses given (already checked), no redirect following, no cookies, no credentials, no custom headers beyond the identifying User-Agent. Tests swap it for a fake. */
fun interface ImageHop { @Throws(IOException::class) fun get(url: String, addrs: List<InetAddress>): HopResponse }

/**
 * Developer reference photo, the downloader (no SSRF): every URL (the first and each redirect target) is checked by [SafeUrl.host], the name is RESOLVED and EVERY address must be public ([SafeUrl.isPublic]), and the
 * request goes to exactly those checked addresses (a second resolution cannot differ). At most [MAX_REDIRECTS] redirects, each https. The size cap is checked on Content-Length first and again on the streamed bytes (aborted
 * the moment it is exceeded); the whole call has a total deadline. [admission] says why a download is not allowed now (Offline only, no network, the shared data saver): asked before the first request and before every redirect.
 * The result is the raw bytes only when their magic bytes are JPEG, PNG or WebP. Nothing is logged: a link, host or address never leaves this class except as a fixed [PhotoFault].
 */
class ImageFetcher(
    private val resolve: (String) -> List<InetAddress>,
    private val hop: ImageHop,
    private val admission: () -> PhotoFault? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
    private val totalMs: Long = 25_000L,
) {
    fun fetch(link: String): RawPhoto {
        val t0 = clock()
        val expired = { clock() - t0 > totalMs }
        var url = link.trim()
        for (n in 0..MAX_REDIRECTS) {
            if (expired()) return RawPhoto.fail(PhotoFault.TIMEOUT)
            val adm = try { admission() } catch (e: Exception) { PhotoFault.OFFLINE_ONLY } // unreadable = refused
            if (adm != null) return RawPhoto.fail(adm)
            val host = SafeUrl.host(url) ?: return RawPhoto.fail(if (n == 0) PhotoFault.BAD_LINK else PhotoFault.REDIRECT)
            val addrs = try { resolve(host) } catch (e: Exception) { return RawPhoto.fail(PhotoFault.FAILED) }
            if (addrs.isEmpty()) return RawPhoto.fail(PhotoFault.FAILED)
            if (!addrs.all(SafeUrl::isPublic)) return RawPhoto.fail(if (n == 0) PhotoFault.BLOCKED_HOST else PhotoFault.REDIRECT)
            val r = try { hop.get(url, addrs) } catch (e: java.net.SocketTimeoutException) { return RawPhoto.fail(PhotoFault.TIMEOUT) } catch (e: Exception) { return RawPhoto.fail(PhotoFault.FAILED) }
            var next: String? = null
            r.use { resp ->
                if (resp.code in REDIRECTS) {
                    if (n == MAX_REDIRECTS) return RawPhoto.fail(PhotoFault.REDIRECT)
                    val loc = resp.location?.trim()?.takeIf { it.isNotEmpty() } ?: return RawPhoto.fail(PhotoFault.REDIRECT)
                    next = try { URI(url).resolve(loc).toString() } catch (e: Exception) { return RawPhoto.fail(PhotoFault.REDIRECT) }
                    return@use
                }
                if (resp.code != 200) return RawPhoto.fail(PhotoFault.HTTP)
                if (resp.contentLength > RefPhoto.MAX_BYTES) return RawPhoto.fail(PhotoFault.TOO_BIG)
                val ins = resp.body ?: return RawPhoto.fail(PhotoFault.HTTP)
                val bytes = try { RefPhoto.readCapped(ins, RefPhoto.MAX_BYTES, expired) } catch (e: java.net.SocketTimeoutException) { return RawPhoto.fail(PhotoFault.TIMEOUT) } catch (e: Exception) { return RawPhoto.fail(PhotoFault.FAILED) }
                    ?: return RawPhoto.fail(PhotoFault.TOO_BIG)
                if (RefPhoto.sniff(bytes) == null) return RawPhoto.fail(PhotoFault.NOT_AN_IMAGE)
                return RawPhoto.ok(bytes)
            }
            url = next ?: return RawPhoto.fail(PhotoFault.FAILED)
        }
        return RawPhoto.fail(PhotoFault.REDIRECT)
    }

    companion object {
        const val MAX_REDIRECTS = 3
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        /** The identifying User-Agent the Place Guide fetchers use (the app and the repository; nothing about the user or the link). */
        const val USER_AGENT = com.rokid.context.connectors.place.UrlConnectionPlaceHttp.DEFAULT_USER_AGENT
    }
}
