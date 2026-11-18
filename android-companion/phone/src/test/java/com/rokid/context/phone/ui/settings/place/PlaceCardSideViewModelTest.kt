package com.rokid.context.phone.ui.settings.place

import com.rokid.context.core.model.PlaceSide
import com.rokid.context.memory.place.GeoBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** B2: the card position choice of Settings > Place Guide (Developer mode). */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaceCardSideViewModelTest {
    private val d = StandardTestDispatcher()
    @Before fun before() { Dispatchers.setMain(d) }
    @After fun after() { Dispatchers.resetMain() }

    private class Ctl : PlaceGuideControl {
        override var enabled = false
        override var cardSide = PlaceSide.CENTRE
        override suspend fun seed(box: GeoBox) = Result.success(SeedOutcome("ok"))
        override suspend fun summary() = ""
    }

    @Test fun startsAtCentreAndTheChoiceWritesThrough() {
        val c = Ctl(); val vm = PlaceGuideViewModel(c, d, developerMode = true)
        assertEquals(PlaceSide.CENTRE, vm.state.value.cardSide); assertTrue(vm.state.value.developerMode)
        vm.setCardSide(PlaceSide.RIGHT); assertEquals(PlaceSide.RIGHT, c.cardSide); assertEquals(PlaceSide.RIGHT, vm.state.value.cardSide)
        vm.setCardSide(PlaceSide.LEFT); assertEquals(PlaceSide.LEFT, c.cardSide)
    }

    @Test fun aSavedChoiceIsShownOnOpen() = assertEquals(PlaceSide.LEFT, PlaceGuideViewModel(Ctl().apply { cardSide = PlaceSide.LEFT }, d).state.value.cardSide)

    @Test fun theChoiceIsHiddenOutsideDeveloperMode() = assertFalse(PlaceGuideViewModel(Ctl(), d).state.value.developerMode)

    @Test fun theLabelsMatchTheEnumOrder() = assertEquals(PlaceSide.entries.map { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }, PlaceGuideModel.SIDE_LABELS)
}
