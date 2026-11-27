package com.rokid.context.work.frames.place

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.ImageTextEmbedder
import com.rokid.context.memory.place.PlaceEntity
import com.rokid.context.memory.place.PlaceImageEntity
import com.rokid.context.memory.place.PlacePack
import com.rokid.context.memory.place.PlacesDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** B2: embedding of stored place photos: gates (switch, thermal, charging), the Developer force that ignores charging only, resume, and a re-embed after a vector-space change from the STORED thumbnail. Fake embedder, synthetic bytes. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlacePhotoEmbedderTest {
    private lateinit var db: PlacesDatabase
    @Before fun open() { db = PlacesDatabase.inMemory(ApplicationProvider.getApplicationContext()) }
    @After fun close() = db.close()
    private fun pack() = PlacePack(db, null, PlacePack.DEFAULT_BUDGET_BYTES, { 1_000L }, { })

    private class Emb(override val spaceId: String, override val dim: Int = 2) : ImageTextEmbedder {
        var calls = 0; var failOn: Int = -1
        override suspend fun embedImage(bytes: ByteArray): FloatArray { calls++; if (calls == failOn) error("boom"); return floatArrayOf(1f, bytes[0].toFloat()).copyOf(dim) }
        override suspend fun embedText(text: String) = FloatArray(dim)
    }
    private fun place(id: String) = PlaceEntity(id, null, "N $id", "monument", 17.0, 78.0, 50, "s", "wikipedia:en", "p", 1L, true, false, 0, 10)
    private fun img(id: String, n: Byte = 1) = PlaceImageEntity(id, "OK", 0, byteArrayOf(n, 2, 3), 330, 220, "s", "f", "a", "l", null, null, null, null, null, null, 5, 3)
    private suspend fun seed(p: PlacePack, n: Int) { db.dao().putPlaces((1..n).map { place("wd:Q$it") }); p.putImages((1..n).map { img("wd:Q$it", it.toByte()) }) }
    private fun embedder(p: PlacePack, e: Emb?, charging: Boolean = true, cool: Boolean = true, on: Boolean = true, batch: Int = 25, pauses: MutableList<Long> = mutableListOf()) =
        PlacePhotoEmbedder(p, { e }, { charging }, { cool }, { on }, { 9L }, { pauses += it }, batch, 2_000)

    @Test fun embedsEveryStoredThumbnailAndMarksSpaceDimAndVersion() = runBlocking {
        val p = pack(); seed(p, 3); val e = Emb("sp1")
        val x = embedder(p, e); assertEquals(EmbedEnd.DONE, x.run())
        assertEquals(3, x.embedded); assertEquals(setOf("wd:Q1", "wd:Q2", "wd:Q3"), p.imageVectorsFor(listOf("wd:Q1", "wd:Q2", "wd:Q3", "wd:Q9"), "sp1").keys)
        val r = p.imagesOf(listOf("wd:Q1")).single(); assertEquals("sp1", r.space); assertEquals(2, r.dim); assertEquals(PlacePhotoEmbedder.VEC_VERSION, r.vecVersion); assertEquals(9L, r.embeddedAt)
        assertEquals(EmbedEnd.DONE, embedder(p, e).run()); assertEquals(3, e.calls) // nothing is embedded twice
    }

    @Test fun notChargingWaitsButTheDeveloperButtonIgnoresChargingOnly() = runBlocking {
        val p = pack(); seed(p, 2); val e = Emb("sp1")
        assertEquals(EmbedEnd.NOT_CHARGING, embedder(p, e, charging = false).run()); assertEquals(0, e.calls)
        assertEquals(EmbedEnd.DONE, embedder(p, e, charging = false).run(ignoreCharging = true)); assertEquals(2, e.calls)
    }

    @Test fun heatStopsEvenTheDeveloperButtonAndTheSwitchOffStopsEverything() = runBlocking {
        val p = pack(); seed(p, 2); val e = Emb("sp1")
        assertEquals(EmbedEnd.HOT, embedder(p, e, cool = false).run(ignoreCharging = true)); assertEquals(EmbedEnd.HOT, embedder(p, e, cool = false).run())
        assertEquals(EmbedEnd.OFF, embedder(p, e, on = false).run(ignoreCharging = true)); assertEquals(0, e.calls)
    }

    @Test fun noActiveSpaceMeansNoVectorsAndNoCrash() = runBlocking {
        val p = pack(); seed(p, 1)
        assertEquals(EmbedEnd.NO_SPACE, embedder(p, null).run()); assertTrue(p.imageVectorsFor(listOf("wd:Q1"), "sp1").isEmpty())
    }

    @Test fun aSpaceChangeReEmbedsFromTheStoredThumbnailAndTheOldSpaceReadsNothing() = runBlocking {
        val p = pack(); seed(p, 2)
        embedder(p, Emb("old")).run()
        val e2 = Emb("new", dim = 3)
        assertEquals(EmbedEnd.DONE, embedder(p, e2).run()); assertEquals(2, e2.calls)
        assertEquals(2, p.imageVectorsFor(listOf("wd:Q1", "wd:Q2"), "new").size); assertTrue(p.imageVectorsFor(listOf("wd:Q1"), "old").isEmpty())
        assertEquals(3, p.imagesOf(listOf("wd:Q1")).single().dim)
    }

    @Test fun oneFailingImageIsSkippedAndTheRestGoOnAndTheBatchPauses() = runBlocking {
        val p = pack(); seed(p, 5); val e = Emb("sp1").also { it.failOn = 2 }; val pauses = mutableListOf<Long>()
        val x = embedder(p, e, batch = 2, pauses = pauses); assertEquals(EmbedEnd.DONE, x.run())
        assertEquals(4, x.embedded); assertEquals(1, x.failed); assertTrue(pauses.isNotEmpty() && pauses.all { it == 2_000L })
        assertEquals(1, p.imagesOf((1..5).map { "wd:Q$it" }).count { it.vector == null })
    }

    @Test fun aTrimmedThumbnailWithoutAVectorIsNotEmbeddedAndAPlaceWithoutPhotoStaysAbsent() = runBlocking {
        val p = pack(); db.dao().putPlaces(listOf(place("wd:Q1"), place("wd:Q2")))
        p.putImages(listOf(PlaceImageEntity("wd:Q1", "OK", 0, null, 1, 1, null, null, null, null, null, null, null, null, null, null, 5, 0)))
        val e = Emb("sp1"); assertEquals(EmbedEnd.DONE, embedder(p, e).run()); assertEquals(0, e.calls); assertNull(p.imageVectorsFor(listOf("wd:Q2"), "sp1")["wd:Q2"])
    }
}
