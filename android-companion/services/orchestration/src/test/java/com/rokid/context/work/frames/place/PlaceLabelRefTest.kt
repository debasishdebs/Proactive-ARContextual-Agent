package com.rokid.context.work.frames.place

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.db.CardLabel
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.FrameMetaEntity
import com.rokid.context.memory.forget.ForgetService
import com.rokid.context.memory.moment.MomentId
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
 * B3 (context.db v19): the label API of the place card history and the loop guard (D14): `place_link` role `REF` for a confirmed glance, cap 5 per place, never from WRONG or NOT_A_PLACE, gone with a label change and with
 * Forget, plus the eval column written with the row. Real in-memory Room, the real [ForgetService]. Synthetic ids only.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlaceLabelRefTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db: ContextDatabase = DatabaseFactory.inMemory(ctx)
    private var now = 1_000_000_000_000L
    private val logs = mutableListOf<String>()
    private var seq = 0
    private val history = PlaceCardHistory(db, clock = { now }, log = { logs += it }, newId = { "h${++seq}" })
    private val svc = ForgetService(db, ProposalRepository(db.proposalDao(), db.actionDao(), db)) { 1_000 }
    private val shot = 1_761_000_100_000L
    private val pin = "user:00000000-0000-0000-0000-00000000000a"
    private val links get() = db.placeLinkDao()

    @After fun close() { db.close() }

    /** A live photo f<i> captured i minutes after [shot] (one Moment per photo). */
    private suspend fun photo(i: Int, atOffsetMs: Long = i * 60_000L) {
        db.frameDao().insertIgnore(FrameEntity("f$i", 1, "/f$i.jpg", "rokid_glasses"))
        db.frameMetaDao().upsert(FrameMetaEntity("f$i", null, null, "UNKNOWN", false, null, null, null, null, null, null, null, null, null, 1, capturedAtMs = shot + atOffsetMs))
    }
    private suspend fun row(i: Int, place: String = "wd:Q1", kind: HistoryKind = HistoryKind.PHOTO, delivery: String? = null, ref: Boolean = false, eval: String? = null): String {
        now += 1000
        return requireNotNull(history.add(PlaceHistoryEvent(kind, place, "Site A", frameId = "f$i", delivery = delivery, authority = history.capture(), eval = eval, glanceRef = ref)))
    }
    private suspend fun refs(place: String = "wd:Q1") = links.refLinksOf(place)

    @Test fun labelSetChangeClearAndUnknown() = runBlocking {
        photo(1); val id = row(1)
        assertTrue(history.setLabel(id, CardLabel.RIGHT)); history.get(id)!!.let { assertEquals("RIGHT", it.label); assertEquals(now, it.labelAt) }
        now += 5; assertTrue(history.setLabel(id, CardLabel.WRONG)); history.get(id)!!.let { assertEquals("WRONG", it.label); assertEquals(now, it.labelAt) }
        assertTrue(history.setLabel(id, null)); history.get(id)!!.let { assertNull(it.label); assertNull(it.labelAt) }
        assertFalse(history.setLabel(id, "MAYBE")); assertNull(history.get(id)!!.label)
        assertFalse("a row that is gone", history.setLabel("nope", CardLabel.RIGHT))
        assertTrue("the log line has the token only", logs.filter { it.startsWith("place history label") }.all { it == "place history label set=RIGHT" || it == "place history label set=WRONG" || it == "place history label set=none" })
    }

    @Test fun summaryCountsLabelsAndShownAttemptsOnly() = runBlocking {
        (1..6).forEach { photo(it) }
        val a = row(1); val b = row(2); val c = row(3)
        row(4, kind = HistoryKind.NEARBY, delivery = CardDelivery.HELD_DATA_SAVER); row(5, "none", HistoryKind.NO_MATCH, CardDelivery.HELD_NOT_CONFIRMED); row(6, kind = HistoryKind.POSSIBLE)
        history.setLabel(a, CardLabel.RIGHT); history.setLabel(b, CardLabel.WRONG); history.setLabel(c, CardLabel.NOT_A_PLACE)
        val s = history.labelSummary()
        assertEquals(LabelSummary(1, 1, 1, 3), s)
    }

    @Test fun rightOnALiveMomentMakesARefAndAnyOtherLabelOrClearRemovesIt() = runBlocking {
        photo(1); val id = row(1)
        assertTrue(history.setLabel(id, CardLabel.RIGHT))
        assertEquals(listOf("REF"), refs().map { it.role }); assertEquals(mapOf("wd:Q1" to listOf("f1")), history.refFrames(listOf("wd:Q1", "wd:Q2")))
        assertEquals(MomentId.ofMs(shot + 60_000L), refs().single().momentId)
        history.setLabel(id, CardLabel.WRONG); assertEquals(0, links.count())
        history.setLabel(id, CardLabel.RIGHT); assertEquals(1, links.count()); history.setLabel(id, CardLabel.NOT_A_PLACE); assertEquals(0, links.count())
        history.setLabel(id, CardLabel.RIGHT); history.setLabel(id, null); assertEquals(0, links.count())
        assertTrue(history.refFrames(listOf("wd:Q1")).isEmpty())
    }

    @Test fun wrongAndNotAPlaceNeverMakeAReference() = runBlocking {
        photo(1); photo(2); val a = row(1); val b = row(2)
        history.setLabel(a, CardLabel.WRONG); history.setLabel(b, CardLabel.NOT_A_PLACE)
        assertEquals(0, links.count())
    }

    @Test fun noRefForNoMatchGoneOrExpiredOrForgottenPhotos() = runBlocking {
        photo(1); photo(2); photo(3)
        val nm = row(1, "none", HistoryKind.NO_MATCH, CardDelivery.HELD_NOT_CONFIRMED)
        history.setLabel(nm, CardLabel.RIGHT); assertEquals("a no-match row names no place", 0, links.count())
        // an expired photo (photoGone = 1)
        val ex = row(2); db.openHelper.writableDatabase.execSQL("UPDATE place_card_log SET photoGone = 1 WHERE id = '$ex'")
        history.setLabel(ex, CardLabel.RIGHT); assertEquals(0, links.count())
        // a photo forgotten after the row was written
        val gone = row(3); db.openHelper.writableDatabase.execSQL("INSERT INTO forget_tombstone (frameId, createdAt) VALUES ('f3', 1)")
        history.setLabel(gone, CardLabel.RIGHT); assertEquals(0, links.count())
        assertEquals("the label itself is still stored", "RIGHT", history.get(gone)!!.label)
    }

    @Test fun aHeldRowOfARealPlaceCanBeRightAndThatIsAReference() = runBlocking {
        photo(1); val id = row(1, kind = HistoryKind.NEARBY, delivery = CardDelivery.HELD_NOT_CONFIRMED)
        history.setLabel(id, CardLabel.RIGHT); assertEquals(1, links.count())
    }

    @Test fun capOfFiveRefsPerPlaceDropsTheOldest() = runBlocking {
        (1..7).forEach { photo(it) }
        val ids = (1..7).map { row(it) }
        ids.forEach { history.setLabel(it, CardLabel.RIGHT); now += 10 }
        val kept = refs().map { it.anchorId }
        assertEquals(5, kept.size); assertEquals(listOf("f3", "f4", "f5", "f6", "f7"), kept)
        assertEquals("another place has its own five", 0, refs("wd:Q2").size)
    }

    @Test fun aPinCardLinkBecomesCardRefAndComesBackToCard() = runBlocking {
        photo(1); val id = row(1, pin, HistoryKind.PIN)
        assertEquals(listOf("CARD"), links.refLinksOf(pin).map { it.role }.ifEmpty { listOf("CARD") }); assertEquals(1, links.count())
        history.setLabel(id, CardLabel.RIGHT); assertEquals(listOf("CARD_REF"), refs(pin).map { it.role }); assertEquals(1, links.count())
        history.setLabel(id, null)
        assertEquals(0, refs(pin).size); assertEquals("the card link is back", 1, links.count()); assertEquals("CARD", links.roleOf(pin, MomentId.ofMs(shot + 60_000L)))
    }

    @Test fun aSecondRightRowOfTheSameMomentKeepsTheRef() = runBlocking {
        photo(1); db.frameDao().insertIgnore(FrameEntity("f1b", 1, "/f1b.jpg", "rokid_glasses"))
        db.frameMetaDao().upsert(FrameMetaEntity("f1b", null, null, "UNKNOWN", false, null, null, null, null, null, null, null, null, null, 1, capturedAtMs = shot + 60_000L + 300))
        val a = row(1); now += 1000; val b = requireNotNull(history.add(PlaceHistoryEvent(HistoryKind.PHOTO, "wd:Q1", "Site A", frameId = "f1b", authority = history.capture())))
        history.setLabel(a, CardLabel.RIGHT); history.setLabel(b, CardLabel.RIGHT)
        assertEquals(1, refs().size)
        history.setLabel(a, null); assertEquals("the other RIGHT row still holds it", 1, refs().size)
        history.setLabel(b, null); assertEquals(0, refs().size)
    }

    @Test fun forgettingTheMomentRemovesTheRefAndItsRow() = runBlocking {
        photo(1); photo(2); val a = row(1); val b = row(2)
        history.setLabel(a, CardLabel.RIGHT); history.setLabel(b, CardLabel.RIGHT)
        assertEquals(2, refs().size)
        svc.forgetMoment("f1")
        assertEquals(listOf("f2"), refs().map { it.anchorId }); assertEquals(mapOf("wd:Q1" to listOf("f2")), history.refFrames(listOf("wd:Q1")))
        assertNull(history.get(a))
    }

    @Test fun theSecondSignalMakesARefWithoutALabelAndAWrongLabelRemovesIt() = runBlocking {
        photo(1); val id = row(1, ref = true)
        assertEquals(1, refs().size); assertEquals("REF", refs().single().role); assertNull(history.get(id)!!.label)
        history.setLabel(id, CardLabel.WRONG); assertEquals(0, refs().size)
    }

    @Test fun noRefFromAnExpiredOrForgottenPhotoEvenWithTheSignal() = runBlocking {
        photo(1); db.openHelper.writableDatabase.execSQL("INSERT INTO forget_tombstone (frameId, createdAt) VALUES ('f1', 1)")
        history.add(PlaceHistoryEvent(HistoryKind.PHOTO, "wd:Q1", "Site A", frameId = "f1", authority = history.capture(), glanceRef = true)) // expired: the row stays, nothing links
        assertEquals(0, links.count())
    }

    @Test fun evalIsStoredWithTheRowAndAnOversizedOneIsDropped() = runBlocking {
        photo(1); photo(2)
        val ok = row(1, eval = """{"v":1,"layer":"L2"}"""); assertEquals("""{"v":1,"layer":"L2"}""", history.get(ok)!!.eval)
        val big = row(2, eval = "x".repeat(PlaceEval.MAX_BYTES + 1)); assertNull(history.get(big)!!.eval)
        assertNotNull(history.get(big))
    }

    @Test fun placeLinkIsDeletedWithEveryRowDeletePathOfThePin() = runBlocking {
        photo(1); val id = row(1, pin, HistoryKind.PIN); history.setLabel(id, CardLabel.RIGHT)
        history.deletePins(listOf(pin)); assertEquals(0, links.count())
    }
}
