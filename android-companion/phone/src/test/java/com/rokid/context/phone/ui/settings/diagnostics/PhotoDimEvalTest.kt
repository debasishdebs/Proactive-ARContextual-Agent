package com.rokid.context.phone.ui.settings.diagnostics

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.memory.ImageDecodeException
import com.rokid.context.phone.ui.settings.DiagnosticsScreen
import com.rokid.context.phone.ui.settings.DiagnosticsUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class PhotoDimEvalTest {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val compose = createComposeRule()

    private val SECRET_QUERY = "where did I leave the zebra-keys"
    private val SECRET_ID = "frame-9f3a"

    /** Raw one-hot-ish vectors: photo i lights dim i (or i + [offset]); query i is centred on the same dim. */
    private fun oneHot(i: Int) = FloatArray(768) { if (it == i) 1f else 0f }
    private fun centred(i: Int) = FloatArray(768) { if (it == i) 1f else -1f / 767 }

    private class Fake(val offset: Int, val badPhotos: Set<Int> = emptySet()) : PhotoDimEval.Embedder {
        var imageCalls = 0; var queryCalls = 0
        override suspend fun images(jpegs: List<ByteArray>): List<FloatArray> {
            imageCalls++
            if (jpegs.any { it[0].toInt() in badPhotos }) throw ImageDecodeException("bad")
            return jpegs.map { j -> FloatArray(768) { if (it == j[0].toInt() + offset) 1f else 0f } }
        }
        override suspend fun queries(prefixed: List<String>): List<FloatArray> {
            queryCalls++
            return prefixed.map { p -> val i = p.removePrefix("search_query: ").substringAfter("#").toInt(); FloatArray(768) { if (it == i + offset) 1f else -1f / 767 } }
        }
    }

    private fun loader(n: Int) = PhotoDimEval.PhotoLoader { ids -> ids.filter { it.startsWith("p") && it.drop(1).toInt() < n }.associateWith { byteArrayOf(it.drop(1).toInt().toByte()) } }

    /** [photos] photos p0.., [queries] queries, the first [small] of them small; query i must hit photo (i % photos). */
    private fun fixture(photos: Int, queries: Int, small: Int, firstText: String = "q") = org.json.JSONArray().also { a ->
        for (i in 0 until queries) a.put(JSONObject().put("query", "$firstText#${i % photos}").put("frameIds", org.json.JSONArray().put("p${i % photos}")).put("small", i < small))
        // make sure every photo id appears (undersized photo corpora are the point of some tests)
        for (p in queries until photos) a.put(JSONObject().put("query", "x#$p").put("frameIds", org.json.JSONArray().put("p$p")).put("small", false))
    }.toString()

    private fun eval(json: String, embedder: PhotoDimEval.Embedder, photos: Int = 80, gate: () -> Boolean = { true }): Pair<PhotoDimEval, java.io.File> {
        val dir = tmp.newFolder("photo-eval")
        java.io.File(dir, "queries.json").writeText(json)
        return PhotoDimEval(dir, loader(photos), embedder, gate, Dispatchers.Unconfined) to dir
    }

    @Test fun refusesUndersizedFixture() = runBlocking {
        for ((json, expect) in listOf(
            fixture(photos = 59, queries = 59, small = 20) to "59 photos",
            fixture(photos = 60, queries = 60, small = 14) to "14 small",
        )) {
            val emb = Fake(0)
            val (e, dir) = eval(json, emb)
            val out = e.run()
            assertTrue(out.toString(), out is PhotoDimEval.Outcome.Refused)
            val msg = (out as PhotoDimEval.Outcome.Refused).message
            assertTrue(msg, msg.contains(expect) && msg.contains("60 photos") && msg.contains("40 queries") && msg.contains("15"))
            assertEquals(0, emb.imageCalls + emb.queryCalls)
            assertFalse(java.io.File(dir, "result.json").exists())
            tmp.delete(); tmp.create()
        }
        // 39 queries
        val (e, _) = eval(fixture(photos = 60, queries = 39, small = 20).let { JSONObject("{\"a\":$it}").getJSONArray("a").let { a -> org.json.JSONArray((0 until 39).map { a.get(it) }).toString() } }, Fake(0))
        val out = e.run()
        assertTrue((out as PhotoDimEval.Outcome.Refused).message.contains("39 queries"))
    }

    @Test fun resultHasNoText() = runBlocking {
        val json = fixture(photos = 60, queries = 45, small = 18, firstText = SECRET_QUERY).replace("p1\"", "$SECRET_ID\"")
        val emb = Fake(0)
        val dir = tmp.newFolder("photo-eval")
        java.io.File(dir, "queries.json").writeText(json)
        // the loader knows the secret id as photo 1
        val l = PhotoDimEval.PhotoLoader { ids -> ids.associateWith { id -> byteArrayOf((if (id == SECRET_ID) 1 else id.drop(1).toInt()).toByte()) } }
        val out = PhotoDimEval(dir, l, emb, { true }, Dispatchers.Unconfined).run()
        assertTrue(out.toString(), out is PhotoDimEval.Outcome.Done)
        val text = java.io.File(dir, "result.json").readText()
        assertFalse(text.contains("zebra")); assertFalse(text.contains(SECRET_ID)); assertFalse(text.contains("frame"))
        val o = JSONObject(text)
        assertEquals("PASS_256", o.getString("verdict"))
        assertEquals(768, o.getJSONObject("r768").getInt("dims")); assertEquals(256, o.getJSONObject("r256").getInt("dims"))
        assertEquals(1.0, o.getJSONObject("r768").getDouble("overall"), 1e-9)
        assertEquals(60, o.getJSONObject("r768").getInt("photos")); assertEquals(18, o.getJSONObject("r768").getInt("smallQueries"))
        // nothing but the numbers file and the pushed fixture sits in the dir
        assertEquals(setOf("queries.json", "result.json"), dir.list()!!.toSet())
    }

    @Test fun verdictKeep768WhenTruncationHurts() = runBlocking {
        // every photo's signal lives beyond dim 256, so the 256 variant sees nothing
        val (e, dir) = eval(fixture(photos = 60, queries = 60, small = 20), Fake(offset = 400), photos = 60)
        val out = e.run() as PhotoDimEval.Outcome.Done
        assertFalse(out.pass256); assertEquals(1.0, out.r768.overall, 1e-9)
        assertTrue(out.r256.overall < 0.2)
        assertEquals("KEEP_768", JSONObject(java.io.File(dir, "result.json").readText()).getString("verdict"))
    }

    @Test fun gateBlocksBeforeAnyEmbedding() = runBlocking {
        val emb = Fake(0)
        val (e, dir) = eval(fixture(60, 60, 20), emb, gate = { false })
        assertTrue(e.run() is PhotoDimEval.Outcome.Blocked)
        assertEquals(0, emb.imageCalls + emb.queryCalls)
        assertFalse(java.io.File(dir, "result.json").exists())
    }

    @Test fun undecodablePhotoIsSkippedNotFatal() = runBlocking {
        val (e, _) = eval(fixture(61, 61, 20), Fake(0, badPhotos = setOf(5)), photos = 61)
        val out = e.run()
        assertTrue(out.toString(), out is PhotoDimEval.Outcome.Done)
        assertEquals(60, (out as PhotoDimEval.Outcome.Done).r768.photos)
    }

    @Test fun retryableFailureIsFailedWithClassNameOnly() = runBlocking {
        val boom = object : PhotoDimEval.Embedder {
            override suspend fun images(jpegs: List<ByteArray>): List<FloatArray> = throw IllegalStateException(SECRET_QUERY)
            override suspend fun queries(prefixed: List<String>): List<FloatArray> = emptyList()
        }
        val (e, _) = eval(fixture(60, 60, 20), boom, photos = 60)
        val out = e.run() as PhotoDimEval.Outcome.Failed
        assertFalse(out.message.contains("zebra")); assertTrue(out.message.contains("IllegalStateException"))
    }

    @Test fun releaseBuildHidesRow() {
        assertFalse(PhotoDimEval.rowVisible(debug = false, developerMode = true))
        assertFalse(PhotoDimEval.rowVisible(debug = true, developerMode = false))
        assertTrue(PhotoDimEval.rowVisible(debug = true, developerMode = true))
        compose.setContent { PcTheme { DiagnosticsScreen(DiagnosticsUiState(), {}, {}, {}, {}, {}, {}, {}, {}, {}, onOpenPhotoDimTest = null) } }
        compose.onNodeWithTag("photo_dim_row").assertDoesNotExist()
    }

    @Test fun debugDeveloperBuildShowsRow() {
        compose.setContent { PcTheme { DiagnosticsScreen(DiagnosticsUiState(), {}, {}, {}, {}, {}, {}, {}, {}, {}, onOpenPhotoDimTest = {}) } }
        compose.onNodeWithTag("photo_dim_row").assertExists()
    }
}
