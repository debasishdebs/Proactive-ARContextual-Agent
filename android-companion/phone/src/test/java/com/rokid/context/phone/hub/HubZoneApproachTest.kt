package com.rokid.context.phone.hub

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.Channels
import com.rokid.context.core.model.Decoded
import com.rokid.context.core.model.Health
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.LinkState
import com.rokid.context.core.model.MemoryLine
import com.rokid.context.core.model.Wire
import com.rokid.context.data.frames.DirSync
import com.rokid.context.link.FakeGlassesLink
import com.rokid.context.link.FrameAdmission
import com.rokid.context.link.FrameReassembler
import com.rokid.context.link.ReceivedFrameLedger
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** Pause zones: `HudSettings.zoneApproach` is computed at every push (never the stored value) and a zone change pushes at once. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class HubZoneApproachTest {
    @get:Rule val tmp = TemporaryFolder()
    private val ctx = ApplicationProvider.getApplicationContext<Application>()
    private val db = testDb(ctx)
    init { DirSync.testOverride = DirSync { true } }
    @After fun close() { DirSync.testOverride = null; db.close() }
    private val now = 1_793_000_000_000L
    private val sources = object : HubSources {
        override suspend fun today() = listOf(MemoryLine("m1", now, "x")); override suspend fun queueDepth() = 0; override suspend fun aiHealth(nowMs: Long): Health = Health.Ok
    }
    private var approach = false

    private fun TestScope.rig(): Pair<Hub, FakeGlassesLink> {
        val link = FakeGlassesLink(); link.setState(LinkState.CONNECTED)
        val pauses = HubPauses(db.pauseDao(), now = { now })
        val ledger = ReceivedFrameLedger(File(tmp.root, "ledger.txt"))
        val ingestor = FrameIngestor(File(tmp.root, "inbox"), FrameReassembler(), FrameAdmission(ledger, pauses), ledger, onStored = {})
        val settings = HubSettingsStore(ctx.getSharedPreferences("hub_zone_${tmp.root.name}", 0)) { "hud" }
        settings.write(settings.read().copy(zoneApproach = true)) // a stored value must never reach the wire
        val hub = Hub(backgroundScope, link, pauses, settings, ingestor, sources, clock = { now }, io = StandardTestDispatcher(testScheduler), zoneApproach = { approach })
        hub.start(); runCurrent()
        return hub to link
    }

    private fun FakeGlassesLink.pushes(): List<HudSettings> = sent.filter { it.first == Channels.SETTINGS }.map { (Wire.decode<HudSettings>(it.second) as Decoded.Ok).value }

    @Test fun thePushCarriesTheLiveApproachFlagNotTheStoredOne() = runTest {
        val (hub, link) = rig(); link.emitReady(); runCurrent()
        hub.updateSettings { it }; runCurrent()
        assertTrue(link.pushes().isNotEmpty()); assertTrue(link.pushes().none { it.zoneApproach })
        approach = true; hub.updateSettings { it }; runCurrent()
        assertTrue(link.pushes().last().zoneApproach)
    }

    @Test fun aZoneChangePushesTheNewFlagAtOnce() = runTest {
        val (hub, link) = rig(); link.emitReady(); runCurrent()
        val before = link.pushes().size
        approach = true; hub.zonesChanged(); runCurrent()
        assertEquals(before + 1, link.pushes().size); assertTrue(link.pushes().last().zoneApproach)
        approach = false; hub.zonesChanged(); runCurrent()
        assertFalse(link.pushes().last().zoneApproach)
    }

    @Test fun theDefaultHubNeverSetsTheFlag() = runTest {
        approach = false; val (hub, link) = rig(); link.emitReady(); runCurrent(); hub.updateSettings { it }; runCurrent()
        assertTrue(link.pushes().all { !it.zoneApproach })
    }
}
