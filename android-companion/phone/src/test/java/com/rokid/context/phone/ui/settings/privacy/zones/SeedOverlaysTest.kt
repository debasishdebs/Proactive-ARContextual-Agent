package com.rokid.context.phone.ui.settings.privacy.zones

import com.rokid.context.memory.place.AREA_DONE
import com.rokid.context.memory.place.AREA_PARTIAL
import com.rokid.context.memory.place.AREA_SEEDING
import com.rokid.context.memory.place.GeoBox
import com.rokid.context.memory.place.Geohash5
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.cancelScope
import com.rokid.context.phone.ui.settings.place.PlaceGuideModel
import com.rokid.context.phone.ui.settings.place.SeedNeedsWifiException
import com.rokid.context.phone.ui.settings.place.SeedOutcome
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 27 Nov: bulk mode changes the cap (30,000 cells), the time text (an ESTIMATE) and keeps the confirm above 2,000 cells; the per-cell numbers are unchanged with the flag OFF. */
class SeedEstimateBulkTest {
    @After fun reset() { SeedEstimate.bulkMode = { false } }
    private val big = GeoBox(10.0, 70.0, 10.0 + 70 * Geohash5.LAT_STEP, 70.0 + 120 * Geohash5.LON_STEP) // 71 x 121 = 8,591 cells

    @Test fun perCellModeKeepsTheOldCapAndText() {
        SeedEstimate.bulkMode = { false }
        assertEquals(4_000L, SeedEstimate.seedMaxCells())
        assertEquals(SeedLevel.TOO_LARGE, SeedEstimate.level(SeedEstimate.cells(big)))
        assertTrue(SeedEstimate.estimate(GeoBox(10.0, 70.0, 10.2, 70.2)).contains(SeedEstimate.TIME_FROM_LAPTOP))
    }

    @Test fun bulkModeRaisesTheCapTo30000AndSaysEstimate() {
        SeedEstimate.bulkMode = { true }
        assertEquals(30_000L, SeedEstimate.seedMaxCells())
        assertTrue(SeedEstimate.cells(big) > 4_000)
        assertEquals(SeedLevel.CONFIRM, SeedEstimate.level(SeedEstimate.cells(big)))
        assertEquals(SeedLevel.TOO_LARGE, SeedEstimate.level(30_001))
        assertTrue(SeedEstimate.line(GeoBox(10.0, 70.0, 10.0 + 400 * Geohash5.LAT_STEP, 70.0 + 100 * Geohash5.LON_STEP)).contains("at most 30000 cells"))
        val text = SeedEstimate.estimate(big)
        assertTrue(text, text.contains("estimate") && text.contains("bulk mode") && !text.contains(SeedEstimate.TIME_FROM_LAPTOP))
        assertTrue(text, text.contains(SeedEstimate.LARGE_WARNING_BULK)) // above 2,000 cells the warning stays
        assertTrue(SeedEstimate.confirmText(8_591).contains("estimate") && SeedEstimate.confirmText(8_591).contains(SeedEstimate.LARGE_WARNING_BULK))
        assertFalse(SeedEstimate.confirmText(500).contains(SeedEstimate.LARGE_WARNING_BULK)) // not large: no warning
        assertEquals("about 50 min", SeedEstimate.duration(30_000))
    }
}

class SeedEstimateTest {
    @Test fun cellCountMatchesTheCoveringList() {
        for (b in listOf(PlaceGuideModel.HYDERABAD, PlaceGuideModel.RAMOJI, PlaceGuideModel.GANGTOK.boxes[0], GeoBox(-33.9, 18.3, -33.5, 18.9), GeoBox(0.01, -0.02, 0.2, 0.3))) {
            assertEquals(Geohash5.cellsCovering(b).size.toLong(), SeedEstimate.cells(b))
        }
    }

    @Test fun hugeBoxIsCountedWithoutBuildingTheList() {
        val n = SeedEstimate.cells(GeoBox(-80.0, -170.0, 80.0, 170.0))
        assertTrue(n > SeedEstimate.MAX_CELLS)
        assertEquals(SeedLevel.TOO_LARGE, SeedEstimate.level(n))
    }

