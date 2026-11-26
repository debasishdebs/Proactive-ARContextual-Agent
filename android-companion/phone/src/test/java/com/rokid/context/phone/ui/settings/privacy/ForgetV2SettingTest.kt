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

/** Owner 6b: multi-select Forget (flag forgetV2) is ON by default and the Settings > Privacy switch turns it off. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ForgetV2SettingTest {
    @get:Rule val main = MainDispatcherRule()
    private class Fake(var enabled: Boolean = true) : ForgetV2Control { override fun isOn() = enabled; override fun setOn(on: Boolean) { enabled = on } }
    private val vms = mutableListOf<PrivacyViewModel>()
    @After fun tearDown() = vms.forEach { it.cancelScope() }
    private fun vm(c: ForgetV2Control?) = PrivacyViewModel(RecordingControl(), FakeHudSettings(), work = main.dispatcher, forgetV2Control = c).also { vms += it }

    @Test fun theSwitchIsHiddenWhereItIsNotWired() { assertNull(vm(null).state.value.forgetV2) }

    @Test fun theSwitchWritesTheControlAndTheState() {
        val c = Fake(); val v = vm(c)
        v.setForgetV2(false)
        assertFalse(c.enabled)
        v.setForgetV2(true)
        assertTrue(c.enabled)
    }

    @Test fun theProductionControlDefaultsToOnAndTurnsOff() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("pc_dev", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val c = AppForgetV2(app)
        assertTrue(c.isOn())
        c.setOn(false); assertFalse(c.isOn()); assertFalse(com.rokid.context.orchestration.forget.DeveloperFlags.forgetV2(app))
        c.setOn(true); assertEquals(true, c.isOn())
    }
}
