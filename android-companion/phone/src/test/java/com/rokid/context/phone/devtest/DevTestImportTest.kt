package com.rokid.context.phone.devtest

import com.rokid.context.memory.moment.MomentId
import com.rokid.context.memory.place.UserPlace
import com.rokid.context.work.frames.place.PlaceFix
import com.rokid.context.work.frames.place.PlacePinning
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class DevTestImportTest {
    private var stored = listOf<UserPlace>()
    private var ids = 0
    private var fix: PlaceFix? = PlaceFix(12.9, 77.6, 1_000, 10f)
    private var zone = false
    private var ingested = 0
    private var ingestAt: Long? = 1_700_000_000_000L
    private var fetchCalls = 0
    private var fetchResult: Result<ByteArray> = Result.success(byteArrayOf(1, 2, 3))
    private var allowed = true

    private val pinning = PlacePinning(
        fix = { fix }, zoneBlocked = { zone }, zones = { emptyList() }, nearby = { _, _ -> emptyList() }, load = { stored }, save = { stored = it; true },
        elapsed = { 0L }, newId = { "user:00000000-0000-0000-0000-%012d".format(++ids) }, schedule = { _, _ -> {} },
        momentAlive = { true },
    )
    private val dev = DevTestImport(pinning, ingest = { ingested++; ingestAt }, fetch = { fetchCalls++; fetchResult }, fetchAllowed = { allowed })

    @Test fun placeHereMakesAMarkedTestPin() = runBlocking {
        assertTrue(dev.placeHere("Corner shop").startsWith("Test place added"))
        val p = stored.single()
        assertEquals("Test: Corner shop", p.name); assertEquals(DevTestImport.NOTE, p.note); assertEquals(12.9, p.lat, 0.0); assertTrue(p.isValid)
        assertEquals(listOf(p), dev.testPlaces())
    }

    @Test fun placeHereIsRefusedInAZoneOrWithoutAFix() = runBlocking {
        zone = true; assertTrue(dev.placeHere("x").contains("pause zone")); assertTrue(stored.isEmpty())
        zone = false; fix = null; assertTrue(dev.placeHere("x").contains("No fresh")); assertTrue(stored.isEmpty())
    }

    @Test fun placeAtTakesTypedCoordinatesAndRejectsBadOnes() = runBlocking {
        assertTrue(dev.placeAt("", "12.5", "77.5").startsWith("Test place added"))
        assertEquals("Test: place", stored.single().name)
        assertTrue(dev.placeAt("x", "abc", "1").startsWith("Enter a latitude"))
        assertTrue(dev.placeAt("x", "95", "1").startsWith("Enter a latitude"))
        assertEquals(1, stored.size)
    }

    @Test fun addImageAttachesTheMomentToTheTestPlace() = runBlocking {
        dev.placeAt("Shop", "1", "2")
        val pin = stored.single()
        assertTrue(dev.addImage(pin.id, byteArrayOf(9)).startsWith("Imported photo attached"))
        assertEquals(MomentId.ofMs(1_700_000_000_000L), stored.single().momentId) // the pin points at the Moment, never the other way round
    }

    @Test fun addImageNeedsATestPlaceAndAStoredPhoto() = runBlocking {
        assertTrue(dev.addImage("user:none", byteArrayOf(1)).startsWith("Choose a test place"))
        assertEquals(0, ingested)
        dev.placeAt("Shop", "1", "2"); ingestAt = null
        assertTrue(dev.addImage(stored.single().id, byteArrayOf(1)).startsWith("The image was not stored"))
        assertNull(stored.single().momentId)
    }

    @Test fun aRealPinIsNotATestPlace() = runBlocking {
        stored = listOf(UserPlace("user:00000000-0000-0000-0000-000000000099", "Real", "attraction", 1.0, 2.0, note = "my note"))
        assertTrue(dev.testPlaces().isEmpty())
        assertTrue(dev.addImage(stored.single().id, byteArrayOf(1)).startsWith("Choose a test place"))
    }

    @Test fun urlFetchIsHttpsOnlyAndGated() = runBlocking {
        dev.placeAt("Shop", "1", "2"); val id = stored.single().id
        assertTrue(dev.addImageFromUrl(id, "http://example.com/a.jpg").startsWith("Only https"))
        assertTrue(dev.addImageFromUrl(id, "file:///sdcard/a.jpg").startsWith("Only https"))
        allowed = false
        assertTrue(dev.addImageFromUrl(id, "https://example.com/a.jpg").contains("refuses"))
        assertEquals(0, fetchCalls)
        allowed = true
        assertTrue(dev.addImageFromUrl(id, "https://example.com/a.jpg").startsWith("Imported photo attached"))
        assertEquals(1, fetchCalls)
        fetchResult = Result.failure(IOException("HTTP 404"))
        assertTrue(dev.addImageFromUrl(id, "https://example.com/b.jpg").contains("HTTP 404"))
    }

    @Test fun validUrlAcceptsOnlyHttps() {
        assertNotNull(DevImageFetch.validUrl(" https://example.com/a.jpg "))
        assertNull(DevImageFetch.validUrl("http://example.com/a.jpg")); assertNull(DevImageFetch.validUrl("ftp://x/y")); assertNull(DevImageFetch.validUrl("not a url"))
    }

    @Test fun readCappedStopsAtTheCap() {
        assertEquals(10, DevImageFetch.readCapped(ByteArrayInputStream(ByteArray(10)), 10).size)
        assertTrue(runCatching { DevImageFetch.readCapped(ByteArrayInputStream(ByteArray(11)), 10) }.exceptionOrNull() is IOException)
        assertEquals(10L * 1024 * 1024, DevImageFetch.MAX_BYTES)
    }

    private fun clientAnswering(code: Int, body: ByteArray, contentLength: Long? = null) = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val rb = object : okhttp3.ResponseBody() {
            override fun contentType() = "image/jpeg".toMediaType()
            override fun contentLength() = contentLength ?: body.size.toLong()
            override fun source() = okio.Buffer().write(body)
        }
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("m").body(rb).build()
    }).build()

    @Test fun fetchReadsAnImageRefusesAnOversizeOneAndAnHttpError() {
        assertEquals(3, DevImageFetch.fetch(clientAnswering(200, ByteArray(3)), "https://example.com/a.jpg").getOrThrow().size)
        assertTrue(DevImageFetch.fetch(clientAnswering(200, ByteArray(3), contentLength = DevImageFetch.MAX_BYTES + 1), "https://example.com/a.jpg").isFailure)
        assertTrue(DevImageFetch.fetch(clientAnswering(200, ByteArray(20)), "https://example.com/a.jpg", max = 10).isFailure)
        assertTrue(DevImageFetch.fetch(clientAnswering(404, ByteArray(3)), "https://example.com/a.jpg").isFailure)
        assertTrue(DevImageFetch.fetch(clientAnswering(200, ByteArray(3)), "http://example.com/a.jpg").isFailure)
    }
}
