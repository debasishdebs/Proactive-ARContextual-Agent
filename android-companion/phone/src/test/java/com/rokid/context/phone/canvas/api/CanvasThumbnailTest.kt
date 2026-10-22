package com.rokid.context.phone.canvas.api

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.InMemoryKeyVault
import com.rokid.context.ai.InMemorySeedState
import com.rokid.context.ai.ProviderBuilder
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.ForgetTombstoneEntity
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.forget.ForgetService
import com.rokid.context.memory.frames.FrameCipher
import com.rokid.context.memory.frames.FrameImages
import com.rokid.context.memory.frames.TinkFrameCipher
import com.rokid.context.model.ModelProvider
import com.rokid.context.orchestration.MemoryWipe
import com.rokid.context.phone.canvas.server.AssetSource
import com.rokid.context.phone.canvas.server.CanvasAuth
import com.rokid.context.phone.canvas.server.CanvasRouter
import com.rokid.context.phone.canvas.server.HostPolicy
import com.rokid.context.phone.canvas.server.HttpRequest
import com.rokid.context.phone.canvas.server.HttpResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The photo routes of the canvas (spec 0z, user addition): `GET /api/frames/{id}/thumb` and `/image` through the real router, API and backend.
 * A photo is served only to a paired browser, on the same origin, by an id the caller names (there is no listing), never after Forget / Delete all /
 * expiry, decrypted in memory only, with `Cache-Control: no-store`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CanvasThumbnailTest {
    @get:Rule val tmp = TemporaryFolder()
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val auth = CanvasAuth({ System.currentTimeMillis() })
    private val logs = mutableListOf<String>()
    private lateinit var router: CanvasRouter
    private lateinit var ai: AiGraph
    private lateinit var token: String
    private val port = 8765
    private val host = "127.0.0.1:$port"
    private fun encode(format: Bitmap.CompressFormat, w: Int, h: Int) = ByteArrayOutputStream().also { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).compress(format, 90, it) }.toByteArray()
    private val jpeg = encode(Bitmap.CompressFormat.JPEG, 640, 480)
    private val png = encode(Bitmap.CompressFormat.PNG, 64, 48)

    @Before fun setUp() {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(app))
        FrameImages.configureForTests(TinkFrameCipher.inMemoryForTests()) { true }
        ai = AiGraph(MemoryGraph.get(app), InMemoryKeyVault(), InMemorySeedState(), ProviderBuilder { _, _, _ ->
            object : ModelProvider { override suspend fun generate(prompt: String): String = "{}" }
        }).also { AiGraph.resetForTests(it) }
        val backend = CanvasBackendImpl.create(app, ai, auth, { false }, scope)
        router = CanvasRouter(auth, HostPolicy({ port }, { null }), CanvasApi(backend), AssetSource { null }) { logs += it }
        token = auth.newWebViewToken()
    }

    @After fun tearDown() {
        scope.cancel()
        FrameImages.configureForTests(null) { false }
        AiGraph.resetForTests(null); MemoryGraph.get(app).db.close(); MemoryGraph.resetForTests(null)
    }

    private fun seed(id: String, ts: Long = 1_000, bytes: ByteArray = jpeg, sealed: Boolean = true, name: String = "rokid_${ts}_$id.jpg"): FrameEntity {
        val file = File(tmp.root, name).apply { writeBytes(if (sealed) FrameImages.forDisk(bytes) else bytes) }
        return FrameEntity(id, ts, file.path, "rokid").also { runBlocking { MemoryGraph.get(app).db.frameDao().insertIgnore(it) } }
    }

    private fun get(path: String, bearer: String? = token, headers: Map<String, String> = emptyMap(), method: String = "GET", h: String = host) =
        router.handle(HttpRequest(method, path, emptyMap(), buildMap {
            put("host", h); bearer?.let { put("authorization", "Bearer $it") }; putAll(headers)
        }, ByteArray(0)))

    private fun HttpResponse.header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
    private fun assertSecurityHeaders(r: HttpResponse) {
        assertEquals("no-store", r.header("Cache-Control"))
        assertEquals("nosniff", r.header("X-Content-Type-Options"))
        assertNotNull(r.header("Content-Security-Policy"))
    }
    private fun forget(id: String) = runBlocking { val db = MemoryGraph.get(app).db; ForgetService(db, ProposalRepository(db.proposalDao(), db.actionDao(), db)).forgetMoment(id) }

    // ---- what is served ----

    @Test fun thumbnailIsASmallJpegWithNoStoreAndSameOriginHeaders() {
        seed("f1")
        val r = get("/api/frames/f1/thumb")
        assertEquals(200, r.status)
        assertEquals("image/jpeg", r.contentType)
        assertSecurityHeaders(r)
        assertEquals("same-origin", r.header("Cross-Origin-Resource-Policy"))
        assertTrue(r.body.size < jpeg.size)
        val b = BitmapFactory.decodeByteArray(r.body, 0, r.body.size)
        assertEquals(160, maxOf(b.width, b.height))
    }

    @Test fun fullSizeKeepsItsContentType() {
        seed("f1"); seed("f2", ts = 2_000, bytes = png)
        val a = get("/api/frames/f1/image")
        assertEquals(200, a.status); assertEquals("image/jpeg", a.contentType); assertTrue(a.body.contentEquals(jpeg)); assertSecurityHeaders(a)
        val b = get("/api/frames/f2/image")
        assertEquals(200, b.status); assertEquals("image/png", b.contentType); assertTrue(b.body.contentEquals(png))
        assertEquals("a PNG's thumbnail is a JPEG", "image/jpeg", get("/api/frames/f2/thumb").contentType)
    }

    @Test fun somethingThatIsNotAnImageIsNeverServed() {
        seed("f1", bytes = "secret text, not a photo".toByteArray())
        assertEquals(404, get("/api/frames/f1/image").status)
        assertEquals(404, get("/api/frames/f1/thumb").status)
        seed("f2", ts = 2_000, bytes = byteArrayOf(0x25, 0x50, 0x44, 0x46, 1, 2, 3, 4, 5, 6, 7, 8, 9)) // a PDF
        assertEquals(404, get("/api/frames/f2/image").status)
    }

    // ---- never after Forget, Delete all or expiry ----

    @Test fun forgottenFrameIs404OnBothRoutes() {
        seed("f1")
        assertEquals(200, get("/api/frames/f1/thumb").status)
        forget("f1")
        for (route in listOf("thumb", "image")) {
            val r = get("/api/frames/f1/$route")
            assertEquals(route, 404, r.status)
            assertEquals("not_found", Json.parseToJsonElement(String(r.body)).jsonObject["error"]!!.jsonPrimitive.content)
            assertSecurityHeaders(r) // the error path carries the headers too
            assertTrue("the error holds no photo", r.body.size < 200)
        }
    }

    @Test fun aTombstonedFrameWhoseRowIsKeptIs404() {
        seed("f1")
        runBlocking { MemoryGraph.get(app).db.forgetDao().insertTombstone(ForgetTombstoneEntity("f1", 1)) } // forgetFacts keeps the row with a tombstone
        assertTrue(runBlocking { MemoryGraph.get(app).db.frameDao().byIds(listOf("f1")) }.isNotEmpty())
        assertEquals(404, get("/api/frames/f1/thumb").status)
        assertEquals(404, get("/api/frames/f1/image").status)
    }

    @Test fun anExpiredOrDeletedPhotoIs404() {
        val f = seed("f1")
        File(f.path).delete() // the file is gone, the row is still there
        assertEquals(404, get("/api/frames/f1/thumb").status)
        val g = seed("f2", ts = 2_000)
        runBlocking { MemoryGraph.get(app).db.openHelper.writableDatabase.execSQL("DELETE FROM frame WHERE id = 'f2'") } // photo expiry removes the row, the file may linger
        assertTrue(File(g.path).exists())
        assertEquals(404, get("/api/frames/f2/image").status)
    }

    @Test fun aDeleteAllDuringTheDecryptServesNothing() {
        val real = TinkFrameCipher.inMemoryForTests()
        FrameImages.configureForTests(object : FrameCipher {
            override fun seal(plain: ByteArray) = real.seal(plain)
            override fun open(sealed: ByteArray): ByteArray { MemoryWipe.generation.incrementAndGet(); return real.open(sealed) } // a Delete all lands while the file is opened
        }) { true }
        seed("f1")
        assertEquals(404, get("/api/frames/f1/image").status)
        assertEquals(404, get("/api/frames/f1/thumb").status)
    }

    @Test fun aForgetDuringTheDecryptServesNothing() {
        val real = TinkFrameCipher.inMemoryForTests()
        FrameImages.configureForTests(object : FrameCipher {
            override fun seal(plain: ByteArray) = real.seal(plain)
            override fun open(sealed: ByteArray): ByteArray {
                runBlocking { MemoryGraph.get(app).db.forgetDao().insertTombstone(ForgetTombstoneEntity("f1", 1)) } // Forget lands while the file is opened
                return real.open(sealed)
            }
        }) { true }
        seed("f1")
        assertEquals(404, get("/api/frames/f1/image").status)
    }

    // ---- ids ----

    @Test fun anUnknownIdIs404() {
        seed("f1")
        assertEquals(404, get("/api/frames/nope/thumb").status)
        assertEquals(404, get("/api/frames/nope/image").status)
    }

    @Test fun idsCannotReachAFileOrAPath() {
        val f = seed("f1")
        // a file next to the photo, which a traversal would reach
        File(tmp.root, "secret.txt").writeText("TOP SECRET")
        val bad = listOf("..", ".", "..%2fsecret.txt", "%2e%2e", "a%2fb", "f1%00", "f1.jpg", "../secret.txt", "f1/../f1", "a b", "f1;x", "x".repeat(65), f.path, "..\\secret.txt")
        for (id in bad) for (route in listOf("thumb", "image")) {
            val r = get("/api/frames/$id/$route")
            assertTrue("$id/$route -> ${r.status}", r.status in listOf(400, 404))
            assertFalse(String(r.body).contains("TOP SECRET"))
            assertSecurityHeaders(r)
        }
        // the photo itself is still served by its real id
        assertEquals(200, get("/api/frames/f1/thumb").status)
    }

    // ---- who may ask ----

    @Test fun authIsRequired() {
        seed("f1")
        for (route in listOf("thumb", "image")) {
            val none = get("/api/frames/f1/$route", bearer = null)
            assertEquals(401, none.status); assertSecurityHeaders(none)
            assertEquals(401, get("/api/frames/f1/$route", bearer = "not-a-token").status)
            assertEquals(401, get("/api/frames/f1/$route", bearer = "").status)
        }
        // a token that was signed out stops working at once
        val other = auth.newWebViewToken()
        assertEquals(200, get("/api/frames/f1/thumb", bearer = other).status)
        runBlocking { auth.logout(other) }
        assertEquals(401, get("/api/frames/f1/thumb", bearer = other).status)
        // the token goes in the Authorization header only: a query or a cookie never authenticates
        assertEquals(401, router.handle(HttpRequest("GET", "/api/frames/f1/thumb", mapOf("token" to token, "access_token" to token), mapOf("host" to host, "cookie" to "token=$token"), ByteArray(0))).status)
    }

    @Test fun onlyTheSameOriginMayAsk() {
        seed("f1")
        for (route in listOf("thumb", "image")) {
            assertEquals(200, get("/api/frames/f1/$route", headers = mapOf("origin" to "http://$host")).status)
            assertEquals(200, get("/api/frames/f1/$route", headers = mapOf("sec-fetch-site" to "same-origin")).status)
            val foreign = get("/api/frames/f1/$route", headers = mapOf("origin" to "http://evil.example"))
            assertEquals("$route from a foreign Origin", 403, foreign.status); assertSecurityHeaders(foreign)
            assertEquals(403, get("/api/frames/f1/$route", headers = mapOf("sec-fetch-site" to "cross-site")).status)
            assertEquals(403, get("/api/frames/f1/$route", headers = mapOf("sec-fetch-site" to "same-site")).status)
            assertEquals("a rebinding host name", 421, get("/api/frames/f1/$route", h = "evil.example:$port").status)
            assertEquals(421, get("/api/frames/f1/$route", h = "").status)
        }
        assertFalse(get("/api/frames/f1/thumb", headers = mapOf("origin" to "http://evil.example")).headers.keys.any { it.startsWith("Access-Control-", true) })
    }

    @Test fun thereIsNoListingEndpoint() {
        seed("f1"); seed("f2", ts = 2_000)
        for (path in listOf("/api/thumbs", "/api/images", "/api/photos", "/api/frames/thumbs", "/api/frames/images", "/api/frames/thumb", "/api/frames/image", "/api/frames/f1/thumbs", "/api/frames/f1/images", "/api/frames/f1/", "/api/frames/f1/thumb/extra")) {
            val r = get(path)
            assertTrue("$path -> ${r.status}", r.status in listOf(400, 404, 405))
            assertFalse(String(r.body).trimStart().startsWith("["))
        }
        // the frame list is metadata only: no image, no thumbnail, no url, no bytes
        val list = Json.parseToJsonElement(String(get("/api/frames").body)).jsonArray
        assertEquals(2, list.size)
        val keys = list.flatMap { it.jsonObject.keys }.toSet()
        assertTrue(keys.none { k -> listOf("thumb", "image", "url", "data", "base64", "photo").any { k.contains(it, true) } })
        // nothing but GET is a photo route
        for (m in listOf("POST", "PUT", "DELETE", "PATCH")) assertEquals(m, 405, get("/api/frames/f1/thumb", method = m).status)
    }

    // ---- limits, privacy of the bytes ----

    @Test fun anOversizedFileIsNotServed() {
        val big = ByteArray(FrameImagesHttp.MAX_IMAGE_BYTES + 1).also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[2] = 0xFF.toByte() }
        seed("f1", bytes = big, sealed = false)
        assertEquals(404, get("/api/frames/f1/image").status)
        assertEquals(404, get("/api/frames/f1/thumb").status)
        seed("f2", ts = 2_000)
        assertEquals(200, get("/api/frames/f2/image").status)
    }

    @Test fun theThumbnailDecoderRefusesAHugeImage() {
        assertNotNull(FrameImagesHttp.thumbnail(jpeg))
        assertNull("640x480 is 307,200 pixels", FrameImagesHttp.thumbnail(jpeg, maxPixels = 300_000L))
        assertNull(FrameImagesHttp.thumbnail("not an image".toByteArray()))
    }

    @Test fun nothingIsWrittenToDisk() {
        seed("f1"); seed("f2", ts = 2_000, bytes = png)
        fun snapshot(): Set<String> = listOf(app.filesDir, app.cacheDir, tmp.root, File(app.dataDir, "databases")).flatMap { d -> d.walkTopDown().filter { it.isFile && !it.name.endsWith("-journal") }.map { it.path + ":" + it.length() }.toList() }.toSet()
        val before = snapshot()
        repeat(2) { for (id in listOf("f1", "f2")) for (route in listOf("thumb", "image")) assertEquals(200, get("/api/frames/$id/$route").status) }
        assertEquals(before, snapshot())
    }

    @Test fun theLogCarriesNoFrameId() {
        seed("f1")
        get("/api/frames/f1/thumb"); get("/api/frames/f1/image"); get("/api/frames/nope/thumb")
        assertTrue(logs.isNotEmpty())
        assertTrue(logs.all { !it.contains("f1") && !it.contains("nope") })
        assertTrue(logs.any { it.startsWith("GET /api/frames/:id/:id 200") })
        assertFalse(logs.any { it.contains(token) })
    }
}
