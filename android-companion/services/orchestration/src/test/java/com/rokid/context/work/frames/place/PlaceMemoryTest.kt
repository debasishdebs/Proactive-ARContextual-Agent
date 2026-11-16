package com.rokid.context.work.frames.place

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.WorkManagerTestInitHelper
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.FrameMetaEntity
import com.rokid.context.memory.place.CellFetch
import com.rokid.context.memory.place.CellSource
import com.rokid.context.memory.place.FetchedPlace
import com.rokid.context.memory.place.GeoBox
import com.rokid.context.memory.place.Geohash5
import com.rokid.context.memory.place.PlaceEntity
import com.rokid.context.memory.place.PlacePack
import com.rokid.context.memory.place.PlacesDatabase
import com.rokid.context.orchestration.MemoryWipe
import com.rokid.context.orchestration.forget.ForgetActions
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

private const val LAT = 17.361944
private const val LON = 78.474722

private fun site(id: String, name: String, parent: String? = null, kind: String = "monument") =
    PlaceEntity(id, parent, name, kind, LAT, LON, 50, "A monument.", "wikipedia:en", "page:1", 1_700_000_000_000L, false, false, 0, 100)

private fun meta(id: String, dwell: Boolean, createdAt: Long) =
    FrameMetaEntity(id, "w_$id", "s", "STILL", dwell, null, null, null, null, null, null, null, null, null, createdAt)

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlaceMemoryTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val graph get() = MemoryGraph.get(ctx)

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx); MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        com.rokid.context.data.runtime.QueueRuntimeStore.clearAll(ctx); com.rokid.context.data.runtime.AppConfigStore.setPipelineEnabled(ctx, false)
    }
    @After fun tearDown() {
        com.rokid.context.data.runtime.AppConfigStore.setPipelineEnabled(ctx, true); graph.db.close(); MemoryGraph.resetForTests(null); PlaceGraph.installForTests(null)
    }

    private fun frame(id: String): File {
        val jpg = File(ctx.filesDir, "rokid_archive/$id.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        runBlocking { graph.db.frameDao().insertIgnore(FrameEntity(id, 1, jpg.absolutePath, "rokid_glasses")) }
        return jpg
    }

    private fun request(frameId: String, place: PlaceEntity = site("wd:Q1", "Charminar"), parent: String? = "Old City") =
        PlaceMemoryRequest(frameId, place, parent, 0.86f, true, MemoryWipe.generation.get())

    @Test fun theMatchIsOnePlaceMemoryLinkedToThePhoto() = runBlocking {
        frame("f1")
        assertTrue(PlaceMemory.save(ctx, request("f1")))
        val m = graph.db.memoryDao().all().single()
        assertEquals("place", m.type); assertEquals("f1", m.frameId)
        assertEquals("Looked at Charminar (Old City) · photo match 0.86", m.text)
        assertEquals("place wd:q1 monument old city", m.tags)
        assertEquals(true, m.imageDelivered); assertEquals(0.86, m.confidence, 0.001)
        assertEquals(listOf("f1" to "FACT"), graph.db.frameSourceDao().forMemory(m.id).map { it.frameId to it.role })
        // findable by text through the existing FTS
        assertEquals(listOf(m.id), graph.memories.search("charminar", 5).map { it.id })
    }

    @Test fun writingTheSameMatchAgainReplacesNotDuplicates() = runBlocking {
        frame("f1"); PlaceMemory.save(ctx, request("f1")); PlaceMemory.save(ctx, request("f1"))
        assertEquals(1, graph.db.memoryDao().count()); assertEquals(1, graph.db.frameSourceDao().forMemory(PlaceMemory.idFor("f1")).size)
    }

    @Test fun textWithoutAParentHasNoBrackets() {
        assertEquals("Looked at Golconda Fort · photo match 0.50", PlaceMemory.text("Golconda Fort", null, 0.5f))
    }

    @Test fun forgettingThePhotoRemovesThePlaceMemory() = runBlocking {
        frame("f1"); frame("f2")
        PlaceMemory.save(ctx, request("f1")); PlaceMemory.save(ctx, request("f2", site("wd:Q2", "Golconda"), null))
        assertTrue(ForgetActions.forgetMoment(ctx, "f1"))
        assertNull(graph.db.memoryDao().all().firstOrNull { it.id == PlaceMemory.idFor("f1") })
        assertTrue(graph.db.frameSourceDao().forMemory(PlaceMemory.idFor("f1")).isEmpty())
        assertNotNull("the other photo's memory stays", graph.db.memoryDao().all().firstOrNull { it.id == PlaceMemory.idFor("f2") })
        assertTrue(graph.memories.search("charminar", 5).isEmpty())
    }

    @Test fun aForgottenPhotoNeverGetsAMemoryAfterTheFact() = runBlocking {
        frame("f1"); ForgetActions.forgetMoment(ctx, "f1")
        assertFalse(PlaceMemory.save(ctx, request("f1"))); assertEquals(0, graph.db.memoryDao().count())
    }

    @Test fun aWipeSinceTheMatchStartedDropsTheMemory() = runBlocking {
        frame("f1"); val r = request("f1"); MemoryWipe.generation.incrementAndGet()
        assertFalse(PlaceMemory.save(ctx, r)); assertEquals(0, graph.db.memoryDao().count())
    }

    @Test fun theDwellQueryReturnsOnlyDwellPhotosAndNotForgottenOnes() = runBlocking {
        val dao = graph.db.frameMetaDao()
        dao.upsert(meta("a", false, 1_000)); assertNull(dao.newestDwell(0))
        dao.upsert(meta("d1", true, 500)); dao.upsert(meta("d2", true, 900))
        assertEquals("d2", dao.newestDwell(0)!!.frameId)
        assertNull("received before the window", dao.newestDwell(1_000))
        frame("d2"); ForgetActions.forgetMoment(ctx, "d2")
        assertEquals("d1", dao.newestDwell(0)?.frameId)
    }

    // ---- Delete all ----

    private class Source : CellSource {
        override suspend fun fetchCell(cell: String) = CellFetch(listOf(FetchedPlace("wd:Q${cell.hashCode().toString().replace('-', '9')}", null, "P-$cell", "monument", LAT, LON, 60, "x", "wikipedia:en", "p")))
    }

    @Test fun deleteAllClearsFetchedPlacesButKeepsSeededAreasAndNeverTouchesPauseZones() = runBlocking {
        val db = PlacesDatabase.inMemory(ctx)
        val pack = PlacePack(db, Source())
        PlaceGraph.installForTests(pack)
        val seedBox = GeoBox(LAT - 0.01, LON - 0.01, LAT + 0.01, LON + 0.01)
        pack.seedArea("blue1", seedBox)
        val seededCount = pack.size().first
        assertTrue(seededCount > 0)
        // a fetched (cache-aside) place far from the seeded area
        val far = pack.candidates(48.8584, 2.2945, networkUp = true)
        assertEquals(com.rokid.context.memory.place.CellOutcome.FETCHED, far.outcome)
        assertTrue(pack.size().first > seededCount)
        val zones = ctx.getSharedPreferences("pause_zones", Context.MODE_PRIVATE).also { it.edit().putString("zones", "[{\"id\":\"jarawa\"}]").commit() }

        MemoryWipe.deleteAll(ctx)

        assertEquals("seeded places stay, the fetched one goes", seededCount, pack.size().first)
        assertEquals(1, pack.areas().size)
        assertEquals("[{\"id\":\"jarawa\"}]", zones.getString("zones", null))
        db.close()
    }

    @Test fun deleteAllWithNoPackCreatesNothing() = runBlocking {
        PlaceGraph.installForTests(null)
        MemoryWipe.deleteAll(ctx)
        assertFalse(ctx.getDatabasePath(PlacesDatabase.NAME).exists())
    }
}
