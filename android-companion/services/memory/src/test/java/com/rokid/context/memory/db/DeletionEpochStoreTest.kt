package com.rokid.context.memory.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DeletionEpochStoreTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    @Before fun clean() { ctx.getSharedPreferences(DeletionEpochStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun installIdStable() {
        val first = DeletionEpochStore.of(ctx).read()
        assertTrue("a random UUID", Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(first.installId))
        assertEquals(0L, first.counter); assertEquals(0L, first.wipedAtMs)
        repeat(3) { assertEquals(first.installId, DeletionEpochStore.of(ctx).read().installId) }
        assertEquals("a wipe never changes the install id", first.installId, DeletionEpochStore.of(ctx).commitWipe(5_000).installId)
        assertEquals(first.installId, DeletionEpochStore.of(ctx).read().installId)
    }

    @Test fun twoInstallsGetDifferentIds() {
        val a = DeletionEpochStore.of(ctx).read().installId
        ctx.getSharedPreferences(DeletionEpochStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit() // a re-install: no prefs
        assertNotEquals(a, DeletionEpochStore.of(ctx).read().installId)
    }

    @Test fun commitWipeCountsUpAndStampsTheTime() {
        val s = DeletionEpochStore.of(ctx)
        assertEquals(1L, s.commitWipe(7_000).counter)
        val e = s.commitWipe(9_000)
        assertEquals(2L, e.counter); assertEquals(9_000L, e.wipedAtMs)
        assertEquals("read back through a fresh store, as after a process death", e, DeletionEpochStore.of(ctx).read())
    }

    @Test fun theCounterIsTheRealFenceAndTheWipeTimeIsJustTheCommitClock() {
        val s = DeletionEpochStore.of(ctx)
        s.commitWipe(9_000_000_000_000) // a clock far in the future at the first wipe
        val e = s.commitWipe(1_000)
        assertEquals("the counter still grows", 2L, e.counter)
        assertEquals("the later real wipe time is not held at the bogus larger value", 1_000L, e.wipedAtMs)
    }

    @Test fun installIdFallbackIsStableWhenTheFirstWriteFails() {
        val real = ctx.getSharedPreferences("fallback-x", Context.MODE_PRIVATE)
        real.edit().clear().commit()
        val broken = object : android.content.SharedPreferences by real {
            override fun edit(): android.content.SharedPreferences.Editor = object : android.content.SharedPreferences.Editor by real.edit() {
                override fun putString(key: String?, value: String?) = this
                override fun commit() = false
            }
        }
        var n = 0
        val s = DeletionEpochStore(broken) { "id-${n++}" } // would be a different id on every call without the fallback
        val first = s.read().installId
        assertEquals(first, s.read().installId)
        assertEquals(first, DeletionEpochStore(broken) { "id-${n++}" }.read().installId)
    }

    @Test fun anUncommittableWriteThrowsSoDeleteAllNeverStartsUnrecorded() {
        val real = ctx.getSharedPreferences("x", Context.MODE_PRIVATE)
        val broken = object : android.content.SharedPreferences by real {
            override fun edit(): android.content.SharedPreferences.Editor = object : android.content.SharedPreferences.Editor by real.edit() {
                override fun putString(key: String?, value: String?) = this
                override fun putLong(key: String?, value: Long) = this
                override fun commit() = false
            }
        }
        val s = DeletionEpochStore(broken) { "id" }
        org.junit.Assert.assertThrows(java.io.IOException::class.java) { s.commitWipe(1) }
    }

    /** Like SharedPreferences: a failed commit() still updated the in-memory map. */
    private fun applyButFail(real: android.content.SharedPreferences) = object : android.content.SharedPreferences by real {
        override fun edit(): android.content.SharedPreferences.Editor { val e = real.edit(); return object : android.content.SharedPreferences.Editor by e {
            override fun putLong(key: String?, value: Long) = apply { e.putLong(key, value) }
            override fun putString(key: String?, value: String?) = apply { e.putString(key, value) }
            override fun remove(key: String?) = apply { e.remove(key) }
            override fun commit(): Boolean { e.apply(); return false }
        } }
    }

    @Test fun aFailedEpochCommitLeavesNoPendingWipeInThisProcess() {
        val real = ctx.getSharedPreferences("epoch-fail", Context.MODE_PRIVATE); real.edit().clear().commit()
        val s = DeletionEpochStore(real) { "id" }
        org.junit.Assert.assertThrows(java.io.IOException::class.java) { DeletionEpochStore(applyButFail(real)) { "id" }.commitWipe(5) }
        assertFalse("resumeIfPending would see no wipe", s.wipePending()); assertEquals(0L, s.read().counter); assertEquals(0L, s.read().wipedAtMs)
    }

    @Test fun aFailedEpochCommitKeepsAnEarlierPendingWipePending() {
        val real = ctx.getSharedPreferences("epoch-fail2", Context.MODE_PRIVATE); real.edit().clear().commit()
        val s = DeletionEpochStore(real) { "id" }
        assertEquals(1L, s.commitWipe(1).counter); assertTrue(s.wipePending())
        org.junit.Assert.assertThrows(java.io.IOException::class.java) { DeletionEpochStore(applyButFail(real)) { "id" }.commitWipe(9) }
        assertTrue("the earlier wipe is still pending", s.wipePending()); assertEquals(1L, s.read().counter); assertEquals(1L, s.read().wipedAtMs)
    }
}
