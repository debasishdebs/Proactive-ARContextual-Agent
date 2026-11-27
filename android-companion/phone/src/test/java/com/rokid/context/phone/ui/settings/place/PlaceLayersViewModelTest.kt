package com.rokid.context.phone.ui.settings.place

import com.rokid.context.memory.place.CardDelivery
import com.rokid.context.memory.place.GeoBox
import com.rokid.context.memory.retrieval.KindThresholds
import com.rokid.context.memory.retrieval.PhotoMatchConfig
import com.rokid.context.work.frames.place.CheckMode
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

/** Layered confirmation settings on Settings > Place Guide: defaults, write-through, the nine numbers (clamped by the store) and the plain wording of every held reason. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaceLayersViewModelTest {
    private val d = StandardTestDispatcher()
    @Before fun before() { Dispatchers.setMain(d) }
    @After fun after() { Dispatchers.resetMain() }

    private class Ctl : PlaceGuideControl {
        override var enabled = false
        override suspend fun seed(box: GeoBox) = Result.success(SeedOutcome("ok"))
        override suspend fun summary() = ""
        override var confirmedOnly = true
        override var pinCardsNearby = false
        override var checkMode = CheckMode.AUTOMATIC
        override var photoMatch = PhotoMatchConfig.DEFAULT
        override fun resetPhotoMatch() { photoMatch = PhotoMatchConfig.DEFAULT }
        override var layerPhoto = true
        override var layerOnline = true
        override var layerOffline = true
        override var multiFrame = true
        override var bestOfK = false
        override var showAllAttempts = false
    }

    @Test fun defaultsAreConfirmedOnlyAutomaticAndEveryLayerOn() {
        val s = PlaceGuideViewModel(Ctl(), d).state.value
        assertTrue(s.confirmedOnly); assertFalse(s.pinCardsNearby); assertEquals(CheckMode.AUTOMATIC, s.checkMode); assertEquals(PhotoMatchConfig.DEFAULT, s.photoMatch)
        assertTrue(s.layerPhoto && s.layerOnline && s.layerOffline && s.multiFrame); assertFalse(s.bestOfK); assertFalse(s.showAllAttempts)
    }

    @Test fun everySettingWritesThrough() {
        val c = Ctl(); val vm = PlaceGuideViewModel(c, d, developerMode = true)
        vm.setConfirmedOnly(false); vm.setPinCardsNearby(true); vm.setCheckMode(CheckMode.ONLINE_ONLY)
        assertFalse(c.confirmedOnly); assertTrue(c.pinCardsNearby); assertEquals(CheckMode.ONLINE_ONLY, c.checkMode); assertEquals(CheckMode.ONLINE_ONLY, vm.state.value.checkMode)
        for (w in listOf("photo", "online", "offline", "multi")) vm.setLayer(w, false)
        assertFalse(c.layerPhoto || c.layerOnline || c.layerOffline || c.multiFrame); assertFalse(vm.state.value.layerPhoto)
        vm.setLayer("bestk", true); assertTrue(c.bestOfK)
    }

    @Test fun theNineNumbersAndTheReset() {
        val c = Ctl(); val vm = PlaceGuideViewModel(c, d, developerMode = true)
        val custom = PhotoMatchConfig(KindThresholds(950, 850, 25), KindThresholds(970, 880, 25), KindThresholds(940, 0, 15))
        vm.setPhotoMatch(custom); assertEquals(custom, c.photoMatch); assertEquals(custom, vm.state.value.photoMatch)
        vm.resetPhotoMatch(); assertEquals(PhotoMatchConfig.DEFAULT, vm.state.value.photoMatch)
    }

    @Test fun everyHeldReasonHasPlainWordsAndTheNewKindsHaveLabels() {
        for (t in CardDelivery.HELD) { val w = PlaceHistoryModel.deliveryLabel(t); assertTrue("$t: $w", w.startsWith("Not sent to glasses: ") && w != "unknown") }
        assertEquals("photo match · on the phone", PlaceHistoryModel.kindLabel("IMAGE")); assertEquals("no place found", PlaceHistoryModel.kindLabel("NO_MATCH"))
        assertTrue(PlaceHistoryModel.showAgainBlock("NO_MATCH", null, false, ShowAgainEnv(true, false, true)) != null)
    }

    @Test fun theWordingIsPlain() {
        assertEquals("Cards on glasses: only when the photo matches", PlaceGuideModel.CONFIRMED_TITLE); assertEquals(listOf("Automatic", "Online only", "On the phone only"), PlaceGuideModel.CHECK_LABELS)
        assertTrue(PlaceGuideModel.THRESH_NOTE.contains("not calibrated"))
    }
}
