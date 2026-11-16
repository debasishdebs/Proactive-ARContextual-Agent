package com.rokid.context.phone.zone

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.zone.PauseZone
import com.rokid.context.core.model.zone.ZoneEvaluator
import com.rokid.context.core.model.zone.ZonePresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ZoneStoreTest {
    private val ctx = ApplicationProvider.getApplicationContext<Application>()
    private val z = PauseZone("a", "A", 1.0, 2.0, 3.0, 4.0)

    @Test fun zonesPersistAcrossInstancesAndDropInvalidAndDuplicateOnes() {
        val name = "zs_${System.nanoTime()}"
        ZoneStore(ctx.getSharedPreferences(name, 0)).write(listOf(z, z.copy(name = "dup"), z.copy(id = "bad:id"), ZonePresets.JARAWA))
        assertEquals(listOf(z, ZonePresets.JARAWA), ZoneStore(ctx.getSharedPreferences(name, 0)).zones())
    }

    @Test fun anUnreadableListIsAnExplicitErrorNotAnEmptyListAndIsNeverOverwrittenBlindly() { // Codex r1 P1-9
        val p = ctx.getSharedPreferences("zs_bad_${System.nanoTime()}", 0); p.edit().putString("zones", "{not json").commit()
        val s = ZoneStore(p)
        assertTrue(s.corrupt); assertFalse("a write must not replace what cannot be read", s.write(listOf(z))); assertTrue(s.corrupt)
        assertTrue(s.replaceAll(listOf(z))); assertFalse(s.corrupt); assertEquals(listOf(z), s.zones())
    }

    @Test fun aFailedCommitIsReportedAndLeavesTheListAlone() {
        val p = FailingPrefs(ctx.getSharedPreferences("zs_fail_${System.nanoTime()}", 0)); val s = ZoneStore(p)
        assertTrue(s.write(listOf(z))); p.fail = true
        assertFalse(s.write(listOf(z, ZonePresets.JARAWA))); assertEquals(listOf(z), s.zones())
    }

    @Test fun aSequenceNumberThatCouldNotBeStoredIsNeverUsed() {
        val name = "zs_seqfail_${System.nanoTime()}"; val p = FailingPrefs(ctx.getSharedPreferences(name, 0)); val s = ZoneStore(p)
        assertEquals(1L, s.nextSeq()); p.fail = true
        try { s.nextSeq(); org.junit.Assert.fail("must throw") } catch (e: IllegalStateException) { /* expected */ }
        p.fail = false; assertEquals("the number that failed is issued only once it is durable", 2L, s.nextSeq())
    }

    @Test fun theRecordCounterIsDurableAndNeverRepeats() {
        val name = "zs_seq_${System.nanoTime()}"
        val a = ZoneStore(ctx.getSharedPreferences(name, 0)); assertEquals(listOf(1L, 2L), listOf(a.nextSeq(), a.nextSeq()))
        assertEquals(3L, ZoneStore(ctx.getSharedPreferences(name, 0)).nextSeq())
    }

    @Test fun theDefaultsHoldTheSpecAndOnlyUserConfigurationIsStored() {
        assertEquals("pause_zones", ZoneStore.PREFS); assertEquals(300, z.bufferM); assertTrue(z.enabled)
        assertEquals(3 * 60_000L, ZoneEvaluator.STALE_MS)
    }
}

/** SharedPreferences whose commit() can be made to fail (a full disk). */
class FailingPrefs(private val d: android.content.SharedPreferences, @Volatile var fail: Boolean = false) : android.content.SharedPreferences by d {
    override fun edit(): android.content.SharedPreferences.Editor {
        val e = d.edit()
        return object : android.content.SharedPreferences.Editor by e {
            override fun putString(k: String?, v: String?) = apply { e.putString(k, v) }
            override fun putLong(k: String?, v: Long) = apply { e.putLong(k, v) }
            override fun commit(): Boolean = if (fail) false else e.commit()
        }
    }
}