    @Test fun invalidBoxHasNoCells() { assertEquals(0L, SeedEstimate.cells(GeoBox(2.0, 1.0, 1.0, 2.0))); assertEquals(SeedLevel.EMPTY, SeedEstimate.level(0)) }

    @Test fun thresholds() {
        assertEquals(SeedLevel.OK, SeedEstimate.level(1)); assertEquals(SeedLevel.OK, SeedEstimate.level(100))
        assertEquals(SeedLevel.CONFIRM, SeedEstimate.level(101)); assertEquals(SeedLevel.CONFIRM, SeedEstimate.level(4_000))
        assertEquals(SeedLevel.TOO_LARGE, SeedEstimate.level(4_001))
    }

    @Test fun aBoxOver2000CellsGetsTheLargeWarningInTheEstimateAndTheConfirmText() {
        val big = GeoBox(27.0, 87.0, 29.0, 89.8) // well over 2,000 cells, under the cap
        val n = SeedEstimate.cells(big)
        assertTrue(n.toString(), n > SeedEstimate.LARGE_CELLS && n <= SeedEstimate.MAX_CELLS)
        assertEquals(SeedLevel.CONFIRM, SeedEstimate.level(n))
        assertTrue(SeedEstimate.estimate(big).endsWith("it runs in the background and may take hours."))
        assertTrue(SeedEstimate.confirmText(n).contains("may take hours"))
        val small = SeedEstimate.confirmText(150)
        assertTrue(small, !small.contains("may take hours"))
        assertTrue(!SeedEstimate.estimate(GeoBox(27.0, 88.0, 27.6, 88.5)).contains("may take hours"))
    }

    @Test fun durationWords() {
        assertEquals("about 7 s", SeedEstimate.duration(1)); assertEquals("about 89 s", SeedEstimate.duration(12)); assertEquals("about 13 min", SeedEstimate.duration(100)); assertEquals("about 9 h", SeedEstimate.duration(4_000))
        assertEquals("about 3 min", SeedEstimate.duration(100, 1.5)) // the phone's own figure replaces the laptop one
    }

    @Test fun boxOfRejectsBadBoxes() {
        assertNull(SeedEstimate.boxOf(null)); assertNull(SeedEstimate.boxOf(doubleArrayOf(2.0, 1.0, 1.0, 2.0))); assertNull(SeedEstimate.boxOf(doubleArrayOf(1.0, 2.0, 3.0)))
        assertEquals(GeoBox(1.0, 2.0, 3.0, 4.0), SeedEstimate.boxOf(doubleArrayOf(1.0, 2.0, 3.0, 4.0)))
    }

    @Test fun estimateIsMarkedAsAnEstimateAndRoughUntil20SeededCells() {
        val sikkim = GeoBox(27.0, 88.0, 28.2, 89.0)
        val n = SeedEstimate.cells(sikkim)
        val rough = SeedEstimate.estimate(sikkim)
        assertTrue(rough, rough.startsWith("About $n cells (5 km), about ${n / 2} places, about "))
        assertTrue(rough, rough.contains("(measured on a laptop; your phone will refine it)") && rough.contains("rough"))
        val own = SeedRates.of(cells = 40, places = 80, bytes = 80 * 2048L)
        assertFalse(own.rough)
        val phone = SeedEstimate.estimate(sikkim, SeedRates.of(cells = 5, places = 0, bytes = 0, secondsPerCell = 2.0))
        assertTrue(phone, phone.contains("(from this phone's earlier seeds)") && !phone.contains("laptop") && phone.contains("rough")) // own timing is used even while places/size are still rough
        assertEquals(null, SeedRates.of(40, 80, 80 * 2048L, secondsPerCell = Double.NaN).secondsPerCell)
        val line = SeedEstimate.estimate(sikkim, own)
        assertTrue(line, line.contains("about ${n * 2} places") && !line.contains("rough"))
        assertTrue(SeedRates.of(19, 80, 1000).rough)
        assertTrue(SeedEstimate.estimate(GeoBox(-80.0, -170.0, 80.0, 170.0)).startsWith("Too large"))
        assertEquals("1 KB", SeedEstimate.sizeText(500)); assertEquals("0.3 MB", SeedEstimate.sizeText(300_000))
    }

