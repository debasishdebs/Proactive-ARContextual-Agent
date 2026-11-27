package com.rokid.context.work.frames.place

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress

/** The Developer reference photo downloader: link forms, address ranges, redirects, the size cap, magic bytes and pixel caps. No network: a fake hop and a fake resolver. */
class RefPhotoTest {
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4)
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A, 0, 0)
    private val webp = "RIFF".toByteArray() + byteArrayOf(1, 0, 0, 0) + "WEBPVP8 ".toByteArray()
    private val html = "<html><body>not an image</body></html>".toByteArray()
    private fun ip(vararg b: Int) = InetAddress.getByAddress(ByteArray(b.size) { b[it].toByte() })
    private fun v6(s: String) = InetAddress.getByName(s) // an IP literal: no DNS
    private val pub = ip(93, 184, 216, 34)

    // ---- the link forms ----
    @Test fun onlyPlainHttpsDnsNamesAreAccepted() {
        assertEquals("example.com", SafeUrl.host("https://example.com/a.jpg"))
        assertEquals("images.example.co.uk", SafeUrl.host("  HTTPS://Images.Example.co.uk:443/x?y=1  "))
        for (bad in listOf(
            "http://example.com/a.jpg", "ftp://example.com/a.jpg", "file:///sdcard/a.jpg", "//example.com/a.jpg", "example.com/a.jpg", "https://", "https://user:pw@example.com/a", "https://user@example.com/a",
            "https://example.com:8443/a", "https://example.com:80/a", "https://127.0.0.1/a", "https://10.0.0.1/a", "https://192.168.1.1/a", "https://2130706433/a", "https://0x7f.0.0.1/a", "https://017700000001/a",
            "https://[::1]/a", "https://[2001:db8::1]/a", "https://[::ffff:127.0.0.1]/a", "https://localhost/a", "https://foo.localhost/a", "https://printer.local/a", "https://nas.lan/a", "https://router.home/a", "https://svc.internal/a",
            "https://intranet/a", "https://exa mple.com/a", "https://exämple.com/a", "javascript:alert(1)", "", "   ", "https://" + "a".repeat(2100) + ".com/",
        )) assertNull(bad.take(40), SafeUrl.host(bad))
    }

    @Test fun everyPrivateLoopbackLinkLocalAndMappedAddressIsRefused() {
        val refused = listOf(
            ip(0, 0, 0, 0), ip(10, 1, 2, 3), ip(127, 0, 0, 1), ip(127, 8, 8, 8), ip(169, 254, 169, 254), ip(172, 16, 0, 1), ip(172, 31, 255, 255), ip(192, 168, 0, 1), ip(100, 64, 0, 1), ip(100, 127, 0, 1),
            ip(192, 0, 0, 1), ip(192, 0, 2, 1), ip(198, 18, 0, 1), ip(198, 51, 100, 1), ip(203, 0, 113, 1), ip(224, 0, 0, 1), ip(240, 0, 0, 1), ip(255, 255, 255, 255),
            v6("::1"), v6("::"), v6("fe80::1"), v6("fc00::1"), v6("fd12:3456::1"), v6("fec0::1"), v6("ff02::1"), v6("2001:db8::1"), v6("2001:0:4136:e378:8000:63bf:3fff:fdd2"),
            v6("::ffff:127.0.0.1"), v6("::ffff:10.0.0.1"), v6("::ffff:192.168.1.1"), v6("::ffff:169.254.169.254"), v6("64:ff9b::7f00:1"), v6("64:ff9b::a00:1"), v6("2002:7f00:1::1"), v6("2002:c0a8:101::1"), v6("::127.0.0.1"),
        )
        for (a in refused) assertFalse(a.hostAddress, SafeUrl.isPublic(a))
        for (a in listOf(ip(93, 184, 216, 34), ip(8, 8, 8, 8), ip(172, 15, 0, 1), ip(172, 32, 0, 1), ip(100, 63, 0, 1), v6("2606:2800:220:1:248:1893:25c8:1946"), v6("::ffff:8.8.8.8"), v6("64:ff9b::808:808"))) assertTrue(a.hostAddress, SafeUrl.isPublic(a))
    }

    // ---- the fetcher over a fake hop ----
    private class Hop(val answers: ArrayDeque<HopResponse>) : ImageHop {
        val urls = ArrayList<String>(); var addrsSeen = ArrayList<List<InetAddress>>()
        override fun get(url: String, addrs: List<InetAddress>): HopResponse { urls += url; addrsSeen += addrs; return answers.removeFirst() }
    }
    private fun ok(body: ByteArray, length: Long = body.size.toLong(), stream: InputStream = ByteArrayInputStream(body)) = HopResponse(200, null, length, stream)
    private fun redirect(to: String, code: Int = 302) = HopResponse(code, to, 0, ByteArrayInputStream(ByteArray(0)))
    private fun fetcher(hop: ImageHop, resolve: (String) -> List<InetAddress> = { listOf(pub) }, admission: () -> PhotoFault? = { null }, clock: () -> Long = { 0L }) = ImageFetcher(resolve, hop, admission, clock)
    private fun hop(vararg r: HopResponse) = Hop(ArrayDeque(r.toList()))

    @Test fun happyPathReturnsTheRawBytesOfAJpegPngOrWebp() {
        for (b in listOf(jpeg, png, webp)) {
            val h = hop(ok(b)); val r = fetcher(h).fetch("https://example.com/building")
            assertNull(r.fault); assertTrue(b.contentEquals(r.bytes)); assertEquals(1, h.urls.size); assertEquals(listOf(pub), h.addrsSeen.single())
        }
    }

    @Test fun badLinksNeverReachTheNetwork() {
        for (bad in listOf("http://example.com/a.jpg", "https://127.0.0.1/a.jpg", "https://localhost/a.jpg", "https://[::1]/a", "https://user:pw@example.com/a")) {
            val h = hop(ok(jpeg)); assertEquals(bad, PhotoFault.BAD_LINK, fetcher(h).fetch(bad).fault); assertTrue(h.urls.isEmpty())
        }
    }

    @Test fun aNameThatResolvesToAnyPrivateAddressIsRefusedBeforeTheRequest() {
        val h = hop(ok(jpeg))
        assertEquals(PhotoFault.BLOCKED_HOST, fetcher(h, { listOf(pub, ip(10, 0, 0, 5)) }).fetch("https://evil.example.com/a").fault) // ONE private answer among public ones is enough
        assertEquals(PhotoFault.BLOCKED_HOST, fetcher(h, { listOf(v6("::ffff:127.0.0.1")) }).fetch("https://evil.example.com/a").fault)
        assertEquals(PhotoFault.FAILED, fetcher(h, { emptyList() }).fetch("https://evil.example.com/a").fault)
        assertEquals(PhotoFault.FAILED, fetcher(h, { throw IOException("nx") }).fetch("https://evil.example.com/a").fault)
        assertTrue(h.urls.isEmpty())
    }

    @Test fun aRedirectToAPrivateOrInsecureTargetIsRefused() {
        for (to in listOf("https://127.0.0.1/a.jpg", "https://192.168.0.1/a.jpg", "http://example.com/a.jpg", "https://[::1]/a", "ftp://example.com/a", "https://printer.local/a")) {
            val h = hop(redirect(to), ok(jpeg)); assertEquals(to, PhotoFault.REDIRECT, fetcher(h).fetch("https://example.com/a").fault); assertEquals(1, h.urls.size)
        }
        // a redirect to a NAME that resolves to a private address
        val h = hop(redirect("https://inner.example.org/a"), ok(jpeg))
        assertEquals(PhotoFault.REDIRECT, fetcher(h, { n -> if (n == "inner.example.org") listOf(ip(172, 20, 0, 1)) else listOf(pub) }).fetch("https://example.com/a").fault); assertEquals(1, h.urls.size)
        // a redirect with no Location
        assertEquals(PhotoFault.REDIRECT, fetcher(hop(HopResponse(302, null, 0, null))).fetch("https://example.com/a").fault)
    }

    @Test fun upToThreeRedirectsFollowAndRelativeTargetsResolve() {
        val h = hop(redirect("/b"), redirect("https://cdn.example.net/c", 301), redirect("d", 307), ok(png))
        val r = fetcher(h).fetch("https://example.com/a/x")
        assertNull(r.fault); assertEquals(listOf("https://example.com/a/x", "https://example.com/b", "https://cdn.example.net/c", "https://cdn.example.net/d"), h.urls)
        val four = hop(redirect("/1"), redirect("/2"), redirect("/3"), redirect("/4"), ok(png))
        assertEquals(PhotoFault.REDIRECT, fetcher(four).fetch("https://example.com/a").fault); assertEquals(4, four.urls.size)
    }

    @Test fun theSizeCapAbortsTheStreamAndTheContentLengthPreCheckNeverReadsIt() {
        class Endless : InputStream() { var read = 0L; override fun read(): Int { read++; return 1 }; override fun read(b: ByteArray, o: Int, l: Int): Int { read += l; return l } }
        val e = Endless()
        assertEquals(PhotoFault.TOO_BIG, fetcher(hop(ok(ByteArray(0), length = -1, stream = e))).fetch("https://example.com/a").fault)
        assertTrue("aborted just past the cap, not read to the end: ${e.read}", e.read <= RefPhoto.MAX_BYTES + 32 * 1024)
        val e2 = Endless()
        assertEquals(PhotoFault.TOO_BIG, fetcher(hop(ok(ByteArray(0), length = RefPhoto.MAX_BYTES + 1L, stream = e2))).fetch("https://example.com/a").fault)
        assertEquals("the pre-check reads nothing", 0L, e2.read)
        val exact = jpeg + ByteArray(RefPhoto.MAX_BYTES - jpeg.size)
        assertNull(fetcher(hop(ok(exact))).fetch("https://example.com/a").fault)
        assertEquals(PhotoFault.TOO_BIG, fetcher(hop(ok(exact + byteArrayOf(1)))).fetch("https://example.com/a").fault)
    }

    @Test fun onlyMagicBytesDecideAndTheNameAndTheHeaderAreNeverRead() {
        // an HTML page behind a link that ends in .jpg
        assertEquals(PhotoFault.NOT_AN_IMAGE, fetcher(hop(ok(html))).fetch("https://example.com/photo.jpg").fault)
        assertEquals(PhotoFault.NOT_AN_IMAGE, fetcher(hop(ok(byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0, 0)))).fetch("https://example.com/a.png").fault) // GIF
        assertEquals(PhotoFault.NOT_AN_IMAGE, fetcher(hop(ok("RIFF....WAVEfmt ".toByteArray()))).fetch("https://example.com/a.webp").fault)
        assertEquals(PhotoFault.NOT_AN_IMAGE, fetcher(hop(ok(ByteArray(0)))).fetch("https://example.com/a.jpg").fault) // an empty body
        assertNull(fetcher(hop(ok(jpeg))).fetch("https://example.com/readme.txt").fault) // a JPEG behind a .txt name is a JPEG
        assertEquals("jpeg", RefPhoto.sniff(jpeg)); assertEquals("png", RefPhoto.sniff(png)); assertEquals("webp", RefPhoto.sniff(webp)); assertNull(RefPhoto.sniff(html))
    }

    @Test fun anyStatusButOkIsAFaultAndNothingIsRetried() {
        val h = hop(HopResponse(404, null, 0, ByteArrayInputStream(jpeg)), ok(jpeg))
        assertEquals(PhotoFault.HTTP, fetcher(h).fetch("https://example.com/a").fault); assertEquals(1, h.urls.size)
        assertEquals(PhotoFault.HTTP, fetcher(hop(HopResponse(200, null, 4, null))).fetch("https://example.com/a").fault)
        assertEquals(PhotoFault.FAILED, fetcher({ _, _ -> throw IOException("x") }).fetch("https://example.com/a").fault)
        assertEquals(PhotoFault.TIMEOUT, fetcher({ _, _ -> throw java.net.SocketTimeoutException() }).fetch("https://example.com/a").fault)
    }

    @Test fun theTotalDeadlineStopsASlowBodyAndTheAdmissionIsAskedBeforeEveryHop() {
        var t = 0L
        val slow = object : InputStream() { override fun read(): Int { t += 10_000; return 1 }; override fun read(b: ByteArray, o: Int, l: Int): Int { t += 10_000; b[o] = 1; return 1 } }
        assertEquals(PhotoFault.TIMEOUT, fetcher(hop(ok(ByteArray(0), -1, slow)), clock = { t }).fetch("https://example.com/a").fault)
        for (f in PhotoFault.values().filter { it in setOf(PhotoFault.OFFLINE_ONLY, PhotoFault.NOT_FREE_WIFI, PhotoFault.DATA_SAVER, PhotoFault.NO_NETWORK) }) {
            val h = hop(ok(jpeg)); assertEquals(f, fetcher(h, admission = { f }).fetch("https://example.com/a").fault); assertTrue(h.urls.isEmpty())
        }
        var asked = 0; val h = hop(redirect("/b"), ok(jpeg))
        assertNull(fetcher(h, admission = { asked++; null }).fetch("https://example.com/a").fault); assertEquals(2, asked)
        var n = 0; val flip = hop(redirect("/b"), ok(jpeg))
        assertEquals("Offline only switched on during a redirect stops the next hop", PhotoFault.OFFLINE_ONLY, fetcher(flip, admission = { if (n++ == 0) null else PhotoFault.OFFLINE_ONLY }).fetch("https://example.com/a").fault); assertEquals(1, flip.urls.size)
        assertEquals("an unreadable state is refused", PhotoFault.OFFLINE_ONLY, fetcher(hop(ok(jpeg)), admission = { throw IllegalStateException() }).fetch("https://example.com/a").fault)
    }

    // ---- pixels and the picker's stream ----
    @Test fun pixelCapsAndUnreadableImagesAreRefusedBeforeAnythingIsStored() {
        fun check(w: Int, h: Int, bytes: ByteArray = jpeg) = RefPhoto.validate(bytes, { w to h })
        assertNull(check(4000, 3000)); assertNull(check(8000, 6000)); assertEquals(PhotoFault.DIMENSIONS, check(8000, 8000))
        assertEquals(PhotoFault.DIMENSIONS, check(8001, 10)); assertEquals(PhotoFault.DIMENSIONS, check(10, 8001)); assertEquals(PhotoFault.DIMENSIONS, check(7000, 7000)) // 49 MP
        assertEquals(PhotoFault.UNREADABLE, check(0, 10)); assertEquals(PhotoFault.UNREADABLE, RefPhoto.validate(jpeg) { null }); assertEquals(PhotoFault.UNREADABLE, RefPhoto.validate(jpeg) { throw IllegalStateException() })
        assertEquals(PhotoFault.NOT_AN_IMAGE, check(10, 10, html)); assertEquals(PhotoFault.NOT_AN_IMAGE, check(10, 10, ByteArray(0))); assertEquals(PhotoFault.TOO_BIG, check(10, 10, jpeg + ByteArray(RefPhoto.MAX_BYTES)))
    }

    @Test fun thePickersStreamHasTheSameCap() {
        assertNotNull(RefPhoto.readCapped(ByteArrayInputStream(jpeg + ByteArray(100))))
        assertNull(RefPhoto.readCapped(ByteArrayInputStream(ByteArray(2000)), cap = 1000))
        assertNotNull(RefPhoto.readCapped(ByteArrayInputStream(ByteArray(1000)), cap = 1000))
    }

    @Test fun theUserAgentIsThePoliteOneAndCarriesNothingOfTheUser() {
        assertTrue(ImageFetcher.USER_AGENT.startsWith("ProactiveContextAgent/")); assertTrue(ImageFetcher.USER_AGENT.contains("github.com")); assertFalse(ImageFetcher.USER_AGENT.contains("@"))
    }
}
