package com.rokid.context.phone.hub

import com.rokid.context.data.frames.DirSync
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
import com.rokid.context.core.model.MotionHint
import com.rokid.context.core.model.MotionState
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.core.model.context.MotionContextMessage
import com.rokid.context.core.model.NudgeLine
import com.rokid.context.core.model.Overlay
import com.rokid.context.core.model.PROTOCOL_VERSION
import com.rokid.context.core.model.PauseOrigin
import com.rokid.context.core.model.QuietHours
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
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.junit.Assert.assertFalse
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
    init { DirSync.testOverride = DirSync { true } } // Robolectric cannot fsync a directory
    @After fun close() { DirSync.testOverride = null; db.close() }
    private val now = 1_793_000_000_000L
    /** Set to hold a broadcast inside its IO reads (Codex r1 P2 repro). */
    @Volatile private var todayGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    @Volatile private var todayEntered: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    private val sources = object : HubSources {
        override suspend fun today(): List<MemoryLine> { todayGate?.let { g -> todayEntered?.complete(Unit); g.await() }; return listOf(MemoryLine("m1", now, "Keys on the hallway shelf")) }
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
        voice: HubVoice = HubVoice.None,
        actions: HubActions = HubActions.None,
        nudges: HubNudges = HubNudges.None,
        reopenGuards: RelaunchGuards? = null,
        relaunchStore: RelaunchStore = InMemoryRelaunchStore(),
        standDown: StandDown = InMemoryStandDown(),
        phoneUse: kotlinx.coroutines.flow.StateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(false),
        motionContext: kotlinx.coroutines.flow.StateFlow<MotionContextMessage?> = kotlinx.coroutines.flow.MutableStateFlow(null),
        onMotionHint: (MotionHint) -> Unit = {},
        ctxStart: () -> Unit = {},
        ctxStop: () -> Unit = {},
        applyToCapture: (() -> Boolean)? = null,
        phoneInstall: () -> String = { "" },
        onPlanAck: (com.rokid.context.core.model.CapturePlanAck) -> Unit = {},
        onPlanCaps: (List<String>) -> Unit = {},
        onMark: suspend (com.rokid.context.core.model.MarkMessage, com.rokid.context.canvas.marks.MarkArrival) -> Boolean = { _, _ -> true },
        onGlassesListenStart: suspend (Long) -> Unit = {},
        onMarkPhoto: suspend (com.rokid.context.core.model.MarkPhotoResult) -> Unit = {},
        reopenEnabled: () -> Boolean = { true },
        paired: () -> Boolean = { true },
        onPositiveSignal: () -> Unit = {},
        inFront: () -> Boolean = { false },
        placeCards: kotlinx.coroutines.flow.StateFlow<com.rokid.context.work.frames.place.PlaceCard?> = MutableStateFlow(null),
        placeClear: () -> Unit = {},
        suggestionCards: kotlinx.coroutines.flow.StateFlow<SuggestionHudHolder.Shown?> = MutableStateFlow(null),
        suggestionClearIf: (String) -> Boolean = { false },
        onSuggestionDismissed: (String) -> Unit = {},
        zoneApproach: () -> Boolean = { false },
        hudHost: () -> com.rokid.context.core.model.HudHost = { com.rokid.context.core.model.HudHost.PCA },
        onStopped: () -> Unit = {},
    ): Hub {
        val pauses = HubPauses(dao, now = clock)
        val ledger = ReceivedFrameLedger(File(tmp.root, "ledger.txt"))
        val ingestor = FrameIngestor(File(tmp.root, "inbox"), FrameReassembler(), FrameAdmission(ledger, pauses), ledger, onStored = onStored)
        val settings = HubSettingsStore(ctx.getSharedPreferences("hub_test_${tmp.root.name}", 0)) { "hud" }
        return Hub(
            backgroundScope, link, pauses, settings, ingestor, sources, clock = clock, io = StandardTestDispatcher(testScheduler),
            startService = startService, voice = voice, actions = actions, nudges = nudges, reopenGuards = reopenGuards, relaunchStore = relaunchStore, standDown = standDown, phoneUse = phoneUse,
            motionContext = motionContext, onMotionHint = onMotionHint, ctxStart = ctxStart, ctxStop = ctxStop, applyToCapture = applyToCapture,
            phoneInstall = phoneInstall, onPlanAck = onPlanAck, onPlanCaps = onPlanCaps, onMark = onMark, onMarkPhoto = onMarkPhoto, onGlassesListenStart = onGlassesListenStart,
            reopenEnabled = reopenEnabled, paired = paired, onPositiveSignal = onPositiveSignal, inFront = inFront,
            placeCards = placeCards, placeClear = placeClear, suggestionCards = suggestionCards, suggestionClearIf = suggestionClearIf, onSuggestionDismissed = onSuggestionDismissed, zoneApproach = zoneApproach, hudHost = hudHost, onStopped = onStopped,
        )
            .also { if (start) { it.start(userInitiated = true); runCurrent() } }
    }
    private fun FakeGlassesLink.lastOn(channel: String) = sent.last { it.first == channel }.second
    private fun hello(glassesNowMs: Long = now, pauses: List<PauseRecord> = emptyList()) =
        GlassesEvent.HelloReceived(Hello(glassesBatteryPct = 77, bufferSize = 4, glassesNowMs = glassesNowMs, pauses = pauses))

    private fun ctxMsg(at: Long = now, since: Long = now - 60_000, state: ContextState = ContextState.VEHICLE_DRIVING) =
        MotionContextMessage(state = state, confidence = 0.9f, sinceMs = since, atMs = at, reasons = listOf("ANDROID_AUTO"))

    @Test fun motionContextIsSentOnItsChannelWithTheSessionStart() = runTest {
        val link = FakeGlassesLink(); val flow = MutableStateFlow<MotionContextMessage?>(null)
        hub(link, motionContext = flow)
        assertTrue(link.sent.none { it.first == Channels.MOTION_CONTEXT })
        link.emitReady(); runCurrent(); link.sent.clear()
        flow.value = ctxMsg(); runCurrent()
        val sent = Wire.decode<MotionContextMessage>(link.lastOn(Channels.MOTION_CONTEXT)) as Decoded.Ok
        assertEquals(ContextState.VEHICLE_DRIVING, sent.value.state)
        assertEquals(now, sent.value.sessionStartMs)
    }

    @Test fun motionContextIsResentOnReadyAsAFreshEnvelope() = runTest {
        val link = FakeGlassesLink(); val flow = MutableStateFlow<MotionContextMessage?>(ctxMsg(at = now - 500_000))
        var t = now
        hub(link, clock = { t }, motionContext = flow)
        link.emitReady(); runCurrent()
        t = now + 90_000
        link.sent.clear(); link.emitReady(); runCurrent()
        val m = (Wire.decode<MotionContextMessage>(link.lastOn(Channels.MOTION_CONTEXT)) as Decoded.Ok).value
        assertEquals(t, m.sessionStartMs)
        assertTrue(m.atMs >= m.sessionStartMs)
        assertEquals(now - 60_000, m.sinceMs)
        assertEquals(ContextState.VEHICLE_DRIVING, m.state)
    }

    @Test fun motionContextGoingNullSendsAnExplicitRevoke() = runTest {
        val link = FakeGlassesLink(); val flow = MutableStateFlow<MotionContextMessage?>(null)
        hub(link, motionContext = flow)
        flow.value = ctxMsg(); runCurrent(); link.sent.clear()
        flow.value = null; runCurrent()
        val m = (Wire.decode<MotionContextMessage>(link.lastOn(Channels.MOTION_CONTEXT)) as Decoded.Ok).value
        assertEquals(ContextState.UNKNOWN, m.state); assertTrue(m.reasons.isEmpty())
    }

    @Test fun hubStopSendsUnknownBeforeTheCollectorGoes() = runTest {
        val link = FakeGlassesLink(); val flow = MutableStateFlow<MotionContextMessage?>(null)
        val h = hub(link, motionContext = flow)
        flow.value = ctxMsg(); runCurrent(); link.sent.clear()
        h.stop()
        val m = (Wire.decode<MotionContextMessage>(link.lastOn(Channels.MOTION_CONTEXT)) as Decoded.Ok).value
        assertEquals(ContextState.UNKNOWN, m.state)
    }

    @Test fun theFallbackRevokeUsesTheCurrentApplySetting() = runTest {
        val link = FakeGlassesLink(); val flow = MutableStateFlow<MotionContextMessage?>(null)
        var apply = true
        hub(link, motionContext = flow, applyToCapture = { apply })
        flow.value = ctxMsg(); runCurrent(); link.sent.clear()
        apply = false
        flow.value = null; runCurrent()
        val m = (Wire.decode<MotionContextMessage>(link.lastOn(Channels.MOTION_CONTEXT)) as Decoded.Ok).value
        assertFalse(m.apply)
    }

    @Test fun motionHintEventReachesTheHubCallback() = runTest {
        val link = FakeGlassesLink(); val got = ArrayList<MotionHint>()
        hub(link, onMotionHint = { got += it })
        val h = MotionHint(MotionState.WALKING, now, stillFrac5m = 0.1f)
        link.emit(GlassesEvent.MotionHintReceived(h)); runCurrent()
        assertEquals(listOf(h), got)
    }

    // Codex v3 r3 P1-1: a hint of another glasses session is never calm evidence (its session is stripped), one of this session or of an old glasses build passes as is
    @Test fun aMotionHintOfAnotherSessionLosesItsSession() = runTest {
        val link = FakeGlassesLink(); val got = ArrayList<MotionHint>()
        hub(link, onMotionHint = { got += it })
        link.emitReady(); runCurrent()
        link.emit(GlassesEvent.MotionHintReceived(MotionHint(MotionState.STILL, 5, stillFrac5m = 0.9f, sessionStartMs = now - 1)))
        link.emit(GlassesEvent.MotionHintReceived(MotionHint(MotionState.STILL, 6, stillFrac5m = 0.9f, sessionStartMs = now)))
        link.emit(GlassesEvent.MotionHintReceived(MotionHint(MotionState.STILL, 7, stillFrac5m = 0.9f)))
        runCurrent()
        assertEquals(listOf(0L, now, 0L), got.map { it.sessionStartMs })
    }

    @Test fun contextLifecycleFollowsHubStartAndStop() = runTest {
        var starts = 0; var stops = 0
        val h = hub(FakeGlassesLink(), ctxStart = { starts++ }, ctxStop = { stops++ })
        assertEquals(1, starts); assertEquals(0, stops)
        h.stop(); assertEquals(1, stops)
    }

    @Test fun startConnectsOnceAndReadyPushesSettingsThenState() = runTest {
        val link = FakeGlassesLink()
        hub(link)
        assertEquals(1, link.connectOnceCalls)
        link.sent.clear()
        link.emitReady(); advanceTimeBy(300) // STATE goes through the broadcaster's 250 ms window
        assertEquals(listOf(Channels.SETTINGS, Channels.STATE), link.sent.map { it.first }.filter { it != Channels.PHONE_CONTEXT }.take(2))
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

    /**
     * Carry (Phase 3 review): Reconnect with the hub service down only starts the service; its hub.start() connects
     * once from Idle, so no second connect is sent from here.
     */
    @Test fun reconnectStartsTheHubServiceWhenTheHubIsNotRunning() = runTest {
        val link = FakeGlassesLink(LinkState.OFFLINE)
        var starts = 0
        hub(link, startService = { starts++ }, start = false).requestReconnect()
        assertEquals(1, starts)
        assertEquals(0, link.connectCalls)
    }

    @Test fun reconnectOnARunningHubConnectsWithoutRestartingIt() = runTest {
        val link = FakeGlassesLink()
        var starts = 0
        hub(link, startService = { starts++ }).requestReconnect()
        assertEquals(0, starts)
        assertEquals(1, link.connectCalls)
    }

    /** Fix wave H: the SDK's wear state is forwarded as pc_wear (on change and again when the session opens). */
    @Test fun wearIsForwardedToTheGlasses() = runTest {
        val link = FakeGlassesLink()
        hub(link)
        link.sent.clear()
        link.wearingFlow.value = false; runCurrent()
        val m = (Wire.decode<com.rokid.context.core.model.WearMessage>(link.lastOn(Channels.WEAR)) as Decoded.Ok).value
        assertEquals(false, m.worn)
        link.sent.clear(); link.emitReady(); advanceTimeBy(300)
        assertTrue("re-sent on ready", link.sent.any { it.first == Channels.WEAR })
    }

    /** v1.0 wear fallback: pc_wear carries the system wear check; the glasses' estimate reaches the hub only while that check is not on. */
    @Test fun wearCheckRidesPcWearAndTheGlassesEstimateIsGated() = runTest {
        val link = FakeGlassesLink()
        val h = hub(link)
        link.setState(LinkState.CONNECTED); runCurrent()
        link.sent.clear()
        link.wearingFlow.value = true; runCurrent()
        assertNull("not read yet", (Wire.decode<com.rokid.context.core.model.WearMessage>(link.lastOn(Channels.WEAR)) as Decoded.Ok).value.wearCheck)
        link.wearCheckFlow.value = false; runCurrent()
        assertEquals("a new read is re-sent at once", false, (Wire.decode<com.rokid.context.core.model.WearMessage>(link.lastOn(Channels.WEAR)) as Decoded.Ok).value.wearCheck)
        link.emit(com.rokid.context.link.GlassesEvent.MotionHintReceived(com.rokid.context.core.model.MotionHint(com.rokid.context.core.model.MotionState.STILL, 1, wearEst = true))); runCurrent()
        assertTrue(h.wearEstimate.active.value)
        link.wearCheckFlow.value = true; runCurrent()
        assertFalse("the real sensor is on: it wins", h.wearEstimate.active.value)
    }

    /** Battery wave A task 7: the phone-in-use flag is forwarded as pc_phone_ctx on change and again when the session opens. */
    @Test fun phoneUseIsForwardedToTheGlasses() = runTest {
        val link = FakeGlassesLink()
        val use = kotlinx.coroutines.flow.MutableStateFlow(false)
        hub(link, phoneUse = use)
        link.sent.clear()
        use.value = true; runCurrent()
        val m = (Wire.decode<com.rokid.context.core.model.PhoneContextMessage>(link.lastOn(Channels.PHONE_CONTEXT)) as Decoded.Ok).value
        assertEquals(true, m.inUse)
        link.sent.clear(); link.emitReady(); advanceTimeBy(300)
        assertTrue("re-sent on ready", link.sent.any { it.first == Channels.PHONE_CONTEXT })
    }

    /** Fix wave H (D): the glasses' pc_user_exit marks the state; an explicit open clears it at once. */
    @Test fun userExitIsRememberedUntilAnExplicitOpen() = runTest {
        val link = FakeGlassesLink()
        val h = hub(link)
        link.emit(GlassesEvent.UserExit); runCurrent()
        assertTrue(h.state.value.closedByUser)
        h.requestReconnect(); runCurrent()
        assertFalse(h.state.value.closedByUser)
    }

    /** Fix wave H4: the glasses' Rokid-closed counters reach the hub state (Diagnostics line), counts only. */
    @Test fun helloCarriesTheRokidClosedCounters() = runTest {
        val link = FakeGlassesLink()
        val h = hub(link)
        assertEquals(null, h.state.value.rokidClosesLine())
        link.emit(GlassesEvent.HelloReceived(Hello(glassesBatteryPct = 77, bufferSize = 0, glassesNowMs = now, pauses = emptyList(), rokidCloses = 5, lastRestartGapSec = 38))); runCurrent()
        assertEquals("Rokid closed the app 5 times (last gap 38s)", h.state.value.rokidClosesLine())
    }

    /** Watchdog: any glasses message stamps the heartbeat, and the watchdog's note reaches the status the banner reads. */
    @Test fun anyGlassesEventStampsTheHeartbeatAndTheWatchdogNoteReachesTheStatus() = runTest {
        val link = FakeGlassesLink()
        val h = hub(link)
        assertNull(h.lastHeardAtMs)
        link.emit(GlassesEvent.HelloReceived(Hello(glassesBatteryPct = 77, bufferSize = 0, glassesNowMs = now, pauses = emptyList()))); runCurrent()
        assertNotNull(h.lastHeardAtMs)
        h.setWatchdogNote("Glasses not connected"); assertEquals("Glasses not connected", h.status.value.note)
        h.setWatchdogNote(null); assertNull(h.status.value.note)
    }

    /** Wave 3: unverified and other force-stops reach the Diagnostics line on the phone. */
    @Test fun helloCarriesTheUnverifiedAndOtherForceStopCounts() = runTest {
        val link = FakeGlassesLink()
        val h = hub(link)
        link.emit(GlassesEvent.HelloReceived(Hello(glassesBatteryPct = 77, bufferSize = 0, glassesNowMs = now, pauses = emptyList(), rokidCloses = 5, lastRestartGapSec = 38, unverifiedCloses = 2, otherCloses = 3))); runCatching { runCurrent() }
        assertEquals("Rokid closed the app 5 times (last gap 38s; 2 unverified; 3 other force-stops)", h.state.value.rokidClosesLine())
        assertEquals(true, h.state.value.glassesDiagnostics()!!.contains("3 other force-stops"))
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

    @Test fun hudSettingsFlowFollowsUpdatesImmediately() = runTest {
        val h = hub(FakeGlassesLink())
        assertEquals(300, h.hudSettings.value.captureIntervalSec)
        h.updateSettings { it.copy(captureIntervalSec = 600) }
        assertEquals(600, h.hudSettings.value.captureIntervalSec)
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

    /** Minimised Option A: the background link carries heartbeats and acks, and keeps the session's clock offset. */
    @Test fun minimisedLinkKeepsHeartbeatingAckingAndTheOffset() = runTest {
        val link = FakeGlassesLink()
        val h = hub(link)
        link.emit(hello(glassesNowMs = now + 7_200_000)); runCurrent()
        link.setState(LinkState.MINIMISED); runCurrent()
        assertEquals("same glasses session: the offset is kept", 7_200_000L, h.state.value.offset!!.glassesMinusPhoneMs)
        assertEquals(LinkState.MINIMISED, h.status.value.link)
        link.sent.clear()
        advanceTimeBy(11_000)
        val frame = (Wire.decode<StateFrame>(link.lastOn(Channels.STATE)) as Decoded.Ok).value
        assertTrue("heartbeat keeps the glasses' liveness check open", frame.phoneLinked)
        link.emit(GlassesEvent.Frame(FrameCodec.split("gm1", ByteArray(2_000) { 3 }, capturedAtMs = now + 7_200_000 - 1_000).single())); runCurrent()
        assertEquals(FrameAck(frameId = "gm1", ok = true), (Wire.decode<FrameAck>(link.lastOn(Channels.FRAME_ACK)) as Decoded.Ok).value)
        assertEquals(now - 1_000, h.status.value.lastFrameAtMs)
    }

    /** Review I2: "Open on glasses" while minimised never connects (appStart); the glasses get a pop-up instead. */
    @Test fun openOnGlassesWhileMinimisedShowsAPopupAndNeverConnects() = runTest {
        val link = FakeGlassesLink(LinkState.MINIMISED)
        val h = hub(link)
        link.sent.clear()
        h.requestReconnect(); runCurrent(); advanceTimeBy(300)
        assertEquals("no CUSTOM_APP connect", 0, link.connectCalls)
        val shown = (Wire.decode<StateFrame>(link.lastOn(Channels.STATE)) as Decoded.Ok).value.overlay
        assertEquals(Overlay.Toast(Hub.OPEN_WHILE_MINIMISED), shown)
        advanceTimeBy(Hub.MINIMISED_HINT_MS + 300)
        assertNull("the pop-up clears", (Wire.decode<StateFrame>(link.lastOn(Channels.STATE)) as Decoded.Ok).value.overlay)
        link.setState(LinkState.CLOSED_ON_GLASSES); runCurrent()
        h.requestReconnect()
        assertEquals("a real exit still reconnects", 1, link.connectCalls)
    }

    private val openGuards = object : RelaunchGuards { override fun callIdle() = true; override fun mediaIdle() = true; override fun worn(): Boolean? = null }

    /** Fix wave I C1: the whole chain on the phone. Icon-mode kill -> silence -> CLOSED_ON_GLASSES -> guarded automatic reopen (8 s, then backoff), told to the glasses as automatic. */
    @Test fun anIconModeKillIsReopenedAutomaticallyAfterTheGuardsAndTheGlassesAreTold() = runTest {
        val link = FakeGlassesLink(LinkState.MINIMISED)
        hub(link, clock = { now + testScheduler.currentTime }, reopenGuards = openGuards)
        link.setState(LinkState.CLOSED_ON_GLASSES); runCurrent()          // the liveness probe went unanswered twice
        advanceTimeBy(7_900); assertEquals(0, link.automaticCalls)
        advanceTimeBy(300); assertEquals("reopened once, as an automatic connect", 1, link.automaticCalls)
        assertEquals("never an explicit connect", 0, link.connectCalls)
        link.setState(LinkState.CONNECTING); link.setState(LinkState.CONNECTED); link.sent.clear()
        link.emitReady(); advanceTimeBy(300)
        assertTrue("the glasses learn this start was automatic", link.sent.any { it.first == Channels.AUTO_OPEN })
        link.sent.clear(); advanceTimeBy(60_000)
        link.emitReady(); advanceTimeBy(300)
        assertTrue("no marker long after the reopen", link.sent.none { it.first == Channels.AUTO_OPEN })
    }

    /** Review hub-wp2 r1 P1: a lost hub's recovery start connects the glasses only through the call guard and the hourly cap; an ordinary start is unchanged. */
    @Test fun aRecoveryStartNeverConnectsDuringACallAndConnectsOnceAfterIt() = runTest {
        val link = FakeGlassesLink(LinkState.OFFLINE)
        var idle = false
        val guards = object : RelaunchGuards { override fun callIdle() = idle; override fun mediaIdle() = true; override fun worn(): Boolean? = null }
        val store = InMemoryRelaunchStore()
        val h = hub(link, clock = { now + testScheduler.currentTime }, reopenGuards = guards, relaunchStore = store, start = false)
        h.start(); runCurrent()
        advanceTimeBy(120_000)
        assertEquals("no connect of any kind during a call", 0, link.connectOnceCalls + link.automaticCalls + link.connectCalls)
        idle = true; advanceTimeBy(10_100)
        assertEquals("connects once the call is over", 1, link.automaticCalls); assertEquals(0, link.connectOnceCalls)
        assertEquals("and the connect counts against the hourly cap", 1, store.attempts().size)
    }

    /** Review hub-wp2 r4 P2-4: the production wiring: the link reports the end of an automatic open to the relauncher (its owner), which retries after the 5 min back-off. */
    @Test fun aFailedAutomaticOpenReportedByTheLinkIsRetriedByTheRelauncher() = runTest {
        val link = FakeGlassesLink(LinkState.OFFLINE)
        val guards = object : RelaunchGuards { override fun callIdle() = true; override fun mediaIdle() = true; override fun worn(): Boolean? = null }
        val h = hub(link, clock = { now + testScheduler.currentTime }, reopenGuards = guards, relaunchStore = InMemoryRelaunchStore(), start = false)
        h.start(); runCurrent(); advanceTimeBy(100)
        assertEquals(1, link.automaticCalls)
        link.failAutomatic() // the open failed synchronously: OFFLINE stayed OFFLINE, so only the explicit result says so
        advanceTimeBy(299_000); assertEquals(1, link.automaticCalls)
        advanceTimeBy(2_000); assertEquals("retried after five minutes", 2, link.automaticCalls)
    }

    @Test fun aRecoveryStartWithTheHourlyQuotaSpentDoesNotConnect() = runTest {
        val link = FakeGlassesLink(LinkState.OFFLINE)
        val guards = object : RelaunchGuards { override fun callIdle() = true; override fun mediaIdle() = true; override fun worn(): Boolean? = null }
        val store = InMemoryRelaunchStore().apply { saveAttempts(List(6) { now - 1 }) }
        val h = hub(link, clock = { now + testScheduler.currentTime }, reopenGuards = guards, relaunchStore = store, start = false)
        h.start(); runCurrent(); advanceTimeBy(30 * 60_000L)
        assertEquals("six reopens in the hour: no seventh", 0, link.automaticCalls + link.connectOnceCalls)
    }

    /** Review hub-wp2 r2 P1-1/P3: the production start of a sticky restart (null intent) is a recovery start: no connect during a call. */
    @Test fun aStickyRestartWithNullIntentNeverConnectsDuringACall() = runTest {
        val link = FakeGlassesLink(LinkState.OFFLINE)
        var idle = false
        val guards = object : RelaunchGuards { override fun callIdle() = idle; override fun mediaIdle() = true; override fun worn(): Boolean? = null }
        val h = hub(link, clock = { now + testScheduler.currentTime }, reopenGuards = guards, start = false)
        h.start(userInitiated = ContextHubService.isUserStart(null)); runCurrent(); advanceTimeBy(120_000)
        assertEquals("no connect of any kind during a call", 0, link.connectOnceCalls + link.automaticCalls + link.connectCalls + link.resumeCalls)
        idle = true; advanceTimeBy(10_100)
        assertEquals(1, link.automaticCalls); assertEquals(0, link.connectOnceCalls)
    }

    /** Review hub-wp2 r2 P1-1: the watchdog's own reconnect (link OFFLINE, hub up) goes through the call guard and the hourly quota. */
    @Test fun theWatchdogReconnectNeverBypassesTheCallGuardOrTheQuota() = runTest {
        val link = FakeGlassesLink(LinkState.OFFLINE)
        var idle = false
        val guards = object : RelaunchGuards { override fun callIdle() = idle; override fun mediaIdle() = true; override fun worn(): Boolean? = null }
        val store = InMemoryRelaunchStore().apply { saveAttempts(List(6) { now - 1 }) }
        val h = hub(link, clock = { now + testScheduler.currentTime }, reopenGuards = guards, relaunchStore = store, start = false)
        h.watchdogReconnect(); runCurrent()
        assertEquals("a call and all six attempts spent", 0, link.automaticCalls + link.connectOnceCalls)
        idle = true; h.watchdogReconnect(); runCurrent()
        assertEquals("the quota still blocks after the call", 0, link.automaticCalls)
        val free = FakeGlassesLink(LinkState.OFFLINE); val fresh = InMemoryRelaunchStore(); idle = false
        val h2 = hub(free, clock = { now + testScheduler.currentTime }, reopenGuards = guards, relaunchStore = fresh, start = false)
        h2.watchdogReconnect(); runCurrent(); assertEquals("a call: no reconnect", 0, free.automaticCalls)
        idle = true; h2.watchdogReconnect(); runCurrent()
        assertEquals("after the call and its settle: one reconnect", 1, free.automaticCalls); assertEquals("counted against the quota", 1, fresh.attempts().size)
    }

    /** Review hub-wp2 r3 P1-1: an AutoStop wake start (wake, not forced) is guarded by default: no connect during a call, one automatic connect after it. */
    @Test fun anAutoStopWakeStartNeverConnectsDuringACall() = runTest {
        val link = FakeGlassesLink(LinkState.OFFLINE)
        var idle = false
        val guards = object : RelaunchGuards { override fun callIdle() = idle; override fun mediaIdle() = true; override fun worn(): Boolean? = null }
        val h = hub(link, clock = { now + testScheduler.currentTime }, reopenGuards = guards, start = false)
        val wake = android.content.Intent(ctx, ContextHubService::class.java).putExtra("com.rokid.context.hub.WAKE", true)
        h.start(userInitiated = ContextHubService.isUserStart(wake)); runCurrent(); advanceTimeBy(120_000)
        assertEquals("no connect of any kind during a call", 0, link.connectOnceCalls + link.automaticCalls + link.connectCalls + link.resumeCalls)
        idle = true; advanceTimeBy(10_100)
        assertEquals(1, link.automaticCalls); assertEquals(0, link.connectOnceCalls)
    }

    /** Review hub-wp2 r3 P2-5: a BT-paused link makes the watchdog reconnect a no-op that must not spend the last quota slot. */
    @Test fun aPausedBtWatchdogTickConsumesNoQuota() = runTest {
        val link = FakeGlassesLink(LinkState.PAUSED_BT)
        val guards = object : RelaunchGuards { override fun callIdle() = true; override fun mediaIdle() = true; override fun worn(): Boolean? = null }
        val store = InMemoryRelaunchStore().apply { saveAttempts(List(5) { now - 1 }) }
        val h = hub(link, clock = { now + testScheduler.currentTime }, reopenGuards = guards, relaunchStore = store, start = false)
        repeat(3) { h.watchdogReconnect(); runCurrent() }
        assertEquals("nothing opened", 0, link.automaticCalls + link.resumeCalls); assertEquals("no slot spent", 5, store.attempts().size)
        link.setState(LinkState.OFFLINE); h.watchdogReconnect(); runCurrent()
        assertEquals("the OFFLINE recovery still has its slot", 1, link.automaticCalls); assertEquals(6, store.attempts().size)
    }

    /** Review hub-wp2 r3 P1-2: the watchdog's probe of a minimised app never opens the app itself; a wearer's tap still does. */
    @Test fun theWatchdogProbeNeverOpensTheApp() = runTest {
        val link = FakeGlassesLink(LinkState.MINIMISED)
        val h = hub(link)
        h.watchdogProbe(); assertEquals(1, link.probeCalls); assertEquals(0, link.probeOpenCalls)
        h.requestReconnect(); runCurrent(); assertEquals(1, link.probeOpenCalls)
    }

    @Test fun aUserStartStillConnectsAtOnce() = runTest {
        val link = FakeGlassesLink(LinkState.OFFLINE)
        val guards = object : RelaunchGuards { override fun callIdle() = false; override fun mediaIdle() = true; override fun worn(): Boolean? = null }
        hub(link, reopenGuards = guards)
        assertEquals(1, link.connectOnceCalls)
    }

    /** Fix wave I C1: "Open on glasses" while minimised checks that the glasses are alive (the link opens the app if they are dead). */
    @Test fun openOnGlassesWhileMinimisedProbesTheGlasses() = runTest {
        val link = FakeGlassesLink(LinkState.MINIMISED)
        val h = hub(link)
        h.requestReconnect(); runCurrent()
        assertEquals(1, link.probeCalls); assertEquals(0, link.connectCalls)
    }

    /** Fix wave I1: a worn=false from before a link loss never comes back; the glasses are never sent a value the phone dropped. */
    @Test fun aStaleNotWornNeverReturnsAfterAReconnect() = runTest {
        val link = FakeGlassesLink()
        hub(link)
        link.wearingFlow.value = false; runCurrent()
        link.setState(LinkState.OFFLINE); link.wearingFlow.value = null; runCurrent() // BT lost: the link drops it
        link.setState(LinkState.CONNECTED); link.sent.clear()
        link.emitReady(); advanceTimeBy(300)
        assertTrue("nothing to say about wear", link.sent.none { it.first == Channels.WEAR })
        link.emit(hello()); runCurrent()
        assertTrue(link.sent.none { it.first == Channels.WEAR })
        link.wearingFlow.value = true; runCurrent()
        assertEquals(true, (Wire.decode<com.rokid.context.core.model.WearMessage>(link.lastOn(Channels.WEAR)) as Decoded.Ok).value.worn)
    }

    @Test fun aWearMessageNeverPredatesItsSession() = runTest {
        var t = now
        val link = FakeGlassesLink()
        hub(link, clock = { t })
        link.wearingFlow.value = true; runCurrent()
        t += 5_000; link.sent.clear(); link.emitReady(); advanceTimeBy(300)
        val m = (Wire.decode<com.rokid.context.core.model.WearMessage>(link.lastOn(Channels.WEAR)) as Decoded.Ok).value
        assertTrue("the value is current for this session", m.atMs >= m.sessionStartMs && m.sessionStartMs == now + 5_000)
    }

    /** Fix wave I2: the stand-down is persisted: a restarted phone neither connects nor resumes into an app the wearer exited. */
    @Test fun aPhoneRestartNeverReopensWhatTheWearerExited() = runTest {
        val prefs = ctx.getSharedPreferences("stand_down_${tmp.root.name}", 0)
        val first = FakeGlassesLink()
        hub(first, standDown = PrefsStandDown(prefs)).also { first.emit(GlassesEvent.UserExit); runCurrent() }
        val second = FakeGlassesLink(LinkState.OFFLINE)
        val h2 = hub(second, standDown = PrefsStandDown(prefs))
        assertEquals("no connectOnce after a restart", 0, second.connectOnceCalls)
        assertEquals("but the wearer's own reopen is still watched for (N4)", 1, second.watchCalls)
        assertTrue(h2.state.value.closedByUser)
        h2.requestReconnect(); runCurrent()
        assertFalse(PrefsStandDown(prefs).active)
        val third = FakeGlassesLink(LinkState.OFFLINE)
        hub(third, standDown = PrefsStandDown(prefs))
        assertEquals("an explicit open ended it", 1, third.connectOnceCalls)
    }

    /** Fix wave I2: a lost pc_user_exit is repeated in the next hello, but an old stamp or one before an explicit open is not news. */
    @Test fun aHelloRepeatsTheWearerExitOnlyWhenItIsRecentAndNotAnsweredByAnOpen() = runTest {
        var t = now
        val sd = InMemoryStandDown()
        val link = FakeGlassesLink()
        val h = hub(link, clock = { t }, standDown = sd)
        fun hello(exitAt: Long) = GlassesEvent.HelloReceived(Hello(glassesBatteryPct = 1, bufferSize = 0, glassesNowMs = t, pauses = emptyList(), userExitAtMs = exitAt))
        t += 30_000
        link.emit(hello(t - 25_000)); runCurrent() // the exit message was lost with the session; the session connected 30 s ago
        assertTrue(sd.active); assertTrue(h.state.value.closedByUser)
        h.requestReconnect(); runCurrent(); assertFalse(sd.active)
        t += 1_000
        link.emit(hello(t - 20_000)); runCurrent()
        assertFalse("that exit is older than the explicit open", sd.active)
        t += 5_000
        link.emit(hello(t - Hub.EXIT_STAMP_FRESH_MS - 1)); runCurrent()
        assertFalse("an old stamp is not news", sd.active)
        link.emit(hello(t - 2_000)); runCurrent()
        assertTrue("a stamp newer than the explicit open counts", sd.active)
    }

    /** Fix wave I N4: a plain hello (no fresh exit) during a stand-down means the wearer reopened the app: the stand-down ends. */
    @Test fun aHelloFromARunningAppEndsTheStandDown() = runTest {
        val sd = InMemoryStandDown().also { it.enter(0) }
        val link = FakeGlassesLink(LinkState.OFFLINE)
        val h = hub(link, standDown = sd)
        assertTrue(sd.active)
        link.emit(hello()); runCurrent()
        assertFalse(sd.active); assertFalse(h.state.value.closedByUser)
    }

    @Test fun theIconBlockedRowGoesWhenTheLinkIsLost() = runTest {
        val link = FakeGlassesLink()
        val h = hub(link)
        link.emit(GlassesEvent.HelloReceived(Hello(glassesBatteryPct = 1, bufferSize = 0, glassesNowMs = now, pauses = emptyList(), iconBlocked = true))); runCurrent()
        assertTrue(h.state.value.iconBlocked)
        link.setState(LinkState.OFFLINE); runCurrent()
        assertFalse(h.state.value.iconBlocked)
    }

    @Test fun anIconlessMinimiseFromTheGlassesShowsADiagnosticsLine() = runTest {
        val link = FakeGlassesLink()
        val h = hub(link)
        assertEquals(null, h.state.value.glassesDiagnostics())
        link.emit(GlassesEvent.HelloReceived(Hello(glassesBatteryPct = 1, bufferSize = 0, glassesNowMs = now, pauses = emptyList(), iconBlocked = true))); runCurrent()
        assertEquals(com.rokid.context.phone.hub.ICON_BLOCKED_LINE, h.state.value.glassesDiagnostics())
    }

    /** Hand-back: a frame acked on the background link and resent on the new session is acked again, never ingested twice. */
    @Test fun handBackFromMinimisedNeverIngestsAFrameTwice() = runTest {
        var stored = 0
        val link = FakeGlassesLink(LinkState.MINIMISED)
        val h = hub(link, onStored = { stored++ })
        link.emit(hello()); runCurrent()
        val c = FrameCodec.split("gm2", ByteArray(2_000) { 4 }, capturedAtMs = now - 2_000).single()
        link.emit(GlassesEvent.Frame(c)); runCurrent()
        assertEquals(1, stored)
        link.setState(LinkState.CONNECTING); runCurrent()
        link.setState(LinkState.CONNECTED); link.emitReady(); runCurrent()
        link.emit(hello()); runCurrent()
        link.emit(GlassesEvent.Frame(c)); runCurrent()
        assertEquals(FrameAck(frameId = "gm2", ok = true, reason = "duplicate"), (Wire.decode<FrameAck>(link.lastOn(Channels.FRAME_ACK)) as Decoded.Ok).value)
        assertEquals("exactly once", 1, stored)
        assertEquals(now - 2_000, h.status.value.lastFrameAtMs)
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

    private class FakeActions : HubActions {
        override val overlay = MutableStateFlow<Overlay?>(null)
        val calls = mutableListOf<String>()
        override fun onConfirm(proposalId: String, yes: Boolean) { calls += "confirm:$proposalId:$yes" }
        override fun onUndo(proposalId: String) { calls += "undo:$proposalId" }
        val shown = mutableListOf<Overlay?>()
        override fun onShown(overlay: Overlay?) { shown += overlay }
    }

    private class FakeNudges : HubNudges {
        override val latest = MutableStateFlow<NudgeLine?>(null)
        override val overlay = MutableStateFlow<Overlay.Nudge?>(null)
    }

    private fun FakeGlassesLink.lastFrame() = (Wire.decode<StateFrame>(lastOn(Channels.STATE)) as Decoded.Ok).value

    @Test fun confirmAndUndoCommandsReachActionsPort() = runTest {
        val link = FakeGlassesLink()
        val actions = FakeActions()
        hub(link, actions = actions)
        link.emit(GlassesEvent.Cmd(Command.Confirm("p", yes = true))); runCurrent()
        link.emit(GlassesEvent.Cmd(Command.Undo("p"))); runCurrent()
        assertEquals(listOf("confirm:p:true", "undo:p"), actions.calls)
    }

    @Test fun overlayPrecedence() = runTest {
        val link = FakeGlassesLink()
        val voice = FakeHubVoice()
        val actions = FakeActions()
        val nudges = FakeNudges()
        hub(link, voice = voice, actions = actions, nudges = nudges)
        val confirm = Overlay.Confirm("p", "Add milk?", "Shopping list")
        voice.overlay.value = Overlay.Listening("what"); actions.overlay.value = confirm; advanceTimeBy(300)
        assertEquals(Overlay.Listening("what"), link.lastFrame().overlay)
        voice.overlay.value = Overlay.Answer("q", "a", speaking = false); advanceTimeBy(300)
        assertEquals(confirm, link.lastFrame().overlay)
        voice.overlay.value = null; actions.overlay.value = null; nudges.overlay.value = Overlay.Nudge("Heads up"); advanceTimeBy(300)
        assertEquals(Overlay.Nudge("Heads up"), link.lastFrame().overlay)
        nudges.latest.value = NudgeLine("Milk low", now); advanceTimeBy(300)
        assertEquals(NudgeLine("Milk low", now), link.lastFrame().latestNudge)
    }

    private val placeCard = com.rokid.context.work.frames.place.PlaceCard("p1", "Charminar", "A 16th-century mosque and monument.", "Wikipedia · fetched 12 Nov", com.rokid.context.work.frames.place.PlaceConfidence.PHOTO, "f1")

    /** Place Guide PG3: a card re-broadcasts at once as a Nudge carrying the place, "title: line" as its text for an older glasses build. */
    @Test fun aPlaceCardIsBroadcastWhenItArrives() = runTest {
        val link = FakeGlassesLink(); val cards = MutableStateFlow<com.rokid.context.work.frames.place.PlaceCard?>(null)
        hub(link, placeCards = cards); link.emitReady(); runCurrent(); advanceTimeBy(300)
        link.sent.clear()
        cards.value = placeCard; advanceTimeBy(300)
        val o = link.lastFrame().overlay as Overlay.Nudge
        assertEquals("Charminar: A 16th-century mosque and monument.", o.text)
        assertEquals(com.rokid.context.core.model.PlaceCardInfo("Charminar", "A 16th-century mosque and monument.", "Wikipedia · fetched 12 Nov", nearby = false), o.place)
    }

    @Test fun aNearbyCardIsMarkedNearby() = runTest {
        val link = FakeGlassesLink(); val cards = MutableStateFlow<com.rokid.context.work.frames.place.PlaceCard?>(null)
        hub(link, placeCards = cards); link.emitReady(); runCurrent()
        cards.value = com.rokid.context.work.frames.place.PlaceCard("p2", "Mecca Masjid", "40 m away", com.rokid.context.work.frames.place.PlaceCards.NEARBY_LABEL, com.rokid.context.work.frames.place.PlaceConfidence.NEARBY, "f2")
        advanceTimeBy(300)
        val p = (link.lastFrame().overlay as Overlay.Nudge).place!!
        assertTrue(p.nearby); assertEquals("nearby · not checked", p.source)
    }

    @Test fun theCardIsClearedAfterTwelveSecondsAndTheGlassesAreTold() = runTest {
        val link = FakeGlassesLink(); val cards = MutableStateFlow<com.rokid.context.work.frames.place.PlaceCard?>(null)
        hub(link, placeCards = cards, placeClear = { cards.value = null }); link.emitReady(); runCurrent()
        cards.value = placeCard; advanceTimeBy(300)
        advanceTimeBy(11_000); assertNotNull(link.lastFrame().overlay)
        advanceTimeBy(1_500); assertNull(cards.value); assertNull(link.lastFrame().overlay)
    }

    @Test fun aGlassesDismissClearsOnlyTheMatchingCard() = runTest {
        val link = FakeGlassesLink(); val cards = MutableStateFlow<com.rokid.context.work.frames.place.PlaceCard?>(null); var cleared = 0
        hub(link, placeCards = cards, placeClear = { cleared++; cards.value = null }); link.emitReady(); runCurrent()
        cards.value = placeCard; advanceTimeBy(300)
        link.emit(GlassesEvent.Cmd(Command.DismissPlace("Golconda"))); runCurrent()
        assertEquals("a stale dismiss of another card is ignored", 0, cleared)
        link.emit(GlassesEvent.Cmd(Command.DismissPlace("Charminar"))); runCurrent()
        assertEquals(1, cleared); assertNull(cards.value)
    }

    @Test fun aDelayedDismissOfAnOldCardNeverClearsANewerCardWithTheSameTitle() = runTest {
        val link = FakeGlassesLink(); val cards = MutableStateFlow<com.rokid.context.work.frames.place.PlaceCard?>(null)
        hub(link, placeCards = cards, placeClear = { cards.value = null }); link.emitReady(); runCurrent()
        cards.value = placeCard.copy(cardId = "pc1"); advanceTimeBy(300)
        cards.value = placeCard.copy(cardId = "pc2", frameId = "f2"); advanceTimeBy(300) // same title, a new instance
        assertEquals("pc2", (link.lastFrame().overlay as Overlay.Nudge).place!!.id)
        link.emit(GlassesEvent.Cmd(Command.DismissPlace("Charminar", "pc1"))); runCurrent()
        assertEquals("the old instance's dismissal is ignored", "pc2", cards.value?.cardId)
        link.emit(GlassesEvent.Cmd(Command.DismissPlace("Charminar", "pc2"))); runCurrent()
        assertNull(cards.value)
    }

    @Test fun anOlderGlassesDismissWithoutAnIdStillMatchesByTitle() = runTest {
        val link = FakeGlassesLink(); val cards = MutableStateFlow<com.rokid.context.work.frames.place.PlaceCard?>(null)
        hub(link, placeCards = cards, placeClear = { cards.value = null }); link.emitReady(); runCurrent()
        cards.value = placeCard.copy(cardId = "pc1"); advanceTimeBy(300)
        link.emit(GlassesEvent.Cmd(Command.DismissPlace("Charminar"))); runCurrent()
        assertNull(cards.value)
    }

    @Test fun aCardThatAlreadyExistsWhenTheHubStartsStillExpires() = runTest {
        val link = FakeGlassesLink(); val cards = MutableStateFlow<com.rokid.context.work.frames.place.PlaceCard?>(placeCard.copy(cardId = "pc1"))
        hub(link, placeCards = cards, placeClear = { cards.value = null }); link.emitReady(); runCurrent()
        advanceTimeBy(11_000); assertNotNull(cards.value)
        advanceTimeBy(1_500); assertNull("the restart did not skip its expiry", cards.value)
    }

    @Test fun aNewCardRestartsTheTwelveSeconds() = runTest {
        val link = FakeGlassesLink(); val cards = MutableStateFlow<com.rokid.context.work.frames.place.PlaceCard?>(null)
        hub(link, placeCards = cards, placeClear = { cards.value = null }); link.emitReady(); runCurrent()
        cards.value = placeCard; advanceTimeBy(8_000)
        cards.value = placeCard.copy(placeId = "p3", title = "Golconda"); advanceTimeBy(8_000)
        assertEquals("Golconda", (link.lastFrame().overlay as Overlay.Nudge).place!!.title)
        advanceTimeBy(4_500); assertNull(cards.value)
    }

    @Test fun noPlaceCardOnTheWireDuringAZone() = runTest {
        val link = FakeGlassesLink(); val cards = MutableStateFlow<com.rokid.context.work.frames.place.PlaceCard?>(null)
        hub(link, placeCards = cards, zoneApproach = { true }); link.emitReady(); runCurrent()
        cards.value = placeCard; advanceTimeBy(300)
        assertNull(link.lastFrame().overlay)
    }

    // ---- v3.1: the suggestion card rides the SAME additive overlay as a place card (PROTOCOL_VERSION stays 1) ----

    private val sgCard = com.rokid.context.orchestration.proactive.DeliveredCard("sg_1", "Golconda", "last visit 12 Nov.", "from 2 items \u00b7 12 Nov", setOf("m1"))
    private fun kotlinx.coroutines.test.TestScope.suggestionHub(link: FakeGlassesLink, holder: SuggestionHudHolder, dismissed: MutableList<String> = ArrayList(), zone: () -> Boolean = { false }, host: () -> com.rokid.context.core.model.HudHost = { com.rokid.context.core.model.HudHost.PCA }) =
        hub(link, clock = { now + testScheduler.currentTime }, suggestionCards = holder.card, suggestionClearIf = holder::clearIf, onSuggestionDismissed = { dismissed += it }, zoneApproach = zone, hudHost = host)

    @Test fun aSuggestionCardIsBroadcastAsAPlaceShapedNudgeWithASuggestionSource() = runTest {
        val link = FakeGlassesLink(); val holder = SuggestionHudHolder { now + testScheduler.currentTime }
        suggestionHub(link, holder); link.emitReady(); runCurrent(); advanceTimeBy(300); link.sent.clear()
        holder.show(sgCard); advanceTimeBy(300)
        val o = link.lastFrame().overlay as Overlay.Nudge
        assertEquals("Golconda: last visit 12 Nov.", o.text) // an older glasses build shows this text
        assertEquals(com.rokid.context.core.model.PlaceCardInfo("Golconda", "last visit 12 Nov.", "Suggestion \u00b7 from 2 items \u00b7 12 Nov", nearby = false, id = "sg_1"), o.place)
        assertEquals(1, link.lastFrame().v)
    }

    @Test fun aSuggestionCardNearTheQuietBoundaryCarriesAGlassesCapAndTheHubClearsItThen() = runTest {
        val link = FakeGlassesLink(); val holder = SuggestionHudHolder { now + testScheduler.currentTime }; val dismissed = ArrayList<String>()
        suggestionHub(link, holder, dismissed); link.emitReady(); runCurrent(); advanceTimeBy(300); link.sent.clear()
        holder.show(sgCard.copy(quietInMs = 8_000)); advanceTimeBy(300)
        val o = link.lastFrame().overlay as Overlay.Nudge
        assertEquals("5 s: the boundary minus the 3 s margin", 5_000L, o.place!!.maxShowMs); assertEquals(1, link.lastFrame().v)
        advanceTimeBy(5_500); assertNull("the phone clears at the cap too", holder.card.value); assertTrue(dismissed.isEmpty())
        holder.show(sgCard.copy(quietInMs = 1_000)); advanceTimeBy(300); assertEquals("inside the margin: 0 = the glasses draw nothing", 0L, (link.lastFrame().overlay as? Overlay.Nudge)?.place?.maxShowMs ?: 0L)
        holder.show(sgCard.copy(quietInMs = 3_600_000)); advanceTimeBy(300); assertNull("far from the boundary: no field", (link.lastFrame().overlay as Overlay.Nudge).place!!.maxShowMs)
    }

    @Test fun aSuggestionCardExpiresAfterTwelveSecondsWithoutFeedback() = runTest {
        val link = FakeGlassesLink(); val holder = SuggestionHudHolder { now + testScheduler.currentTime }; val dismissed = ArrayList<String>()
        suggestionHub(link, holder, dismissed); link.emitReady(); runCurrent()
        holder.show(sgCard); advanceTimeBy(11_000); assertNotNull(link.lastFrame().overlay)
        advanceTimeBy(1_500); assertNull(holder.card.value); assertNull(link.lastFrame().overlay); assertTrue("expiry is not a dismissal", dismissed.isEmpty())
    }

    /** Codex v3.1 r3 P1-2: driving closing or the quiet-hours boundary withdraws the displayed card from the glasses overlay (overlay = null), Nexus and the feed's close event, with no Home collecting. */
    private fun withdrawalWiring(holder: SuggestionHudHolder, nexusAll: IntArray, body: () -> Unit) {
        val savedS = com.rokid.context.orchestration.proactive.ProactiveEnvironment.surfaces; val savedD = com.rokid.context.orchestration.proactive.SuggestionGateEvents.dispatch
        com.rokid.context.orchestration.proactive.ProactiveEnvironment.surfaces = PhoneSuggestionSurfaces(holder, { false }, {}, { nexusAll[0]++ })
        com.rokid.context.orchestration.proactive.SuggestionGateEvents.dispatch = { it() }
        com.rokid.context.orchestration.proactive.SuggestionWithdrawal.install()
        try { body() } finally {
            com.rokid.context.orchestration.proactive.SuggestionWithdrawal.uninstall(); com.rokid.context.orchestration.proactive.SuggestionGateEvents.dispatch = savedD
            com.rokid.context.orchestration.proactive.ProactiveEnvironment.surfaces = savedS
        }
    }

    @Test fun drivingStartingWithdrawsAPublishedSuggestionFromTheGlassesNexusAndTheFeed() = runTest {
        val link = FakeGlassesLink(); val holder = SuggestionHudHolder { now + testScheduler.currentTime }; val nexusAll = intArrayOf(0)
        suggestionHub(link, holder); link.emitReady(); runCurrent(); advanceTimeBy(300)
        holder.show(sgCard); advanceTimeBy(300); assertNotNull(link.lastFrame().overlay)
        withdrawalWiring(holder, nexusAll) {
            var t = 10_000_000_000L; val tracker = com.rokid.context.orchestration.proactive.DrivingTracker { t }
            repeat(70) { tracker.heartbeat(false); t += 10_000 }
            tracker.calm(com.rokid.context.orchestration.proactive.CalmSource.PHONE_STEPS, com.rokid.context.orchestration.proactive.Calm.WALKING); tracker.calm(com.rokid.context.orchestration.proactive.CalmSource.PHONE_ACTIVITY, com.rokid.context.orchestration.proactive.Calm.STILL)
            val closes = com.rokid.context.orchestration.proactive.SuggestionGateEvents.closes.value
            tracker.vehicle(0)
            assertEquals("Nexus: withdraw-all", 1, nexusAll[0]); assertNull("HUD holder cleared", holder.card.value)
            assertTrue("the Home feed re-derives", com.rokid.context.orchestration.proactive.SuggestionGateEvents.closes.value > closes)
        }
        advanceTimeBy(300); assertNull("the glasses overlay is gone (overlay = null)", link.lastFrame().overlay)
    }

    @Test fun theQuietHoursBoundaryWithdrawsAPublishedSuggestionFromEverySurface() = runTest {
        val link = FakeGlassesLink(); val holder = SuggestionHudHolder { now + testScheduler.currentTime }; val nexusAll = intArrayOf(0)
        suggestionHub(link, holder); link.emitReady(); runCurrent(); advanceTimeBy(300)
        holder.show(sgCard); advanceTimeBy(300); assertNotNull(link.lastFrame().overlay)
        withdrawalWiring(holder, nexusAll) {
            var task: (() -> Unit)? = null
            val watch = com.rokid.context.orchestration.proactive.QuietBoundaryWatch({ _, tk -> task = tk; AutoCloseable { } })
            val zone = java.time.ZoneId.of("UTC")
            watch.arm(com.rokid.context.orchestration.nudge.NudgeHud(com.rokid.context.core.model.QuietHours(22 * 60, 7 * 60), com.rokid.context.core.model.Verbosity.MEDIUM, zone), java.time.LocalDateTime.of(2026, 11, 18, 21, 59).atZone(zone).toInstant().toEpochMilli())
            assertNotNull("armed while a card is displayed", task); task!!.invoke() // 22:00 crossed
            assertEquals(1, nexusAll[0]); assertNull(holder.card.value)
        }
        advanceTimeBy(300); assertNull(link.lastFrame().overlay)
    }

    @Test fun aHeadMotionDismissalIsTheWearersDismissalAndTheGlassesOwnExpiryIsNot() = runTest {
        val link = FakeGlassesLink(); val holder = SuggestionHudHolder { now + testScheduler.currentTime }; val dismissed = ArrayList<String>()
        suggestionHub(link, holder, dismissed); link.emitReady(); runCurrent()
        holder.show(sgCard); advanceTimeBy(4_000)
        link.emit(GlassesEvent.Cmd(Command.DismissPlace("Golconda", "sg_1"))); runCurrent()
        assertNull(holder.card.value); assertEquals(listOf("sg_1"), dismissed)
        holder.show(sgCard.copy(id = "sg_2")); advanceTimeBy(11_800)
        link.emit(GlassesEvent.Cmd(Command.DismissPlace("Golconda", "sg_2"))); runCurrent()
        assertEquals("a dismissal at the 12 s mark is the expiry", listOf("sg_1"), dismissed)
        link.emit(GlassesEvent.Cmd(Command.DismissPlace("Golconda", "sg_1"))); runCurrent()
        assertEquals("a repeated dismissal of a card that is gone does nothing", listOf("sg_1"), dismissed)
    }

    @Test fun noSuggestionCardOnTheWireDuringAZoneOrWhileNexusDraws() = runTest {
        val link = FakeGlassesLink(); val holder = SuggestionHudHolder { now + testScheduler.currentTime }; var zone = true
        suggestionHub(link, holder, zone = { zone }); link.emitReady(); runCurrent()
        holder.show(sgCard); advanceTimeBy(300); assertNull(link.lastFrame().overlay)
        zone = false; holder.clear(); holder.show(sgCard); advanceTimeBy(300); assertNotNull(link.lastFrame().overlay)
        val link2 = FakeGlassesLink(); val h2 = SuggestionHudHolder { now + testScheduler.currentTime }
        suggestionHub(link2, h2, host = { com.rokid.context.core.model.HudHost.NEXUS }); link2.emitReady(); runCurrent()
        h2.show(sgCard); advanceTimeBy(300); assertNull("Nexus draws it, never the PCA glasses as well", link2.lastFrame().overlay)
    }

    @Test fun aPlaceCardWinsOverASuggestionCardAndAWithdrawalClearsTheSuggestionAtOnce() = runTest {
        val link = FakeGlassesLink(); val holder = SuggestionHudHolder { now + testScheduler.currentTime }; val cards = MutableStateFlow<com.rokid.context.work.frames.place.PlaceCard?>(null)
        hub(link, placeCards = cards, suggestionCards = holder.card, suggestionClearIf = holder::clearIf); link.emitReady(); runCurrent()
        holder.show(sgCard); advanceTimeBy(300); assertEquals("sg_1", (link.lastFrame().overlay as Overlay.Nudge).place!!.id)
        cards.value = placeCard; advanceTimeBy(300); assertEquals("Charminar", (link.lastFrame().overlay as Overlay.Nudge).place!!.title)
        cards.value = null; advanceTimeBy(300); assertEquals("sg_1", (link.lastFrame().overlay as Overlay.Nudge).place!!.id)
        holder.clearIf("sg_1"); advanceTimeBy(300); assertNull(link.lastFrame().overlay)
    }

    /** Codex z1 r1 P1-3: the final zone filter of the answer overlay: an Answer already queued is never framed while a zone is approached or entered, and a zone that starts withdraws it from the next frame. */
    @Test fun anAnswerOverlayIsNeverFramedInAZoneAndAZoneStartingWithdrawsIt() = runTest {
        val link = FakeGlassesLink(); val voice = FakeHubVoice(); var zone = false
        val h = hub(link, voice = voice, zoneApproach = { zone }); link.emitReady(); runCurrent()
        voice.overlay.value = Overlay.Answer("q", "Milk.", speaking = false, askId = "ask_1"); advanceTimeBy(300)
        assertEquals("ask_1", (link.lastFrame().overlay as Overlay.Answer).askId)
        zone = true; h.refresh(); advanceTimeBy(300)
        assertNull("the queued answer is withdrawn from the next frame", link.lastFrame().overlay)
        voice.overlay.value = Overlay.Answer("q2", "Eggs.", speaking = false, askId = "ask_2"); advanceTimeBy(300)
        assertNull("a new answer is never framed in a zone", link.lastFrame().overlay)
        voice.overlay.value = Overlay.Listening("x"); advanceTimeBy(300)
        assertEquals("only an answer (stored text) is filtered", Overlay.Listening("x"), link.lastFrame().overlay)
    }

    @Test fun aVoiceOverlayBeatsAPlaceCard() = runTest {
        val link = FakeGlassesLink(); val voice = FakeHubVoice(); val cards = MutableStateFlow<com.rokid.context.work.frames.place.PlaceCard?>(null)
        hub(link, voice = voice, placeCards = cards); link.emitReady(); runCurrent()
        cards.value = placeCard; voice.overlay.value = Overlay.Listening("x"); advanceTimeBy(300)
        assertEquals(Overlay.Listening("x"), link.lastFrame().overlay)
    }

    /**
     * rc1 (2026-11-13): a Forget that redacted an ask AFTER the phone's 30 s hold (voice already Idle) still reaches the glasses: the next frame goes out at once
     * and names the ask in `withdrawnAskIds` for a minute; a voice Answer of a withdrawn ask is never framed, even before the voice side clears it.
     */
    @Test fun aForgetAfterTheHoldNamesTheWithdrawnAskInTheNextFrames() = runTest {
        val link = FakeGlassesLink()
        val voice = FakeHubVoice()
        var t = now
        val h = hub(link, clock = { t }, voice = voice)
        voice.overlay.value = Overlay.Answer("where did I keep my shoes", "On the floor.", speaking = false, askId = "ask_1"); advanceTimeBy(300)
        assertEquals("ask_1", (link.lastFrame().overlay as Overlay.Answer).askId)
        voice.overlay.value = null; advanceTimeBy(300) // the hold ended: Idle
        assertNull(link.lastFrame().overlay)
        assertTrue(link.lastFrame().withdrawnAskIds.isEmpty())
        val before = link.sent.count { it.first == Channels.STATE }
        h.withdrawAsks(listOf("ask_1")); advanceTimeBy(300)
        assertTrue("a frame goes out at once", link.sent.count { it.first == Channels.STATE } > before)
        assertEquals(listOf("ask_1"), link.lastFrame().withdrawnAskIds)
        voice.overlay.value = Overlay.Answer("where did I keep my shoes", "On the floor.", speaking = false, askId = "ask_1"); advanceTimeBy(300)
        assertNull("a withdrawn ask's Answer is never framed", link.lastFrame().overlay)
        voice.overlay.value = null
        t += Hub.WITHDRAWN_HINT_MS; h.refresh(); advanceTimeBy(300)
        assertTrue("the hint ends after a minute", link.lastFrame().withdrawnAskIds.isEmpty())
    }

    /** Codex r1 P2: a withdrawal made while a broadcast is inside its IO reads (its time snapshot older) is kept, and every frame of the next minute names it. */
    @Test fun aWithdrawalDuringAnInFlightBroadcastIsNeverPrunedByItsOlderTime() = runTest {
        val link = FakeGlassesLink()
        var t = now
        val h = hub(link, clock = { t })
        advanceTimeBy(300)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>(); val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        todayEntered = entered; todayGate = gate
        h.refresh(); advanceTimeBy(300); runCurrent()
        assertTrue("the broadcast is held inside sources.today()", entered.isCompleted)
        t += 5_000
        h.withdrawAsks(listOf("ask_1"))
        todayGate = null; gate.complete(Unit); advanceTimeBy(300); runCurrent()
        assertEquals("the held broadcast keeps it", listOf("ask_1"), link.lastFrame().withdrawnAskIds)
        h.refresh(); advanceTimeBy(300)
        assertEquals("so does the next frame", listOf("ask_1"), link.lastFrame().withdrawnAskIds)
        t += Hub.WITHDRAWN_HINT_MS - 1_000; h.refresh(); advanceTimeBy(300)
        assertEquals("still inside its minute", listOf("ask_1"), link.lastFrame().withdrawnAskIds)
    }

    /**
     * BUG-R2-5: while the undo window is open an Answer carries it (the HUD shows the answer with the undo line and a
     * double-tap undoes), in either order; a phone Listening still wins; once the window closes the plain Answer shows.
     */
    @Test fun undoWindowRidesOnTheAnswerInEitherOrder() = runTest {
        val link = FakeGlassesLink()
        val voice = FakeHubVoice()
        val actions = FakeActions()
        hub(link, voice = voice, actions = actions)
        val toast = Overlay.Toast("Added \u00b7 double-tap undo", undoProposalId = "p")
        val answer = Overlay.Answer("add milk", "Added milk.", speaking = true)
        // Toast, then the answer.
        actions.overlay.value = toast; advanceTimeBy(300)
        assertEquals(toast, link.lastFrame().overlay)
        voice.overlay.value = answer; advanceTimeBy(300)
        assertEquals(answer.copy(undoProposalId = "p"), link.lastFrame().overlay)
        actions.overlay.value = null; advanceTimeBy(300)
        assertEquals(answer, link.lastFrame().overlay)
        // The answer, then the toast (a typed ask mirrored to the HUD, or a late auto-add).
        voice.overlay.value = null; advanceTimeBy(300)
        voice.overlay.value = answer; advanceTimeBy(300)
        actions.overlay.value = toast.copy(undoProposalId = "q"); advanceTimeBy(300)
        assertEquals(answer.copy(undoProposalId = "q"), link.lastFrame().overlay)
        // A phone Listening (a new ask) still outranks it.
        voice.overlay.value = Overlay.Listening("what"); advanceTimeBy(300)
        assertEquals(Overlay.Listening("what"), link.lastFrame().overlay)
        // The port hears what reached the glasses.
        assertTrue(actions.shown.contains(answer.copy(undoProposalId = "q")))
        assertTrue(actions.shown.contains(toast))
    }

    @Test fun pausePhonePushesSettingsWithNewRecord() = runTest {
        val link = FakeGlassesLink()
        val h = hub(link)
        link.sent.clear()
        val r = h.pausePhone(untilMs = now + 60_000); advanceTimeBy(300)
        assertEquals(PauseOrigin.PHONE, r.origin); assertEquals(now, r.createdAtMs); assertEquals(now + 60_000, r.untilMs)
        val pushed = (Wire.decode<HudSettings>(link.lastOn(Channels.SETTINGS)) as Decoded.Ok).value
        assertEquals(listOf(r), pushed.pauses)
        assertEquals(false, pushed.pausesExact)
        assertEquals(java.time.ZoneId.systemDefault().id, pushed.zoneId)
        assertEquals(listOf(r), h.activePauses.value)
        h.resume(setOf(r.pauseId)); advanceTimeBy(300)
        assertEquals(emptyList<PauseRecord>(), (Wire.decode<HudSettings>(link.lastOn(Channels.SETTINGS)) as Decoded.Ok).value.pauses)
        assertEquals(emptyList<PauseRecord>(), h.activePauses.value)
    }

    /** BUG-R2-6a: turning quiet hours off ends the running quiet pause now (every QUIET_HOURS record), nothing else. */
    @Test fun turningQuietHoursOffEndsTheRunningQuietPause() = runTest {
        val link = FakeGlassesLink()
        val h = hub(link)
        h.updateSettings { it.copy(quietHours = QuietHours(16 * 60 + 45, 17 * 60)) }; advanceTimeBy(300)
        val store = HubPauses(db.pauseDao()) { now }
        store.add(PauseRecord("q-phone", PauseOrigin.QUIET_HOURS, now - 60_000, now + 600_000))
        store.add(PauseRecord("q-glasses", PauseOrigin.QUIET_HOURS, now - 59_000, now + 600_000))
        val phone = h.pausePhone(untilMs = null); advanceTimeBy(300)
        assertEquals(3, h.activePauses.value.size)
        h.updateSettings { it.copy(quietHours = null) }; advanceTimeBy(300)
        assertEquals(listOf(phone), h.activePauses.value)
        val pushed = (Wire.decode<HudSettings>(link.lastOn(Channels.SETTINGS)) as Decoded.Ok).value
        assertEquals(listOf(phone), pushed.pauses)
        assertTrue(pushed.resumedPauseIds.containsAll(listOf("q-phone", "q-glasses")))
        assertEquals(listOf(phone), store.stored())
    }

    @Test fun changingTheQuietWindowKeepsTheRunningQuietPause() = runTest {
        val h = hub(FakeGlassesLink())
        h.updateSettings { it.copy(quietHours = QuietHours(16 * 60 + 45, 17 * 60)) }; advanceTimeBy(300)
        HubPauses(db.pauseDao()) { now }.add(PauseRecord("q", PauseOrigin.QUIET_HOURS, now - 60_000, now + 600_000))
        h.updateSettings { it.copy(quietHours = QuietHours(16 * 60, 18 * 60)) }; advanceTimeBy(300)
        h.refresh(); advanceTimeBy(300)
        assertEquals(listOf("q"), h.activePauses.value.map { it.pauseId })
    }

    @Test fun activePausesFollowPausePhoneAndResumeWithoutStart() = runTest {
        val h = hub(FakeGlassesLink(), start = false)
        val r = h.pausePhone(untilMs = null)
        assertEquals(listOf(r), h.activePauses.value)
        h.resume(setOf(r.pauseId))
        assertEquals(emptyList<PauseRecord>(), h.activePauses.value)
    }

    @Test fun ackFromOtherInstallIgnored() = runTest {
        val link = FakeGlassesLink(); val got = ArrayList<com.rokid.context.core.model.CapturePlanAck>()
        val h = hub(link, phoneInstall = { "mine" }, onPlanAck = { got += it })
        link.emitReady(); runCurrent()
        link.emit(GlassesEvent.PlanAckReceived(com.rokid.context.core.model.CapturePlanAck(gen = 3, phoneInstall = "theirs", sessionStartMs = now))); runCurrent()
        assertTrue(got.isEmpty()); assertEquals(1, h.planAckStale)
    }

    @Test fun ackFromOldSessionIgnoredAndCurrentPasses() = runTest {
        val link = FakeGlassesLink(); val got = ArrayList<com.rokid.context.core.model.CapturePlanAck>()
        var t = now
        val h = hub(link, clock = { t }, phoneInstall = { "mine" }, onPlanAck = { got += it })
        link.emitReady(); runCurrent()
        val oldSession = now
        t = now + 60_000
        link.emitReady(); runCurrent() // a new session: sessionStartMs = t
        link.emit(GlassesEvent.PlanAckReceived(com.rokid.context.core.model.CapturePlanAck(gen = 3, phoneInstall = "mine", sessionStartMs = oldSession))); runCurrent()
        assertTrue(got.isEmpty()); assertEquals(1, h.planAckStale)
        link.emit(GlassesEvent.PlanAckReceived(com.rokid.context.core.model.CapturePlanAck(gen = 3, phoneInstall = "mine", sessionStartMs = t))); runCurrent()
        assertEquals(listOf(3L), got.map { it.gen }); assertEquals(1, h.planAckStale)
    }

    @Test fun capsAckIsNotStaleAndMarksThePeerCapable() = runTest {
        val link = FakeGlassesLink(); var caps: List<String>? = null
        val h = hub(link, phoneInstall = { "mine" }, onPlanCaps = { caps = it })
        link.emitReady(); runCurrent()
        link.emit(hello()); runCurrent()
        link.emit(GlassesEvent.PlanAckReceived(com.rokid.context.core.model.CapturePlanAck(gen = 0, caps = listOf("plan1", "shadow")))); runCurrent()
        assertEquals(listOf("plan1", "shadow"), caps); assertEquals(0, h.planAckStale)
        assertEquals(com.rokid.context.link.PlanPeer.CAPABLE, h.planPeer())
    }

    @Test fun glassesWithoutACapsAckAreTooOldAfterTheGrace() = runTest {
        val link = FakeGlassesLink(); var t = now
        val h = hub(link, clock = { t })
        link.emitReady(); runCurrent(); link.emit(hello()); runCurrent()
        assertEquals(com.rokid.context.link.PlanPeer.UNKNOWN, h.planPeer())
        t = now + 11_000
        assertEquals(com.rokid.context.link.PlanPeer.TOO_OLD, h.planPeer())
    }

    @Test fun queuedMarkOlderThanSessionAccepted() = runTest {
        val link = FakeGlassesLink(); val got = ArrayList<com.rokid.context.core.model.MarkMessage>()
        hub(link, onMark = { m, _ -> got += m; true })
        link.emitReady(); runCurrent()
        link.emit(GlassesEvent.MarkReceived(com.rokid.context.core.model.MarkMessage(epoch = "e", seq = 1, atMs = now - 3_600_000, atElapsedMs = 5, sessionStartMs = now))); runCurrent()
        assertEquals(listOf(1L), got.map { it.seq })
    }

    @Test fun aRecordedMarkIsAckedByEpochAndSeq() = runTest {
        val link = FakeGlassesLink()
        hub(link, onMark = { _, _ -> true })
        link.emitReady(); runCurrent()
        link.emit(GlassesEvent.MarkReceived(com.rokid.context.core.model.MarkMessage(epoch = "e9", seq = 4, atMs = now, atElapsedMs = 5))); runCurrent()
        val ack = (Wire.decode<com.rokid.context.core.model.MarkAck>(link.lastOn(Channels.MARK_ACK)) as Decoded.Ok).value
        assertEquals(com.rokid.context.core.model.MarkAck(epoch = "e9", seqs = listOf(4L)), ack)
    }

    @Test fun aMarkTheRecorderFailedOnIsNotAckedSoTheGlassesResend() = runTest {
        val link = FakeGlassesLink()
        hub(link, onMark = { _, _ -> throw IllegalStateException("db") })
        link.emitReady(); runCurrent()
        link.emit(GlassesEvent.MarkReceived(com.rokid.context.core.model.MarkMessage(epoch = "e", seq = 1, atMs = now, atElapsedMs = 5))); runCurrent()
        assertTrue(link.sent.none { it.first == Channels.MARK_ACK })
    }

    @Test fun aMarkIsMappedToPhoneTimeWithTheHelloOffsetElseReceiptTime() = runTest {
        val link = FakeGlassesLink(); val arrivals = ArrayList<com.rokid.context.canvas.marks.MarkArrival>()
        hub(link, onMark = { _, a -> arrivals += a; true })
        link.emitReady(); runCurrent()
        link.emit(GlassesEvent.MarkReceived(com.rokid.context.core.model.MarkMessage(epoch = "e", seq = 1, atMs = 777, atElapsedMs = 5))); runCurrent()
        assertEquals(com.rokid.context.canvas.marks.MarkArrival(now, false, now), arrivals[0])
        link.emit(hello(glassesNowMs = now + 140)); runCurrent()
        link.emit(GlassesEvent.MarkReceived(com.rokid.context.core.model.MarkMessage(epoch = "e", seq = 2, atMs = now + 140 - 2_000, atElapsedMs = 5))); runCurrent()
        assertEquals(com.rokid.context.canvas.marks.MarkArrival(now - 2_000, true, now), arrivals[1])
    }

    @Test fun aMarkPhotoReportReachesTheRecorderAndIsNeverAcked() = runTest {
        val link = FakeGlassesLink(); val got = ArrayList<com.rokid.context.core.model.MarkPhotoResult>()
        hub(link, onMarkPhoto = { got += it })
        link.emitReady(); runCurrent()
        val r = com.rokid.context.core.model.MarkPhotoResult(epoch = "e", seq = 2, reason = "camera_busy")
        link.emit(GlassesEvent.MarkPhotoReceived(r)); runCurrent()
        assertEquals(listOf(r), got)
        assertTrue(link.sent.none { it.first == Channels.MARK_ACK })
    }

    @Test fun aHelloSaysWhetherTheGlassesAnswerAPhotoRequestAndTheRequestGoesOnItsOwnChannel() = runTest {
        val link = FakeGlassesLink(); val h = hub(link)
        link.emitReady(); runCurrent()
        assertFalse("nothing is promised before a Hello", h.peerMarkPhoto)
        link.emit(hello()); runCurrent()
        assertFalse("an old glasses Hello says nothing", h.peerMarkPhoto)
        link.emit(GlassesEvent.HelloReceived(Hello(glassesBatteryPct = 1, bufferSize = 0, glassesNowMs = now, pauses = emptyList(), markPhotoRequest = true))); runCurrent()
        assertTrue(h.peerMarkPhoto)
        link.sent.clear()
        assertTrue(h.requestMarkPhoto("phone:5")); runCurrent()
        val sent = Wire.decode<com.rokid.context.core.model.MarkPhotoRequest>(link.lastOn(Channels.MARK_PHOTO_REQUEST)) as Decoded.Ok
        assertEquals("phone:5", sent.value.ref)
    }

    @Test fun aFailingMarkPhotoReportDoesNotBreakTheHub() = runTest {
        val link = FakeGlassesLink(); var n = 0
        hub(link, onMarkPhoto = { n++; throw IllegalStateException("db") }, onMark = { _, _ -> true })
        link.emitReady(); runCurrent()
        link.emit(GlassesEvent.MarkPhotoReceived(com.rokid.context.core.model.MarkPhotoResult(epoch = "e", seq = 2, reason = "x"))); runCurrent()
        link.emit(GlassesEvent.MarkReceived(com.rokid.context.core.model.MarkMessage(epoch = "e", seq = 1, atMs = now, atElapsedMs = 5))); runCurrent()
        assertEquals(1, n); assertTrue("the hub still handles marks", link.sent.any { it.first == Channels.MARK_ACK })
    }

    @Test fun aGlassesListenStartIsForwardedToTheMarkJoin() = runTest {
        val link = FakeGlassesLink(); val starts = ArrayList<Long>()
        hub(link, onGlassesListenStart = { starts += it })
        link.emitReady(); runCurrent()
        link.emit(GlassesEvent.Cmd(com.rokid.context.core.model.Command.ListenStart)); runCurrent()
        assertEquals(listOf(now), starts)
    }

    // ---- v2.0 T7: HUD host ----

    @Test fun theHudHostDefaultsToPcaOnTheWire() = runTest {
        val link = FakeGlassesLink()
        hub(link); link.emitReady(); runCurrent()
        assertEquals(com.rokid.context.core.model.HudHost.PCA, (Wire.decode<HudSettings>(link.lastOn(Channels.SETTINGS)) as Decoded.Ok).value.hudHost)
    }

    @Test fun theChosenHostIsReadAtEveryPushAndASwitchPushesAtOnce() = runTest {
        val link = FakeGlassesLink(); var host = com.rokid.context.core.model.HudHost.PCA
        val h = hub(link, hudHost = { host })
        link.emitReady(); runCurrent()
        host = com.rokid.context.core.model.HudHost.NEXUS
        h.updateSettings { it }; runCurrent()
        assertEquals(com.rokid.context.core.model.HudHost.NEXUS, (Wire.decode<HudSettings>(link.lastOn(Channels.SETTINGS)) as Decoded.Ok).value.hudHost)
        assertEquals(1, com.rokid.context.core.model.PROTOCOL_VERSION)
    }

    @Test fun theStopHookRunsWhenTheHubStops() = runTest {
        val link = FakeGlassesLink(); var stopped = 0
        val h = hub(link, onStopped = { stopped++ })
        h.stop()
        assertEquals(1, stopped)
    }

    @Test fun aHostOfNexusDropsAnswerAndNudgeOverlaysButKeepsPcasOwnInputOverlays() {
        val nexus = com.rokid.context.core.model.HudHost.NEXUS; val pca = com.rokid.context.core.model.HudHost.PCA
        val answer = com.rokid.context.core.model.Overlay.Answer("q", "a", speaking = false, askId = "a1")
        val nudge = com.rokid.context.core.model.Overlay.Nudge("heads up")
        val listening = com.rokid.context.core.model.Overlay.Listening("hel")
        val toast = com.rokid.context.core.model.Overlay.Toast("undo", undoProposalId = "p1")
        assertEquals(null, Hub.forHost(answer, nexus)); assertEquals(null, Hub.forHost(nudge, nexus))
        assertEquals(listening, Hub.forHost(listening, nexus)); assertEquals(toast, Hub.forHost(toast, nexus)); assertEquals(null, Hub.forHost(null, nexus))
        assertEquals(answer, Hub.forHost(answer, pca)); assertEquals(nudge, Hub.forHost(nudge, pca))
    }

    @Test fun newPhoneSendsPlanAwareTrue() = runTest {
        val link = FakeGlassesLink()
        hub(link); link.emitReady(); runCurrent()
        assertTrue((Wire.decode<HudSettings>(link.lastOn(Channels.SETTINGS)) as Decoded.Ok).value.planAware)
    }

    @Test fun anyDecodedPlanAckMarksThePeerCapable() = runTest {
        val link = FakeGlassesLink()
        val h = hub(link, phoneInstall = { "mine" })
        link.emitReady(); runCurrent(); link.emit(hello()); runCurrent()
        // a caps-shaped ack that also carries an install, and a stale one: both prove the channel
        link.emit(GlassesEvent.PlanAckReceived(com.rokid.context.core.model.CapturePlanAck(gen = 0, phoneInstall = "other", caps = listOf("plan1")))); runCurrent()
        assertEquals(com.rokid.context.link.PlanPeer.CAPABLE, h.planPeer())
        assertEquals(1, h.planAckStale)
    }

    // ---- v0.9.9 battery: the glasses link machinery runs only when enabled in settings AND paired ([BackgroundPolicy]); a tapped Reconnect always works ----

    @Test fun noAutomaticConnectWithTheSettingOffOrWithoutAPair() = runTest {
        val off = FakeGlassesLink(LinkState.OFFLINE); hub(off, reopenEnabled = { false })
        assertEquals("setting off: hub start does not connect", 0, off.connectOnceCalls)
        val unpaired = FakeGlassesLink(LinkState.OFFLINE); hub(unpaired, paired = { false })
        assertEquals("no pair: hub start does not connect", 0, unpaired.connectOnceCalls)
        val on = FakeGlassesLink(LinkState.OFFLINE); hub(on)
        assertEquals("enabled and paired connects as before", 1, on.connectOnceCalls)
    }

    @Test fun inFrontTheLinkConnectsAsBeforeEvenWithTheSettingOff() = runTest {
        val l = FakeGlassesLink(LinkState.OFFLINE); val h = hub(l, reopenEnabled = { false }, inFront = { true })
        assertEquals(1, l.connectOnceCalls)
        h.onAppResumed(); assertEquals(1, l.resumeCalls)
    }

    @Test fun resumeReconnectsOnlyWhenAllowedAndAlwaysSignalsPositive() = runTest {
        var positives = 0
        val l = FakeGlassesLink(LinkState.OFFLINE); val h = hub(l, paired = { false }, onPositiveSignal = { positives++ })
        h.onAppResumed(); assertEquals(0, l.resumeCalls); assertEquals("a resume is a positive signal", 1, positives)
    }

    @Test fun aTappedReconnectAlwaysWorksAndIsAPositiveSignalAndKeepsTheHubWanted() = runTest {
        var positives = 0; var t = now; var service = 0
        val l = FakeGlassesLink(LinkState.OFFLINE)
        val h = hub(l, clock = { t }, reopenEnabled = { false }, paired = { false }, onPositiveSignal = { positives++ }, startService = { service++ })
        assertFalse(h.busy())
        h.requestReconnect(); runCurrent()
        assertEquals("the link's own connect, whatever the setting", 1, l.connectCalls)
        assertEquals(1, positives); assertTrue("wanted for a while", h.busy())
        t += BackgroundPolicy.USER_ASK_HOLD_MS + 1; assertFalse("then it is not", h.busy())
    }

    @Test fun aWearEventAndALinkUpArePositiveSignals() = runTest {
        var positives = 0
        val l = FakeGlassesLink(LinkState.OFFLINE); hub(l, onPositiveSignal = { positives++ })
        val before = positives
        l.wearingFlow.value = true; runCurrent(); assertTrue("wear", positives > before)
        val mid = positives
        l.setState(LinkState.CONNECTED); runCurrent(); assertTrue("link up", positives > mid)
    }
}