    @Test fun areaRowShowsNamePlacesSizeStateAndDateWithoutCoordinates() {
        val a = SeedAreaInfo("z1", 1.0, 2.0, 3.0, 4.0, AREA_PARTIAL, name = "Sikkim", places = 12, bytes = 300_000, seededAt = 1_700_000_000_000)
        val r = SeedAreaList.row(0, a, java.time.ZoneOffset.UTC)
        val cells = SeedAreaList.cellCount(a)
        assertEquals("Sikkim · Not finished: tap Seed again · 12 places · $cells cells · 0.3 MB · 2023-11-14", r)
        assertEquals("Seeded area 2 · Done · $cells cells", SeedAreaList.row(1, SeedAreaInfo("z2", 1.0, 2.0, 3.0, 4.0, AREA_DONE)))
        assertTrue(SeedAreaList.ATTRIBUTION.contains("Wikipedia") && SeedAreaList.ATTRIBUTION.contains("Wikidata") && SeedAreaList.ATTRIBUTION.contains("OpenStreetMap"))
    }

    @Test fun lineSaysCountTimeAndWifiOrWhyNot() {
        assertTrue(SeedEstimate.line(PlaceGuideModel.RAMOJI).contains("1 cell (5 km each)"))
        assertTrue(SeedEstimate.line(PlaceGuideModel.RAMOJI).contains("runs in the background"))
        assertTrue(SeedEstimate.line(GeoBox(-80.0, -170.0, 80.0, 170.0)).startsWith("Too large"))
        assertTrue(SeedEstimate.line(null).startsWith("Draw a box"))
    }
}

class SeedOverlayModelTest {
    private val a = SeedAreaInfo("seed:1", 27.0, 88.0, 27.1, 88.1, AREA_DONE)

    @Test fun looksFollowTheStatus() {
        assertEquals(AreaLook.DONE, SeedOverlays.look(AREA_DONE)); assertEquals(AreaLook.PARTIAL, SeedOverlays.look(AREA_PARTIAL)); assertEquals(AreaLook.SEEDING, SeedOverlays.look(AREA_SEEDING))
        assertEquals(AreaLook.DONE, SeedOverlays.look("anything else"))
    }

    @Test fun labelsNameNoCoordinates() {
        val o = SeedOverlays.of(listOf(a, a.copy(id = "seed:2", status = AREA_PARTIAL), a.copy(id = "seed:3", status = AREA_SEEDING)))
        assertEquals(listOf("Seeded area 1", "Seeded area 2 (not finished, seed again)", "Seeded area 3 (not finished, seed again)"), o.map { it.label })
        assertTrue(o.none { it.label.contains("27.") })
    }

    @Test fun legendCountsAndPlurals() {
        assertEquals("1 pause zone · 1 seed area · 1 pin", SeedOverlays.legend(1, 1, 1))
        assertEquals("0 pause zones · 4 seed areas · 17 pins", SeedOverlays.legend(0, 4, 17))
    }

    @Test fun tinyShapesGrowToTheMinimumAroundTheirCentre() {
        val v = MapViewport(27.05, 88.05, 10.0, 400f, 400f)
        val box = a.box
        assertTrue(SeedOverlays.isTiny(v, box, 16f)) // 0.1 deg at 10 px/deg = 1 px
        val r = SeedOverlays.screenRect(v, box, 16f)
        assertEquals(16f, r[2], 0.01f); assertEquals(16f, r[3], 0.01f)
        assertEquals(200f, r[0] + r[2] / 2, 0.5f); assertEquals(200f, r[1] + r[3] / 2, 0.5f)
        val big = v.copy(ppd = 4_000.0)
        assertFalse(SeedOverlays.isTiny(big, box, 16f))
        assertTrue(SeedOverlays.screenRect(big, box, 16f)[2] > 300f)
    }

