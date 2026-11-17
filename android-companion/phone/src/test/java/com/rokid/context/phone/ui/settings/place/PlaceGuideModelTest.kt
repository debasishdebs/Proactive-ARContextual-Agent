package com.rokid.context.phone.ui.settings.place

import com.rokid.context.memory.place.GeoBox
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
            fix = { PlaceFix(7.957, 80.759, 1_000, 10f) }, zoneBlocked = { zone },
            nearby = { _, _ -> listOf(PlaceEntity("wd:Q1", null, "Sigiriya", PlaceKinds.ARCHAEOLOGICAL_SITE, 7.957, 80.759, 250, "s", "wikipedia:en", "x", 1, false, false, 0, 10)) },
            load = { stored }, save = { stored = it; true }, elapsed = { 5_000L },
        )
        override suspend fun seed(box: GeoBox): Result<String> { seeded += box; return if (fail) Result.failure(RuntimeException("x")) else Result.success("Seeded 12 cells, 80 places in the pack.") }
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
