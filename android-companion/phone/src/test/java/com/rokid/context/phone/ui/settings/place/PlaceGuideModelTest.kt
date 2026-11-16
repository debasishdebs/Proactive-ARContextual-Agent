package com.rokid.context.phone.ui.settings.place

import com.rokid.context.memory.place.GeoBox
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
}
