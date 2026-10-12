package com.rokid.context.phone.hub

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.CaptureState
import com.rokid.context.core.model.Channels
import com.rokid.context.core.model.Command
import com.rokid.context.core.model.Decoded
import com.rokid.context.core.model.FrameAck
import com.rokid.context.core.model.FrameCodec
import com.rokid.context.core.model.GlassesStatus
import com.rokid.context.core.model.Health
import com.rokid.context.core.model.Hello
import com.rokid.context.core.model.HelloRequest
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.LinkState
import com.rokid.context.core.model.MemoryLine
import com.rokid.context.core.model.PROTOCOL_VERSION
import com.rokid.context.core.model.PauseOrigin
import com.rokid.context.core.model.PauseRecord
import com.rokid.context.core.model.StateFrame
import com.rokid.context.core.model.Verbosity
import com.rokid.context.core.model.Wire
import com.rokid.context.link.FakeGlassesLink
import com.rokid.context.link.FrameAdmission
import com.rokid.context.link.FrameReassembler
import com.rokid.context.link.GlassesEvent
import com.rokid.context.link.GlassesLink
import com.rokid.context.link.ReceivedFrameLedger
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import android.database.sqlite.SQLiteFullException
import com.rokid.context.core.model.UserFacingErrors
import com.rokid.context.memory.db.PauseDao
import com.rokid.context.memory.db.PauseRecordEntity

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class HubTest {
    @get:Rule val tmp = TemporaryFolder()
    private val ctx = ApplicationProvider.getApplicationContext<Application>()
    private val db = testDb(ctx)
    @After fun close() = db.close()
    private val now = 1_793_000_000_000L
    private val sources = object : HubSources {
        override suspend fun today() = listOf(MemoryLine("m1", now, "Keys on the hallway shelf"))
        override suspend fun queueDepth() = 2
        override suspend fun aiHealth(nowMs: Long): Health = Health.Ok
    }

    private fun TestScope.hub(
        link: GlassesLink,
        clock: () -> Long = { now },
        dao: PauseDao = db.pauseDao(),
        onStored: () -> Unit = {},
        startService: () -> Unit = {},
        start: Boolean = true,
    ): Hub {
        val pauses = HubPauses(dao, clock)
        val ledger = ReceivedFrameLedger(File(tmp.root, "ledger.txt"))
        val ingestor = FrameIngestor(File(tmp.root, "inbox"), FrameReassembler(), FrameAdmission(ledger, pauses), ledger, onStored = onStored)
        val settings = HubSettingsStore(ctx.getSharedPreferences("hub_test_${tmp.root.name}", 0)) { "hud" }
        return Hub(backgroundScope, link, pauses, settings, ingestor, sources, clock = clock, io = StandardTestDispatcher(testScheduler), startService = startService)
            .also { if (start) { it.start(); runCurrent() } }
    }
    private fun FakeGlassesLink.lastOn(channel: String) = sent.last { it.first == channel }.second
    private fun hello(glassesNowMs: Long = now, pauses: List<PauseRecord> = emptyList()) =
        GlassesEvent.HelloReceived(Hello(glassesBatteryPct = 77, bufferSize = 4, glassesNowMs = glassesNowMs, pauses = pauses))

    @Test fun startConnectsOnceAndReadyPushesSettingsThenState() = runTest {
        val link = FakeGlassesLink()
        hub(link)
        assertEquals(1, link.connectOnceCalls)
        link.sent.clear()
        link.emitReady(); advanceTimeBy(300) // STATE goes through the broadcaster's 250 ms window
        assertEquals(listOf(Channels.SETTINGS, Channels.STATE), link.sent.map { it.first }.take(2))
        val frame = (Wire.decode<StateFrame>(link.lastOn(Channels.STATE)) as Decoded.Ok).value
        assertEquals(CaptureState.Running, frame.capture)
        assertEquals(2, frame.queueDepth)
        assertTrue(frame.phoneLinked)
        assertEquals("Keys on the hallway shelf", frame.today.single().text)
    }

    /** Review Focus 1: glasses 2 h ahead; their pause is stored in phone clock and sent back in glasses clock. */
    @Test fun helloMeasuresTheOffsetAndUnionsGlassesPausesInPhoneClock() = runTest {
        val link = FakeGlassesLink()
        val h = hub(link)
        val g = now + 7_200_000
        link.emit(hello(glassesNowMs = g, pauses = listOf(PauseRecord("g1", PauseOrigin.GLASSES, g - 60_000, null)))); runCurrent()
        assertEquals(7_200_000L, h.state.value.offset!!.glassesMinusPhoneMs)
        assertEquals(GlassesStatus(LinkState.CONNECTED, 77, 4, null, h.status.value.frame), h.status.value)
        advanceTimeBy(300)
        val paused = (Wire.decode<StateFrame>(link.lastOn(Channels.STATE)) as Decoded.Ok).value.capture as CaptureState.Paused
        assertEquals("sent back in glasses clock", g - 60_000, paused.records.single().createdAtMs)
        assertEquals("stored in phone clock", now - 60_000, HubPauses(db.pauseDao()) { now }.active().single().createdAtMs)
    }

    @Test fun pauseAndResumeCommandsChangeTheCaptureState() = runTest {
        val link = FakeGlassesLink()
        val h = hub(link)
        link.emit(hello()); runCurrent()
        link.emit(GlassesEvent.Cmd(Command.Pause(PauseRecord("g2", PauseOrigin.GLASSES, now, null)))); advanceTimeBy(300)
        assertTrue(h.status.value.frame!!.capture is CaptureState.Paused)
        link.emit(GlassesEvent.Cmd(Command.Resume(setOf("unknown")))); advanceTimeBy(300)
        assertTrue("an unknown id clears nothing", h.status.value.frame!!.capture is CaptureState.Paused)
        link.emit(GlassesEvent.Cmd(Command.Resume(setOf("g2")))); advanceTimeBy(300)
        assertEquals(CaptureState.Running, h.status.value.frame!!.capture)
    }

    /**
     * Cross-boundary: pause P stored while linked; the wearer resumes offline (the Resume command is lost); the next
     * session's hello names P in `resumedAtMs`. P ends at the glasses' resume instant, so a frame buffered offline after
     * the resume and the next live frame are both admitted, not PAUSED_DROP.
     */
    @Test fun aResumeCarriedInHelloEndsThePauseAndFramesAreAdmitted() = runTest {
        val link = FakeGlassesLink()
        var t = now
        val h = hub(link, clock = { t })
        val skew = 3_600_000L // glasses clock = phone + 1 h
        link.emit(hello(glassesNowMs = t + skew)); runCurrent()
        link.emit(GlassesEvent.Cmd(Command.Pause(PauseRecord("P", PauseOrigin.GLASSES, t + skew, null)))); runCurrent()
        assertEquals(listOf("P"), HubPauses(db.pauseDao()) { t }.active().map { it.pauseId })

        val resumedAt = t + 60_000 // glasses clock - skew; BT is down from here
        t += 600_000
        link.setState(LinkState.PAUSED_BT); runCurrent(); link.setState(LinkState.CONNECTED); runCurrent()
        link.emit(
            GlassesEvent.HelloReceived(
                Hello(glassesBatteryPct = 70, bufferSize = 1, glassesNowMs = t + skew, pauses = emptyList(), resumedAtMs = mapOf("P" to resumedAt + skew)),
            ),
        ); runCurrent()
        assertTrue("no active pause", HubPauses(db.pauseDao()) { t }.active().isEmpty())

        val offline = FrameCodec.split("g_offline", ByteArray(2_000) { 4 }, capturedAtMs = resumedAt + skew + 1_000).single()
        link.emit(GlassesEvent.Frame(offline)); runCurrent()
        assertEquals(FrameAck(frameId = "g_offline", ok = true), (Wire.decode<FrameAck>(link.lastOn(Channels.FRAME_ACK)) as Decoded.Ok).value)
        val live = FrameCodec.split("g_live", ByteArray(2_000) { 5 }, capturedAtMs = t + skew - 1_000).single()
        link.emit(GlassesEvent.Frame(live)); runCurrent()
        assertEquals(FrameAck(frameId = "g_live", ok = true), (Wire.decode<FrameAck>(link.lastOn(Channels.FRAME_ACK)) as Decoded.Ok).value)
        advanceTimeBy(300)
        assertEquals(CaptureState.Running, h.status.value.frame!!.capture)
    }

    @Test fun frameIsHeldBeforeHelloThenIngestedAndAcked() = runTest {
        val link = FakeGlassesLink()
        val h = hub(link)
        val c = FrameCodec.split("g5", ByteArray(2_000) { 3 }, capturedAtMs = now - 1_000).single()
        link.emit(GlassesEvent.Frame(c)); runCurrent()
        assertTrue("held before hello", link.sent.none { it.first == Channels.FRAME_ACK })
        link.emit(hello()); runCurrent()
        link.emit(GlassesEvent.Frame(c)); runCurrent()
        assertEquals(FrameAck(frameId = "g5", ok = true), (Wire.decode<FrameAck>(link.lastOn(Channels.FRAME_ACK)) as Decoded.Ok).value)
        assertEquals(now - 1_000, h.status.value.lastFrameAtMs)
    }

    /** Review Focus 4: a mismatched glasses app is shown as such until a new session starts. */
    @Test fun outdatedGlassesShowVersionMismatchUntilANewSession() = runTest {
        val link = FakeGlassesLink()
        val h = hub(link)
        link.emit(GlassesEvent.Outdated(PROTOCOL_VERSION + 1)); runCurrent()
        assertEquals(LinkState.VERSION_MISMATCH, h.status.value.link)
        link.emitReady(); runCurrent()
        assertEquals(LinkState.CONNECTED, h.status.value.link)
    }

    @Test fun linkStateFlowsIntoTheStatus() = runTest {
        val link = FakeGlassesLink()
        val h = hub(link)
        link.setState(LinkState.CLOSED_ON_GLASSES); runCurrent()
        assertEquals(LinkState.CLOSED_ON_GLASSES, h.status.value.link)
    }

    @Test fun reconnectIsAnExplicitConnect() = runTest {
        val link = FakeGlassesLink()
        hub(link).requestReconnect()
        assertEquals(1, link.connectCalls)
    }

    /** Carry (Phase 3 review): Reconnect with the hub service down starts it first; it is never a silent no-op. */
    @Test fun reconnectStartsTheHubServiceWhenTheHubIsNotRunning() = runTest {
        val link = FakeGlassesLink(LinkState.OFFLINE)
        var starts = 0
        hub(link, startService = { starts++ }, start = false).requestReconnect()
        assertEquals(1, starts)
        assertEquals(1, link.connectCalls)
    }

    @Test fun reconnectDoesNotRestartARunningHub() = runTest {
        var starts = 0
        hub(FakeGlassesLink(), startService = { starts++ }).requestReconnect()
        assertEquals(0, starts)
    }

    /** Carry (Phase 3 review): each frame the glasses deliver leaves their buffer, so the count drops without a new hello. */
    @Test fun bufferedFramesCountDownAsTheFlushIsAcked() = runTest {
        val link = FakeGlassesLink()
        val h = hub(link)
        link.emit(hello()); runCurrent() // bufferSize = 4
        assertEquals(4, h.status.value.bufferedFrames)
        for (i in 1..3) {
            link.emit(GlassesEvent.Frame(FrameCodec.split("b$i", ByteArray(2_000) { i.toByte() }, capturedAtMs = now - 10_000 + i).single())); runCurrent()
        }
        assertEquals(1, h.status.value.bufferedFrames)
        link.emit(GlassesEvent.Frame(FrameCodec.split("b3", ByteArray(2_000) { 3 }, capturedAtMs = now - 10_000 + 3).single())); runCurrent()
        assertEquals("a resend (our ack was lost) is still in their buffer: counted once", 1, h.status.value.bufferedFrames)
        link.emit(GlassesEvent.Frame(FrameCodec.split("b4", ByteArray(2_000) { 4 }, capturedAtMs = now - 10_000 + 4).single())); runCurrent()
        link.emit(GlassesEvent.Frame(FrameCodec.split("b5", ByteArray(2_000) { 5 }, capturedAtMs = now - 10_000 + 5).single())); runCurrent()
        assertEquals("never below zero", 0, h.status.value.bufferedFrames)
    }

    @Test fun nothingIsSentWhileTheLinkIsDownButTheFrameIsKept() = runTest {
        val link = FakeGlassesLink(LinkState.OFFLINE)
        val h = hub(link)
        advanceTimeBy(11_000)
        assertTrue(link.sent.isEmpty())
        assertNotNull(h.status.value.frame)
    }

    @Test fun settingsChangesArePushed() = runTest {
        val link = FakeGlassesLink()
        val h = hub(link)
        h.updateSettings { it.copy(verbosity = Verbosity.MAX) }; runCurrent()
        assertEquals(Verbosity.MAX, (Wire.decode<HudSettings>(link.lastOn(Channels.SETTINGS)) as Decoded.Ok).value.verbosity)
    }
    /** Ruling (Task 4 review): a pause heard before any offset was anchored at receipt; the first hello re-converts it. */
    @Test fun pausesHeardBeforeTheFirstHelloAreReConvertedWhenTheOffsetArrives() = runTest {
        val link = FakeGlassesLink()
        hub(link)
        val g = now + 7_200_000
        link.emit(GlassesEvent.Cmd(Command.Pause(PauseRecord("g6", PauseOrigin.GLASSES, g - 30_000, g + 60_000)))); runCurrent()
        link.emit(GlassesEvent.Cmd(Command.Pause(PauseRecord("g7", PauseOrigin.GLASSES, g - 20_000, null)))); runCurrent()
        link.emit(GlassesEvent.Cmd(Command.Resume(setOf("g7")))); runCurrent()
        assertEquals("anchored at receipt", now, db.pauseDao().intervalFor("g6")!!.startMs)
        link.emit(hello(glassesNowMs = g)); runCurrent()
        val r = HubPauses(db.pauseDao()) { now }.active().single()
        assertEquals("start moved to the true capture-side instant", now - 30_000, r.createdAtMs)
        assertEquals("the later (more restrictive) end is kept", now + 90_000, r.untilMs)
        assertEquals(now - 30_000, db.pauseDao().intervalFor("g6")!!.startMs)
        val closed = db.pauseDao().intervalFor("g7")!!
        assertEquals("a resumed pause still covers its true start", now - 20_000, closed.startMs)
        assertEquals("and stays resumed", now, closed.effectiveEndMs)
    }

    /** Ruling (Task 4 review): a held frame is not acked and asks the glasses for a fresh hello, rate-limited. */
    @Test fun aHeldFrameAsksForAFreshHelloAtMostOncePerWindow() = runTest {
        var t = now
        val link = FakeGlassesLink()
        hub(link, clock = { t })
        link.emit(hello()); runCurrent()
        fun future(id: String) = GlassesEvent.Frame(FrameCodec.split(id, ByteArray(2_000) { 3 }, capturedAtMs = now + 60_000).single())
        fun requests() = link.sent.count { it.first == Channels.HELLO_REQUEST }
        link.emit(future("g8")); runCurrent()
        link.emit(future("g9")); runCurrent()
        assertTrue(link.sent.none { it.first == Channels.FRAME_ACK })
        assertEquals(1, requests())
        assertTrue(Wire.decode<HelloRequest>(link.lastOn(Channels.HELLO_REQUEST)) is Decoded.Ok)
        t += Hub.HELLO_REQUEST_MIN_INTERVAL_MS
        link.emit(future("g10")); runCurrent()
        assertEquals(2, requests())
    }

    /** Fix round 1 (master Review Focus 5): a full disk while handling an event is shown, never crashes, never stops the hub. */
    @Test fun aFullDiskInAnEventIsShownAndLaterEventsStillWork() = runTest {
        var full = true
        val dao = object : PauseDao by db.pauseDao() {
            override suspend fun addMerged(record: PauseRecordEntity, activeNow: Boolean, now: Long) {
                if (full) throw SQLiteFullException("database or disk is full")
                db.pauseDao().addMerged(record, activeNow, now)
            }
        }
        val link = FakeGlassesLink()
        val h = hub(link, dao = dao)
        link.emit(hello()); runCurrent()
        link.emit(GlassesEvent.Cmd(Command.Pause(PauseRecord("g12", PauseOrigin.GLASSES, now, null)))); runCurrent()
        assertEquals(UserFacingErrors.StorageFull, h.state.value.error)
        full = false
        link.emit(GlassesEvent.Cmd(Command.Pause(PauseRecord("g13", PauseOrigin.GLASSES, now, null)))); advanceTimeBy(300)
        assertTrue("collection survived", h.status.value.frame!!.capture is CaptureState.Paused)
    }

    /** Fix round 1: the queue enqueue after a stored frame throws (ENOSPC): the frame is acked, the error shown, the hub lives. */
    @Test fun aFailingQueueEnqueueAcksTheStoredFrameAndShowsStorageFull() = runTest {
        val link = FakeGlassesLink()
        val h = hub(link, onStored = { throw IOException("write failed: ENOSPC (No space left on device)") })
        link.emit(hello()); runCurrent()
        link.emit(GlassesEvent.Frame(FrameCodec.split("g14", ByteArray(2_000) { 3 }, capturedAtMs = now - 1_000).single())); runCurrent()
        assertEquals(FrameAck(frameId = "g14", ok = true), (Wire.decode<FrameAck>(link.lastOn(Channels.FRAME_ACK)) as Decoded.Ok).value)
        assertEquals(UserFacingErrors.StorageFull, h.state.value.error)
        link.emit(GlassesEvent.Cmd(Command.Pause(PauseRecord("g15", PauseOrigin.GLASSES, now, null)))); advanceTimeBy(300)
        assertTrue(h.status.value.frame!!.capture is CaptureState.Paused)
        assertEquals("Phone storage full", h.status.value.frame!!.error)
    }

    /** Fix round 1: a new session forgets the old offset (the glasses have no RTC), so an early pause is anchored, then re-converted. */
    @Test fun aNewSessionForgetsTheOldOffset() = runTest {
        val link = FakeGlassesLink()
        val h = hub(link)
        link.emit(hello(glassesNowMs = now + 7_200_000)); runCurrent()
        link.emitReady(); runCurrent()
        assertNull("ready starts a new session", h.state.value.offset)
        link.emit(hello(glassesNowMs = now + 7_200_000)); runCurrent()
        link.setState(LinkState.PAUSED_BT); runCurrent()
        assertNull("leaving CONNECTED ends the session", h.state.value.offset)
        link.setState(LinkState.CONNECTED); link.emitReady(); runCurrent()
        // The glasses rebooted and their clock now matches the phone's.
        link.emit(GlassesEvent.Cmd(Command.Pause(PauseRecord("g16", PauseOrigin.GLASSES, now - 10_000, null)))); runCurrent()
        assertEquals("anchored at receipt, not converted with the stale 2 h offset", now, db.pauseDao().intervalFor("g16")!!.startMs)
        link.emit(hello(glassesNowMs = now)); runCurrent()
        assertEquals(now - 10_000, db.pauseDao().intervalFor("g16")!!.startMs)
    }

    private class TimedLink(val inner: FakeGlassesLink, val time: () -> Long) : GlassesLink by inner {
        val stateAt = mutableListOf<Long>()
        override suspend fun send(channel: String, json: String): Boolean =
            inner.send(channel, json).also { if (it && channel == Channels.STATE) stateAt += time() }
    }

    /** Fix round 1 (≤ 4 Hz Global Constraint): ready goes through the broadcaster, so frames are always ≥ 250 ms apart. */
    @Test fun readyAndAnImmediateChangeNeverSendTwoFramesWithin250Ms() = runTest {
        val fake = FakeGlassesLink()
        val link = TimedLink(fake) { testScheduler.currentTime }
        hub(link)
        advanceTimeBy(100) // inside the window of the start-up frame
        fake.emitReady(); runCurrent()
        fake.emit(hello()); runCurrent()
        advanceTimeBy(2_000)
        val at = link.stateAt.toList()
        assertTrue("frames: $at", at.size >= 2)
        at.zipWithNext().forEach { (a, b) -> assertTrue("frames ${b - a} ms apart in $at", b - a >= 250) }
    }
}
