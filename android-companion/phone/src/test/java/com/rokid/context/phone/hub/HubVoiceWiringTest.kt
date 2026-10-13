package com.rokid.context.phone.hub

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.Channels
import com.rokid.context.core.model.Command
import com.rokid.context.core.model.Decoded
import com.rokid.context.core.model.Health
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.LinkState
import com.rokid.context.core.model.MemoryLine
import com.rokid.context.core.model.Overlay
import com.rokid.context.core.model.StateFrame
import com.rokid.context.core.model.Wire
import com.rokid.context.link.FakeGlassesLink
import com.rokid.context.link.FrameAdmission
import com.rokid.context.link.FrameReassembler
import com.rokid.context.link.GlassesEvent
import com.rokid.context.link.ReceivedFrameLedger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

class FakeHubVoice : HubVoice {
    override val overlay = MutableStateFlow<Overlay?>(null)
    override val askBlockedReason = MutableStateFlow<String?>(null)
    val calls = mutableListOf<String>()
    val audio = mutableListOf<Int>()
    override fun onListenStart() { calls += "start" }
    override fun onAudio(pcm: ByteArray) { audio += pcm.size }
    override fun onListenEnd() { calls += "end" }
    override fun onCancel() { calls += "cancel" }
    override fun onLinkLost() { calls += "lost" }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class HubVoiceWiringTest {
    @get:Rule val tmp = TemporaryFolder()
    private val ctx = ApplicationProvider.getApplicationContext<Application>()
    private val db = testDb(ctx)
    @After fun close() = db.close()
    private val now = 1_793_000_000_000L
    private val voice = FakeHubVoice()
    private val sources = object : HubSources {
        override suspend fun today() = listOf(MemoryLine("m1", now, "Keys on the hallway shelf"))
        override suspend fun queueDepth() = 0
        override suspend fun aiHealth(nowMs: Long): Health = Health.Ok
    }

    private fun TestScope.hub(link: FakeGlassesLink): Hub {
        val pauses = HubPauses(db.pauseDao()) { now }
        val ledger = ReceivedFrameLedger(File(tmp.root, "ledger.txt"))
        val ingestor = FrameIngestor(File(tmp.root, "inbox"), FrameReassembler(), FrameAdmission(ledger, pauses), ledger, onStored = {})
        val settings = HubSettingsStore(ctx.getSharedPreferences("hub_voice_${tmp.root.name}", 0)) { "hud" }
        return Hub(backgroundScope, link, pauses, settings, ingestor, sources, clock = { now }, io = StandardTestDispatcher(testScheduler), voice = voice)
            .also { it.start(); runCurrent() }
    }
    private fun FakeGlassesLink.lastOn(channel: String) = sent.last { it.first == channel }.second

    @Test fun glassesVoiceEventsReachThePipeline() = runTest {
        val link = FakeGlassesLink()
        hub(link)
        link.emit(GlassesEvent.Cmd(Command.ListenStart))
        link.emit(GlassesEvent.Audio(ByteArray(3_200)))
        link.emit(GlassesEvent.ListenEnd)
        link.emit(GlassesEvent.Cmd(Command.Cancel)); runCurrent()
        assertEquals(listOf("start", "end", "cancel"), voice.calls)
        assertEquals(listOf(3_200), voice.audio)
    }

    @Test fun theVoiceOverlayGoesOutInTheNextStateFrame() = runTest {
        val link = FakeGlassesLink()
        hub(link)
        voice.overlay.value = Overlay.Listening("where are my")
        advanceTimeBy(300)
        assertEquals(Overlay.Listening("where are my"), (Wire.decode<StateFrame>(link.lastOn(Channels.STATE)) as Decoded.Ok).value.overlay)
        voice.overlay.value = null
        advanceTimeBy(300)
        assertNull((Wire.decode<StateFrame>(link.lastOn(Channels.STATE)) as Decoded.Ok).value.overlay)
    }

    @Test fun theAskGateIsPushedButNeverStoredAsAPhoneSetting() = runTest {
        val link = FakeGlassesLink()
        val h = hub(link)
        voice.askBlockedReason.value = "Voice not supported on this phone"; runCurrent()
        assertEquals("Voice not supported on this phone", (Wire.decode<HudSettings>(link.lastOn(Channels.SETTINGS)) as Decoded.Ok).value.askBlockedReason)
        assertNull(h.hudSettings.value.askBlockedReason)
        link.emitReady(); runCurrent()
        assertEquals("Voice not supported on this phone", (Wire.decode<HudSettings>(link.lastOn(Channels.SETTINGS)) as Decoded.Ok).value.askBlockedReason)
    }

    @Test fun aDroppedLinkCancelsAnOpenListen() = runTest {
        val link = FakeGlassesLink()
        hub(link)
        link.setState(LinkState.PAUSED_BT); runCurrent()
        assertTrue("lost" in voice.calls)
    }

    /** Preflight F15: the service stopping ends the link too, so an open glasses listen is dropped, never answered. */
    @Test fun stoppingTheHubDropsAnOpenListen() = runTest {
        val link = FakeGlassesLink()
        link.setState(LinkState.CONNECTED)
        val h = hub(link)
        voice.calls.clear()
        h.stop(); runCurrent()
        assertEquals(listOf("lost"), voice.calls)
    }

    /** Review minor 5 (Phase 4/5 ruling): Ask is a user action, so a capture pause does not hold back a glasses ask. */
    @Test fun aCapturePauseDoesNotHoldBackAGlassesAsk() = runTest {
        val link = FakeGlassesLink()
        hub(link)
        link.emit(GlassesEvent.Cmd(Command.Pause(com.rokid.context.core.model.PauseRecord("g1", com.rokid.context.core.model.PauseOrigin.GLASSES, now, null)))); runCurrent()
        assertEquals(1, HubPauses(db.pauseDao()) { now }.active().size)
        link.emit(GlassesEvent.Cmd(Command.ListenStart))
        link.emit(GlassesEvent.Audio(ByteArray(3_200)))
        link.emit(GlassesEvent.ListenEnd); runCurrent()
        assertEquals(listOf("start", "end"), voice.calls)
        assertEquals(listOf(3_200), voice.audio)
    }
}
