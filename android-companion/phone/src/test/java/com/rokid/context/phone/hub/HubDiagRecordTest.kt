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

/** LGC4P: the phone publishes `diagRecord` from the Developer-mode store at every settings push and re-sends the trace control until the glasses report enabled. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class HubDiagRecordTest {
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

    private fun TestScope.rig(devOn: Boolean = false, storedDiag: Boolean = false, prefsName: String = "hub_diag_${tmp.root.name}", extra: HudSettings.() -> HudSettings = { this }): Rig {
        val link = FakeGlassesLink(); link.setState(LinkState.CONNECTED)
        val dev = MutableStateFlow(devOn); val status = MutableGlassesDiagStatus(); val resends = mutableListOf<Long>()
        val pauses = HubPauses(db.pauseDao(), now = { now })
        val ledger = ReceivedFrameLedger(File(tmp.root, "ledger.txt"))
        val ingestor = FrameIngestor(File(tmp.root, "inbox"), FrameReassembler(), FrameAdmission(ledger, pauses), ledger, onStored = {})
        val settings = HubSettingsStore(ctx.getSharedPreferences(prefsName, 0)) { "hud" }
        if (storedDiag) settings.write(settings.read().copy(diagRecord = true).extra())
        val retry = DiagControlRetry(backgroundScope, status, diagWanted = { dev.value }, resend = { resends += testScheduler.currentTime })
        val hub = Hub(
            backgroundScope, link, pauses, settings, ingestor, sources, clock = { now }, io = StandardTestDispatcher(testScheduler),
            devMode = dev, diagControl = retry,
        )
        hub.start(); runCurrent()
        return Rig(hub, link, dev, status, resends)
    }

    private fun Rig.settingsPushes(): List<HudSettings> = link.sent.filter { it.first == Channels.SETTINGS }.map { (Wire.decode<HudSettings>(it.second) as Decoded.Ok).value }

    @Test fun DevModeToggleRepublishesSettings() = runTest {
        val r = rig(); r.link.emitReady(); runCurrent(); r.link.sent.clear()
        r.dev.value = true; runCurrent()
        assertEquals("one immediate republish on", listOf(true), r.settingsPushes().map { it.diagRecord })
        r.dev.value = false; runCurrent()
        assertEquals("and one on off", listOf(true, false), r.settingsPushes().map { it.diagRecord })
    }

    @Test fun DiagRecordFalseWhenDevOff() = runTest {
        // Even a stored HudSettings that says true must not leak: the store value alone decides.
        val r = rig(devOn = false, storedDiag = true); r.link.emitReady(); runCurrent()
        assertTrue(r.settingsPushes().isNotEmpty())
        assertTrue(r.settingsPushes().all { !it.diagRecord })
        r.hub.updateSettings { it }; runCurrent()
        assertTrue(r.settingsPushes().all { !it.diagRecord })
    }

    @Test fun DeveloperOffRepublishesFalseOnce() = runTest {
        val r = rig(devOn = true); r.link.emitReady(); runCurrent(); r.link.sent.clear()
        r.dev.value = false; runCurrent()
        assertEquals(listOf(false), r.settingsPushes().map { it.diagRecord })
        advanceTimeBy(600_000)
        assertEquals("no further push and no control re-send", 1, r.settingsPushes().size)
        assertTrue("no control re-send after off", r.resends.isEmpty())
    }

    @Test fun PhoneRestartWithDeveloperModeAlreadyOnFirstPushCarriesTrue() = runTest {
        val r = rig(devOn = true) // fresh hub and store: nothing observed a change
        r.link.emitReady(); runCurrent()
        assertEquals(true, r.settingsPushes().first().diagRecord)
        assertTrue(r.settingsPushes().all { it.diagRecord })
    }

    @Test fun EveryPushCarriesTheCurrentValueNotTheStoredOne() = runTest {
        val r = rig(devOn = true, storedDiag = false); r.link.emitReady(); runCurrent()
        r.hub.updateSettings { it }; runCurrent()
        r.link.emit(GlassesEvent.HelloReceived(com.rokid.context.core.model.Hello(glassesBatteryPct = 77, bufferSize = 4, glassesNowMs = now, pauses = emptyList()))); runCurrent()
        assertTrue(r.settingsPushes().size >= 3)
        assertTrue(r.settingsPushes().all { it.diagRecord })
    }

    @Test fun OldGlassesCompatibilityOnlyDiagRecordDiffers() = runTest {
        val off = rig(devOn = false, prefsName = "hub_cmp_a_${tmp.root.name}"); off.link.emitReady(); runCurrent()
        val on = rig(devOn = true, prefsName = "hub_cmp_b_${tmp.root.name}"); on.link.emitReady(); runCurrent()
        val a = off.settingsPushes().first(); val b = on.settingsPushes().first()
        assertEquals(a, b.copy(diagRecord = false))
        assertFalse(a.diagRecord); assertTrue(b.diagRecord)
        val json = com.rokid.context.core.model.Wire.encode(a)
        // the field is the only new key relative to an all-default message; version unchanged
        assertEquals(1, com.rokid.context.core.model.PROTOCOL_VERSION)
        assertTrue(json.contains("\"diagRecord\":false") || !json.contains("diagRecord"))
    }

    @Test fun PhoneRetriesControlUntilStatusEnabledAtHubLevel() = runTest {
        val r = rig(devOn = true); r.link.emitReady(); runCurrent()
        assertTrue("no re-send before the first backoff", r.resends.isEmpty())
        advanceTimeBy(5_001); assertEquals(1, r.resends.size)
        r.status.update("armed_no_epoch"); advanceTimeBy(10_000); assertEquals(2, r.resends.size)
        r.status.update("enabled"); advanceTimeBy(600_000); assertEquals(2, r.resends.size)
    }

    @Test fun NoRetryWhileDeveloperModeOffAndOnlyAfterItIsSwitchedOn() = runTest {
        val r = rig(devOn = false); r.link.emitReady(); runCurrent()
        advanceTimeBy(600_000); assertTrue(r.resends.isEmpty())
        r.dev.value = true; runCurrent(); advanceTimeBy(5_001)
        assertEquals(1, r.resends.size)
    }

    @Test fun ReconnectRestartsTheScheduleAndDropsStaleStatus() = runTest {
        val r = rig(devOn = true); r.link.emitReady(); runCurrent()
        r.status.update("enabled"); advanceTimeBy(600_000); assertTrue(r.resends.isEmpty())
        r.link.emitReady(); runCurrent() // a new glasses session
        assertEquals(DiagFlagState.UNKNOWN, r.status.state.value)
        advanceTimeBy(5_001); assertEquals(1, r.resends.size)
    }

    @Test fun LinkLossStopsTheRetry() = runTest {
        val r = rig(devOn = true); r.link.emitReady(); runCurrent()
        r.link.setState(LinkState.OFFLINE); runCurrent()
        advanceTimeBy(600_000); assertTrue(r.resends.isEmpty())
    }
}
