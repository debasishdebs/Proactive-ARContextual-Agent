package com.rokid.context.work.frames.place

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.connectors.place.ExtraPlaceTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 27 Nov: the "Extra place types" switches live in the `place_guide` prefs, are ON by default, persist across instances, and an unknown key stores nothing. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlaceGuideExtraTypesTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    @Before fun setUp() { ctx.getSharedPreferences(PlaceGuideSettings.PREFS, Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun everyTypeIsOnByDefault() {
        val s = PlaceGuideSettings.of(ctx)
        assertEquals(ExtraPlaceTypes.DEFAULT_ON, s.extraTypes)
        for (k in ExtraPlaceTypes.KEYS) assertTrue(k, s.extraType(k))
    }

    @Test fun eachSwitchPersistsOnItsOwn() {
        for (t in ExtraPlaceTypes.SHOWN) {
            PlaceGuideSettings.of(ctx).setExtraType(t.key, false)
            ctx.getSharedPreferences(PlaceGuideSettings.PREFS, Context.MODE_PRIVATE).edit().commit()
            val s = PlaceGuideSettings.of(ctx)
            assertFalse(t.key, s.extraType(t.key)); assertEquals(ExtraPlaceTypes.KEYS - t.key, s.extraTypes)
            s.setExtraType(t.key, true)
            assertTrue(PlaceGuideSettings.of(ctx).extraType(t.key))
        }
    }

    @Test fun anUnknownKeyStoresNothingAndReadsOff() {
        val s = PlaceGuideSettings.of(ctx)
        s.setExtraType("villages", true)
        assertFalse(s.extraType("villages")); assertEquals(ExtraPlaceTypes.DEFAULT_ON, s.extraTypes)
        assertFalse(ctx.getSharedPreferences(PlaceGuideSettings.PREFS, Context.MODE_PRIVATE).all.keys.any { it.contains("villages") })
    }
}
