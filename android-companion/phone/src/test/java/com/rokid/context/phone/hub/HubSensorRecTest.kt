package com.rokid.context.phone.hub

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.Channels
import com.rokid.context.core.model.Decoded
import com.rokid.context.core.model.GlassesStatus
import com.rokid.context.core.model.Health
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.LinkState
import com.rokid.context.core.model.MemoryLine
import com.rokid.context.core.model.Wire
import com.rokid.context.data.frames.DirSync
import com.rokid.context.link.FakeGlassesLink
import com.rokid.context.link.FrameAdmission
import com.rokid.context.link.FrameReassembler
import com.rokid.context.link.GlassesEvent
import com.rokid.context.link.ReceivedFrameLedger
import com.rokid.context.phone.hub.plan.DiagControlRetry
import com.rokid.context.phone.hub.plan.DiagFlagState
import com.rokid.context.phone.hub.plan.MutableGlassesDiagStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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

/** v0.9.7 B item 3: the phone puts the sensor-recording rate on the wire only while Developer mode is on, never from the stored value alone. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class HubSensorRecTest {
    @get:Rule val tmp = TemporaryFolder()
    private val ctx = ApplicationProvider.getApplicationContext<Application>()
    private val db = testDb(ctx)
    init { DirSync.testOverride = DirSync { true } }
    @After fun close() { DirSync.testOverride = null; db.close() }
    private val now = 1_793_000_000_000L
    private val sources = object : HubSources {
        override suspend fun today() = listOf(MemoryLine("m1", now, "x"))
        override suspend fun queueDepth() = 2
        override suspend fun aiHealth(nowMs: Long): Health = Health.Ok
    }

    private class Rig(val hub: Hub, val link: FakeGlassesLink, val dev: MutableStateFlow<Boolean>, val status: MutableGlassesDiagStatus, val resends: MutableList<Long>)

    private fun TestScope.rig(devOn: Boolean = false, storedDiag: Boolean = false, storedHz: Int = 0, prefsName: String = "hub_srec_${tmp.root.name}", extra: HudSettings.() -> HudSettings = { this }, linkState: LinkState = LinkState.CONNECTED, io: kotlinx.coroutines.CoroutineDispatcher? = null): Rig {
        val link = FakeGlassesLink(); link.setState(linkState)
        val dev = MutableStateFlow(devOn); val status = MutableGlassesDiagStatus(); val resends = mutableListOf<Long>()
        val pauses = HubPauses(db.pauseDao(), now = { now })
        val ledger = ReceivedFrameLedger(File(tmp.root, "ledger.txt"))
        val ingestor = FrameIngestor(File(tmp.root, "inbox"), FrameReassembler(), FrameAdmission(ledger, pauses), ledger, onStored = {})
        val settings = HubSettingsStore(ctx.getSharedPreferences(prefsName, 0)) { "hud" }
        if (storedDiag) settings.write(settings.read().copy(diagRecord = true).extra())
        settings.write(settings.read().copy(sensorRecHz = storedHz))
        val retry = DiagControlRetry(backgroundScope, status, diagWanted = DiagControlRetry.wantedWhen(dev, link.state), resend = { resends += testScheduler.currentTime })
        val hub = Hub(
            backgroundScope, link, pauses, settings, ingestor, sources, clock = { now }, io = io ?: StandardTestDispatcher(testScheduler),
            devMode = dev, diagControl = retry, diagStatus = status,
        )
        hub.start(); runCurrent()
        return Rig(hub, link, dev, status, resends)
    }

    private fun Rig.settingsPushes(): List<HudSettings> = link.sent.filter { it.first == Channels.SETTINGS }.map { (Wire.decode<HudSettings>(it.second) as Decoded.Ok).value }

    @Test fun aStoredRateNeverReachesTheGlassesWhileDeveloperModeIsOff() = runTest {
        val r = rig(devOn = false, storedHz = 10); r.link.emitReady(); runCurrent()
        r.hub.updateSettings { it }; runCurrent()
        assertTrue(r.settingsPushes().isNotEmpty()); assertTrue(r.settingsPushes().all { it.sensorRecHz == 0 })
    }

    @Test fun withDeveloperModeOnTheStoredPresetIsSentAndTurningItOffRepublishesZero() = runTest {
        val r = rig(devOn = true, storedHz = 10); r.link.emitReady(); runCurrent()
        assertEquals(10, r.settingsPushes().first().sensorRecHz); assertTrue(r.settingsPushes().all { it.sensorRecHz == 10 })
        r.dev.value = false; runCurrent()
        assertEquals(0, r.settingsPushes().last().sensorRecHz)
        r.dev.value = true; runCurrent()
        assertEquals(10, r.settingsPushes().last().sensorRecHz)
    }

    @Test fun aRateThatIsNoPresetIsSentAsOff() = runTest {
        val r = rig(devOn = true, storedHz = 7); r.link.emitReady(); runCurrent()
        assertTrue(r.settingsPushes().all { it.sensorRecHz == 0 })
    }
}
