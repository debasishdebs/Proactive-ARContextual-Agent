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
import kotlinx.coroutines.test.TestScope
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
            log = { logged += it }, momentAlive = { id -> moments.any { it.id == id } },
        )
        /** Slice 1.4.2: the photo Moments that exist (newest first); a Forget removes one from here. */
        var moments = listOf<MomentChoice>()
        var momentCount = 0
        override suspend fun placeMomentCount(pinId: String) = momentCount
        val logged = mutableListOf<String>()
        override suspend fun recentMoments(limit: Int) = moments.take(limit)
        var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null; var gateDay: Long? = null
        override suspend fun momentsBetween(fromMs: Long, toMs: Long, limit: Int, beforeId: String?): List<MomentChoice> {
            if (gateDay == fromMs) gate?.await() // a slow load of that day
            return moments.filter { it.atMs in fromMs until toMs && (beforeId == null || it.id < beforeId) }.sortedByDescending { it.id }.take(limit)
        }
        override suspend fun momentsOf(ids: List<String>) = moments.filter { it.id in ids }.associateBy { it.id }
        override suspend fun setMoment(pinId: String, momentId: String?) = pinning.setMoment(pinId, momentId)
        override suspend fun sweepMoments() { val alive = moments.map { it.id }.toSet(); stored = stored.map { if (it.momentId != null && it.momentId !in alive) it.copy(momentId = null) else it } }
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
        assertEquals("Cells: 0 ok, 12 seeded without OSM extras, 0 failed. 40 places in the pack. 12 of 12 areas lack OSM extras, will retry. Seed again to retry now.", PlaceGuideModel.seedMessage(osmDown))
        val mixed = com.rokid.context.memory.place.SeedResult(cells = 12, fetched = 11, skipped = 0, failed = 1, places = 40, partial = 10, retried = 1)
        assertEquals("Cells: 1 ok, 10 seeded without OSM extras, 1 failed (1 retried once). 40 places in the pack. 1 of 12 areas failed and 10 lack OSM extras, will retry. Seed again when online to retry now.", PlaceGuideModel.seedMessage(mixed))
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
        val c = Control().also { it.moments = listOf(MomentChoice("0000000a", "/p/a", NOW - 60_000)) }; val vm = PlaceGuideViewModel(c, d, now = { NOW }, zone = java.time.ZoneOffset.UTC)
        vm.beginPin(); d.scheduler.advanceUntilIdle()
        vm.attachLatest(null); d.scheduler.advanceUntilIdle()
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
        assertEquals(1, vm.state.value.pins.size) // mode (a): Delete only asks
        vm.confirmDeletePin(); d.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value.pins.isEmpty()); assertNull(vm.state.value.pinDeleteConfirm)
    }

    @Test fun deleteAsksFirstShowsTheMomentCountAndCancelKeepsThePin() = runTest(d) {
        val id = "user:00000000-0000-0000-0000-000000000009"
        val c = Control().also { it.stored = listOf(UserPlace(id, "Antera", PlaceKinds.MONUMENT, 7.0, 80.0)); it.momentCount = 7 }
        val vm = PlaceGuideViewModel(c, d); d.scheduler.advanceUntilIdle()
        vm.deletePin(id); d.scheduler.advanceUntilIdle()
        assertEquals(PinDeleteConfirm(id, "Antera", 7), vm.state.value.pinDeleteConfirm)
        assertEquals(1, c.stored.size)
        vm.cancelDeletePin(); d.scheduler.advanceUntilIdle()
        assertNull(vm.state.value.pinDeleteConfirm); assertEquals(1, c.stored.size)
        vm.confirmDeletePin(); d.scheduler.advanceUntilIdle() // nothing open: nothing removed
        assertEquals(1, c.stored.size)
        vm.deletePin("user:none"); d.scheduler.advanceUntilIdle() // an unknown pin opens nothing
        assertNull(vm.state.value.pinDeleteConfirm)
    }

    @Test fun deleteDialogTextSaysWhatGoesAndWhatStays() {
        val t7 = PlaceGuideModel.deletePlaceText(7)
        assertTrue(t7.contains("links to 7 Moments")); assertTrue(t7.contains("The Moments, photos and notes stay")); assertTrue(t7.contains("cannot be undone"))
        assertTrue(PlaceGuideModel.deletePlaceText(1).contains("links to 1 Moment."))
        assertFalse(PlaceGuideModel.deletePlaceText(0).contains("links to"))
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

    // ---- slice 1.4.2: Pin to Moment ----

    private fun TestScope.newPin(c: Control, vm: PlaceGuideViewModel) {
        vm.beginPin(); d.scheduler.advanceUntilIdle()
        vm.updatePinDialog { it.copy(name = "Mirror Wall") }
    }

    @Test fun savingANewPinIsBlockedWithoutAMomentAndSaysWhy() = runTest(d) {
        val c = Control().also { it.moments = listOf(MomentChoice("0000000a", "/p/a", NOW - 60_000)) }; val vm = PlaceGuideViewModel(c, d, now = { NOW }, zone = java.time.ZoneOffset.UTC)
        newPin(c, vm)
        vm.savePin(); d.scheduler.advanceUntilIdle()
        assertEquals(PlaceGuideModel.ATTACH_FIRST, vm.state.value.pinDialog!!.error); assertTrue(c.stored.isEmpty())
        vm.attachLatest(null); d.scheduler.advanceUntilIdle()
        assertEquals("0000000a", vm.state.value.pinDialog!!.momentId); assertEquals("/p/a", vm.state.value.pinDialog!!.moment!!.path)
        vm.savePin(); d.scheduler.advanceUntilIdle()
        assertEquals("0000000a", c.stored.single().momentId); assertTrue("pin moment linked" in c.logged)
        assertEquals("0000000a", vm.state.value.moments["0000000a"]!!.id); assertFalse(PlaceGuideModel.needsMoment(vm.state.value.pins.single(), vm.state.value.moments))
    }

    @Test fun saveWithoutAMomentNeedsTheExplicitConfirmAndFlagsThePin() = runTest(d) {
        val c = Control(); val vm = PlaceGuideViewModel(c, d, now = { NOW }, zone = java.time.ZoneOffset.UTC)
        newPin(c, vm)
        vm.attachLatest(null); d.scheduler.advanceUntilIdle()
        assertEquals(PlaceGuideModel.NO_MOMENT_YET, vm.state.value.pinDialog!!.momentHint) // nothing captured yet
        vm.askSkipMoment(); assertTrue(vm.state.value.pinDialog!!.askSkipMoment)
        vm.cancelSkipMoment(); vm.savePin(); d.scheduler.advanceUntilIdle()
        assertTrue(c.stored.isEmpty()) // cancelling the confirm saves nothing
        vm.askSkipMoment(); vm.confirmSkipMoment(); d.scheduler.advanceUntilIdle()
        val pin = c.stored.single(); assertNull(pin.momentId)
        assertTrue(PlaceGuideModel.needsMoment(vm.state.value.pins.single(), vm.state.value.moments)) // the "Needs a Moment" badge
    }

    @Test fun latestMomentMustBeWithinThirtyMinutesElsePickFromTheList() = runTest(d) {
        val old = MomentChoice("00000001", "/p/old", NOW - 31 * 60_000)
        val c = Control().also { it.moments = listOf(old) }; val vm = PlaceGuideViewModel(c, d, now = { NOW }, zone = java.time.ZoneOffset.UTC)
        newPin(c, vm)
        vm.attachLatest(null); d.scheduler.advanceUntilIdle()
        assertNull(vm.state.value.pinDialog!!.momentId); assertTrue(vm.state.value.pinDialog!!.momentHint!!.contains("30 minutes"))
        vm.openMomentPicker(null); d.scheduler.advanceUntilIdle()
        assertEquals(listOf(old), vm.state.value.momentPicker!!.items)
        vm.pickMoment(old); d.scheduler.advanceUntilIdle()
        assertNull(vm.state.value.momentPicker); assertEquals("00000001", vm.state.value.pinDialog!!.momentId)
        assertEquals(old, PlaceGuideModel.latestWithin(listOf(old), NOW - 60_000)); assertNull(PlaceGuideModel.latestWithin(emptyList(), NOW))
    }

    private val DAY = 24 * 3_600_000L
    private val DAY0 = NOW - NOW % DAY // UTC midnight of "today"

    @Test fun thePickerOpensOnTodayAndPagesThirtyAtATime() = runTest(d) {
        val c = Control().also { c -> c.moments = (1..45).map { MomentChoice("%08x".format(1000 + it), "/p/$it", DAY0 + it * 1000L) } }
        val vm = PlaceGuideViewModel(c, d, now = { NOW }, zone = java.time.ZoneOffset.UTC)
        vm.openMomentPicker(null); d.scheduler.advanceUntilIdle()
        val pk = vm.state.value.momentPicker!!
        assertEquals(DAY0, pk.dayStartMs); assertEquals(30, pk.items.size); assertTrue(pk.hasMore)
        assertEquals("newest first", "%08x".format(1045), pk.items.first().id)
        vm.pickerMore(); d.scheduler.advanceUntilIdle()
        val all = vm.state.value.momentPicker!!
        assertEquals(45, all.items.size); assertFalse(all.hasMore); assertEquals(45, all.items.map { it.id }.toSet().size)
    }

    @Test fun theDaySelectorGoesBackAndForwardButNeverPastToday() = runTest(d) {
        val yesterday = MomentChoice("00000001", "/p/y", DAY0 - 3_600_000L); val today = MomentChoice("00000002", "/p/t", DAY0 + 1000L)
        val c = Control().also { it.moments = listOf(yesterday, today) }; val vm = PlaceGuideViewModel(c, d, now = { NOW }, zone = java.time.ZoneOffset.UTC)
        vm.openMomentPicker(null); d.scheduler.advanceUntilIdle()
        assertEquals(listOf(today), vm.state.value.momentPicker!!.items)
        vm.pickerDay(-1); d.scheduler.advanceUntilIdle()
        assertEquals(DAY0 - DAY, vm.state.value.momentPicker!!.dayStartMs); assertEquals(listOf(yesterday), vm.state.value.momentPicker!!.items)
        vm.pickerDay(-1); d.scheduler.advanceUntilIdle()
        assertTrue("an empty day is shown empty, not an error", vm.state.value.momentPicker!!.items.isEmpty())
        vm.pickerDay(1); vm.pickerDay(1); d.scheduler.advanceUntilIdle(); vm.pickerDay(1); d.scheduler.advanceUntilIdle()
        assertEquals("never past today", DAY0, vm.state.value.momentPicker!!.dayStartMs); assertEquals(listOf(today), vm.state.value.momentPicker!!.items)
    }

    @Test fun aSlowEarlierDayLoadNeverOverwritesALaterOne() = runTest(d) {
        val y = MomentChoice("00000001", "/p/y", DAY0 - 3_600_000L); val t = MomentChoice("00000002", "/p/t", DAY0 + 1000L)
        val c = Control().also { it.moments = listOf(y, t) }; val vm = PlaceGuideViewModel(c, d, now = { NOW }, zone = java.time.ZoneOffset.UTC)
        vm.openMomentPicker(null); d.scheduler.advanceUntilIdle()
        c.gateDay = DAY0 - DAY; c.gate = kotlinx.coroutines.CompletableDeferred()
        vm.pickerDay(-1); d.scheduler.advanceUntilIdle() // yesterday is slow
        vm.closeMomentPicker(); vm.openMomentPicker(null); d.scheduler.advanceUntilIdle() // today again, fast
        c.gate!!.complete(Unit); d.scheduler.advanceUntilIdle() // the slow answer arrives late
        assertEquals(DAY0, vm.state.value.momentPicker!!.dayStartMs); assertEquals(listOf(t), vm.state.value.momentPicker!!.items)
    }

    @Test fun loadMorePagesByTheLastIdOfItsOwnDayAndNeverRepeats() = runTest(d) {
        val c = Control().also { c -> c.moments = (1..65).map { MomentChoice("%08x".format(2000 + it), "/p/$it", DAY0 + it * 1000L) } }
        val vm = PlaceGuideViewModel(c, d, now = { NOW }, zone = java.time.ZoneOffset.UTC)
        vm.openMomentPicker(null); d.scheduler.advanceUntilIdle()
        vm.pickerMore(); d.scheduler.advanceUntilIdle(); vm.pickerMore(); d.scheduler.advanceUntilIdle()
        val pk = vm.state.value.momentPicker!!
        assertEquals(65, pk.items.size); assertEquals(65, pk.items.map { it.id }.toSet().size); assertFalse(pk.hasMore)
        assertEquals(pk.items.map { it.id }.sortedDescending(), pk.items.map { it.id })
    }

    @Test fun aForgottenMomentIsNotListedAndAYesterdayMomentCanBeAttachedToAnExistingPin() = runTest(d) {
        val y = MomentChoice("00000001", "/p/y", DAY0 - 3_600_000L); val gone = MomentChoice("00000002", "/p/g", DAY0 - 7_200_000L)
        val c = Control().also { it.moments = listOf(y); it.stored = listOf(UserPlace("user:00000000-0000-0000-0000-000000000007", "Old pin", PlaceKinds.MONUMENT, 7.0, 80.0)) } // `gone` was forgotten: the control no longer has it
        val vm = PlaceGuideViewModel(c, d, now = { NOW }, zone = java.time.ZoneOffset.UTC); d.scheduler.advanceUntilIdle()
        vm.openMomentPicker(c.stored.single().id); d.scheduler.advanceUntilIdle(); vm.pickerDay(-1); d.scheduler.advanceUntilIdle()
        assertEquals(listOf(y), vm.state.value.momentPicker!!.items); assertFalse(gone in vm.state.value.momentPicker!!.items)
        vm.pickMoment(y); d.scheduler.advanceUntilIdle()
        assertEquals("00000001", c.stored.single().momentId); assertNull(vm.state.value.momentPicker)
    }

    @Test fun aMomentForgottenWhileTheDialogIsOpenIsRefusedAndTheDialogStaysOpen() = runTest(d) {
        val m = MomentChoice("0000000a", "/p/a", NOW - 60_000)
        val c = Control().also { it.moments = listOf(m) }; val vm = PlaceGuideViewModel(c, d, now = { NOW }, zone = java.time.ZoneOffset.UTC)
        newPin(c, vm); vm.attachLatest(null); d.scheduler.advanceUntilIdle()
        c.moments = emptyList() // Forgotten while the dialog was open
        vm.savePin(); d.scheduler.advanceUntilIdle()
        val dlg = vm.state.value.pinDialog!!
        assertEquals("That Moment is gone, pick another or save without a Moment.", dlg.error); assertNull(dlg.momentId); assertTrue("nothing stored", c.stored.isEmpty())
        assertTrue("the held position survives so the user can go on", c.pinning.hasDraft)
        vm.askSkipMoment(); vm.confirmSkipMoment(); d.scheduler.advanceUntilIdle()
        assertNull(c.stored.single().momentId); assertTrue("pinnedAt is set for a new pin", c.stored.single().pinnedAt != null)
    }

    @Test fun deleteAllNamesThePinsLikeTheOtherDialogs() {
        val line = com.rokid.context.phone.ui.settings.memory.MemorySettingsCopy.pinsLine(7, listOf("A", "B", "C", "D", "E"))
        listOf("A", "B", "C", "D", "E").forEach { assertTrue(line.contains("\u201C$it\u201D")) }
        assertTrue(line.contains("and 2 more")); assertTrue(line.contains("cannot be undone")); assertTrue(line.contains("Pins without a Moment stay"))
        assertEquals("", com.rokid.context.phone.ui.settings.memory.MemorySettingsCopy.pinsLine(0))
    }

    @Test fun theMomentNoteSaysAnExplicitForgetDeletesThePin() {
        val n = PlaceGuideModel.MOMENT_NOTE
        assertTrue(n.contains("the pin is deleted with it") && n.contains("you are asked first") && n.contains("Needs a Moment"))
        assertFalse("the stale promise that the pin stays after a Forget", n.contains("the link goes and the pin stays") && !n.contains("only expires"))
    }

    @Test fun anExistingPinGetsAMomentRemovesItAndAnEditKeepsIt() = runTest(d) {
        val m = MomentChoice("0000000b", "/p/b", NOW - 1000)
        val c = Control().also { it.moments = listOf(m); it.stored = listOf(UserPlace("user:00000000-0000-0000-0000-000000000001", "Old pin", PlaceKinds.MONUMENT, 7.0, 80.0)) }
        val vm = PlaceGuideViewModel(c, d, now = { NOW }, zone = java.time.ZoneOffset.UTC); d.scheduler.advanceUntilIdle()
        val id = c.stored.single().id
        assertTrue(PlaceGuideModel.needsMoment(vm.state.value.pins.single(), vm.state.value.moments)) // an existing pin: badge, still a pin
        vm.attachLatest(id); d.scheduler.advanceUntilIdle()
        assertEquals("0000000b", c.stored.single().momentId); assertEquals(m, vm.state.value.moments["0000000b"])
        vm.editPin(id); vm.updatePinDialog { it.copy(name = "Renamed") }; vm.savePin(); d.scheduler.advanceUntilIdle()
        assertEquals("0000000b", c.stored.single().momentId) // an edit keeps the link
        vm.removeMoment(id); d.scheduler.advanceUntilIdle()
        assertNull(c.stored.single().momentId); assertEquals(1, c.stored.size); assertTrue("pin moment unlinked by user" in c.logged)
    }

    @Test fun aForgottenMomentClearsTheLinkAndTheBadgeReturnsWhileThePinStays() = runTest(d) {
        val m = MomentChoice("0000000c", "/p/c", NOW - 1000)
        val c = Control().also { it.moments = listOf(m); it.stored = listOf(UserPlace("user:00000000-0000-0000-0000-000000000002", "Pin", PlaceKinds.MONUMENT, 7.0, 80.0, momentId = "0000000c")) }
        val vm = PlaceGuideViewModel(c, d, now = { NOW }, zone = java.time.ZoneOffset.UTC); d.scheduler.advanceUntilIdle()
        assertFalse(PlaceGuideModel.needsMoment(vm.state.value.pins.single(), vm.state.value.moments))
        c.moments = emptyList() // Forget removed the Moment
        vm.cancelSeeds(); d.scheduler.advanceUntilIdle() // any refresh of the screen
        val pin = vm.state.value.pins.single()
        assertNull(pin.momentId); assertTrue(vm.state.value.moments.isEmpty()); assertTrue(PlaceGuideModel.needsMoment(pin, vm.state.value.moments))
    }

    @Test fun aLinkToAGoneMomentIsNeverStored() = runTest(d) {
        val c = Control().also { it.stored = listOf(UserPlace("user:00000000-0000-0000-0000-000000000003", "Pin", PlaceKinds.MONUMENT, 7.0, 80.0)) }
        assertFalse(c.setMoment("user:00000000-0000-0000-0000-000000000003", "0000000d"))
        assertNull(c.stored.single().momentId)
    }

    private companion object { const val NOW = 1_800_000_000_000L }
}
