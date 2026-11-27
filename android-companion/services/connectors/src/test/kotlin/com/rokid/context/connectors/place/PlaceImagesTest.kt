package com.rokid.context.connectors.place

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.place.ImageState
import com.rokid.context.memory.place.PlaceEntity
import com.rokid.context.memory.place.PlaceNetGuard
import com.rokid.context.memory.place.PlacePack
import com.rokid.context.memory.place.PlacesDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.net.HttpURLConnection
import java.net.URL

/** B2: the photo phase with fake HTTP only (no socket): parsers, licence filter, thumbnail rules, pacing, pause, resume and catch-up. Synthetic ids and names. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlaceImagesTest {
    private lateinit var db: PlacesDatabase
    @Before fun open() { db = PlacesDatabase.inMemory(ApplicationProvider.getApplicationContext()) }
    @After fun close() = db.close()
    private fun pack() = PlacePack(db, null, PlacePack.DEFAULT_BUDGET_BYTES, { 1_000L }, { })
    private fun place(id: String, seeded: Boolean = true) = PlaceEntity(id, null, "N $id", "monument", 17.0, 78.0, 50, "s", "wikipedia:en", "p", 1L, seeded, false, 0, 10)
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4)

    // ---- parsers ----
    @Test fun wikidataRefsReadP18AndTheEnglishTitleAndMissingParts() {
        val r = PlaceImageHttpSource.parseWikidata("""{"entities":{"Q1":{"claims":{"P18":[{"mainsnak":{"datavalue":{"value":"A_b.jpg"}}}]},"sitelinks":{"enwiki":{"title":"T1"}}},"Q2":{"claims":[],"sitelinks":[]}}}""")
        assertEquals(WikidataImageRef("T1", "A b.jpg"), r["Q1"]); assertEquals(WikidataImageRef(null, null), r["Q2"])
    }

    @Test fun pageImagesParseIncludingAPageWithoutAnImageAndNormalizedAndRedirectedTitles() {
        val body = """{"batchcomplete":true,"query":{"normalized":[{"from":"t_one","to":"T one"}],"redirects":[{"from":"Old","to":"New"}],"pages":[{"pageid":1,"title":"T one","pageimage":"X_y.jpg"},{"pageid":2,"title":"New","pageimage":"Z.jpg"},{"pageid":3,"title":"No image"}]}}"""
        val r = PlaceImageHttpSource.parsePageImages(body, listOf("t_one", "Old", "No image", "Absent"))
        assertEquals(mapOf("t_one" to "X y.jpg", "Old" to "Z.jpg"), r)
    }

    @Test fun anApiErrorBodyIsAFailureNotAnEmptyAnswer() {
        try { PlaceImageHttpSource.parsePageImages("""{"error":{"code":"ratelimited"}}""", listOf("a")); throw AssertionError("no throw") } catch (e: kotlinx.serialization.SerializationException) { }
    }

    private fun commons(name: String, lic: String?, nonFree: String? = null, mime: String = "image/jpeg", thumb: String = "https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/$name/330px-$name", artist: String = "<a href=\"x\">Ann &amp; Bo</a>", licUrl: String = "https://creativecommons.org/licenses/by-sa/4.0") =
        """{"title":"File:$name","imageinfo":[{"thumburl":"$thumb","mime":"$mime","descriptionurl":"https://commons.wikimedia.org/wiki/File:$name","extmetadata":{${if (lic != null) "\"LicenseShortName\":{\"value\":\"$lic\"}," else ""}"LicenseUrl":{"value":"$licUrl"},"Artist":{"value":${kotlinx.serialization.json.JsonPrimitive(artist)}}${if (nonFree != null) ",\"NonFree\":{\"value\":\"$nonFree\"}" else ""}}}]}"""

    @Test fun commonsLicenceFilterKeepsFreeAndDropsNonFreeUnknownAndNonCommercial() {
        val names = listOf("a.jpg" to "CC BY-SA 4.0", "b.jpg" to "CC0", "c.jpg" to "Public domain", "d.jpg" to "CC BY-NC 4.0", "e.jpg" to "Fair use", "f.jpg" to null, "g.jpg" to "CC BY-ND 2.0", "h.jpg" to "Attribution")
        val body = """{"query":{"pages":[${names.joinToString(",") { commons(it.first, it.second) }},${commons("i.jpg", "CC BY 4.0", nonFree = "true")}]}}"""
        val r = PlaceImageHttpSource.parseCommons(body)
        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), r.values.filter { it.free }.map { it.name }.sorted())
        assertEquals("Ann & Bo", r.getValue("a.jpg").author); assertEquals("CC BY-SA 4.0", r.getValue("a.jpg").licence)
        assertEquals("https://creativecommons.org/licenses/by-sa/4.0", r.getValue("a.jpg").licenceUrl)
        assertEquals("https://commons.wikimedia.org/wiki/File:a.jpg", r.getValue("a.jpg").filePage)
        assertFalse(r.getValue("i.jpg").free)
    }

    @Test fun aThumbnailOrLicenceLinkOnAnotherHostIsDropped() {
        val r = PlaceImageHttpSource.parseCommons("""{"query":{"pages":[${commons("a.jpg", "CC0", thumb = "https://evil.example/a.jpg", licUrl = "http://creativecommons.org/x")}]}}""").getValue("a.jpg")
        assertNull(r.thumbUrl); assertNull(r.licenceUrl)
    }

    // ---- the fetcher ----
    private class Api : PlaceImageApi {
        val refs = HashMap<String, WikidataImageRef>(); val lead = HashMap<String, String>(); val files = HashMap<String, CommonsFile>(); val bytes = HashMap<String, ByteArray>()
        val thumbCalls = mutableListOf<String>(); var wikidataCalls = 0; var pageCalls = 0; var commonsCalls = 0; var thumbError: (String) -> Exception? = { null }
        override suspend fun wikidataRefs(qids: List<String>): Map<String, WikidataImageRef> { wikidataCalls++; return qids.mapNotNull { q -> refs[q]?.let { q to it } }.toMap() }
        override suspend fun pageImages(titles: List<String>): Map<String, String> { pageCalls++; return titles.mapNotNull { t -> lead[t]?.let { t to it } }.toMap() }
        override suspend fun commonsFiles(names: List<String>): Map<String, CommonsFile> { commonsCalls++; return names.mapNotNull { n -> files[n]?.let { n to it } }.toMap() }
        override suspend fun thumbnail(url: String): ByteArray { thumbCalls += url; thumbError(url)?.let { throw it }; return bytes[url] ?: throw PlaceHttpException(404) }
    }
    private fun file(n: String, free: Boolean = true, mime: String = "image/jpeg") = CommonsFile(n, "https://upload.wikimedia.org/t/$n", mime, "https://commons.wikimedia.org/wiki/File:$n", "Ann", "CC BY 4.0", "https://creativecommons.org/licenses/by/4.0/", free)
    private val codec = ThumbCodec { Thumb(it, 330, 220) }
    private var now = 1_000L
    private fun fetcher(api: Api, c: ThumbCodec = codec, conc: DownloadConcurrency = DownloadConcurrency()) = PlaceImageFetcher(api, c, { now }, conc)
    private fun cands(vararg ids: String) = ids.map { PhotoCandidate(it, 0) }

    @Test fun leadImageIsPreferredP18IsTheFallbackAndNoImageIsNone() = runBlocking {
        val a = Api()
        a.refs["Q1"] = WikidataImageRef("T1", "p18a.jpg"); a.lead["T1"] = "lead.jpg"
        a.refs["Q2"] = WikidataImageRef("T2", "p18b.jpg")
        a.refs["Q3"] = WikidataImageRef("T3", null)
        listOf("lead.jpg", "p18b.jpg").forEach { a.files[it] = file(it); a.bytes["https://upload.wikimedia.org/t/$it"] = jpeg }
        val rows = mutableListOf<com.rokid.context.memory.place.PlaceImageEntity>()
        val c = fetcher(a).fetch(cands("wd:Q1", "wd:Q2", "wd:Q3", "osm:w9")) { rows += it }
        assertEquals(PlaceImageFetcher.Counts(ok = 2, none = 2, skipped = 0, failed = 0), c)
        assertEquals(1, a.wikidataCalls); assertEquals(1, a.pageCalls); assertEquals(1, a.commonsCalls) // one call per stage for the whole batch, not per place
        val q1 = rows.single { it.placeId == "wd:Q1" }
        assertEquals("OK", q1.status); assertEquals("CC BY 4.0", q1.licence); assertEquals("Ann", q1.author); assertEquals("https://commons.wikimedia.org/wiki/File:lead.jpg", q1.filePage); assertEquals(jpeg.size, q1.bytes)
        assertEquals("NONE", rows.single { it.placeId == "osm:w9" }.status)
    }

    @Test fun nonFreeAndNonJpegAreSkippedAndStoreNoPhotoAndNoCredit() = runBlocking {
        val a = Api(); a.refs["Q1"] = WikidataImageRef("T1", "nf.jpg"); a.refs["Q2"] = WikidataImageRef("T2", "map.png")
        a.files["nf.jpg"] = file("nf.jpg", free = false); a.files["map.png"] = file("map.png", mime = "image/png")
        val rows = mutableListOf<com.rokid.context.memory.place.PlaceImageEntity>()
        val c = fetcher(a).fetch(cands("wd:Q1", "wd:Q2")) { rows += it }
        assertEquals(2, c.skipped); assertTrue(a.thumbCalls.isEmpty())
        assertTrue(rows.all { it.status == "SKIPPED" && it.jpeg == null && it.author == null && it.licence == null })
    }

    @Test fun downloadsAreOnePerSecondAndMagicBytesAndCapAreEnforced() = runBlocking {
        val a = Api()
        for (i in 1..4) { a.refs["Q$i"] = WikidataImageRef(null, "f$i.jpg"); a.files["f$i.jpg"] = file("f$i.jpg") }
        a.bytes["https://upload.wikimedia.org/t/f1.jpg"] = jpeg
        a.bytes["https://upload.wikimedia.org/t/f2.jpg"] = "<html>not an image</html>".toByteArray()
        a.thumbError = { if (it.endsWith("f3.jpg")) PlaceTooLarge() else null }
        a.bytes["https://upload.wikimedia.org/t/f4.jpg"] = jpeg
        val rows = mutableListOf<com.rokid.context.memory.place.PlaceImageEntity>()
        val c = fetcher(a).fetch(cands("wd:Q1", "wd:Q2", "wd:Q3", "wd:Q4")) { rows += it }
        assertEquals(PlaceImageFetcher.Counts(ok = 2, none = 0, skipped = 1, failed = 1), c)
        assertEquals("FAILED", rows.single { it.placeId == "wd:Q2" }.status); assertEquals(1, rows.single { it.placeId == "wd:Q2" }.tries)
        assertTrue(rows.none { it.placeId == "wd:Q2" && it.jpeg != null })
    }

    @Test fun downloadsRunSeveralAtOnceButNeverMoreThanTheLimit() = runBlocking {
        val a = Api(); for (i in 1..10) { a.refs["Q$i"] = WikidataImageRef(null, "f$i.jpg"); a.files["f$i.jpg"] = file("f$i.jpg"); a.bytes["https://upload.wikimedia.org/t/f$i.jpg"] = jpeg }
        val live = java.util.concurrent.atomic.AtomicInteger(); val peak = java.util.concurrent.atomic.AtomicInteger()
        val slow = object : PlaceImageApi by a {
            override suspend fun thumbnail(url: String): ByteArray { val n = live.incrementAndGet(); peak.updateAndGet { maxOf(it, n) }; kotlinx.coroutines.delay(20); live.decrementAndGet(); return a.thumbnail(url) }
        }
        val rows = mutableListOf<com.rokid.context.memory.place.PlaceImageEntity>()
        val c = PlaceImageFetcher(slow, codec, { now }, DownloadConcurrency(4)).fetch(cands(*Array(10) { "wd:Q${it + 1}" })) { rows += it }
        assertEquals(10, c.ok); assertEquals(10, rows.size)
        assertEquals(4, peak.get())
    }

    @Test fun a429DropsToOneConnectionAndItClimbsBackSlowly() {
        val conc = DownloadConcurrency(4)
        assertEquals(4, conc.limit)
        conc.onThrottle(); assertEquals(1, conc.limit)
        conc.onCleanBatch(); assertEquals(1, conc.limit)
        conc.onCleanBatch(); assertEquals(2, conc.limit)
        repeat(6) { conc.onCleanBatch() }; assertEquals(4, conc.limit)
        conc.onCleanBatch(); conc.onCleanBatch(); assertEquals(4, conc.limit)
    }

    @Test fun aThrottledDownloadSetsTheLimitToOneAndAsksTheCallerToRetry() {
        val a = Api(); for (i in 1..3) { a.refs["Q$i"] = WikidataImageRef(null, "f$i.jpg"); a.files["f$i.jpg"] = file("f$i.jpg"); a.bytes["https://upload.wikimedia.org/t/f$i.jpg"] = jpeg }
        a.thumbError = { if (it.endsWith("f2.jpg")) PlaceHttpException(503, 9) else null }
        val conc = DownloadConcurrency(4)
        try { runBlocking { fetcher(a, conc = conc).fetch(cands("wd:Q1", "wd:Q2", "wd:Q3")) { } }; throw AssertionError("no retry") } catch (e: PhotoPhaseRetry) { }
        assertEquals(1, conc.limit)
    }

    @Test fun pngAndWebpMagicPassTheCheckAndAnUndecodableOneFailsOnlyThatPlace() = runBlocking {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A, 0, 0)
        val webp = "RIFF....WEBPxx".toByteArray()
        assertTrue(ImageMagic.isImage(png) && ImageMagic.isImage(webp) && ImageMagic.isImage(jpeg)); assertFalse(ImageMagic.isImage("GIF89a......".toByteArray()))
        val a = Api(); a.refs["Q1"] = WikidataImageRef(null, "p.jpg"); a.files["p.jpg"] = file("p.jpg"); a.bytes["https://upload.wikimedia.org/t/p.jpg"] = png
        val rows = mutableListOf<com.rokid.context.memory.place.PlaceImageEntity>()
        fetcher(a, ThumbCodec { Thumb(jpeg, 330, 200) }).fetch(cands("wd:Q1")) { rows += it }
        assertEquals("OK", rows.single().status) // a PNG was re-encoded to a JPEG by the codec
        rows.clear(); fetcher(a, ThumbCodec { null }).fetch(cands("wd:Q1")) { rows += it }
        assertEquals("FAILED", rows.single().status)
    }

    @Test fun a429OrANetworkErrorBurnsNoTryAndKeepsWhatWasStored() {
        val a = Api(); for (i in 1..2) { a.refs["Q$i"] = WikidataImageRef(null, "f$i.jpg"); a.files["f$i.jpg"] = file("f$i.jpg"); a.bytes["https://upload.wikimedia.org/t/f$i.jpg"] = jpeg }
        a.thumbError = { if (it.endsWith("f2.jpg")) PlaceHttpException(429, 7) else null }
        val rows = mutableListOf<com.rokid.context.memory.place.PlaceImageEntity>()
        try { runBlocking { fetcher(a).fetch(cands("wd:Q1", "wd:Q2")) { rows += it } }; throw AssertionError("no retry") } catch (e: PhotoPhaseRetry) { }
        assertEquals(listOf("wd:Q1"), rows.map { it.placeId }) // the first is kept, the second has no row and no try
    }

    @Test fun aSingle429IsWaitedOutInTheRunAndEveryPlaceIsStoredOnce() {
        val a = Api(); for (i in 1..4) { a.refs["Q$i"] = WikidataImageRef(null, "f$i.jpg"); a.files["f$i.jpg"] = file("f$i.jpg"); a.bytes["https://upload.wikimedia.org/t/f$i.jpg"] = jpeg }
        var refused = false
        a.thumbError = { if (!refused && it.endsWith("f2.jpg")) { refused = true; PlaceHttpException(429, 7) } else null }
        val rows = mutableListOf<com.rokid.context.memory.place.PlaceImageEntity>()
        runBlocking { fetcher(a).fetch(cands("wd:Q1", "wd:Q2", "wd:Q3", "wd:Q4")) { rows += it } } // no PhotoPhaseRetry: it carries on after the refusal
        assertEquals(setOf("wd:Q1", "wd:Q2", "wd:Q3", "wd:Q4"), rows.map { it.placeId }.toSet())
        assertEquals(4, rows.size) // none stored twice
    }

    // ---- the phase over a real pack (resume, catch-up) ----
    @Test fun thePhaseFillsAreasSeededBeforeAndResumesAfterAStop() = runBlocking {
        val p = pack(); db.dao().putPlaces((1..5).map { place("wd:Q$it") } + place("osm:w1") + place("user:00000000-0000-0000-0000-000000000001", seeded = false))
        val a = Api(); for (i in 1..5) { a.refs["Q$i"] = WikidataImageRef(null, "f$i.jpg"); a.files["f$i.jpg"] = file("f$i.jpg"); a.bytes["https://upload.wikimedia.org/t/f$i.jpg"] = jpeg }
        var boom = true
        a.thumbError = { if (boom && it.endsWith("f3.jpg")) PlaceHttpException(503) else null }
        val phase = PlaceImagePhase(p, fetcher(a), { "sp" })
        try { phase.run(); throw AssertionError("no stop") } catch (e: PhotoPhaseRetry) { }
        val kept = p.imagesOf((1..5).map { "wd:Q$it" }).map { it.placeId }.toSet()
        assertTrue("what finished before the stop is kept, the stopped place has no row", "wd:Q1" in kept && "wd:Q3" !in kept)
        boom = false
        assertEquals(PhotoEnd.DONE, phase.run())
        val t = p.photoTotals(); assertEquals(6, t.seeded); assertEquals(5, t.ok); assertEquals(1, t.none); assertEquals(0, t.waiting) // osm place = none; the pin is not counted
        assertTrue(a.thumbCalls.count { it.endsWith("f1.jpg") } == 1) // a finished place is never fetched twice
    }

    @Test fun aFailedPlaceIsRetriedUpToTheCapThenLeftAlone() = runBlocking {
        val p = pack(); db.dao().putPlaces(listOf(place("wd:Q1")))
        val a = Api(); a.refs["Q1"] = WikidataImageRef(null, "f.jpg"); a.files["f.jpg"] = file("f.jpg") // bytes absent: 404
        assertEquals(PhotoEnd.DONE, PlaceImagePhase(p, fetcher(a), { "sp" }).run())
        val r = p.imagesOf(listOf("wd:Q1")).single(); assertEquals("FAILED", r.status); assertEquals(PlaceImageFetcher.MAX_TRIES, r.tries)
        assertEquals(PlaceImageFetcher.MAX_TRIES, a.thumbCalls.size)
    }

    @Test fun theCapStopsThePhase() = runBlocking {
        val p = pack(); db.dao().putPlaces(listOf(place("wd:Q1")))
        assertEquals(PhotoEnd.CAP_REACHED, PlaceImagePhase(p, fetcher(Api()), { "sp" }, capBytes = 0).run())
    }

    // ---- the real HTTP class: allow-list, scheme, redirects, Retry-After through the shared guard ----
    private class Conn(u: URL, private val code: Int, private val body: ByteArray, private val big: Int = 0) : HttpURLConnection(u) {
        override fun connect() {}; override fun disconnect() {}; override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getHeaderField(name: String?): String? = if (name.equals("Retry-After", true)) "9" else null
        override fun getContentEncoding(): String? = null
        override fun getInputStream() = if (big > 0) java.io.ByteArrayInputStream(ByteArray(big)) else java.io.ByteArrayInputStream(body)
    }
    private fun realHttp(code: Int = 200, big: Int = 0, opened: MutableList<String> = mutableListOf(), guard: PlaceNetGuard = PlaceNetGuard()) =
        UrlConnectionPlaceHttp(minGapMs = 0, guard = guard, open = { opened += it.host; Conn(it, code, jpeg, big) })

    @Test fun getBytesRefusesOtherHostsHttpAndCredentialsBeforeAnyConnection() {
        val opened = mutableListOf<String>(); val h = realHttp(opened = opened)
        for (u in listOf("https://evil.example/a.jpg", "http://upload.wikimedia.org/a.jpg", "https://user@upload.wikimedia.org/a.jpg", "https://commons.wikimedia.org/a.jpg", "https://upload.wikimedia.org.evil.example/a.jpg"))
            try { runBlocking { h.getBytes(u, 1000) }; throw AssertionError(u) } catch (e: IllegalArgumentException) { }
        assertTrue(opened.isEmpty())
        assertEquals(jpeg.size, runBlocking { h.getBytes("https://upload.wikimedia.org/a.jpg", 1000) }.size); assertEquals(listOf("upload.wikimedia.org"), opened)
    }

    @Test fun aRedirectIsNotFollowedAndABodyOverTheCapIsTooLarge() {
        try { runBlocking { realHttp(code = 302).getBytes("https://upload.wikimedia.org/a.jpg", 1000) }; throw AssertionError("followed") } catch (e: PlaceHttpException) { assertEquals(302, e.code) }
        try { runBlocking { realHttp(big = 2_000).getBytes("https://upload.wikimedia.org/a.jpg", 1_000) }; throw AssertionError("no cap") } catch (e: PlaceTooLarge) { }
    }

    @Test fun aMediaRetryAfterPausesTheWikimediaGroupThroughTheSharedGuard() {
        var t = 1_000_000L; val waits = mutableListOf<Long>(); val logs = mutableListOf<String>()
        val guard = PlaceNetGuard(clock = { t }, sleep = { t += it; waits += it }, log = { logs += it })
        try { runBlocking { realHttp(code = 429, guard = guard).getBytes("https://upload.wikimedia.org/a.jpg", 1000) }; throw AssertionError() } catch (e: PlaceHttpException) { assertEquals(9, e.retryAfterS) }
        runBlocking { realHttp(guard = guard).get("https://en.wikipedia.org/w/api.php?action=query") }
        assertEquals(9_000L, waits.sum()) // the next TEXT request waits the media pause: one group
        assertTrue(logs.any { it.startsWith("place req failed svc=wiki_image status=429") })
        assertTrue(logs.none { it.contains("upload.wikimedia") || it.contains("a.jpg") })
    }
}
