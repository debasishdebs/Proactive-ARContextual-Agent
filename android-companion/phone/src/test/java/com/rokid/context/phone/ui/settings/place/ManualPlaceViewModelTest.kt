package com.rokid.context.phone.ui.settings.place

import com.rokid.context.core.model.zone.PauseZone
import com.rokid.context.memory.place.PackStats
import com.rokid.context.memory.place.PlaceKinds
import com.rokid.context.memory.place.UserPlace
import com.rokid.context.work.frames.place.ManualPlace
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The Developer "Add place manually" form in the Place Guide view model: gating, creation through the pin path, the zone refusal, the source label and the delete. */
@OptIn(ExperimentalCoroutinesApi::class)
class ManualPlaceViewModelTest {
    private val d = StandardTestDispatcher()
    @Before fun before() { Dispatchers.setMain(d) }
    @After fun after() { Dispatchers.resetMain() }

    private class Control : PlaceGuideControl {
        override var enabled = false
        var stored = listOf<UserPlace>()
        var zones: List<PauseZone>? = emptyList()
        var ids = 0
        override suspend fun seed(box: com.rokid.context.memory.place.GeoBox) = Result.success(SeedOutcome("x"))
        override suspend fun summary() = ""
        override suspend fun stats() = PackStats(pinned = stored.size, seeded = 3, fetched = 0, bytes = 0, budgetBytes = 1)
        override val pinning = PlacePinning(
            fix = { PlaceFix(1.0, 2.0, 1_000, 10f) }, zoneBlocked = { false }, zones = { zones }, nearby = { _, _ -> emptyList() },
            load = { stored }, save = { stored = it; true }, elapsed = { 5_000L }, newId = { "user:00000000-0000-0000-0000-%012d".format(++ids) },
        )
    }

    private fun fill(vm: PlaceGuideViewModel, name: String = "My Office", coords: String = "12.9716, 77.5946", radius: String = "") =
        vm.updateManual { it.copy(name = name, note = "Ground floor.", kindIndex = ManualPlace.KINDS.indexOf(PlaceKinds.ATTRACTION), coordinates = coords, radius = radius) }

    @Test fun theFormIsDeveloperOnly() = runTest(d) {
        val vm = PlaceGuideViewModel(Control(), d, developerMode = false)
        vm.openManual(); assertNull(vm.state.value.manualForm)
        vm.saveManual(); d.scheduler.advanceUntilIdle(); assertNull(vm.state.value.manualForm)
        val dev = PlaceGuideViewModel(Control(), d, developerMode = true)
        dev.openManual(); assertNotNull(dev.state.value.manualForm)
    }

    @Test fun savingMakesARealPinListedWithSourceManualAndDeletableLikeAnyPin() = runTest(d) {
        val c = Control(); val vm = PlaceGuideViewModel(c, d, developerMode = true)
        vm.openManual(); fill(vm); vm.saveManual(); d.scheduler.advanceUntilIdle()
        assertNull(vm.state.value.manualForm); assertEquals("Place added.", vm.state.value.pinMessage)
        val pin = vm.state.value.pins.single(); assertEquals(c.stored.single(), pin)
        assertEquals("My Office", pin.name); assertEquals(60, pin.radiusM); assertNull(pin.momentId); assertEquals("manual", pin.toEntity().source)
        assertEquals("Source: manual", PlaceGuideModel.sourceLine())
        assertEquals("1 manual place · 3 seeded places", PlaceGuideModel.countsHeader(vm.state.value.pins.size, vm.state.value.stats!!.seeded))
        assertEquals("0 manual places · 1 seeded place", PlaceGuideModel.countsHeader(0, 1))
        vm.deletePin(pin.id); d.scheduler.advanceUntilIdle(); assertNotNull(vm.state.value.pinDeleteConfirm)
        vm.confirmDeletePin(); d.scheduler.advanceUntilIdle()
        assertTrue(c.stored.isEmpty()); assertTrue(vm.state.value.pins.isEmpty())
    }

    @Test fun aBadFormStaysOpenWithAClearErrorAndStoresNothing() = runTest(d) {
        val c = Control(); val vm = PlaceGuideViewModel(c, d, developerMode = true)
        vm.openManual(); fill(vm, coords = "95, 10"); vm.saveManual(); d.scheduler.advanceUntilIdle()
        assertEquals(ManualPlace.faultText(com.rokid.context.work.frames.place.ManualFault.COORDINATES), vm.state.value.manualForm!!.error); assertTrue(c.stored.isEmpty())
        fill(vm, name = " "); vm.saveManual(); d.scheduler.advanceUntilIdle(); assertNotNull(vm.state.value.manualForm!!.error); assertTrue(c.stored.isEmpty())
        fill(vm, radius = "5000"); vm.saveManual(); d.scheduler.advanceUntilIdle(); assertNotNull(vm.state.value.manualForm!!.error); assertTrue(c.stored.isEmpty())
        vm.cancelManual(); assertNull(vm.state.value.manualForm)
    }

    @Test fun insideAPauseZoneIsRefusedWithAClearMessage() = runTest(d) {
        val c = Control().also { it.zones = listOf(PauseZone("z", "Z", 12.97, 77.59, 12.98, 77.60, 100)) }
        val vm = PlaceGuideViewModel(c, d, developerMode = true)
        vm.openManual(); fill(vm); vm.saveManual(); d.scheduler.advanceUntilIdle()
        assertEquals(PlaceGuideModel.MANUAL_ZONE, vm.state.value.manualForm!!.error); assertTrue(c.stored.isEmpty())
    }
}
