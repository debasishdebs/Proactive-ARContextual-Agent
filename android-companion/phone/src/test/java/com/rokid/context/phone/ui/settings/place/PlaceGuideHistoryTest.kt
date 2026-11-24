package com.rokid.context.phone.ui.settings.place

import com.rokid.context.memory.place.CardDelivery
import com.rokid.context.memory.db.PlaceCardLogEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneId

/** B2: the Place Guide "Recent place cards" list, its detail and "Show on glasses again". Synthetic names only. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaceGuideHistoryTest {
    private val d = StandardTestDispatcher()
    @Before fun before() { Dispatchers.setMain(d) }
    @After fun after() { Dispatchers.resetMain() }

    private fun row(id: String, at: Long, kind: String = "PHOTO", delivery: String = CardDelivery.SENT, frame: String? = "f-$id", gone: Int = 0, place: String = "wd:Q$id", conf: Int? = null, members: String? = null) =
        PlaceCardLogEntity(id, at, place, "Site $id", kind, members, frame, conf, "c-$id", delivery, gone)

    private class FakeHistory(var rows: List<PlaceCardLogEntity>) : PlaceHistoryControl {
        val events = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        override val changes: kotlinx.coroutines.flow.Flow<Unit> get() = events
        var lines = mutableMapOf<String, String?>()
        var photos = mutableSetOf<String>()
        var env = ShowAgainEnv(placeGuideOn = true, zoneBlocked = false, glassesConnected = true)
        val shownAgain = mutableListOf<String>()
        var deleted = mutableListOf<String>()
        var showOk = true
        var on = true
        override val historyOn: Boolean get() = on
        override suspend fun rows() = if (!on) emptyList() else rows.sortedByDescending { it.at }
        override suspend fun line(row: PlaceCardLogEntity) = if (row.id in lines) lines[row.id] else "A line for ${row.title}."
        override suspend fun photoPath(frameId: String) = if (frameId in photos) "/photo/$frameId.jpg" else null
        override suspend fun delete(id: String): Boolean { deleted += id; rows = rows.filterNot { it.id == id }; return true }
        override fun showAgainBlock(row: PlaceCardLogEntity) = PlaceHistoryModel.showAgainBlock(row.kind, row.frameId, row.photoGone == 1, env)
        override suspend fun showAgain(row: PlaceCardLogEntity): Boolean { shownAgain += row.id; return showOk }
    }

    private fun control(h: FakeHistory?) = object : PlaceGuideControl {
        override var enabled = true
        override suspend fun seed(box: com.rokid.context.memory.place.GeoBox) = Result.success(SeedOutcome(""))
        override suspend fun summary() = ""
        override val history: PlaceHistoryControl? = h
    }

    private fun vm(h: FakeHistory?) = PlaceGuideViewModel(control(h), d).also { d.scheduler.advanceUntilIdle() }

    @Test fun aStoreChangeEventRefreshesTheListAndDetailWithoutAnyTimer() {
        val h = FakeHistory(listOf(row("a", 100, delivery = CardDelivery.PENDING), row("b", 200)))
        val v = vm(h)
        val job = kotlinx.coroutines.CoroutineScope(d).launch { v.historyChanges.collect { v.refreshHistory() } }
        d.scheduler.advanceUntilIdle()
        v.openHistory("a"); d.scheduler.advanceUntilIdle()
        h.rows = listOf(row("a", 100, delivery = CardDelivery.SENT), row("b", 200))
        h.events.tryEmit(Unit); d.scheduler.advanceUntilIdle()
        assertEquals("sent to glasses", v.state.value.history.first { it.id == "a" }.deliveryText)
        assertEquals("sent to glasses", v.state.value.historyDetail!!.row.deliveryText)
        h.rows = h.rows.filterNot { it.id == "a" } // a Forget: the open detail closes at once
        h.events.tryEmit(Unit); d.scheduler.advanceUntilIdle()
        assertNull(v.state.value.historyDetail); assertEquals(listOf("b"), v.state.value.history.map { it.id })
        job.cancel()
    }

    @Test fun emptyState() {
        val v = vm(FakeHistory(emptyList()))
        assertTrue(v.state.value.history.isEmpty())
        assertEquals("No place cards yet", PlaceHistoryModel.EMPTY)
    }

    @Test fun historyOffShowsNoRowsAndTheOneLineReason() {
        val h = FakeHistory(listOf(row("a", 100))); h.on = false
        val v = vm(h)
        assertTrue(v.state.value.history.isEmpty()); assertFalse(v.state.value.historyOn)
        assertEquals("Card history is off", PlaceHistoryModel.OFF_LINE)
    }

    @Test fun noHistoryOnThisBuildShowsNothing() {
        assertTrue(vm(null).state.value.history.isEmpty())
    }

    @Test fun listIsNewestFirstWithKindAndDeliveryLabels() {
        val v = vm(FakeHistory(listOf(
            row("a", 100, "PHOTO", CardDelivery.SENT), row("b", 300, "PIN", CardDelivery.NOT_SENT_LINK), row("c", 200, "PIN_CLUSTER", CardDelivery.NOT_SENT_HOST),
            row("e", 400, "NEARBY", CardDelivery.NOT_SENT_ZONE), row("f", 500, "TEXT", CardDelivery.NOT_SENT_LATE), row("g", 600, "POSSIBLE", CardDelivery.NOT_SENT_POSSIBLE, conf = 54),
        )))
        val rows = v.state.value.history
        assertEquals(listOf("g", "f", "e", "b", "c", "a"), rows.map { it.id })
        assertEquals(listOf("possible · not shown", "sign read", "nearby · not checked", "your pin", "several pins", "photo match"), rows.map { it.kindText })
        assertEquals(listOf("possible · not shown", "too late for the glasses", "not sent: pause zone", "not sent: glasses not connected", "not sent: Nexus shows the HUD", "sent to glasses"), rows.map { it.deliveryText })
        assertEquals("a row whose kind and delivery say the same shows it once", "possible · not shown", PlaceHistoryModel.summary(rows[0]))
        assertEquals("photo match · sent to glasses", PlaceHistoryModel.summary(rows.last()))
    }

    @Test fun deliveryLabelsCoverEveryState() {
        listOf(CardDelivery.PENDING, CardDelivery.SENT, CardDelivery.NOT_SENT_LINK, CardDelivery.NOT_SENT_HOST, CardDelivery.NOT_SENT_ZONE, CardDelivery.NOT_SENT_POSSIBLE, CardDelivery.NOT_SENT_LATE, CardDelivery.NOT_SENT_BUSY)
            .forEach { assertTrue(it, PlaceHistoryModel.deliveryLabel(it) != "unknown") }
        assertEquals("unknown", PlaceHistoryModel.deliveryLabel("SHOWN_LATER"))
    }

    @Test fun timeIsShownInThePhonesZone() {
        val at = 1_793_000_000_000L // 2026-10-26 07:33:20 UTC
        assertTrue(PlaceHistoryModel.timeLabel(at, ZoneId.of("UTC")).endsWith("07:33"))
        assertTrue(PlaceHistoryModel.timeLabel(at, ZoneId.of("Asia/Kolkata")).endsWith("13:03"))
    }

    @Test fun showAgainBlockReasons() {
        val ok = ShowAgainEnv(true, false, true)
        assertNull(PlaceHistoryModel.showAgainBlock("PHOTO", "f", false, ok))
        assertEquals("Place Guide is off", PlaceHistoryModel.showAgainBlock("PHOTO", "f", false, ok.copy(placeGuideOn = false)))
        assertEquals("A pause zone is active or near", PlaceHistoryModel.showAgainBlock("PHOTO", "f", false, ok.copy(zoneBlocked = true)))
        assertEquals("The glasses are not connected", PlaceHistoryModel.showAgainBlock("PIN", null, false, ok.copy(glassesConnected = false)))
        assertEquals("The photo is gone", PlaceHistoryModel.showAgainBlock("PHOTO", null, true, ok))
        assertEquals("The photo is gone", PlaceHistoryModel.showAgainBlock("PHOTO", null, false, ok))
        assertNull("a pin card needs no photo", PlaceHistoryModel.showAgainBlock("PIN", null, true, ok))
        assertNull("a cluster card needs no photo", PlaceHistoryModel.showAgainBlock("PIN_CLUSTER", null, false, ok))
        assertEquals("This was only a possible match", PlaceHistoryModel.showAgainBlock("POSSIBLE", "f", false, ok))
    }

    @Test fun detailShowsTheLineThePhotoAndEnablesShowAgain() {
        val h = FakeHistory(listOf(row("a", 100))); h.photos += "f-a"
        val v = vm(h); v.openHistory("a"); d.scheduler.advanceUntilIdle()
        val det = v.state.value.historyDetail!!
        assertEquals("A line for Site a.", det.line); assertEquals("/photo/f-a.jpg", det.photoPath); assertNull(det.showAgainBlock)
    }

    @Test fun detailWithAGonePlaceSaysSoAndBlocksShowAgain() {
        val h = FakeHistory(listOf(row("a", 100))); h.lines["a"] = null
        val v = vm(h); v.openHistory("a"); d.scheduler.advanceUntilIdle()
        val det = v.state.value.historyDetail!!
        assertNull(det.line); assertEquals("This place is no longer in your pack", det.showAgainBlock); assertEquals(PlaceHistoryModel.GONE_LINE, "This place is no longer in your pack")
    }

    @Test fun aRowWhosePhotoIsGoneHasNoThumbnailAndPhotoRowsCannotShowAgain() {
        val h = FakeHistory(listOf(row("a", 100, frame = null, gone = 1)))
        val v = vm(h); v.openHistory("a"); d.scheduler.advanceUntilIdle()
        val det = v.state.value.historyDetail!!
        assertNull(det.photoPath); assertTrue(det.row.photoGone); assertEquals("The photo is gone", det.showAgainBlock)
    }

    /** Retention v3 (r2-5): the row keeps its frame id as private lineage when the photo expires; the renderer must NOT load an image for it (it used to rely on frameId being null), even if a path still resolves. */
    @Test fun aGoneRowThatKeepsItsFrameIdNeverLoadsAnImage() {
        val h = FakeHistory(listOf(row("a", 100, frame = "f-a", gone = 1))); h.photos += "f-a" // the frame id is kept, and a path would resolve if anything asked for it
        val v = vm(h); v.openHistory("a"); d.scheduler.advanceUntilIdle()
        val det = v.state.value.historyDetail!!
        assertNull("no image is loaded for a gone photo", det.photoPath); assertTrue(det.row.photoGone); assertEquals("f-a", det.row.frameId)
        assertEquals("The photo is gone", det.showAgainBlock)
    }

    @Test fun showAgainIsDisabledWhenTheGlassesAreNotConnectedOrAZoneIsActive() {
        val h = FakeHistory(listOf(row("a", 100))); h.env = ShowAgainEnv(true, true, false)
        val v = vm(h); v.openHistory("a"); d.scheduler.advanceUntilIdle()
        assertEquals("A pause zone is active or near", v.state.value.historyDetail!!.showAgainBlock)
        v.showAgain(); d.scheduler.advanceUntilIdle()
        assertTrue("a blocked tap publishes nothing", h.shownAgain.isEmpty())
    }

    @Test fun showAgainPublishesOnceAndSaysSo() {
        val h = FakeHistory(listOf(row("a", 100, "PIN", frame = null)))
        val v = vm(h); v.openHistory("a"); d.scheduler.advanceUntilIdle()
        v.showAgain(); d.scheduler.advanceUntilIdle()
        assertEquals(listOf("a"), h.shownAgain)
        assertEquals("Sent to the glasses.", v.state.value.historyDetail!!.message); assertFalse(v.state.value.historyDetail!!.busy)
        h.showOk = false; v.showAgain(); d.scheduler.advanceUntilIdle()
        assertEquals("Not shown: it could not be sent right now.", v.state.value.historyDetail!!.message)
    }

    @Test fun deleteFromHistoryRemovesTheRowAndClosesTheDetail() {
        val h = FakeHistory(listOf(row("a", 100), row("b", 200)))
        val v = vm(h); v.openHistory("a"); d.scheduler.advanceUntilIdle()
        v.deleteHistory(); d.scheduler.advanceUntilIdle()
        assertEquals(listOf("a"), h.deleted); assertNull(v.state.value.historyDetail); assertEquals(listOf("b"), v.state.value.history.map { it.id })
    }

    @Test fun aForgetWhileTheDetailIsOpenClosesItOnTheNextRefresh() {
        val h = FakeHistory(listOf(row("a", 100), row("b", 200)))
        val v = vm(h); v.openHistory("a"); d.scheduler.advanceUntilIdle()
        assertNotNull(v.state.value.historyDetail)
        h.rows = h.rows.filterNot { it.id == "a" } // the photo was forgotten: the row is gone
        v.refreshHistory(); d.scheduler.advanceUntilIdle()
        assertNull(v.state.value.historyDetail); assertEquals(listOf("b"), v.state.value.history.map { it.id })
    }

    @Test fun aDeliveryChangeShowsOnTheNextRefresh() {
        val h = FakeHistory(listOf(row("a", 100, delivery = CardDelivery.PENDING)))
        val v = vm(h)
        assertEquals("waiting to be sent", v.state.value.history.single().deliveryText)
        h.rows = listOf(row("a", 100, delivery = CardDelivery.SENT)); v.refreshHistory(); d.scheduler.advanceUntilIdle()
        assertEquals("sent to glasses", v.state.value.history.single().deliveryText)
    }

    @Test fun theListShowsAPageAndMoreOnRequest() {
        val v = vm(FakeHistory((1..45).map { row("r$it", it.toLong()) }))
        assertEquals(45, v.state.value.history.size); assertEquals(20, v.state.value.historyShown)
        v.historyMore(); assertEquals(40, v.state.value.historyShown)
    }

    @Test fun membersAreSplitFromTheStoredList() {
        val r = row("a", 1, "PIN_CLUSTER", members = "user:1,user:2,user:3").toUi()
        assertEquals(listOf("user:1", "user:2", "user:3"), r.members)
        assertTrue(row("b", 1).toUi().members.isEmpty())
    }
}
