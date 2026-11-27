package com.rokid.context.work.frames.place

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.retrieval.KindThresholds
import com.rokid.context.memory.retrieval.PhotoMatchConfig
import com.rokid.context.orchestration.forget.DeveloperFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The layered-confirmation settings: user switches, check mode, the nine numbers (clamped, resettable) and the Developer layer switches (all ON, L1 is the kill switch). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlaceLayerSettingsTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private fun fresh(): PlaceGuideSettings { ctx.getSharedPreferences(PlaceGuideSettings.PREFS, Context.MODE_PRIVATE).edit().clear().commit(); return PlaceGuideSettings.of(ctx) }

    @Test fun defaultsAreConfirmedOnlyOnPinCardsOffAutomaticExampleNumbers() {
        val s = fresh()
        assertTrue(s.confirmedOnly); assertFalse(s.pinCardsNearby); assertEquals(CheckMode.AUTOMATIC, s.checkMode); assertFalse(s.bestOfK); assertFalse(s.showAllAttempts); assertTrue(s.offlineGuess)
        assertEquals(PhotoMatchConfig.DEFAULT, s.photoMatch)
        val p = s.policy(l1 = true, l2 = true)
        assertTrue(p.confirmedOnly); assertTrue(p.holdPins); assertEquals(CheckMode.AUTOMATIC, p.checkMode)
        assertFalse(PlacePolicy().confirmedOnly) // the pure default is the older behaviour
    }

    @Test fun valuesAreStoredAndAnUnknownModeReadsAsAutomatic() {
        val s = fresh()
        s.confirmedOnly = false; s.pinCardsNearby = true; s.checkMode = CheckMode.PHONE_ONLY; s.bestOfK = true
        val again = PlaceGuideSettings.of(ctx)
        assertFalse(again.confirmedOnly); assertTrue(again.pinCardsNearby); assertEquals(CheckMode.PHONE_ONLY, again.checkMode); assertTrue(again.bestOfK)
        ctx.getSharedPreferences(PlaceGuideSettings.PREFS, Context.MODE_PRIVATE).edit().putString("checkMode", "bogus").commit()
        assertEquals(CheckMode.AUTOMATIC, PlaceGuideSettings.of(ctx).checkMode)
        assertEquals(CheckMode.AUTOMATIC, CheckMode.parse(null))
        assertFalse(PlacePolicy(confirmedOnly = true, pinCardsNearby = true).holdPins)
    }

    @Test fun noPhotoByGpsSwitchDefaultsOffStoresAndFeedsThePolicy() {
        val s = fresh()
        assertFalse(s.noPhotoByGps); assertFalse(s.policy(true, true).noPhotoByGps)
        s.noPhotoByGps = true
        assertTrue(PlaceGuideSettings.of(ctx).noPhotoByGps); assertTrue(PlaceGuideSettings.of(ctx).policy(true, true).noPhotoByGps)
        s.noPhotoByGps = false; assertFalse(PlaceGuideSettings.of(ctx).policy(true, true).noPhotoByGps)
        assertFalse(PlacePolicy().noPhotoByGps)
    }

    @Test fun theNineNumbersAreClampedAndReset() {
        val s = fresh()
        s.photoMatch = PhotoMatchConfig(KindThresholds(10, 5, 9999), KindThresholds(970, 880, 25), KindThresholds(940, 0, 15))
        val c = PlaceGuideSettings.of(ctx).photoMatch
        assertEquals(KindThresholds(500, 500, 200), c.own); assertEquals(KindThresholds(970, 880, 25), c.ownCentroid); assertEquals(KindThresholds(940, 0, 15), c.web)
        s.resetPhotoMatch()
        assertEquals(PhotoMatchConfig.DEFAULT, PlaceGuideSettings.of(ctx).photoMatch)
        ctx.getSharedPreferences(PlaceGuideSettings.PREFS, Context.MODE_PRIVATE).edit().putInt("pm.own.high", 99999).putInt("pm.web.floor", 700).commit()
        assertEquals(999, PlaceGuideSettings.of(ctx).photoMatch.own.high); assertEquals(700, PlaceGuideSettings.of(ctx).photoMatch.web.floor)
    }

    @Test fun theLayerSwitchesAreOnByDefaultAndSwitchable() {
        ctx.getSharedPreferences(DeveloperFlags.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        assertTrue(DeveloperFlags.placeL1Photo(ctx)); assertTrue(DeveloperFlags.placeL2Vision(ctx)); assertTrue(DeveloperFlags.placeMultiFrame(ctx)); assertTrue(DeveloperFlags.offlineTier(ctx)); assertTrue(DeveloperFlags.offlineCaption(ctx))
        DeveloperFlags.setPlaceL1Photo(ctx, false); assertFalse(DeveloperFlags.placeL1Photo(ctx))
        DeveloperFlags.setPlaceL2Vision(ctx, false); assertFalse(DeveloperFlags.placeL2Vision(ctx))
        DeveloperFlags.setPlaceMultiFrame(ctx, false); assertFalse(DeveloperFlags.placeMultiFrame(ctx))
        val p = PlaceGuideSettings.of(ctx).policy(DeveloperFlags.placeL1Photo(ctx), DeveloperFlags.placeL2Vision(ctx))
        assertFalse(p.l1); assertFalse(p.l2)
    }

    @Test fun heldTokensAreAKnownSetAndOnlyHeldOnesAreHeld() {
        val d = com.rokid.context.memory.place.CardDelivery
        assertEquals(7, d.HELD.size); assertTrue(d.HELD.all { it.startsWith("HELD_") && it.length <= 30 })
        assertTrue(d.isHeld(d.HELD_DATA_SAVER)); assertFalse(d.isHeld(d.PENDING)); assertFalse(d.isHeld(null)); assertFalse(d.isHeld("HELD_BOGUS"))
        assertTrue(HistoryKind.entries.map { it.name }.containsAll(listOf("IMAGE", "NO_MATCH")))
    }
}
