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

    /** Task 8 (spec P6.1): per-kind defaults, the offered choices, FOREVER = -1, and the first-run stamp. */
    @Test fun perKindDefaultsChoicesAndStamp() {
        val p = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("memory_settings_kinds", Context.MODE_PRIVATE)
        val store = MemorySettingsStore(p)
        val d = store.settings.value
        assertEquals(listOf(7, 1, -1, -1, -1), listOf(d.photoDays, d.skippedPhotoDays, d.captionDays, d.clusterSummaryDays, d.imageVectorDays))
        assertEquals(0L, d.perKindSince)
        assertEquals(-1, MemorySettings.FOREVER)
        assertEquals(listOf(1, 3, 7, 30, 90, -1), MemorySettingsStore.PHOTO_DAYS)
        assertEquals(listOf(0, 1, 7), MemorySettingsStore.SKIPPED_DAYS)
        assertEquals(listOf(30, 90, 365, -1), MemorySettingsStore.LONG_KIND_DAYS)
        store.update { it.copy(photoDays = 90, skippedPhotoDays = 7, captionDays = 30, clusterSummaryDays = 365, imageVectorDays = 90, perKindSince = 123L) }
        val r = MemorySettingsStore(p).settings.value
        assertEquals(listOf(90, 7, 30, 365, 90), listOf(r.photoDays, r.skippedPhotoDays, r.captionDays, r.clusterSummaryDays, r.imageVectorDays))
        assertEquals(123L, r.perKindSince)
        store.update { it.copy(photoDays = 2, skippedPhotoDays = 2, captionDays = 7, clusterSummaryDays = 0, imageVectorDays = 5, perKindSince = -5L) }
        val c = store.settings.value // not offered → default; a negative stamp is not a stamp
        assertEquals(listOf(7, 1, -1, -1, -1), listOf(c.photoDays, c.skippedPhotoDays, c.captionDays, c.clusterSummaryDays, c.imageVectorDays))
        assertEquals(0L, c.perKindSince)
    }

    /** The stamp is set once, by the first retention run, and only while it is still 0. */
    @Test fun stampOnceStampsOnlyWhenUnset() {
        val store = MemorySettingsStore(ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("memory_settings_stamp", Context.MODE_PRIVATE))
        assertEquals(1_000L, store.stampPerKindSince(1_000L).perKindSince)
        assertEquals(1_000L, store.stampPerKindSince(9_000L).perKindSince)
        assertEquals(1_000L, MemorySettingsStore(ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("memory_settings_stamp", Context.MODE_PRIVATE)).settings.value.perKindSince)
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

    /** Phase 5 T20 carry: the lock is reentrant, so a nested update would be overwritten; it is refused instead. */
    @Test fun anUpdateInsideAnUpdateIsRefused() {
        val store = MemorySettingsStore(ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("memory_settings_nested", Context.MODE_PRIVATE))
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            store.update { s -> store.update { it.copy(quotaMb = 500) }; s.copy(quotaMb = 200) }
        }
        assertEquals(100, store.settings.value.quotaMb)
        store.update { it.copy(quotaMb = 200) }
        assertEquals(200, store.settings.value.quotaMb)
    }
}
