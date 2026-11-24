package com.rokid.context.phone.ui.settings.privacy

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.cancelScope
import com.rokid.context.phone.ui.settings.FakeHudSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** v18 WP4: the "Save location with Moments" switch, the count line and "Delete saved locations". */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class MomentLocationSettingTest {
    @get:Rule val main = MainDispatcherRule()
    private class Fake(var enabled: Boolean = true, var rows: Int = 3) : MomentLocationControl {
        var deletes = 0
        override fun isOn() = enabled
        override fun setOn(on: Boolean) { enabled = on }
        override suspend fun count() = rows
        override suspend fun deleteAll(): Int { deletes++; val n = rows; rows = 0; return n }
    }
    private val vms = mutableListOf<PrivacyViewModel>()
    @After fun tearDown() = vms.forEach { it.cancelScope() }
    private fun vm(c: MomentLocationControl?) = PrivacyViewModel(RecordingControl(), FakeHudSettings(), work = main.dispatcher, momentLoc = c).also { vms += it }

    @Test fun cardIsHiddenWhereTheSettingIsNotWired() { assertNull(vm(null).locationState.value) }

    @Test fun switchOffStopsSavingAndOffersTheDeleteWhenRowsExist() {
        val c = Fake(); val v = vm(c)
        v.setMomentLocation(false)
        assertFalse(c.enabled)
        assertTrue(v.loc().offerDelete); assertEquals(0, c.deletes)
        v.keepSavedLocations(); assertFalse(v.loc().offerDelete); assertEquals(0, c.deletes); assertEquals(3, c.rows)
    }

    @Test fun deleteSavedLocationsDeletesOnlyOnTheExplicitChoice() {
        val c = Fake(); val v = vm(c)
        v.setMomentLocation(false); v.deleteSavedLocations()
        assertEquals(1, c.deletes); assertEquals(0, v.loc().count); assertFalse(v.loc().offerDelete)
    }

    @Test fun noOfferWhenNothingIsSaved() {
        val c = Fake(rows = 0); val v = vm(c)
        v.setMomentLocation(false); assertFalse(v.loc().offerDelete)
    }

    @Test fun countLineWords() {
        assertEquals("1 Moment has a saved location", MomentLocationUi(true, 1).countLine)
        assertEquals("0 Moments have a saved location", MomentLocationUi(true, 0).countLine)
        assertEquals("12 Moments have a saved location", MomentLocationUi(true, 12).countLine)
    }

    private fun PrivacyViewModel.loc() = locationState.value!!
}
