package com.rokid.context.work.frames.place

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.place.CardDelivery
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

/**
 * Place card history over `context.db` (Room v18, WP3): the guarded store, its pruning, the delivery marks, the refusals and the flag. The Forget wiring is pinned by the memory goldens and
 * [HistoryInsertVsForgetTest]. Synthetic names only.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlaceCardHistoryTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db: ContextDatabase = DatabaseFactory.inMemory(ctx)
    private var now = 1_000_000_000_000L
    private val logs = mutableListOf<String>()
    private var on = true
    private var seq = 0
    private val history = PlaceCardHistory(db, clock = { now }, log = { logs += it }, enabled = { on }, newId = { "h${++seq}" })

    @After fun close() { db.close() }

    private suspend fun ev(kind: HistoryKind = HistoryKind.PHOTO, place: String = "wd:Q1", frame: String? = null, members: List<String> = emptyList(), card: String? = null) =
        PlaceHistoryEvent(kind, place, "Site A", members, frame, card, authority = history.capture())

    @Test fun everyCommittedWriteBumpsTheChangeCounterSoUisNeverPoll() = runBlocking {
        var n = PlaceCardHistory.changes.value
        fun assertBumped(what: String) { val v = PlaceCardHistory.changes.value; assertTrue(what, v > n); n = v }
        history.add(ev(card = "c1", frame = "f1")); assertBumped("add")
        history.markDelivery("c1", CardDelivery.SENT); assertBumped("delivery")
        history.markDelivery("nope", CardDelivery.SENT); assertEquals("a failed update is no change", n, PlaceCardHistory.changes.value)
        history.add(ev()); n = PlaceCardHistory.changes.value
        history.delete(history.recent().first().id); assertBumped("delete")
    }

    @Test fun addStoresRowsNewestFirstWithDerivedDelivery() = runBlocking {
        history.add(ev(card = "c1")); now += 1000; history.add(ev(HistoryKind.POSSIBLE).copy(confPct = 54))
        val rows = history.recent()
        assertEquals(listOf("POSSIBLE", "PHOTO"), rows.map { it.kind })
        assertEquals(CardDelivery.NOT_SENT_POSSIBLE, rows[0].delivery); assertEquals(54, rows[0].confPct)
        assertEquals(CardDelivery.PENDING, rows[1].delivery)
        assertTrue(logs.first().startsWith("place history ready rows=")); assertTrue(logs.any { it == "place history add kind=PHOTO delivery=PENDING rows=1" })
    }

    @Test fun history_add_prune90d_cap500() = runBlocking {
        history.add(ev()) // the old one
        now += PlaceCardHistory.RETAIN_MS + 1
        (1..505).forEach { history.add(ev(place = "wd:Q$it")); now += 1 }
        assertEquals("the 90 day row went at the first add (the day prune); the cap is enforced by the next prune", 505, db.placeCardLogDao().cardLogCount())
        assertEquals(5, history.prune())
        assertEquals(500, db.placeCardLogDao().cardLogCount())
        assertEquals("the newest rows stay", "wd:Q505", history.recent().first().placeId)
        assertTrue(history.recent().none { it.placeId == "wd:Q1" })
        assertTrue(logs.contains("place history prune removed=5"))
        assertEquals("a second call the same day is a no-op", 0, history.prune(onlyOncePerDay = true))
    }

    @Test fun offStopsNewRowsButNeverBlocks() = runBlocking {
        val e = ev(); on = false
        assertNull(history.add(e)); assertEquals(0, db.placeCardLogDao().cardLogCount())
    }

    @Test fun rowDeleteAndPinDeleteGoThroughTheHistory() = runBlocking {
        val pin = "user:00000000-0000-0000-0000-000000000001"
        history.add(ev(HistoryKind.PIN, pin)); history.add(ev(HistoryKind.PIN_CLUSTER, "user:00000000-0000-0000-0000-000000000002", members = listOf("user:00000000-0000-0000-0000-000000000002", pin))); history.add(ev())
        val keep = history.recent().first { it.placeId == "wd:Q1" }.id
        assertEquals("the pin's own row and the cluster row that lists it", 2, history.deletePins(listOf(pin)))
        assertEquals(listOf(keep), history.recent().map { it.id })
        assertTrue(history.delete(keep)); assertTrue(history.recent().isEmpty())
        assertTrue(logs.contains("place history pin removed=2"))
    }

    @Test fun deliveryMarks() = runBlocking {
        history.add(ev(card = "c1")); history.add(ev(card = "c2"))
        assertTrue(history.markDelivery("c1", CardDelivery.SENT))
        assertFalse(history.markIfPending("c1", CardDelivery.NOT_SENT_LINK))
        assertTrue(history.markIfPending("c2", CardDelivery.NOT_SENT_LINK))
        assertEquals(setOf(CardDelivery.SENT, CardDelivery.NOT_SENT_LINK), history.recent().map { it.delivery }.toSet())
        assertFalse(history.markDelivery("nope", CardDelivery.SENT))
    }

    @Test fun aDeliveryReportedBeforeTheRowExistsIsAppliedWhenTheRowIsAdded() = runBlocking {
        assertFalse(history.markDelivery("c9", CardDelivery.SENT))
        assertFalse(history.markIfPending("c8", CardDelivery.NOT_SENT_LINK))
        history.add(ev(card = "c9")); history.add(ev(card = "c8")); history.add(ev(card = "c7"))
        val by = history.recent().associate { it.cardId to it.delivery }
        assertEquals(CardDelivery.SENT, by["c9"]); assertEquals(CardDelivery.NOT_SENT_LINK, by["c8"]); assertEquals(CardDelivery.PENDING, by["c7"])
    }

    @Test fun rowsHoldNoPositionAndLogsHoldNoContent() = runBlocking {
        history.add(PlaceHistoryEvent(HistoryKind.PIN, "user:00000000-0000-0000-0000-000000000001", "Room A1", frameId = "frame-1", cardId = "card-1", authority = history.capture()))
        history.deletePins(listOf("user:00000000-0000-0000-0000-000000000001")); history.prune()
        logs.forEach { l -> assertFalse(l, l.contains("Room A1")); assertFalse(l, l.contains("frame-1")); assertFalse(l, l.contains("user:")); assertFalse(l, Regex("\\d{5,}").containsMatchIn(l)) }
        assertNotNull(logs.firstOrNull())
    }

    @Test fun aFailureIsSwallowedAndLogged() = runBlocking {
        val pin = "user:00000000-0000-0000-0000-000000000001"
        val h = PlaceCardHistory(db, clock = { now }, log = { logs += it }, pinActive = { throw IllegalStateException("closed") })
        assertNull(h.add(PlaceHistoryEvent(HistoryKind.PIN, pin, "Room", authority = h.capture())))
        assertEquals("the transaction rolled back", 0, db.placeCardLogDao().cardLogCount())
        assertTrue(logs.contains("place history add failed"))
        logs.forEach { l -> assertFalse(l, l.contains("closed")) } // the exception text is never logged
    }

    // ---- the flag (v18: default ON, a read failure counts as OFF) ----

    private fun prodFlag() = com.rokid.context.orchestration.forget.DeveloperFlags
    private fun devPrefs() = ctx.getSharedPreferences(com.rokid.context.orchestration.forget.DeveloperFlags.PREFS, Context.MODE_PRIVATE)

    @Test fun placeCardHistoryDefaultsOn() = runBlocking {
        devPrefs().edit().clear().commit()
        assertTrue("default ON", prodFlag().placeCardHistory(ctx))
        val h = PlaceCardHistory(db, clock = { now }, enabled = { prodFlag().placeCardHistory(ctx) }, newId = { "x${++seq}" })
        assertNotNull(h.add(PlaceHistoryEvent(HistoryKind.PHOTO, "wd:Q1", "Site A", authority = h.capture())))
        prodFlag().setPlaceCardHistory(ctx, false)
        assertNull("explicitly OFF writes nothing", h.add(PlaceHistoryEvent(HistoryKind.PHOTO, "wd:Q1", "Site A", authority = h.capture())))
        assertEquals(1, db.placeCardLogDao().cardLogCount())
        devPrefs().edit().clear().commit(); Unit
    }

    @Test fun placeCardHistoryReadFailureIsOff() {
        val broken = object : android.content.ContextWrapper(ctx) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String?, mode: Int): android.content.SharedPreferences = throw IllegalStateException("prefs")
        }
        assertFalse(prodFlag().placeCardHistory(broken))
    }

    @Test fun theOwedKeyIsRemovedOnceAtStartup() {
        val prefs = ctx.getSharedPreferences(PlaceGuideSettings.PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean("enabled", true).putStringSet("history_deletions_owed", setOf("a:1")).commit()
        PlaceGraph.dropOwedKey(ctx)
        assertFalse(prefs.contains("history_deletions_owed")); assertTrue("the other keys stay", prefs.getBoolean("enabled", false))
        prefs.edit().clear().commit()
    }

    @Test fun theOwedKeyWaitsForThePlacesDbMigration() {
        val prefs = ctx.getSharedPreferences(PlaceGuideSettings.PREFS, Context.MODE_PRIVATE)
        prefs.edit().putStringSet("history_deletions_owed", setOf("a:1")).commit()
        var ran = 0
        PlaceGraph.dropOwedKey(ctx) { ran++; throw IllegalStateException("open") }
        assertEquals(1, ran); assertTrue("a failed migration keeps the key for the next start", prefs.contains("history_deletions_owed"))
        PlaceGraph.dropOwedKey(ctx) { ran++ }
        assertEquals(2, ran); assertFalse(prefs.contains("history_deletions_owed"))
        PlaceGraph.dropOwedKey(ctx) { ran++ }
        assertEquals("no key, no migration", 2, ran)
    }

    @Test fun noPlacesDbFileIsNeverCreatedByTheCleanup() {
        val prefs = ctx.getSharedPreferences(PlaceGuideSettings.PREFS, Context.MODE_PRIVATE)
        prefs.edit().putStringSet("history_deletions_owed", setOf("a:1")).commit()
        assertFalse(ctx.getDatabasePath(com.rokid.context.memory.place.PlacesDatabase.NAME).exists())
        PlaceGraph.dropOwedKey(ctx)
        assertFalse(ctx.getDatabasePath(com.rokid.context.memory.place.PlacesDatabase.NAME).exists()); assertFalse(prefs.contains("history_deletions_owed"))
    }

    // ---- place object S1: the Moment link of a single-place card for a user pin ----
    private val pinA = "user:00000000-0000-0000-0000-00000000000a"
    private val shot = 1_761_000_100_000L
    private suspend fun photo(id: String, at: Long = shot) = db.frameMetaDao().upsert(com.rokid.context.memory.db.FrameMetaEntity(id, null, null, "UNKNOWN", false, null, null, null, null, null, null, null, null, null, 1, capturedAtMs = at))
    private suspend fun linked() = db.placeLinkDao().momentIdsOf(pinA)

    @Test fun aSinglePinCardLinksItsMomentOnceAndTheSecondPhotoOfTheMomentAddsNothing() = runBlocking {
        photo("f1"); photo("f2", shot + 400)
        history.add(ev(HistoryKind.PIN, pinA, frame = "f1")); history.add(ev(HistoryKind.PIN, pinA, frame = "f2"))
        assertEquals(listOf(com.rokid.context.memory.moment.MomentId.ofMs(shot)), linked())
        assertEquals(1, db.placeLinkDao().count())
    }

    @Test fun noLinkForAClusterAWdPlaceNoPhotoOrAnExpiredPhoto() = runBlocking {
        photo("f1"); photo("f3", shot + 5_000)
        history.add(ev(HistoryKind.PIN_CLUSTER, pinA, frame = "f1", members = listOf(pinA, "user:00000000-0000-0000-0000-00000000000b")))
        history.add(ev(HistoryKind.PHOTO, "wd:Q1", frame = "f1")); history.add(ev(HistoryKind.PIN, pinA, frame = null))
        assertEquals(0, db.placeLinkDao().count())
        db.openHelper.writableDatabase.execSQL("INSERT INTO forget_tombstone (frameId, createdAt) VALUES ('f3', 1)")
        history.add(ev(HistoryKind.PIN, pinA, frame = "f3")) // an expired photo keeps the history row but links nothing
        assertEquals(0, db.placeLinkDao().count())
    }

    @Test fun noLinkWhenTheHistoryIsOffTheFrameIsForgottenOrThePinIsBeingRemoved() = runBlocking {
        photo("f1"); on = false
        history.add(ev(HistoryKind.PIN, pinA, frame = "f1")); on = true
        assertEquals(0, db.placeLinkDao().count())
        val gated = PlaceCardHistory(db, clock = { now }, log = { logs += it }, enabled = { true }, newId = { "g${++seq}" }, removing = setOf(pinA))
        gated.add(PlaceHistoryEvent(HistoryKind.PIN, pinA, "Site A", frameId = "f1", authority = gated.capture()))
        assertEquals(0, db.placeLinkDao().count())
    }

    @Test fun deletingThePinTakesItsLinksAndOnlyItsLinks() = runBlocking {
        val other = "user:00000000-0000-0000-0000-00000000000c"
        photo("f1"); photo("f2", shot + 9_000)
        history.add(ev(HistoryKind.PIN, pinA, frame = "f1")); history.add(ev(HistoryKind.PIN, other, frame = "f2"))
        assertEquals(2, db.placeLinkDao().count())
        history.deletePins(listOf(pinA))
        assertEquals(emptyList<String>(), linked()); assertEquals(1, db.placeLinkDao().momentIdsOf(other).size)
    }
}
