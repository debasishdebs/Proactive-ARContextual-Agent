package com.rokid.context.work.frames.intent

import android.content.Context
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.work.budget.PhoneThermal
import com.rokid.context.work.budget.ThermalStatusProvider
import com.rokid.context.work.frames.KeyframeGraph
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class KeyframeSignalPokeTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Before fun setUp() { MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx)) }
    @After fun tearDown() {
        try { KeyframeGraph.intentSampler = null; KeyframeGraph.thermal = ThermalStatusProvider { PhoneThermal.NONE } }
        finally { try { KeyframeGraph.resetForTests() } finally { MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) } }
    }

    @Test fun aStoredFrameSignalPokesTheInstalledSamplerAndANullOneIsHarmless() {
        var pokes = 0
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val store = RoomIntentFrameStore { MemoryGraph.get(ctx).db }
        KeyframeGraph.intentSampler = IntentSampler(scope, store, { null }, { 0L }, { false }, { _, _, _ -> }, enabled = { pokes++; false }, stats = { })
        KeyframeGraph.get(ctx).signal()
        assertEquals(1, pokes)
        KeyframeGraph.intentSampler = null
        KeyframeGraph.get(ctx).signal()
        assertEquals(1, pokes)
    }

    // ---- admitBackgroundCall: the shared gates
    private fun network(validated: Boolean) {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (validated) shadowOf(caps).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)
    }

    @Test fun admitFollowsHeatBatterySaverAndNetwork() {
        network(true)
        val h = KeyframeGraph.get(ctx)
        assertTrue("baseline: nothing in the way", h.admitBackgroundCall())
        KeyframeGraph.thermal = ThermalStatusProvider { PhoneThermal.SEVERE }
        assertFalse("SEVERE heat pauses it", KeyframeGraph.get(ctx).admitBackgroundCall())
        KeyframeGraph.thermal = ThermalStatusProvider { PhoneThermal.NONE }
        shadowOf(ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).setIsPowerSaveMode(true)
        assertFalse("Battery Saver defers it", KeyframeGraph.get(ctx).admitBackgroundCall())
    }

    @Test fun admitRefusesWithoutAValidatedNetwork() {
        network(false)
        assertFalse(KeyframeGraph.get(ctx).admitBackgroundCall())
    }

    @Test fun moderateHeatGivesOneSharedExtractionSlotPer30Seconds() {
        network(true)
        KeyframeGraph.thermal = ThermalStatusProvider { PhoneThermal.MODERATE }
        val h = KeyframeGraph.get(ctx)
        assertTrue(h.admitBackgroundCall())
        assertFalse("the allowance is shared with extraction", h.admitBackgroundCall())
    }
}
