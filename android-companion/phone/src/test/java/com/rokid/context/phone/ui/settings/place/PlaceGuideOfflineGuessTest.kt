package com.rokid.context.phone.ui.settings.place

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V2b: the Settings fields of the offline guess: defaults, ranges, and the slider grids that hold them. */
class PlaceGuideOfflineGuessTest {
    @Test fun theScreenStartsOffWithTheOwnersDefaults() {
        val s = PlaceGuideUiState()
        assertFalse(s.offlineGuess); assertEquals(0.55f, s.maybeCutoff); assertEquals(150, s.candidateRadiusM)
    }

    /** 27 Nov night: every "Extra place type" is ON by default except battles, valleys, cities and airports, each is its own switch, and the mountains label keeps the owner's plain wording. */
    @Test fun extraPlaceTypesDefaultOnWithPlainWording() {
        assertEquals(com.rokid.context.connectors.place.ExtraPlaceTypes.DEFAULT_ON, PlaceGuideUiState().extraTypes)
        val e = com.rokid.context.connectors.place.ExtraPlaceTypes
        assertTrue(PlaceGuideUiState().extraTypes.containsAll(listOf(e.MOUNTAINS, e.RIVERS, e.GLACIERS, e.HILLS, e.TEA_GARDENS, e.RAILWAY, e.BUS_STOPS, e.POWER, e.STADIUMS, e.VENUES)))
        for (k in listOf(e.BATTLES, e.VALLEYS, e.CITIES, e.AIRPORTS)) { assertFalse(k, k in PlaceGuideUiState().extraTypes); assertTrue(k, e.SHOWN.any { it.key == k }) } // the four new switches are offered, default OFF
        assertTrue("adds place names like city and town for context, not sights" in e.fullLabel(e.byKey(e.CITIES)!!))
        assertEquals("Include mountains and peaks (adds many places; good for a mountain trip)", PlaceGuideModel.MOUNTAINS_LABEL)
    }

    @Test fun cutoffRangeIs030To090AndNaNReadsAsTheDefault() {
        assertEquals(0.30f, PlaceGuideModel.clampCutoff(0.0f)); assertEquals(0.90f, PlaceGuideModel.clampCutoff(2f)); assertEquals(0.55f, PlaceGuideModel.clampCutoff(Float.NaN)); assertEquals(0.62f, PlaceGuideModel.clampCutoff(0.62f))
    }

    @Test fun radiusRangeIs50To500() {
        assertEquals(50, PlaceGuideModel.clampRadius(0)); assertEquals(500, PlaceGuideModel.clampRadius(5_000)); assertEquals(150, PlaceGuideModel.clampRadius(150))
    }

    @Test fun theSlidersSnapToGridsThatContainTheDefaultsAndTheEnds() {
        assertEquals(0.55f, PlaceGuideModel.snapCutoff(0.5512f), 1e-6f); assertEquals(0.30f, PlaceGuideModel.snapCutoff(0.31f), 1e-6f); assertEquals(0.90f, PlaceGuideModel.snapCutoff(0.89f), 1e-6f)
        assertEquals(150, PlaceGuideModel.snapRadius(149f)); assertEquals(50, PlaceGuideModel.snapRadius(60f)); assertEquals(500, PlaceGuideModel.snapRadius(490f))
        assertEquals(11, PlaceGuideModel.CUTOFF_STEPS); assertEquals(8, PlaceGuideModel.RADIUS_STEPS) // (0.90-0.30)/0.05 - 1 and (500-50)/50 - 1 in-between stops
    }

    @Test fun theNoteSaysItIsOnlyAGuess() = assertTrue(PlaceGuideModel.OFFLINE_NOTE.contains("only a guess") && PlaceGuideModel.OFFLINE_NOTE.contains("Maybe"))
}
