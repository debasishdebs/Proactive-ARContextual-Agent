package com.rokid.context.phone.ui.settings.place

import com.rokid.context.memory.place.GeoBox
import com.rokid.context.memory.place.Geohash5
import com.rokid.context.memory.place.PackStats
import com.rokid.context.memory.place.PlaceEntity
import com.rokid.context.memory.place.PlaceKinds
import com.rokid.context.memory.place.UserPlace
import com.rokid.context.work.frames.place.PinRefusal
import com.rokid.context.work.frames.place.PlaceFix
import com.rokid.context.work.frames.place.PlacePinning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaceGuideModelTest {
    private val d = StandardTestDispatcher()
    @Before fun before() { Dispatchers.setMain(d) }
    @After fun after() { Dispatchers.resetMain() }

    private class Control(override var enabled: Boolean = false) : PlaceGuideControl {
        val seeded = mutableListOf<GeoBox>()
        var fail = false
        override var budgetMb = 50
        var applied = 0; var cleared = 0; var zone = false
        var stored = listOf<UserPlace>()
        override suspend fun stats() = PackStats(pinned = stored.size, seeded = 3, fetched = 7, bytes = 5L * 1024 * 1024, budgetBytes = budgetMb * 1024L * 1024L)
        override suspend fun applyBudget(): Int { applied++; return 4 }
        override suspend fun clearFetched(): Int { cleared++; return 7 }
        override val pinning = PlacePinning(
            fix = { PlaceFix(7.957, 80.759, 1_000, 10f) }, zoneBlocked = { zone }, zones = { emptyList() },
            nearby = { _, _ -> listOf(PlaceEntity("wd:Q1", null, "Sigiriya", PlaceKinds.ARCHAEOLOGICAL_SITE, 7.957, 80.759, 250, "s", "wikipedia:en", "x", 1, false, false, 0, 10)) },
            load = { stored }, save = { stored = it; true }, elapsed = { 5_000L },
        )
        /** The production mapping (AndroidPlaceGuideControl: seedArea's SeedResult through PlaceGuideModel.seedOutcome); [failedCells] > 0 fakes a source that kept answering 429. */
        var failedCells = 0
        override suspend fun seed(box: GeoBox): Result<SeedOutcome> {
            seeded += box
            if (fail) return Result.failure(RuntimeException("x"))
            return Result.success(PlaceGuideModel.seedOutcome(com.rokid.context.memory.place.SeedResult(cells = 12, fetched = 12 - failedCells, skipped = 0, failed = failedCells, places = 80, retried = failedCells)))
        }
        override suspend fun summary() = "80 places in the pack (40 KB)"
    }

    @Test fun placeGuideStartsOffAndTheSwitchWritesThrough() {
        val c = Control(); val vm = PlaceGuideViewModel(c, d)
        assertFalse(vm.state.value.enabled)
        vm.setEnabled(true); assertTrue(c.enabled); assertTrue(vm.state.value.enabled)
    }

    @Test fun parsesATypedBoxAndRefusesNonsense() {
        assertEquals(GeoBox(17.34, 78.38, 17.41, 78.52), PlaceGuideModel.parseBox("17.34", " 78.38", "17.41", "78.52 "))
        assertNull(PlaceGuideModel.parseBox("17.41", "78.38", "17.34", "78.52")) // south above north
        assertNull(PlaceGuideModel.parseBox("a", "78", "17", "79"))
        assertNull(PlaceGuideModel.parseBox("-10", "-10", "80", "170")) // far too many cells
    }

    @Test fun seedingAnInvalidBoxSaysSoAndFetchesNothing() = runTest(d) {
        val c = Control(); val vm = PlaceGuideViewModel(c, d)
        vm.seed(null); d.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value.messageIsError); assertTrue(c.seeded.isEmpty())
    }

    @Test fun theHyderabadPresetSeedsTheCharminarGolcondaBox() = runTest(d) {
        val c = Control(); val vm = PlaceGuideViewModel(c, d)
        vm.seed(PlaceGuideModel.HYDERABAD); d.scheduler.advanceUntilIdle()
        assertEquals(listOf(PlaceGuideModel.HYDERABAD), c.seeded)
        assertTrue(PlaceGuideModel.HYDERABAD.contains(17.3616, 78.4747)); assertTrue(PlaceGuideModel.HYDERABAD.contains(17.3833, 78.4011))
        assertNotNull(vm.state.value.message); assertFalse(vm.state.value.messageIsError); assertFalse(vm.state.value.seeding)
        assertEquals("80 places in the pack (40 KB)", vm.state.value.packSummary)
    }

    @Test fun theRamojiPresetIsOneCellOutsideTheHyderabadBoxAroundTheRealCoordinate() = runTest(d) {
        val ramoji = PlaceGuideModel.RAMOJI
        assertTrue(ramoji.valid); assertTrue(ramoji.contains(17.2543, 78.6808))
        assertFalse(PlaceGuideModel.HYDERABAD.intersects(ramoji))
        assertEquals(listOf("tepfn"), Geohash5.cellsCovering(ramoji))
        val c = Control(); val vm = PlaceGuideViewModel(c, d)
        vm.seed(ramoji); d.scheduler.advanceUntilIdle()
        assertEquals(listOf(ramoji), c.seeded)
    }

    /** Site coordinates as Wikidata P625 returned them on 2026-11-18 (the same table as the coverage report): every preset box must contain its sites. */
    @Test fun everyTripPresetBoxContainsItsSitesAndStaysAFewCells() {
        val sites = mapOf(
            PlaceGuideModel.GANGTOK to listOf(27.33 to 88.62, 27.34055556 to 88.6275, 27.312777777778 to 88.604722222222, 27.288611111111 to 88.561388888889, 27.35083333 to 88.60361111),
            PlaceGuideModel.DARJEELING to listOf(27.0375 to 88.263, 27.016666666667 to 88.247222222222, 27.01166667 to 88.25027778, 27.04829 to 88.25142, 27.02756 to 88.259, 26.99484 to 88.28542),
            PlaceGuideModel.WEST_SIKKIM to listOf(27.3 to 88.233333333333, 27.304444444444 to 88.252777777778, 27.369166666667 to 88.222222222222, 27.373333333333 to 88.208333333333),
            PlaceGuideModel.SIGIRIYA to listOf(7.9569444444444 to 80.759722222222, 7.856563 to 80.6483403),
            PlaceGuideModel.ANURADHAPURA to listOf(8.335 to 80.410833333333, 8.35 to 80.3964, 8.351667 to 80.403611, 8.3447222222222 to 80.396666666667),
            PlaceGuideModel.POLONNARUWA to listOf(7.9333333333333 to 81.0, 7.96588 to 81.00497, 7.94722222 to 81.00111111),
            PlaceGuideModel.KANDY to listOf(7.293055555555555 to 80.635, 7.293611111111 to 80.641388888889, 7.27111 to 80.5956),
            PlaceGuideModel.GALLE to listOf(6.02747 to 80.21711, 5.975 to 80.429722222222, 5.945607 to 80.459765),
        )
        assertEquals(PlaceGuideModel.TRIP_PRESETS.toSet(), sites.keys)
        for ((preset, pts) in sites) {
            assertTrue(preset.name, preset.boxes.all { it.valid })
            for ((lat, lon) in pts) assertTrue("${preset.name} $lat,$lon", preset.boxes.any { it.contains(lat, lon) })
            assertTrue("${preset.name} has ${preset.cellCount} cells", preset.cellCount in 1..9)
            assertEquals(preset.boxes.flatMap { Geohash5.cellsCovering(it) }.toSet().size, preset.cellCount)
            assertTrue(preset.buttonLabel.contains("${preset.cellCount} cells"))
            assertTrue(preset.note.startsWith("Approximate: UNVERIFIED."))
        }
        assertEquals(listOf(9, 5, 8, 6, 4, 4, 6, 8), PlaceGuideModel.TRIP_PRESETS.map { it.cellCount })
        assertEquals(PlaceGuideModel.TRIP_PRESETS.map { it.id }.toSet().size, PlaceGuideModel.TRIP_PRESETS.size)
    }

    @Test fun onlyTheSriLankaPresetsCarryTheShortPhotographyHelpAndNoRuleTextIsInTheApp() {
        for (p in PlaceGuideModel.TRIP_PRESETS) {
            val sl = p.name.startsWith("Sri Lanka")
            assertEquals(p.name, sl, p.help == "Check local photography rules.")
            assertEquals(sl, p.note.endsWith("Check local photography rules."))
            for (w in listOf("fresco", "drone", "back to", "fee", "offence")) assertFalse("${p.name} $w", p.note.contains(w, ignoreCase = true))
        }
    }

    @Test fun aTwoBoxPresetSeedsItsBoxesOneAfterTheOtherAndSaysSo() = runTest(d) {
        val c = Control(); val vm = PlaceGuideViewModel(c, d)
        vm.seedPreset(PlaceGuideModel.SIGIRIYA); d.scheduler.advanceUntilIdle()
        assertEquals(PlaceGuideModel.SIGIRIYA.boxes, c.seeded)
        assertFalse(vm.state.value.seeding)
        assertTrue(vm.state.value.message!!.startsWith("Part 1 of 2: ")); assertTrue(vm.state.value.message!!.contains("Part 2 of 2: "))
        val one = Control(); val vm1 = PlaceGuideViewModel(one, d)
        vm1.seedPreset(PlaceGuideModel.KANDY); d.scheduler.advanceUntilIdle()
        assertEquals(PlaceGuideModel.KANDY.boxes, one.seeded); assertFalse(vm1.state.value.message!!.startsWith("Part"))
    }

    @Test fun aFailingPresetStopsAtTheFirstBoxAndShowsAnError() = runTest(d) {
        val c = Control().also { it.fail = true }; val vm = PlaceGuideViewModel(c, d)
        vm.seedPreset(PlaceGuideModel.GALLE); d.scheduler.advanceUntilIdle()
        assertEquals(1, c.seeded.size); assertTrue(vm.state.value.messageIsError); assertFalse(vm.state.value.seeding)
    }

    @Test fun aPresetStopsWhenCellsFailAfterTheRetryAndSaysHowToRetry() = runTest(d) {
        val c = Control().also { it.failedCells = 2 }; val vm = PlaceGuideViewModel(c, d)
        vm.seedPreset(PlaceGuideModel.SIGIRIYA); d.scheduler.advanceUntilIdle()
        assertEquals(1, c.seeded.size) // the second box was never requested
        val s = vm.state.value
        assertTrue(s.messageIsError); assertFalse(s.seeding)
        assertTrue(s.message!!, s.message!!.startsWith("Stopped at box 1 of 2")); assertTrue(s.message!!.contains("Tap seed again to retry"))
        assertEquals(2, PlaceGuideModel.seedOutcome(com.rokid.context.memory.place.SeedResult(12, 10, 0, 2, 5)).failedCells)
        assertEquals(0, PlaceGuideModel.seedOutcome(com.rokid.context.memory.place.SeedResult(12, 12, 0, 0, 5, partial = 3)).failedCells) // OSM-less cells do not stop a preset
    }

    @Test fun theSeedMessageSaysCellsOkPartialAndFailed() {
        val clean = com.rokid.context.memory.place.SeedResult(cells = 12, fetched = 12, skipped = 0, failed = 0, places = 40)
        assertEquals("Cells: 12 ok, 0 seeded without OSM extras, 0 failed. 40 places in the pack.", PlaceGuideModel.seedMessage(clean))
        val osmDown = com.rokid.context.memory.place.SeedResult(cells = 12, fetched = 12, skipped = 0, failed = 0, places = 40, partial = 12)
        assertEquals("Cells: 0 ok, 12 seeded without OSM extras, 0 failed. 40 places in the pack. Seed again later to add the OSM extras.", PlaceGuideModel.seedMessage(osmDown))
        val mixed = com.rokid.context.memory.place.SeedResult(cells = 12, fetched = 11, skipped = 0, failed = 1, places = 40, partial = 10, retried = 1)
        assertEquals("Cells: 1 ok, 10 seeded without OSM extras, 1 failed (1 retried once). 40 places in the pack. Try again when online.", PlaceGuideModel.seedMessage(mixed))
    }

    @Test fun aFailedSeedShowsAnErrorAndFreesTheButton() = runTest(d) {
        val c = Control().also { it.fail = true }; val vm = PlaceGuideViewModel(c, d)
        vm.seed(PlaceGuideModel.HYDERABAD); d.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value.messageIsError); assertFalse(vm.state.value.seeding)
    }

    @Test fun budgetIsOneOfTheFourChoicesAndEvictsAtOnce() = runTest(d) {
        val c = Control(); val vm = PlaceGuideViewModel(c, d)
        assertEquals(50, vm.state.value.budgetMb)
        vm.setBudget(30); d.scheduler.advanceUntilIdle(); assertEquals(50, c.budgetMb); assertEquals(0, c.applied)
        for (mb in listOf(25, 100, 200)) { vm.setBudget(mb); d.scheduler.advanceUntilIdle(); assertEquals(mb, c.budgetMb); assertEquals(mb, vm.state.value.budgetMb) }
        assertEquals(3, c.applied)
        assertEquals(listOf(25, 50, 100, 200), PlaceGuideModel.BUDGET_CHOICES_MB)
        assertEquals("5.0 MB", PlaceGuideModel.mb(5L * 1024 * 1024))
    }

    @Test fun theScreenShowsSizeAndCountsBySeededFetchedAndPinned() = runTest(d) {
        val c = Control(); val vm = PlaceGuideViewModel(c, d); d.scheduler.advanceUntilIdle()
        val st = vm.state.value.stats!!
        assertEquals(3, st.seeded); assertEquals(7, st.fetched); assertEquals(0, st.pinned); assertEquals(5L * 1024 * 1024, st.bytes)
    }

    @Test fun clearFetchedNeedsAConfirmationAndCancelDoesNothing() = runTest(d) {
        val c = Control(); val vm = PlaceGuideViewModel(c, d)
        vm.askClear(); assertTrue(vm.state.value.confirmClear); vm.cancelClear(); d.scheduler.advanceUntilIdle()
        assertFalse(vm.state.value.confirmClear); assertEquals(0, c.cleared)
        vm.askClear(); vm.confirmClear(); d.scheduler.advanceUntilIdle()
        assertEquals(1, c.cleared); assertFalse(vm.state.value.confirmClear)
        assertTrue(vm.state.value.message!!.contains("7") && vm.state.value.message!!.contains("pins were kept"))
    }

    @Test fun pinAPlaceHereOpensTheFormWithNearbySitesAndSavesAPinListedForEditAndDelete() = runTest(d) {
        val c = Control(); val vm = PlaceGuideViewModel(c, d)
        vm.beginPin(); d.scheduler.advanceUntilIdle()
        val dlg = vm.state.value.pinDialog!!
        assertEquals(listOf("Sigiriya"), dlg.parents.map { it.name }); assertEquals("60", dlg.radius)
        vm.updatePinDialog { it.copy(name = "Mirror Wall", parentIndex = 1, note = "tmp", kindIndex = PlacePinning.KINDS.indexOf(PlaceKinds.ARCHAEOLOGICAL_SITE)) }
        vm.updatePinDialog { it.copy(note = "Polished wall.") }
        vm.savePin(); d.scheduler.advanceUntilIdle()
        assertNull(vm.state.value.pinDialog)
        val pin = vm.state.value.pins.single()
        assertEquals("Mirror Wall", pin.name); assertEquals("wd:Q1", pin.parentId); assertEquals(60, pin.radiusM); assertEquals("Polished wall.", pin.note)
        vm.editPin(pin.id); assertEquals("Mirror Wall", vm.state.value.pinDialog!!.name)
        vm.updatePinDialog { it.copy(name = "Mirror Wall 2", radius = "90") }; vm.savePin(); d.scheduler.advanceUntilIdle()
        assertEquals("Mirror Wall 2", vm.state.value.pins.single().name); assertEquals(90, vm.state.value.pins.single().radiusM); assertEquals("wd:Q1", vm.state.value.pins.single().parentId)
        vm.deletePin(pin.id); d.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value.pins.isEmpty())
    }

    @Test fun pinningInsideAZoneIsRefusedAndNoFormOpens() = runTest(d) {
        val c = Control().also { it.zone = true }; val vm = PlaceGuideViewModel(c, d)
        vm.beginPin(); d.scheduler.advanceUntilIdle()
        assertNull(vm.state.value.pinDialog); assertTrue(vm.state.value.pinMessageIsError)
        assertEquals(PlaceGuideModel.refusalText(PinRefusal.ZONE), vm.state.value.pinMessage); assertTrue(c.stored.isEmpty())
    }

    @Test fun aBadFormStaysOpenWithAnErrorAndSavesNothing() = runTest(d) {
        val c = Control(); val vm = PlaceGuideViewModel(c, d)
        vm.beginPin(); d.scheduler.advanceUntilIdle()
        vm.savePin(); d.scheduler.advanceUntilIdle() // no name
        assertNotNull(vm.state.value.pinDialog!!.error); assertTrue(c.stored.isEmpty())
        vm.updatePinDialog { it.copy(name = "A", radius = "5") }; vm.savePin(); d.scheduler.advanceUntilIdle()
        assertNotNull(vm.state.value.pinDialog!!.error); assertTrue(c.stored.isEmpty())
        vm.cancelPin(); assertNull(vm.state.value.pinDialog)
    }

    @Test fun theNoteExplainsWhatStaysLocalAndWhatDeleteAllKeeps() {
        val n = PlaceGuideModel.PIN_NOTE
        assertTrue("Your note" in n && "stay on this phone" in n && "Delete all" in n && "pause zone" in n)
    }
}
