package com.rokid.context.work.frames.place

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.PlaceSide
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** B2: the card side persists in the `place_guide` prefs, defaults to CENTRE and reads an unknown stored value as CENTRE. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlaceCardSideSettingTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = ctx.getSharedPreferences(PlaceGuideSettings.PREFS, Context.MODE_PRIVATE)

    @Before fun setUp() { prefs.edit().clear().commit() }

    @Test fun defaultsToCentre() = assertEquals(PlaceSide.CENTRE, PlaceGuideSettings.of(ctx).cardSide)

    @Test fun eachChoicePersistsAcrossInstances() {
        for (side in PlaceSide.entries) {
            PlaceGuideSettings.of(ctx).cardSide = side
            prefs.edit().commit()
            assertEquals(side, PlaceGuideSettings.of(ctx).cardSide)
        }
    }

    @Test fun anUnknownStoredValueIsCentre() {
        prefs.edit().putString("cardSide", "UP").commit()
        assertEquals(PlaceSide.CENTRE, PlaceGuideSettings.of(ctx).cardSide)
    }
}
