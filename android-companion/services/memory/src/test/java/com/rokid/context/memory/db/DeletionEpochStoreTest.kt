package com.rokid.context.memory.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
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

    @Test fun prefsNameIsTheBackupRuledOne() { assertEquals("deletion_epoch", DeletionEpochStore.PREFS) }

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

    @Test fun theWipeTimeNeverMovesBackward() {
        val s = DeletionEpochStore.of(ctx)
        s.commitWipe(9_000)
        assertEquals("a clock set back keeps the later fence", 9_000L, s.commitWipe(1_000).wipedAtMs)
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
}
