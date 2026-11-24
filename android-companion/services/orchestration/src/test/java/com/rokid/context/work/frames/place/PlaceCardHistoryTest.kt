package com.rokid.context.work.frames.place

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.forget.ForgetListeners
import com.rokid.context.memory.place.CardDelivery
import com.rokid.context.memory.place.PlaceKinds
import com.rokid.context.memory.place.PlacePack
import com.rokid.context.memory.place.PlacesDatabase
import com.rokid.context.memory.place.UserPlace
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

/** Place card history (spec 2026-11-24 B1): the store, its pruning, and the Forget wiring through the real [ForgetListeners]. Synthetic names only. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlaceCardHistoryTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db = PlacesDatabase.inMemory(ctx)
    private val pack = PlacePack(db, null)
    private var now = 1_000_000_000_000L
    private val logs = mutableListOf<String>()
    private var on = true
    private var seq = 0
    private val history = PlaceCardHistory(pack.cardLogDao, clock = { now }, log = { logs += it }, enabled = { on }, newId = { "h${++seq}" })

    @Before fun wire() {
        ctx.getSharedPreferences(com.rokid.context.memory.place.UserPinStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        PlaceGraph.installForTests(pack); PlaceGraph.installHistoryForTests(history); PlaceGraph.attach(ctx); PlaceGraph.registerListeners()
    }
    @After fun close() { PlaceGraph.installForTests(null); db.close() }

    private fun ev(kind: HistoryKind = HistoryKind.PHOTO, place: String = "wd:Q1", frame: String? = null, members: List<String> = emptyList(), card: String? = null) =
        PlaceHistoryEvent(kind, place, "Site A", members, frame, card)

    private fun pin(n: Int) = UserPlace("user:00000000-0000-0000-0000-%012d".format(n), "Room A$n", PlaceKinds.ARCHAEOLOGICAL_SITE, 7.957, 80.759, 20, null, "")

    @Test fun everyCommittedWriteBumpsTheChangeCounterSoUisNeverPoll() = runBlocking {
        var n = PlaceCardHistory.changes.value
        fun assertBumped(what: String) { val v = PlaceCardHistory.changes.value; assertTrue(what, v > n); n = v }
        val id = history.add(ev(card = "c1", frame = "f1"))!!; assertBumped("add")
        history.markDelivery("c1", CardDelivery.SENT); assertBumped("delivery")
        history.markDelivery("nope", CardDelivery.SENT); assertEquals("a failed update is no change", n, PlaceCardHistory.changes.value)
        history.onFramesForgotten(listOf("f1")); assertBumped("forget")
        history.add(ev()); n = PlaceCardHistory.changes.value
        history.delete(history.recent().first().id); assertBumped("delete")
        history.add(ev()); n = PlaceCardHistory.changes.value
        history.clearAll(); assertBumped("clear")
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
        assertEquals("the 90 day row went at the first add (the day prune); the cap is enforced by the next prune", 505, pack.cardLogDao.cardLogCount())
        assertEquals(5, history.prune())
        assertEquals(500, pack.cardLogDao.cardLogCount())
        assertEquals("the newest rows stay", "wd:Q505", history.recent().first().placeId)
        assertTrue(history.recent().none { it.placeId == "wd:Q1" })
        assertTrue(logs.contains("place history prune removed=5"))
    }

    @Test fun offStopsNewRowsButNeverBlocks() = runBlocking {
        on = false
        assertNull(history.add(ev())); assertEquals(0, pack.cardLogDao.cardLogCount())
    }

    @Test fun userForget_deletesRow() = runBlocking {
        history.add(ev(frame = "f1")); history.add(ev(frame = "f2"))
        ForgetListeners.framesForgotten(listOf("f1"))
        assertEquals(listOf("f2"), history.recent().map { it.frameId })
        assertTrue(logs.contains("place history forget frames=1 removed=1"))
    }

    @Test fun photoExpiry_marksGoneKeepsRowAndItsLineage() = runBlocking {
        history.add(ev(frame = "f1"))
        ForgetListeners.photosExpired(listOf("f1"))
        val r = history.recent().single()
        assertEquals("retention v3 (r2-5): the frame id stays as private lineage", "f1", r.frameId); assertEquals(1, r.photoGone)
        assertTrue(logs.contains("place history expire frames=1 marked=1"))
        ForgetListeners.framesForgotten(listOf("f1")) // a later user Forget of the expired photo still deletes the row
        assertTrue(history.recent().isEmpty())
    }

    /** End to end through the real memory ForgetService: expiry only marks the photo gone (row and its lineage stay), a user Forget deletes the row, also for the expired photo. */
    @Test fun realForgetService_expiryKeepsRowAndLineage_userForgetDeletesRow() = runBlocking {
        val mdb = com.rokid.context.memory.db.DatabaseFactory.inMemory(ctx)
        try {
            val svc = com.rokid.context.memory.forget.ForgetService(mdb, com.rokid.context.memory.ProposalRepository(mdb.proposalDao(), mdb.actionDao(), mdb)) { 1_000 }
            listOf("f1", "f2").forEach { mdb.frameDao().insertIgnore(com.rokid.context.memory.db.FrameEntity(it, 1, "/$it.jpg", "rokid_glasses")) }
            history.add(ev(frame = "f1")); history.add(ev(frame = "f2"))
            svc.expirePhotos(listOf("f1"))
            val rows = history.recent()
            assertEquals("expiry keeps both rows", 2, rows.size)
            val gone = rows.single { it.photoGone == 1 }
            assertEquals("the frame id stays as private lineage (r2-5)", "f1", gone.frameId); assertEquals("f2", rows.single { it.photoGone == 0 }.frameId)
            svc.forgetMoment("f2")
            assertEquals("a user Forget deletes the row", 1, history.recent().size)
            assertEquals(1, history.recent().single().photoGone)
            svc.forgetMoment("f1")
            assertTrue("a user Forget of the EXPIRED photo deletes its row too", history.recent().isEmpty())
        } finally { mdb.close() }
    }

    @Test fun retentionRangeNeverDeletesRows_userRangeDoes() = runBlocking {
        history.add(ev()); val at = now
        ForgetListeners.rangeExpired(at - 10, at + 10, emptyList())
        assertEquals("the time link of a retention expiry keeps the row", 1, history.recent().size)
        ForgetListeners.rangeForgotten(at + 1, at + 10, emptyList())
        assertEquals("a range that misses the row keeps it", 1, history.recent().size)
        ForgetListeners.rangeForgotten(at - 10, at + 10, emptyList())
        assertTrue(history.recent().isEmpty()); assertTrue(logs.contains("place history range removed=1"))
    }

    @Test fun deleteAll_clears() = runBlocking {
        history.add(ev()); history.add(ev(HistoryKind.POSSIBLE))
        ForgetListeners.deleteAll()
        assertTrue(history.recent().isEmpty()); assertTrue(logs.contains("place history cleared reason=delete_all removed=2"))
    }

    @Test fun deleteAllThroughThePackAlsoClears() = runBlocking {
        history.add(ev())
        PlaceGraph.deleteAll(ctx)
        assertTrue(history.recent().isEmpty())
    }

    @Test fun pinDeleted_deletesItsRows() = runBlocking {
        val p1 = pin(1); val p2 = pin(2); val p3 = pin(3)
        assertTrue(PlaceGraph.savePins(ctx, listOf(p1, p2, p3)))
        history.add(ev(HistoryKind.PIN, place = p1.id)); history.add(ev(HistoryKind.PIN_CLUSTER, place = p2.id, members = listOf(p1.id, p2.id)))
        history.add(ev(HistoryKind.PIN, place = p3.id)); history.add(ev(HistoryKind.PHOTO, place = "wd:Q1"))
        assertTrue(PlaceGraph.savePins(ctx, listOf(p2, p3))) // p1 removed: its row and the cluster that lists it go
        assertEquals(setOf(p3.id, "wd:Q1"), history.recent().map { it.placeId }.toSet())
        assertTrue(logs.contains("place history pin removed=2"))
        assertTrue(PlaceGraph.savePins(ctx, listOf(p2, p3))) // an unchanged list removes nothing
        assertEquals(2, history.recent().size)
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
        history.add(PlaceHistoryEvent(HistoryKind.PIN, "user:00000000-0000-0000-0000-000000000001", "Room A1", frameId = "frame-1", cardId = "card-1"))
        ForgetListeners.framesForgotten(listOf("frame-1")); ForgetListeners.deleteAll()
        logs.forEach { l -> assertFalse(l, l.contains("Room A1")); assertFalse(l, l.contains("frame-1")); assertFalse(l, l.contains("user:")); assertFalse(l, Regex("\\d{5,}").containsMatchIn(l)) }
        assertNotNull(logs.firstOrNull())
    }

    @Test fun aFailureIsSwallowedAndLogged() = runBlocking {
        val broken = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(com.rokid.context.memory.place.PlacesDao::class.java)) { _, _, _ -> throw IllegalStateException("closed") } as com.rokid.context.memory.place.PlacesDao
        val h = PlaceCardHistory(broken, clock = { now }, log = { logs += it })
        assertNull(h.add(ev())); assertTrue(h.recent().isEmpty()); assertFalse(h.markDelivery("c", CardDelivery.SENT))
        assertTrue(logs.contains("place history add failed")); assertTrue(logs.contains("place history read failed"))
        logs.forEach { l -> assertFalse(l, l.contains("closed")) } // the exception text is never logged
    }
}