    @Test fun mapOpensFittedOnZonesAndAreas() {
        val boxes = SeedOverlays.fitBoxes(listOf(doubleArrayOf(1.0, 1.0, 2.0, 2.0)), listOf(a))
        assertEquals(2, boxes.size)
        val v = MapViewport.fit(boxes, 400f, 400f)
        val p = v.toPx(27.05, 88.05)
        assertTrue(p.x in 0f..400f && p.y in 0f..400f)
    }
}

class SeedMapViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val vms = mutableListOf<SeedMapViewModel>()
    @After fun tearDown() = vms.forEach { it.cancelScope() }

    private class Fake : SeedMapControl {
        var areas = listOf(SeedAreaInfo("seed:1", 27.0, 88.0, 27.1, 88.1, AREA_DONE))
        var pins = listOf(PinInfo(27.05, 88.05, 50))
        val seeded = mutableListOf<GeoBox>()
        val removed = mutableListOf<String>()
        var result: Result<SeedOutcome> = Result.success(SeedOutcome("Cells: 1 ok. Pack: 1 places."))
        override suspend fun areas() = areas
        override suspend fun pins() = pins
        override suspend fun seed(box: GeoBox): Result<SeedOutcome> { seeded += box; return result }
        var dotRows = listOf<com.rokid.context.memory.place.PlaceDot>()
        val dotAsks = mutableListOf<Pair<GeoBox, Int>>()
        var shown = true
        override suspend fun dots(box: GeoBox, cap: Int): List<com.rokid.context.memory.place.PlaceDot> { dotAsks += box to cap; return dotRows.filter { box.contains(it.lat, it.lon) }.take(cap) }
        override suspend fun dotLabel(id: String) = if (id == "d1") "Old fort \u00b7 fort\nBuilt in 1590." else null
        override suspend fun showPlaces() = shown
        override suspend fun setShowPlaces(on: Boolean) { shown = on }
        val reseeded = mutableListOf<Pair<String, GeoBox>>()
        override suspend fun reseed(id: String, box: GeoBox): Result<SeedOutcome> { reseeded += id to box; return result }
        val removedWithPlaces = mutableListOf<Boolean>()
        val renamed = mutableListOf<Pair<String, String?>>()
        override suspend fun rename(id: String, name: String?): Boolean { renamed += id to name; areas = areas.map { if (it.id == id) it.copy(name = name?.trim()?.takeIf { n -> n.isNotEmpty() }) else it }; return true }
        override suspend fun remove(id: String, withPlaces: Boolean) { removed += id; removedWithPlaces += withPlaces; areas = areas.filterNot { it.id == id } }
        override suspend fun setColour(id: String, index: Int) { areas = areas.map { if (it.id == id) it.copy(savedColour = index) else it } }
    }

    private val fake = Fake()
    private fun vm() = SeedMapViewModel(fake, main.dispatcher, settleMs = 0).also { vms += it }
    private val small = GeoBox(17.2443, 78.6708, 17.2643, 78.6908)
    private val large = GeoBox(17.0, 78.0, 19.0, 80.0) // well above 100 cells
    private val huge = GeoBox(-80.0, -170.0, 80.0, 170.0)

    @Test fun refreshLoadsAreasAndPins() = runTest {
        val v = vm(); v.refresh()
        assertEquals(1, v.state.value.areas.size); assertEquals(1, v.state.value.pins.size)
    }

    @Test fun tapSelectsTheAreaAndAsksForScrollAndFitWithAMargin() = runTest {
        val v = vm(); v.refresh()
        assertNull(v.state.value.selectedId); assertEquals(0, v.state.value.focusSeq)
        v.focusArea("seed:1")
        val s = v.state.value
        assertEquals("seed:1", s.selectedId); assertEquals(1, s.focusSeq)
        val fit = s.focusBox!!
        assertTrue(fit[0] < 27.0 && fit[1] < 88.0 && fit[2] > 27.1 && fit[3] > 88.1) // a margin on every side
        v.focusArea("seed:1") // a second tap keeps it focused and asks for another fit
        assertEquals("seed:1", v.state.value.selectedId); assertEquals(2, v.state.value.focusSeq)
        v.focusArea("nope"); assertEquals(2, v.state.value.focusSeq)
    }

    @Test fun editBoxLoadsTheAreaIntoTheDraftAndSeedThisBoxReseedsThatSameArea() = runTest {
        val v = vm(); v.refresh()
        assertNull(v.state.value.editingId)
        v.editArea("seed:1")
        val s = v.state.value
        assertEquals("seed:1", s.editingId); assertEquals(1, s.editSeq); assertEquals(listOf(27.0, 88.0, 27.1, 88.1), s.editBox!!.toList())
        assertEquals("seed:1", s.selectedId) // the map fits it, like a row tap
        v.requestSeed(small)
        assertEquals(listOf("seed:1" to small), fake.reseeded); assertTrue(fake.seeded.isEmpty()) // the area's id, not a new area
        assertNull(v.state.value.editingId) // a started seed leaves the edit
        assertEquals(1, v.state.value.areas.size) // nothing was removed
        assertTrue(fake.removed.isEmpty())
    }

    @Test fun seedAgainOnARowNeverReseedsTheAreaThatIsBeingEdited() = runTest {
        val v = vm(); v.refresh(); v.editArea("seed:1")
        v.requestSeedAgain(small)
        assertEquals(listOf(small), fake.seeded); assertTrue(fake.reseeded.isEmpty())
        assertEquals("seed:1", v.state.value.editingId) // still editing
    }

    @Test fun cancelEditLeavesTheAreaAloneAndALargeEditStillAsksFirst() = runTest {
        val v = vm(); v.refresh(); v.editArea("seed:1")
        v.endEdit()
        assertNull(v.state.value.editingId); assertNull(v.state.value.editBox)
        v.requestSeed(small); assertEquals(listOf(small), fake.seeded); assertTrue(fake.reseeded.isEmpty()) // not editing: an ordinary new seed
        val w = vm(); w.refresh(); w.editArea("seed:1"); w.requestSeed(large)
        assertNotNull(w.state.value.confirmCells); assertTrue(fake.reseeded.isEmpty())
        w.confirmSeed(); assertEquals(listOf("seed:1" to large), fake.reseeded)
        val x = vm(); x.refresh(); x.editArea("seed:1"); x.requestSeed(huge) // too large: refused, still editing
        assertEquals("seed:1", x.state.value.editingId); assertEquals(1, fake.reseeded.size)
    }

    @Test fun editIsRefusedWhileTheAreaIsSeedingAndDroppedWhenTheAreaIsRemoved() = runTest {
        fake.areas = listOf(SeedAreaInfo("seed:1", 27.0, 88.0, 27.1, 88.1, AREA_SEEDING, seed = com.rokid.context.phone.place.SeedAreaState(com.rokid.context.phone.place.SeedPhase.RUNNING, 10)))
        val v = vm(); v.refresh(); v.editArea("seed:1")
        assertNull(v.state.value.editingId); assertTrue(v.state.value.messageIsError)
        fake.areas = listOf(SeedAreaInfo("seed:1", 27.0, 88.0, 27.1, 88.1, AREA_DONE))
        val w = vm(); w.refresh(); w.editArea("seed:1"); fake.areas = emptyList(); w.refresh()
        assertNull(w.state.value.editingId)
    }

    private val view = GeoBox(27.0, 88.0, 27.2, 88.2) // a few cells: close enough to read the places

    @Test fun dotsAreReadOnceWhenTheViewSettlesAndOnlyThePlacesInViewComeBack() = runTest {
        fake.dotRows = listOf(com.rokid.context.memory.place.PlaceDot("d1", 27.05, 88.05, "fort"), com.rokid.context.memory.place.PlaceDot("far", 40.0, 70.0, "hill"))
        val v = vm(); v.refresh()
        v.viewportSettled(view)
        assertEquals(listOf("d1"), v.state.value.dots.map { it.id }); assertFalse(v.state.value.dotsTooFar); assertFalse(v.state.value.dotsCapped)
        assertEquals(SeedDots.CAP + 1, fake.dotAsks.last().second) // one more than the cap: to know it was cut
        assertEquals(view, fake.dotAsks.last().first) // the box of the view, nothing else
    }

    @Test fun aWideViewReadsNothingAndSaysZoomIn() = runTest {
        fake.dotRows = listOf(com.rokid.context.memory.place.PlaceDot("d1", 27.05, 88.05, "fort"))
        val v = vm(); v.refresh(); v.viewportSettled(view)
        val asks = fake.dotAsks.size
        v.viewportSettled(GeoBox(10.0, 70.0, 40.0, 100.0))
        assertTrue(v.state.value.dotsTooFar); assertTrue(v.state.value.dots.isEmpty()); assertEquals(asks, fake.dotAsks.size) // no read at all
        v.viewportSettled(null); assertFalse(v.state.value.dotsTooFar)
    }

    @Test fun moreThanTheCapIsCutAndSaysZoomInToSeeAll() = runTest {
        fake.dotRows = (0 until SeedDots.CAP + 5).map { com.rokid.context.memory.place.PlaceDot("p$it", 27.05 + it * 1e-6, 88.05, "x") }
        val v = vm(); v.refresh(); v.viewportSettled(view)
        assertEquals(SeedDots.CAP, v.state.value.dots.size); assertTrue(v.state.value.dotsCapped)
        assertTrue(SeedDots.CAPPED.contains("zoom in to see all", ignoreCase = true))
        fake.dotRows = fake.dotRows.take(10); v.viewportSettled(view)
        assertFalse(v.state.value.dotsCapped)
    }

    @Test fun theSwitchIsOnByDefaultPersistsAndOffRemovesTheDotsAndStopsReading() = runTest {
        fake.dotRows = listOf(com.rokid.context.memory.place.PlaceDot("d1", 27.05, 88.05, "fort"))
        val v = vm(); v.refresh(); v.viewportSettled(view)
        assertTrue(v.state.value.showPlaces); assertEquals(1, v.state.value.dots.size)
        v.setShowPlaces(false)
        assertFalse(fake.shown); assertTrue(v.state.value.dots.isEmpty())
        val asks = fake.dotAsks.size; v.viewportSettled(view); assertEquals(asks, fake.dotAsks.size) // off: no read
        val w = vm(); w.refresh(); assertFalse(w.state.value.showPlaces) // a new screen reads the saved choice
        w.setShowPlaces(true); w.viewportSettled(view); assertEquals(1, w.state.value.dots.size)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun aMovingViewReadsNothingAndOnlyTheViewThatHeldStillIsReadOnce() = runTest {
        fake.dotRows = listOf(com.rokid.context.memory.place.PlaceDot("d1", 27.05, 88.05, "fort"))
        val v = SeedMapViewModel(fake, main.dispatcher).also { vms += it } // the production hold time
        repeat(20) { v.viewportSettled(GeoBox(27.0 + it * 0.001, 88.0, 27.2, 88.2)); main.dispatcher.scheduler.advanceTimeBy(50) } // a drag: a new view every 50 ms
        assertTrue(fake.dotAsks.isEmpty()) // nothing was read while it moved
        main.dispatcher.scheduler.advanceTimeBy(SeedMapViewModel.SETTLE_MS + 10)
        assertEquals(1, fake.dotAsks.size) // one read, for the last view
        assertEquals(1, v.state.value.dots.size)
    }

    @Test fun aTapOnADotShowsItsLabelAndATapElsewhereOrAMissingPlaceHidesIt() = runTest {
        val v = vm(); v.refresh()
        v.tapDot("d1")
        assertEquals("d1", v.state.value.dotSelectedId); assertEquals("Old fort \u00b7 fort\nBuilt in 1590.", v.state.value.dotLabel!!.text)
        v.tapDot(null); assertNull(v.state.value.dotLabel); assertNull(v.state.value.dotSelectedId)
        v.tapDot("gone"); assertNull(v.state.value.dotLabel) // the place is gone: no label, nothing breaks
    }

    @Test fun aFailingReadGivesNoDotsAndNoCrash() = runTest {
        val bad = object : SeedMapControl {
            override suspend fun areas() = emptyList<SeedAreaInfo>(); override suspend fun pins() = emptyList<PinInfo>()
            override suspend fun seed(box: GeoBox): Result<SeedOutcome> = Result.failure(UnsupportedOperationException())
            override suspend fun remove(id: String, withPlaces: Boolean) {}
            override suspend fun dots(box: GeoBox, cap: Int): List<com.rokid.context.memory.place.PlaceDot> = throw IllegalStateException("db closed")
        }
        val v = SeedMapViewModel(bad, main.dispatcher, settleMs = 0).also { vms += it }; v.refresh(); v.viewportSettled(view)
        assertTrue(v.state.value.dots.isEmpty())
    }

    @Test fun mapBackgroundTapClearsTheHighlightAndRemovalClearsItToo() = runTest {
        val v = vm(); v.refresh(); v.focusArea("seed:1")
        v.clearFocus(); assertNull(v.state.value.selectedId); assertNull(v.state.value.focusBox)
        v.focusArea("seed:1"); v.askRemove("seed:1"); v.confirmRemove()
        assertNull(v.state.value.selectedId)
    }

    @Test fun colourDefaultsCycleByOrderAndAChosenColourIsKept() = runTest {
        fake.areas = (0 until 11).map { SeedAreaInfo("seed:$it", 1.0 + it, 1.0, 1.1 + it, 1.1, AREA_DONE) }
        val v = vm(); v.refresh()
        assertEquals(0, v.state.value.areas[0].colour); assertEquals(1, v.state.value.areas[1].colour)
        assertEquals(0, v.state.value.areas[SeedPalette.colours.size].colour) // cycles
        assertEquals(v.state.value.areas.map { it.colour }, v.state.value.rows.map { it.colour })
        v.setColour("seed:1", 5)
        assertEquals(5, v.state.value.areas[1].colour); assertEquals(5, v.state.value.rows[1].colour)
        v.setColour("seed:1", 99) // not a palette index: ignored
        assertEquals(5, v.state.value.areas[1].colour)
    }

    @Test fun renameStoresTheLabelAndTheListAndOverlayShowIt() = runTest {
        val v = vm(); v.refresh()
        assertTrue(v.state.value.rows[0].text.startsWith("Seeded area 1"))
        v.rename("seed:1", "  Gangtok trip ")
        assertEquals(listOf("seed:1" to "  Gangtok trip "), fake.renamed)
        assertTrue(v.state.value.rows[0].text.startsWith("Gangtok trip")); assertTrue(v.state.value.areas[0].label.startsWith("Gangtok trip"))
        assertEquals("Area renamed.", v.state.value.message)
        v.rename("seed:1", "") // blank = back to the default label
        assertTrue(v.state.value.rows[0].text.startsWith("Seeded area 1"))
    }

    @Test fun theProgressRowShowsOverallOkAndFailedCountsAndPartial() {
        val st = com.rokid.context.phone.place.SeedStates.derive(androidx.work.WorkInfo.State.RUNNING, com.rokid.context.phone.place.SeedProgress(525, 2100, okCells = 200, failCells = 325, partial = 12), com.rokid.context.memory.place.AREA_SEEDING, false)
        val rows = SeedAreaList.progress(listOf(SeedAreaInfo("seed:1", 1.0, 1.0, 1.1, 1.1, com.rokid.context.memory.place.AREA_SEEDING, seed = st)))
        assertEquals("Seeding places 25% done (525 of 2100) \u00b7 38% ok (200) \u00b7 62% failed (325) \u00b7 12 without OSM extras", rows.single().text)
    }

    @Test fun smallBoxSeedsAtOnceAndShowsTheSeedMessage() = runTest {
        val v = vm(); v.requestSeed(small)
        assertEquals(listOf(small), fake.seeded)
        assertEquals("Cells: 1 ok. Pack: 1 places.", v.state.value.message); assertFalse(v.state.value.messageIsError); assertFalse(v.state.value.seeding)
    }

    @Test fun failedCellsMessageIsTheExistingOne() = runTest {
        val r = com.rokid.context.memory.place.SeedResult(cells = 6, fetched = 4, skipped = 0, failed = 2, places = 3)
        fake.result = Result.success(PlaceGuideModel.seedOutcome(r))
        val v = vm(); v.requestSeed(small)
        assertTrue(v.state.value.message!!.contains("2 of 6 areas failed, will retry"))
    }

    @Test fun aboveHundredCellsAsksFirstAndSeedsOnlyOnYes() = runTest {
        assertTrue(SeedEstimate.cells(large) in 101..4_000)
        val v = vm(); v.requestSeed(large)
        assertEquals(SeedEstimate.cells(large), v.state.value.confirmCells); assertTrue(fake.seeded.isEmpty())
        v.cancelConfirm(); assertNull(v.state.value.confirmCells); assertTrue(fake.seeded.isEmpty())
        v.requestSeed(large); v.confirmSeed()
        assertEquals(listOf(large), fake.seeded); assertNull(v.state.value.confirmCells)
    }

    @Test fun tooLargeIsRefusedWithoutAnyRequest() = runTest {
        val v = vm(); v.requestSeed(huge)
        assertTrue(fake.seeded.isEmpty()); assertTrue(v.state.value.messageIsError); assertTrue(v.state.value.message!!.startsWith("Too large")); assertNull(v.state.value.confirmCells)
    }

    @Test fun noBoxIsAMessageNotARequest() = runTest {
        val v = vm(); v.requestSeed(null)
        assertTrue(fake.seeded.isEmpty()); assertTrue(v.state.value.messageIsError)
    }

    @Test fun controlRefusalShowsTheExistingText() = runTest {
        fake.result = Result.failure(SeedNeedsWifiException())
        val v = vm(); v.requestSeed(small)
        assertEquals(SeedNeedsWifiException().message, v.state.value.message); assertTrue(v.state.value.messageIsError); assertFalse(v.state.value.seeding)
    }

    @Test fun removeAsksThenRemovesAndRefreshes() = runTest {
        val v = vm(); v.refresh()
        v.askRemove("seed:1"); assertEquals("seed:1", v.state.value.pendingRemoveId); assertTrue(fake.removed.isEmpty())
        v.cancelRemove(); assertNull(v.state.value.pendingRemoveId); assertTrue(fake.removed.isEmpty())
        v.askRemove("seed:1"); v.confirmRemove()
        assertEquals(listOf("seed:1"), fake.removed); assertTrue(v.state.value.areas.isEmpty()); assertNotNull(v.state.value.message)
    }

    @Test fun removeDefaultsToTheAreaAndItsPlacesAndTheOtherChoiceKeepsThePlaces() = runTest {
        val v = vm(); v.refresh()
        v.askRemove("seed:1"); assertTrue(v.state.value.removeWithPlaces) // preselected
        v.confirmRemove(); assertEquals(listOf(true), fake.removedWithPlaces)
        v.refresh(); v.askRemove("seed:1"); v.setRemoveWithPlaces(false); v.confirmRemove(); assertEquals(listOf(true, false), fake.removedWithPlaces)
        v.askRemove("seed:1"); assertTrue("preselected again on every open", v.state.value.removeWithPlaces)
    }
}
