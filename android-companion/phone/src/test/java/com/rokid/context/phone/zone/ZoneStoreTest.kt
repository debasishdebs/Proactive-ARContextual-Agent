package com.rokid.context.phone.zone

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.zone.PauseZone
import com.rokid.context.core.model.zone.ZoneEvaluator
import com.rokid.context.core.model.zone.ZonePresets
import org.junit.Assert.assertEquals
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

    @Test fun anUnreadableListReadsAsNoZones() {
        val p = ctx.getSharedPreferences("zs_bad_${System.nanoTime()}", 0); p.edit().putString("zones", "{not json").commit()
        assertTrue(ZoneStore(p).zones().isEmpty())
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
