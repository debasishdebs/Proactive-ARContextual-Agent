package com.rokid.context.phone.ui.settings.diagnostics

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.platform.testTag
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.memory.ImageDecodeException
import com.rokid.context.phone.ui.settings.DiagnosticsScreen
import com.rokid.context.phone.ui.settings.DiagnosticsUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class PhotoDimEvalTest {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val compose = createComposeRule()

    private val SECRET_QUERY = "where did I leave the zebra-keys"
    private val SECRET_ID = "frame-9f3a"

    /** Photo bytes carry an index (2 bytes); the fake lights dim (index + offset) % 768. Labelled photos are indices 0..99, distractors 100+. */
    private fun bytesOf(i: Int) = byteArrayOf((i and 0xff).toByte(), (i shr 8).toByte())
    private fun idxOf(b: ByteArray) = (b[0].toInt() and 0xff) or ((b[1].toInt() and 0xff) shl 8)

    /** [missQueries]: query indices whose vector points at a dim no relevant photo has, so they miss at every width. */
    private class Fake(val offset: Int = 0, val badPhotos: Set<Int> = emptySet(), val missQueries: Set<Int> = emptySet()) : PhotoDimEval.Embedder {
        var imageCalls = 0; var queryCalls = 0; var closed = false
        override suspend fun images(jpegs: List<ByteArray>): List<FloatArray> {
            imageCalls++
            val idx = jpegs.map { (it[0].toInt() and 0xff) or ((it[1].toInt() and 0xff) shl 8) }
            if (idx.any { it in badPhotos }) throw ImageDecodeException("bad")
            return idx.map { i -> FloatArray(768) { if (it == (i + offset) % 768) 1f else 0f } }
        }
        override suspend fun queries(prefixed: List<String>): List<FloatArray> {
            queryCalls++
            return prefixed.map { p ->
                val i = p.removePrefix("search_query: ").substringAfter("#").toInt()
                val dim = if (i in missQueries) 767 else (i + offset) % 768
                FloatArray(768) { if (it == dim) 1f else -1f / 767 }
            }
        }
        override fun close() { closed = true }
    }

    /** Labelled photos "p<i>" (index i), distractors "d<i>" (index 100 + i), each 3 min apart so none sits within a minute of a label. */
    private fun frames(labelled: Int, distractors: Int): List<PhotoDimEval.FrameRef> =
        (0 until labelled).map { PhotoDimEval.FrameRef("p$it", 1_000_000L + it * 180_000L) } +
            (0 until distractors).map { PhotoDimEval.FrameRef("d$it", 100_000_000L + it * 180_000L) }

    private fun loader(gone: Set<String> = emptySet(), idMap: Map<String, Int> = emptyMap()) = PhotoDimEval.PhotoLoader { ids ->
        ids.filter { it !in gone }.associateWith { id -> bytesOf(idMap[id] ?: if (id.startsWith("d")) 100 + id.drop(1).toInt() else id.drop(1).toInt()) }
    }

    /** [photos] labelled photos, [queries] queries (query i hits photo i % photos), the first [small] of them small. */
    private fun fixture(photos: Int, queries: Int, small: Int, text: String = "q", extraOn0: List<String> = emptyList()): String = JSONArray().also { a ->
        for (i in 0 until queries) {
            val ids = JSONArray().put("p${i % photos}"); if (i == 0) extraOn0.forEach { ids.put(it) }
            a.put(JSONObject().put("query", "$text#${i % photos}").put("frameIds", ids).put("small", i < small))
        }
        for (p in queries until photos) a.put(JSONObject().put("query", "x#$p").put("frameIds", JSONArray().put("p$p")).put("small", false))
    }.toString()

    private fun eval(
        json: String, embedder: PhotoDimEval.Embedder, labelled: Int = 80, distractors: Int = 400, gate: () -> Boolean = { true },
        photos: PhotoDimEval.PhotoLoader = loader(), catalog: List<PhotoDimEval.FrameRef> = frames(labelled, distractors),
    ): Pair<PhotoDimEval, File> {
        val dir = tmp.newFolder("photo-eval")
        File(dir, "queries.json").writeText(json)
        return PhotoDimEval(dir, photos, { catalog }, embedder, gate, Dispatchers.Unconfined) to dir
    }

    @Test fun refusesUndersizedFixture() = runBlocking {
        for ((json, expect) in listOf(
            fixture(photos = 59, queries = 59, small = 20) to "59 photos",
            fixture(photos = 60, queries = 60, small = 14) to "14 small",
        )) {
            val emb = Fake()
            val (e, dir) = eval(json, emb)
            val out = e.run()
            assertTrue(out.toString(), out is PhotoDimEval.Outcome.Refused)
            val msg = (out as PhotoDimEval.Outcome.Refused).message
            assertTrue(msg, msg.contains(expect) && msg.contains("60 photos") && msg.contains("40 queries") && msg.contains("15"))
            assertEquals(0, emb.imageCalls + emb.queryCalls)
            assertFalse(File(dir, "result.json").exists())
            tmp.delete(); tmp.create()
        }
        val a = JSONArray(fixture(photos = 60, queries = 60, small = 20))
        val (e, _) = eval(JSONArray((0 until 39).map { a.get(it) }).toString(), Fake())
        assertTrue((e.run() as PhotoDimEval.Outcome.Refused).message.contains("39 queries"))
    }

    @Test fun refusesTooFewDistractors() = runBlocking {
        val emb = Fake()
        // 60 labelled photos need max(300, 300) = 300 distractors
        val (e, dir) = eval(fixture(60, 60, 20), emb, labelled = 60, distractors = 299)
        val out = e.run() as PhotoDimEval.Outcome.Refused
        assertTrue(out.message, out.message.contains("299 other photos") && out.message.contains("300") && out.message.contains("60 labelled"))
        assertEquals(0, emb.imageCalls + emb.queryCalls)
        assertFalse(File(dir, "result.json").exists())
        // 70 labelled need 350
        tmp.delete(); tmp.create()
        val (e2, _) = eval(fixture(70, 70, 20), Fake(), labelled = 70, distractors = 349)
        assertTrue((e2.run() as PhotoDimEval.Outcome.Refused).message.contains("350"))
    }

    @Test fun distractorsSkipNeighboursOfLabelledFramesAndAreDeterministic() {
        val labelledTs = 10_000_000L
        val all = listOf(PhotoDimEval.FrameRef("L", labelledTs)) +
            listOf(PhotoDimEval.FrameRef("near1", labelledTs + 60_000), PhotoDimEval.FrameRef("near2", labelledTs - 60_000), // exactly 60 s: excluded
                PhotoDimEval.FrameRef("far1", labelledTs + 60_001), PhotoDimEval.FrameRef("far2", labelledTs - 60_001)) +
            (0 until 3_000).map { PhotoDimEval.FrameRef("o$it", 50_000_000L + it * 5_000L) }
        val a = PhotoDimEval.pickDistractors(all, setOf("L"))
        val b = PhotoDimEval.pickDistractors(all, setOf("L"))
        assertEquals(a.map { it.id }, b.map { it.id })
        assertEquals(PhotoDimEval.MAX_DISTRACTORS, a.size)
        assertFalse(a.any { it.id == "L" || it.id == "near1" || it.id == "near2" })
        assertEquals(a.sortedWith(compareByDescending<PhotoDimEval.FrameRef> { it.ts }.thenBy { it.id }), a) // newest first
        val small = PhotoDimEval.pickDistractors(all.take(20), setOf("L"))
        assertEquals(setOf("far1", "far2") + all.drop(5).take(15).map { it.id }, small.map { it.id }.toSet())
    }

    @Test fun resultHasNoText() = runBlocking {
        val json = fixture(photos = 60, queries = 45, small = 18, text = SECRET_QUERY).replace("p1\"", "$SECRET_ID\"")
        val emb = Fake(missQueries = (0 until 6).toSet())
        val dir = tmp.newFolder("photo-eval")
        File(dir, "queries.json").writeText(json)
        val cat = frames(60, 400).map { if (it.id == "p1") PhotoDimEval.FrameRef(SECRET_ID, it.ts) else it }
        val out = PhotoDimEval(dir, loader(idMap = mapOf(SECRET_ID to 1)), { cat }, emb, { true }, Dispatchers.Unconfined).run()
        assertTrue(out.toString(), out is PhotoDimEval.Outcome.Done)
        val text = File(dir, "result.json").readText()
        assertFalse(text.contains("zebra")); assertFalse(text.contains(SECRET_ID)); assertFalse(text.contains("frame"))
        val o = JSONObject(text)
        assertEquals("PASS_256", o.getString("verdict"))
        assertEquals(768, o.getJSONObject("r768").getInt("dims")); assertEquals(256, o.getJSONObject("r256").getInt("dims"))
        assertEquals(54.0 / 60, o.getJSONObject("r768").getDouble("overall"), 1e-9)
        assertEquals(54, o.getInt("hits768")); assertEquals(54, o.getInt("hits256"))
        assertEquals(12, o.getInt("smallHits768")); assertEquals(18, o.getInt("smallQueries")); assertEquals(60, o.getInt("queries"))
        assertEquals(60, o.getInt("labelledPhotos")); assertEquals(400, o.getInt("distractors")); assertEquals(460, o.getInt("corpusSize"))
        assertEquals(setOf("queries.json", "result.json"), dir.list()!!.toSet())
        assertTrue(emb.closed)
    }

    @Test fun ceilingMakesTheVerdictInconclusive() = runBlocking {
        val (e, dir) = eval(fixture(60, 60, 20), Fake(), labelled = 60, distractors = 400) // perfect at both widths
        val out = e.run() as PhotoDimEval.Outcome.Done
        assertFalse(out.pass256); assertEquals("INCONCLUSIVE", out.verdict)
        assertEquals("INCONCLUSIVE", JSONObject(File(dir, "result.json").readText()).getString("verdict"))
        assertTrue(PhotoDimEval.lines(out).any { it.contains("evidence from your own photos only") })
    }

    @Test fun verdictKeep768WhenTruncationHurts() = runBlocking {
        // every labelled photo's signal lives beyond dim 256, so the 256 variant sees nothing
        val (e, dir) = eval(fixture(60, 60, 20), Fake(offset = 400, missQueries = (0 until 8).toSet()), labelled = 60, distractors = 400)
        val out = e.run() as PhotoDimEval.Outcome.Done
        assertFalse(out.pass256); assertEquals("KEEP_768", out.verdict)
        assertTrue(out.r256.overall < out.r768.overall - 0.2)
        assertEquals("KEEP_768", JSONObject(File(dir, "result.json").readText()).getString("verdict"))
    }

    @Test fun photosThatExpiredAfterLabellingAreRefusedOnTheJudgedQueries() = runBlocking {
        // 41 queries over 70 photos (the 29 extra photos hang off query 0). p1 and p2 expire: 39 queries can still be judged, 68 photos load.
        val extra = (41 until 70).map { "p$it" }
        val (e, dir) = eval(fixture(41, 41, 20, extraOn0 = extra), Fake(), labelled = 70, distractors = 400, photos = loader(gone = setOf("p1", "p2")))
        val out = e.run()
        assertTrue(out.toString(), out is PhotoDimEval.Outcome.Refused)
        val msg = (out as PhotoDimEval.Outcome.Refused).message
        assertTrue(msg, msg.contains("39 queries") && msg.contains("68 photos"))
        assertFalse(File(dir, "result.json").exists())
    }

    @Test fun gateBlocksBeforeAnyEmbedding() = runBlocking {
        val emb = Fake()
        val (e, dir) = eval(fixture(60, 60, 20), emb, gate = { false })
        assertTrue(e.run() is PhotoDimEval.Outcome.Blocked)
        assertEquals(0, emb.imageCalls + emb.queryCalls)
        assertFalse(File(dir, "result.json").exists())
        assertTrue(emb.closed)
    }

    @Test fun undecodablePhotoIsSkippedNotFatal() = runBlocking {
        val (e, _) = eval(fixture(61, 61, 20), Fake(badPhotos = setOf(5), missQueries = (0 until 6).toSet()), labelled = 61, distractors = 400)
        val out = e.run()
        assertTrue(out.toString(), out is PhotoDimEval.Outcome.Done)
        assertEquals(60 + 400, (out as PhotoDimEval.Outcome.Done).r768.photos)
        assertEquals(60, out.labelled)
    }

    @Test fun retryableFailureIsFailedWithClassNameOnly() = runBlocking {
        val boom = object : PhotoDimEval.Embedder {
            override suspend fun images(jpegs: List<ByteArray>): List<FloatArray> = throw IllegalStateException(SECRET_QUERY)
            override suspend fun queries(prefixed: List<String>): List<FloatArray> = emptyList()
        }
        val (e, _) = eval(fixture(60, 60, 20), boom, labelled = 60)
        val out = e.run() as PhotoDimEval.Outcome.Failed
        assertFalse(out.message.contains("zebra")); assertTrue(out.message.contains("IllegalStateException"))
    }

    @Test fun previewCountsWithoutEmbedding() = runBlocking {
        val emb = Fake()
        val (e, _) = eval(fixture(60, 45, 18), emb, labelled = 60, distractors = 500)
        val p = e.preview()
        assertEquals(60, p.queries); assertEquals(18, p.small); assertEquals(60, p.labelledPhotos); assertEquals(500, p.distractors)
        assertEquals(0, emb.imageCalls + emb.queryCalls)
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

    @Test fun releaseBuildRouteGoesBackAndNeverShowsTheScreen() {
        var backs = 0
        compose.setContent { PcTheme { PhotoDimEvalGate(debug = false, developerMode = true, back = { backs++ }) { androidx.compose.material3.Text("screen", modifier = androidx.compose.ui.Modifier.testTag("photo_dim_screen")) } } }
        compose.waitForIdle()
        compose.onNodeWithTag("photo_dim_screen").assertDoesNotExist()
        assertEquals(1, backs)
    }

    @Test fun debugDeveloperRouteShowsTheScreen() {
        var backs = 0
        compose.setContent { PcTheme { PhotoDimEvalGate(debug = true, developerMode = true, back = { backs++ }) { androidx.compose.material3.Text("screen", modifier = androidx.compose.ui.Modifier.testTag("photo_dim_screen")) } } }
        compose.onNodeWithTag("photo_dim_screen").assertExists()
        assertEquals(0, backs)
    }
}
