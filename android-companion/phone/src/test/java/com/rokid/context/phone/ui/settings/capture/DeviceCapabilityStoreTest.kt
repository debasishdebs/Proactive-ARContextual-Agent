package com.rokid.context.phone.ui.settings.capture

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.DeviceCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** v0.9.8 slice D: the stored device capabilities: everything on by default, only the switched-off keys are stored, an unknown pair is refused, a bad stored key is dropped at load. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class DeviceCapabilityStoreTest {
    private fun prefs(name: String) = ApplicationProvider.getApplicationContext<Application>().getSharedPreferences(name, 0).also { it.edit().clear().commit() }

    @Test fun everythingIsOnByDefault() {
        assertEquals(DeviceCapabilities.ALL, DeviceCapabilityStore().capabilities.value)
        assertTrue(DeviceCapabilityStore(prefs("dc0")).capabilities.value.list().all { it.enabled })
    }

    @Test fun aSwitchSurvivesAReloadAndSwitchingBackOnClearsIt() {
        val p = prefs("dc1"); val s = DeviceCapabilityStore(p)
        assertTrue(s.set("glasses", "display", false))
        assertFalse(DeviceCapabilityStore(p).capabilities.value.glassesDisplay)
        s.set("glasses", "display", true)
        assertEquals(DeviceCapabilities.ALL, DeviceCapabilityStore(p).capabilities.value)
    }

    @Test fun anUnknownPairIsRefusedAndABadStoredKeyIsDroppedAtLoad() {
        val s = DeviceCapabilityStore(prefs("dc2"))
        assertFalse(s.set("watch", "mic", false)); assertFalse(s.set("phone", "camera", false)); assertEquals(DeviceCapabilities.ALL, s.capabilities.value)
        val p = prefs("dc3"); p.edit().putString("disabled", """["phone.mic","bogus",7,"glasses.nope"]""").commit()
        assertEquals(setOf("phone.mic"), DeviceCapabilityStore(p).capabilities.value.disabled)
        p.edit().putString("disabled", "not json").commit()
        assertEquals(DeviceCapabilities.ALL, DeviceCapabilityStore(p).capabilities.value)
    }
}
