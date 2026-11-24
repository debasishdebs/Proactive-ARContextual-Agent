package com.rokid.context.work.frames.place

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.place.CardDelivery
import com.rokid.context.memory.db.PlaceCardLogEntity
import com.rokid.context.memory.place.PlaceEntity
import com.rokid.context.memory.place.PlacePack
import com.rokid.context.memory.place.PlacesDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Codex place fixes r1 P1-3: "Show on glasses again" cannot outlive a Forget or Delete all that ran while it read the pack. Synthetic names only. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlaceShowAgainTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db = PlacesDatabase.inMemory(ctx)
    private val pack = PlacePack(db, null)
    private val cdb = com.rokid.context.memory.db.DatabaseFactory.inMemory(ctx)
    private val history = PlaceCardHistory(cdb, clock = { 1_000L }, newId = { "h${seq++}" })
    private var seq = 1
    private val holder = PlaceCardHolder()
    private var gen = 0L
    private val pinId = "user:00000000-0000-0000-0000-000000000001"
    private val place = PlaceEntity(pinId, null, "Room A1", "shop", 1.0, 2.0, 20, "A note", "user", "user", 0, false, true, 0, 0)
    private val row = PlaceCardLogEntity("r1", 500L, pinId, "Room A1", "PIN", null, "f1", null, "pc0", CardDelivery.SENT)

    @After fun close() { db.close(); cdb.close() }

    private fun again(places: suspend (PlaceCardLogEntity) -> List<PlaceEntity> = { listOf(place) }, alive: suspend (PlaceCardLogEntity) -> Boolean = { true }) =
        PlaceShowAgain(holder, { history }, places, { gen }, alive)

    @Test fun normalShowAgain_publishesAndRecordsOneRow() = runBlocking {
        assertTrue(again().run(row))
        assertNotNull(holder.card.value); assertEquals(1, cdb.placeCardLogDao().cardLogCount())
    }

    @Test fun forgetDuringThePackRead_neverPublishesNorRecords() = runBlocking {
        assertFalse(again(places = { holder.clear(); listOf(place) }).run(row)) // Forget / Delete all withdrew everything while placesOf() was suspended
        assertNull(holder.card.value); assertEquals(0, cdb.placeCardLogDao().cardLogCount())
    }

    @Test fun deleteAllDuringThePackRead_generationMoved_neverPublishes() = runBlocking {
        assertFalse(again(places = { gen++; listOf(place) }).run(row))
        assertNull(holder.card.value); assertEquals(0, cdb.placeCardLogDao().cardLogCount())
    }

    @Test fun photoForgottenDuringTheRead_provenanceRevalidated() = runBlocking {
        var gone = false
        assertFalse(again(places = { gone = true; listOf(place) }, alive = { !gone }).run(row))
        assertNull(holder.card.value); assertEquals(0, cdb.placeCardLogDao().cardLogCount())
    }

    @Test fun forgetAfterPublicationBeforeTheRowInsert_cardWithdrawnAndNoRow() = runBlocking {
        var asked = 0
        val shown = again(alive = { if (++asked == 2) holder.clear(); true }).run(row) // the second question is the post-insert recheck
        assertFalse(shown); assertNull(holder.card.value); assertEquals(0, cdb.placeCardLogDao().cardLogCount())
    }

    @Test fun placeGone_noCard() = runBlocking {
        assertFalse(again(places = { emptyList() }).run(row)); assertNull(holder.card.value)
    }
}
