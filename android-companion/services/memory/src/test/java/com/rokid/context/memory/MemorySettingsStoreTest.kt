package com.rokid.context.memory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MemorySettingsStoreTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("memory_settings_test", Context.MODE_PRIVATE)

    @Test fun defaultsAreTheSpecsAndChoicesPersistClamped() {
        val store = MemorySettingsStore(prefs)
        assertEquals(MemorySettings(24, 7, 30, 250), store.settings.value)
        assertEquals(250L * 1024 * 1024, store.settings.value.quotaBytes)
        store.update { it.copy(recallHours = 48, mediumDays = 14, longDays = 90, quotaMb = 5_000) }
        assertEquals(MemorySettings(48, 14, 90, 2_000), MemorySettingsStore(prefs).settings.value)
        store.update { it.copy(recallHours = 3) }
        assertEquals(24, store.settings.value.recallHours) // not one of the offered windows → default
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

    private val day7 = 7 * 24 * 3_600_000L

    /** I3: the stamp is renewed by anything that could bring the bulk delete back; only ever later, never earlier. */
    @Test fun stampIsRenewedWhenStaleOrClockMovesBack() {
        fun store(n: String) = MemorySettingsStore(ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("stamp_$n", Context.MODE_PRIVATE))
        val day = 24 * 3_600_000L
        // normal daily run: the stamp stays, last run advances
        store("a").apply { stampPerKindSince(1_000 * day) ; assertEquals(1_000 * day, settings.value.perKindSince)
            assertEquals(1_000 * day + day, stampPerKindSince(1_000 * day + day).perKindLastRun); assertEquals(1_000 * day, settings.value.perKindSince) }
        // restored / build-switch stamp, 30-day gap
        store("b").apply { update { it.copy(perKindSince = 100 * day, perKindLastRun = 100 * day) }; assertEquals(130 * day, stampPerKindSince(130 * day).perKindSince) }
        // exactly 7 days is still fine, more is not
        store("c").apply { update { it.copy(perKindSince = 100 * day, perKindLastRun = 100 * day) }; assertEquals(100 * day, stampPerKindSince(107 * day).perKindSince) }
        store("c2").apply { update { it.copy(perKindSince = 100 * day, perKindLastRun = 100 * day) }; assertEquals(108 * day, stampPerKindSince(108 * day).perKindSince) }
        // clock behind the stamp / the last run: the stamp never moves earlier
        store("d").apply { update { it.copy(perKindSince = 100 * day, perKindLastRun = 100 * day) }; assertEquals(100 * day, stampPerKindSince(50 * day).perKindSince) }
        // a stamp with no run record (an older build's, or restored) is not trusted
        store("e").apply { update { it.copy(perKindSince = 100 * day) }; assertEquals(101 * day, stampPerKindSince(101 * day).perKindSince) }
    }

    /**
     * Task 9 carry: `memory_settings` is in cloud backup and in the settings export (export only: there is no import). A restored stamp
     * can only be older (an old backup), newer (a phone with a fast clock) or recent; none of them shortens the protection of photos.
     */
    @Test fun restoredStampNeverShortensProtection() {
        fun store(n: String) = MemorySettingsStore(ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("restore_$n", Context.MODE_PRIVATE))
        val day = 24 * 3_600_000L
        // an old backup (stamp and run 40 days ago): renewed to now, so the photos of this install get a fresh 7 days
        store("old").apply { update { it.copy(perKindSince = 60 * day, perKindLastRun = 60 * day) }; assertEquals(100 * day, stampPerKindSince(100 * day).perKindSince) }
        // a stamp from the future (the source phone's clock ran ahead): kept, deletion only comes later
        store("future").apply { update { it.copy(perKindSince = 500 * day, perKindLastRun = 500 * day) }; assertEquals(500 * day, stampPerKindSince(100 * day).perKindSince) }
        // a restore that carries a stamp but no run record, or a zero stamp: never earlier than now
        store("norun").apply { update { it.copy(perKindSince = 90 * day, perKindLastRun = 0L) }; assertEquals(100 * day, stampPerKindSince(100 * day).perKindSince) }
        store("zero").apply { update { it.copy(perKindSince = 0L, perKindLastRun = 99 * day) }; assertEquals(100 * day, stampPerKindSince(100 * day).perKindSince) }
        // a run record AHEAD of now with an older stamp: the stamp is renewed to now, never to an earlier value
        store("ahead").apply { update { it.copy(perKindSince = 50 * day, perKindLastRun = 200 * day) }; assertEquals(100 * day, stampPerKindSince(100 * day).perKindSince) }
    }

    /** N2: whatever the clock does, the stamp is monotonic: a re-stamp can only delay deletion. */
    @Test fun stampIsMonotonicForAnyClockSequence() {
        val day = 24 * 3_600_000L
        val rnd = java.util.Random(42)
        repeat(50) { n ->
            val st = MemorySettingsStore(ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("stamp_prop_$n", Context.MODE_PRIVATE))
            var last = 0L
            repeat(40) {
                val now = (1 + rnd.nextInt(400)) * day + rnd.nextInt(1000)
                val s = st.stampPerKindSince(now)
                assertTrue("stamp ${s.perKindSince} < previous $last", s.perKindSince >= last)
                assertTrue(s.perKindSince > 0)
                last = s.perKindSince
            }
        }
    }

    /** Fix round 2 N4: an edit that starts while another is mid-update is applied after it, not lost. */
    @Test fun twoQuickEditsBothPersistInOrder() {
        val p = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("memory_settings_race", Context.MODE_PRIVATE)
        val store = MemorySettingsStore(p)
        val second = Thread { store.update { it.copy(mediumDays = 14, quotaMb = 300) } }
        store.update { s ->
            second.start(); second.join(300) // without the lock the second edit runs (and is overwritten) right here
            s.copy(recallHours = 48, quotaMb = 200)
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
        assertEquals(250, store.settings.value.quotaMb)
        store.update { it.copy(quotaMb = 200) }
        assertEquals(200, store.settings.value.quotaMb)
    }

    /** R16: the soft limit defaults to 250 MB (the data class and the prefs read agree). */
    @Test fun defaultQuotaIs250() {
        assertEquals(250, MemorySettings().quotaMb)
        val store = MemorySettingsStore(ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("memory_settings_default_quota", Context.MODE_PRIVATE))
        assertEquals(250, store.settings.value.quotaMb)
    }

    /** A limit the user set (even the old default of 100) is kept; only an absent key reads 250. */
    @Test fun storedQuotaKept() {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("memory_settings_stored_quota", Context.MODE_PRIVATE)
        prefs.edit().putInt("quota_mb", 100).commit()
        assertEquals(100, MemorySettingsStore(prefs).settings.value.quotaMb)
        prefs.edit().putInt("quota_mb", 500).commit()
        assertEquals(500, MemorySettingsStore(prefs).settings.value.quotaMb)
    }

    /** R16 on existing installs: retention's stamp (every run) must not materialise the default 250 as a stored value. */
    @Test fun stampPerKindSinceDoesNotStoreAQuota() {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("memory_settings_stamp_no_quota", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val store = MemorySettingsStore(prefs)
        store.stampPerKindSince(1_000_000L); store.stampPerKindSince(2_000_000L)
        assertEquals(false, prefs.contains("quota_mb"))
        assertEquals(250, MemorySettingsStore(prefs).settings.value.quotaMb)
        store.update { it.copy(recallHours = 48) } // editing another setting does not store it either
        assertEquals(false, prefs.contains("quota_mb")); assertEquals(250, MemorySettingsStore(prefs).settings.value.quotaMb)
    }

    @Test fun storedQuotaSurvivesTheStampAndAUserChangeIsStored() {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("memory_settings_stamp_stored", Context.MODE_PRIVATE)
        prefs.edit().clear().putInt("quota_mb", 100).commit()
        val store = MemorySettingsStore(prefs)
        store.stampPerKindSince(1_000_000L)
        assertEquals(100, MemorySettingsStore(prefs).settings.value.quotaMb)
        val fresh = MemorySettingsStore(ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("memory_settings_user_choice", Context.MODE_PRIVATE).also { it.edit().clear().commit() })
        fresh.update { it.copy(quotaMb = 100) } // the user picks 100 on a 250 default: that choice is stored
        assertEquals(100, MemorySettingsStore(ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("memory_settings_user_choice", Context.MODE_PRIVATE)).settings.value.quotaMb)
    }

    /** Retention v3 (K2, SettingsMigrationTest): an upgrade keeps the user's 48 h as Quick recall, a new choice goes to the NEW key and never to `short_hours` (the candidate's clean() would turn 72 / 168 into 24). */
    @Test fun settingsMigrationKeepsTheUsersWindowAndNeverWritesShortHoursAgain() {
        val p = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("memory_settings_v3_migration", Context.MODE_PRIVATE)
        p.edit().clear().putInt("short_hours", 48).putInt("medium_days", 14).putInt("long_days", 365).putInt("photo_days", MemorySettings.FOREVER).putInt("quota_mb", 2_000).commit()
        val store = MemorySettingsStore(p)
        assertEquals("the user's 48 h survives as Quick recall", 48, store.settings.value.recallHours)
        assertEquals("the old per-tier values are read and kept (a downgrade reads sane values)", listOf(14, 365), listOf(store.settings.value.mediumDays, store.settings.value.longDays))
        assertEquals(2_000, store.settings.value.quotaMb)
        store.update { it.copy(recallHours = 168) }
        assertEquals(168, MemorySettingsStore(p).settings.value.recallHours)
        assertEquals("short_hours keeps its last legal value", 48, p.getInt("short_hours", -1))
        assertEquals(168, p.getInt("recall_hours", -1))
        assertEquals(listOf(12, 24, 48, 72, 168), MemorySettingsStore.RECALL_HOURS)
        p.edit().putInt("recall_hours", 5).commit()
        assertEquals("an illegal stored value falls back to the default", 24, MemorySettingsStore(p).settings.value.recallHours)
    }
}
