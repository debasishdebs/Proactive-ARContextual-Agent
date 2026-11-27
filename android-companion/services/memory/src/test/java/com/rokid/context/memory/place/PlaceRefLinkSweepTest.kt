package com.rokid.context.memory.place

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.PlaceLinkEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** B3 loop guard x deletion: a REF `place_link` (context.db) never outlives its place (places.db). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlaceRefLinkSweepTest {
    private lateinit var places: PlacesDatabase
    private lateinit var ctx: ContextDatabase
    private val pin = "user:00000000-0000-0000-0000-00000000000a"

    @Before fun open() { val a = ApplicationProvider.getApplicationContext<android.content.Context>(); places = PlacesDatabase.inMemory(a); ctx = DatabaseFactory.inMemory(a) }
    @After fun close() { places.close(); ctx.close() }

    private fun link(place: String, m: String, role: String = "REF") = PlaceLinkEntity(place, m, 1, "f", role, 1)
    private fun fetched(id: String) = FetchedPlace(id, null, id, PlaceKinds.ARCHAEOLOGICAL_SITE, 7.957, 80.759, 100, "S.", "wikipedia:en", "page:1")

    @Test fun clearFetchedAndTheStartupSweepDropRefLinksOfGonePlacesOnly() = runBlocking {
        val src = object : CellSource { override suspend fun fetchCell(cell: String) = CellFetch(listOf(fetched("wd:Q1"))) }
        val p = PlacePack(places, src, onPlacesRemoved = { PlaceRefSweep.links(ctx, it) })
        p.candidates(7.957, 80.759, networkUp = true)
        val q1 = places.dao().inBox(-90.0, 90.0, -180.0, 180.0).single().id
        ctx.placeLinkDao().insertIfAbsent(link(q1, "m1")); ctx.placeLinkDao().insertIfAbsent(link("wd:Q9", "m2")); ctx.placeLinkDao().insertIfAbsent(link(pin, "m3")); ctx.placeLinkDao().insertIfAbsent(link(pin, "m4", "CARD"))
        assertEquals("the startup sweep: Q9 has no place", 1, PlaceRefSweep.links(ctx, p))
        assertEquals(3, ctx.placeLinkDao().count())
        p.clearFetched()
        assertEquals("Q1 went with Clear fetched; the pin rows stay", 2, ctx.placeLinkDao().count())
        assertEquals(listOf("m3", "m4"), listOf("m3", "m4").filter { ctx.placeLinkDao().roleOf(pin, it) != null })
    }
}
