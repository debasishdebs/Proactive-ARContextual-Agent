package com.rokid.context.phone.ui.settings.capture

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** v0.9.7 B item 2: the stored phone fusion thresholds: validated on write and on load, only non-default keys, reset clears. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class FusionSettingsStoreTest {
    private fun prefs(name: String) = ApplicationProvider.getApplicationContext<Application>().getSharedPreferences(name, 0).also { it.edit().clear().commit() }

    @Test fun aWriteIsClampedAndADefaultIsNoOverride() {
        val s = FusionSettingsStore(prefs("fs1"))
        s.set("fastKmh", 99.0); s.set("enterVehicleMs", 30_000.0); s.set("bogus", 1.0)
        assertEquals(mapOf("fastKmh" to 40.0), s.overrides.value)
    }

    @Test fun valuesSurviveAReloadAndABadStoredValueIsCleanedAtLoad() {
        val p = prefs("fs2"); FusionSettingsStore(p).set("fastTtlMs", 150_000.0)
        assertEquals(mapOf("fastTtlMs" to 150_000.0), FusionSettingsStore(p).overrides.value)
        p.edit().putString("values", """{"fastKmh":1000,"stillFrac":"x","nope":3}""").commit()
        assertEquals(mapOf("fastKmh" to 40.0), FusionSettingsStore(p).overrides.value)
        p.edit().putString("values", "not json").commit()
        assertTrue(FusionSettingsStore(p).overrides.value.isEmpty())
    }

    @Test fun resetGoesBackToDefaults() {
        val p = prefs("fs3"); val s = FusionSettingsStore(p)
        s.set("fastKmh", 30.0); s.reset()
        assertTrue(s.overrides.value.isEmpty()); assertTrue(FusionSettingsStore(p).overrides.value.isEmpty())
    }
}
