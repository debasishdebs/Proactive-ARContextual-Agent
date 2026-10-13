package com.rokid.context.memory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MemorySettingsStoreTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("memory_settings_test", Context.MODE_PRIVATE)

    @Test fun defaultsAreTheSpecsAndChoicesPersistClamped() {
        val store = MemorySettingsStore(prefs)
        assertEquals(MemorySettings(24, 7, 30, 100), store.settings.value)
        assertEquals(100L * 1024 * 1024, store.settings.value.quotaBytes)
        store.update { it.copy(shortHours = 48, mediumDays = 14, longDays = 90, quotaMb = 5_000) }
        assertEquals(MemorySettings(48, 14, 90, 2_000), MemorySettingsStore(prefs).settings.value)
        store.update { it.copy(shortHours = 3) }
        assertEquals(24, store.settings.value.shortHours) // not one of the offered windows → default
    }

    /** Fix round 2 N4: an edit that starts while another is mid-update is applied after it, not lost. */
    @Test fun twoQuickEditsBothPersistInOrder() {
        val p = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("memory_settings_race", Context.MODE_PRIVATE)
        val store = MemorySettingsStore(p)
        val second = Thread { store.update { it.copy(mediumDays = 14, quotaMb = 300) } }
        store.update { s ->
            second.start(); second.join(300) // without the lock the second edit runs (and is overwritten) right here
            s.copy(shortHours = 48, quotaMb = 200)
        }
        second.join()
        assertEquals(MemorySettings(48, 14, 30, 300), store.settings.value) // second edit last: its quota wins
        assertEquals(MemorySettings(48, 14, 30, 300), MemorySettingsStore(p).settings.value)
    }
}
