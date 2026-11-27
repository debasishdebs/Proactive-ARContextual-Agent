package com.rokid.context.memory.place

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.retrieval.VectorCodec
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** B1: the `place_image` table through [PlacePack] and [PlacesDao]: store, read, vectors for the matcher, cap trim, queues and the delete lifecycle. Synthetic ids and bytes only. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlaceImageTest {
    private lateinit var db: PlacesDatabase
    private val lat = 17.361944
    private val lon = 78.474722
    private val pinId = "user:00000000-0000-0000-0000-000000000001"

    @Before fun open() { db = PlacesDatabase.inMemory(ApplicationProvider.getApplicationContext()) }
    @After fun close() = db.close()
    private fun pack() = PlacePack(db, null, PlacePack.DEFAULT_BUDGET_BYTES, { 1_000L }, { })
    private fun <T> run(b: suspend () -> T): T = runBlocking { b() }

    private fun place(id: String, seeded: Boolean = true, pinned: Boolean = false, bytes: Int = 10, la: Double = lat, lo: Double = lon) =
        PlaceEntity(id, null, "N $id", "monument", la, lo, 50, "s", "wikipedia:en", "p", 1L, seeded, pinned, 0, bytes)
    private fun vec(vararg f: Float) = VectorCodec.encode(floatArrayOf(*f))
    private fun img(id: String, jpegLen: Int = 100, vector: ByteArray? = null, space: String? = null, at: Long = 10, status: String = ImageState.OK, tries: Int = 0) = PlaceImageEntity(
        id, status, tries, if (jpegLen > 0) ByteArray(jpegLen) { 7 } else null, 330, 220, "sha", "File:X.jpg", "Author A", "CC BY-SA 4.0", "https://creativecommons.org/licenses/by-sa/4.0/",
        space, vector?.let { it.size / 4 }, vector, if (vector != null) 1 else null, if (vector != null) at else null, at, jpegLen + (vector?.size ?: 0))

    @Test fun putGetListByPlaceIdsAndOnlyForKnownNonPinPlaces() = run {
        val p = pack(); db.dao().putPlaces(listOf(place("wd:Q1"), place("wd:Q2"), place(pinId, seeded = false, pinned = true)))
        assertEquals(2, p.putImages(listOf(img("wd:Q1"), img("wd:Q2"), img(pinId), img("wd:ghost"))))
        assertEquals(setOf("wd:Q1", "wd:Q2"), p.imagesOf(listOf("wd:Q1", "wd:Q2", pinId, "wd:ghost")).map { it.placeId }.toSet())
        val one = p.imagesOf(listOf("wd:Q1")).single()
        assertEquals("Author A", one.author); assertEquals(330, one.width); assertEquals(100, one.jpeg!!.size)
        val st = p.imageStats(); assertEquals(2, st.rows); assertEquals(2, st.ok); assertEquals(200L, st.bytes); assertEquals(250L * 1024 * 1024, st.capBytes)
    }

    @Test fun noPhotoMeansNoVectorAndThePlaceIsSimplyAbsent() = run {
        val p = pack(); db.dao().putPlaces(listOf(place("wd:Q1"), place("wd:Q2")))
        p.putImages(listOf(img("wd:Q1", vector = vec(1f, 0f), space = "sp")))
        val got = p.imageVectorsFor(listOf("wd:Q1", "wd:Q2"), "sp")
        assertEquals(setOf("wd:Q1"), got.keys); assertArrayEquals(floatArrayOf(1f, 0f), got.getValue("wd:Q1"), 0f)
        assertTrue("another space gives nothing", p.imageVectorsFor(listOf("wd:Q1"), "other").isEmpty())
    }

    @Test fun aVectorWhoseLengthDisagreesWithItsStoredDimIsDropped() = run {
        val p = pack(); db.dao().putPlaces(listOf(place("wd:Q1")))
        val bad = img("wd:Q1", vector = vec(1f, 0f), space = "sp"); db.dao().putImages(listOf(PlaceImageEntity(bad.placeId, bad.status, 0, bad.jpeg, 1, 1, null, null, null, null, null, "sp", 3, bad.vector, 1, 5, 5, 10)))
        assertTrue(p.imageVectorsFor(listOf("wd:Q1"), "sp").isEmpty())
    }

    @Test fun markEmbeddedStoresTheVectorAndCountsItsBytes() = run {
        val p = pack(); db.dao().putPlaces(listOf(place("wd:Q1"), place("wd:Q2")))
        p.putImages(listOf(img("wd:Q1", jpegLen = 100), img("wd:Q2", jpegLen = 0, status = ImageState.NONE)))
        assertEquals(listOf("wd:Q1"), p.needsEmbed("sp", 10))
        assertTrue(p.markEmbedded("wd:Q1", "sp", floatArrayOf(0.6f, 0.8f), 1, 99))
        assertEquals(emptyList<String>(), p.needsEmbed("sp", 10)); assertEquals(listOf("wd:Q1"), p.needsEmbed("sp2", 10)) // a space change queues it again
        val r = p.imagesOf(listOf("wd:Q1")).single(); assertEquals("sp", r.space); assertEquals(2, r.dim); assertEquals(99L, r.embeddedAt); assertEquals(108, r.bytes)
        assertTrue(p.markEmbedded("wd:Q1", "sp2", floatArrayOf(1f, 0f, 0f), 2, 100)); assertEquals(112, p.imagesOf(listOf("wd:Q1")).single().bytes) // replaced, not added
        assertTrue("a marker row cannot take a vector", !p.markEmbedded("wd:Q2", "sp", floatArrayOf(1f), 1, 1))
    }

    @Test fun needsFetchListsSeededPlacesWithoutARowAndRetriableFailures() = run {
        val p = pack(); db.dao().putPlaces(listOf(place("wd:Q1"), place("wd:Q2"), place("wd:Q3"), place("wd:Q4"), place("wd:Q5", seeded = false), place(pinId, seeded = false, pinned = true)))
        p.putImages(listOf(img("wd:Q1"), img("wd:Q2", jpegLen = 0, status = ImageState.FAILED, tries = 1), img("wd:Q3", jpegLen = 0, status = ImageState.FAILED, tries = 3)))
        assertEquals(listOf("wd:Q2", "wd:Q4"), p.needsFetch("sp", 3, 10)); assertEquals(listOf("wd:Q2"), p.needsFetch("sp", 3, 1))
    }

    @Test fun trimDropsTheOldestThumbnailsFirstAndKeepsEveryVector() = run {
        val p = pack(); db.dao().putPlaces((1..4).map { place("wd:Q$it") })
        p.putImages(listOf(img("wd:Q1", 1000, vec(1f, 0f), "sp", at = 1), img("wd:Q2", 1000, vec(1f, 0f), "sp", at = 2), img("wd:Q3", 1000, vec(1f, 0f), "sp", at = 3), img("wd:Q4", 1000, null, null, at = 0)))
        assertEquals(4 * 1000 + 3 * 8L, p.imageStats().bytes)
        assertEquals(2, p.trimImages(2024L)) // Q1 and Q2 (the oldest with a vector) lose the thumbnail; Q4 is older but has no vector and stays
        val rows = p.imagesOf(listOf("wd:Q1", "wd:Q2", "wd:Q3", "wd:Q4")).associateBy { it.placeId }
        assertNull(rows.getValue("wd:Q1").jpeg); assertNull(rows.getValue("wd:Q2").jpeg); assertEquals(1000, rows.getValue("wd:Q3").jpeg!!.size); assertEquals(1000, rows.getValue("wd:Q4").jpeg!!.size)
        assertEquals(8, rows.getValue("wd:Q1").bytes); assertEquals(3, p.imageVectorsFor(listOf("wd:Q1", "wd:Q2", "wd:Q3"), "sp").size)
        assertEquals("the cap 0 cannot drop a thumbnail that has no vector, nor a vector", 1, p.trimImages(0L)) // only Q3 had a thumbnail left with a vector
        assertEquals(3, p.imageVectorsFor(listOf("wd:Q1", "wd:Q2", "wd:Q3"), "sp").size); assertEquals(1000, p.imagesOf(listOf("wd:Q4")).single().jpeg!!.size)
        assertTrue("a trimmed row with a vector of the active space needs neither fetch nor embed", p.needsFetch("sp", 3, 10).none { it in setOf("wd:Q1", "wd:Q2") }); assertEquals(listOf("wd:Q4"), p.needsEmbed("sp", 10))
        assertEquals("in another space a trimmed photo cannot be embedded again: back to the fetch queue", setOf("wd:Q1", "wd:Q2", "wd:Q3"), p.needsFetch("other", 3, 10).toSet())
    }

    @Test fun theCapIsEnforcedOnEveryPut() = run {
        val p = pack(); db.dao().putPlaces(listOf(place("wd:Q1")))
        p.putImages(listOf(img("wd:Q1", 100, vec(1f, 0f), "sp"))); assertEquals(0, p.trimImages()) // far below 250 MB: nothing goes
        assertEquals(250L * 1024 * 1024, PlacePack.IMAGE_CAP_BYTES)
    }

    // ---- lifecycle: the photos go in the same transaction as their place ----

    private suspend fun seedTwo(p: PlacePack) {
        db.dao().putPlaces(listOf(place("wd:Q1"), place("wd:F1", seeded = false), place("wd:F2", seeded = false, pinned = true)))
        db.dao().putImages(listOf(img("wd:Q1"), img("wd:F1"), img("wd:F2")))
    }

    @Test fun clearFetchedRemovesTheOrphanPhotosButNotSeededOrPinned() = run {
        val p = pack(); seedTwo(p)
        assertEquals(1, p.clearFetched())
        assertEquals(setOf("wd:Q1", "wd:F2"), p.imagesOf(listOf("wd:Q1", "wd:F1", "wd:F2")).map { it.placeId }.toSet())
    }

    @Test fun deleteAllKeepsSeededPhotosLikeSeededPlaces() = run {
        val p = pack(); seedTwo(p); p.deleteAll()
        assertEquals(setOf("wd:Q1", "wd:F2"), p.imagesOf(listOf("wd:Q1", "wd:F1", "wd:F2")).map { it.placeId }.toSet())
    }

    @Test fun evictionRemovesThePhotoOfAnEvictedFetchedPlace() = run {
        val p = pack(); db.dao().putPlaces(listOf(place("wd:Q1", bytes = 5_000_000), place("wd:F1", seeded = false, bytes = 5_000_000)))
        db.dao().putImages(listOf(img("wd:Q1"), img("wd:F1")))
        assertEquals(1, p.setBudget(PlacePack.MIN_BUDGET_BYTES)) // forces the evict path; seeded stays
        assertEquals(listOf("wd:Q1"), p.imagesOf(listOf("wd:Q1", "wd:F1")).map { it.placeId })
    }

    @Test fun removingAnAreaWithItsPlacesTakesTheirPhotosAndWithoutPlacesKeepsThem() = run {
        val p = pack(); val box = Geohash5.bounds(Geohash5.encode(lat, lon))!!
        db.dao().putArea(AreaEntity("z1", box.south, box.west, box.north, box.east, 1L, AREA_DONE)); db.dao().putPlaces(listOf(place("wd:Q1"))); db.dao().putImages(listOf(img("wd:Q1")))
        p.removeArea("z1", false); assertEquals(1, p.imagesOf(listOf("wd:Q1")).size) // the place stays (now an ordinary fetched place), so does its photo
        db.dao().putArea(AreaEntity("z1", box.south, box.west, box.north, box.east, 1L, AREA_DONE)); db.dao().markSeeded(box.south, box.north, box.west, box.east)
        p.removeArea("z1", true); assertEquals(0, p.placesByIds(listOf("wd:Q1")).size); assertEquals(0, p.imagesOf(listOf("wd:Q1")).size)
    }

    @Test fun syncingPinsDropsAnOrphanPhotoRow() = run {
        val p = pack(); db.dao().putImages(listOf(img("wd:gone"))) // no place row at all
        p.replaceUserPlaces(emptyList()); assertEquals(0, db.dao().imageCount())
    }

    @Test fun deleteImagesRemovesJustTheNamedOnes() = run {
        val p = pack(); db.dao().putPlaces(listOf(place("wd:Q1"), place("wd:Q2"))); p.putImages(listOf(img("wd:Q1"), img("wd:Q2")))
        assertEquals(1, p.deleteImages(listOf("wd:Q1"))); assertEquals(listOf("wd:Q2"), p.imagesOf(listOf("wd:Q1", "wd:Q2")).map { it.placeId })
    }
}
