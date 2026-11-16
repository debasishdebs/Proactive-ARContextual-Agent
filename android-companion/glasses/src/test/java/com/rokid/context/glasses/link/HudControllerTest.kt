package com.rokid.context.glasses.link

import com.rokid.context.core.hud.render.HudConnection

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.CaptureState
import com.rokid.context.core.model.Channels
import com.rokid.context.core.model.FrameAck
import com.rokid.context.core.model.FrameCodec
import com.rokid.context.core.model.Health
import com.rokid.context.core.model.Hello
import com.rokid.context.core.model.HelloRequest
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.PROTOCOL_VERSION
import com.rokid.context.core.model.PauseOrigin
import com.rokid.context.core.model.PauseRecord
import com.rokid.context.core.model.StateFrame
import com.rokid.context.core.model.Verbosity
import com.rokid.context.core.model.Wire
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

private typealias CS = com.rokid.context.core.model.context.ContextState

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HudControllerTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val bridge = FakeBridge()
    private var now = 1_000L
    private fun hello() = Hello(glassesBatteryPct = 80, bufferSize = 2, glassesNowMs = now, pauses = emptyList())
    private fun TestScope.controller(prefs: String = "hud_test", b: FakeBridge = bridge) =
        HudController(backgroundScope, b, HudSettingsCache(ctx.getSharedPreferences(prefs, 0)), ::hello) { now }.also { it.start() }
    private fun state(capture: CaptureState = CaptureState.Running) =
        Wire.encode(StateFrame(seq = 1, capture = capture, intervalSec = 300, phoneLinked = true, aiHealth = Health.Ok))
    private fun hellos() = bridge.sent.count { it.first == Channels.HELLO }

    @Test fun firstPhoneMessageOpensTheSessionAndSaysHelloOnce() = runTest {
        val c = controller()
        var opened = 0
        c.onSessionOpened = { opened++ }
        bridge.phone(Channels.STATE, state()); bridge.phone(Channels.STATE, state())
        assertTrue(c.sessionOpen.value)
        assertEquals(1, opened)
        assertEquals(1, hellos())
        assertEquals(HudConnection.LIVE, c.connection.value)
    }

    @Test fun capsAckOnReadyWithoutPlan() = runTest {
        controller()
        bridge.phone(Channels.STATE, state())
        val acks = bridge.sent.filter { it.first == Channels.CAPTURE_PLAN_ACK }
        assertEquals(1, acks.size)
        val a = (Wire.decode<com.rokid.context.core.model.CapturePlanAck>(acks.single().second.single()) as com.rokid.context.core.model.Decoded.Ok).value
        assertEquals(0L, a.gen)
        assertEquals(null, a.effectiveHash); assertEquals(null, a.publishId)
        assertEquals(com.rokid.context.core.model.PlanMode.SETTINGS, a.mode)
        assertEquals(com.rokid.context.capture.plan.PlanReceiver.CAPS_EXPR, a.caps)
        bridge.phone(Channels.STATE, state())
        assertEquals("once per session open", 1, bridge.sent.count { it.first == Channels.CAPTURE_PLAN_ACK })
        bridge.disconnected(); bridge.phone(Channels.STATE, state())
        assertEquals("again on the next session", 2, bridge.sent.count { it.first == Channels.CAPTURE_PLAN_ACK })
    }

    @Test fun capsAckIsResentOnAHelloRequestAndNeverBreaksTheSession() = runTest {
        val c = controller()
        var calls = 0
        c.capsAck = { calls++; if (calls == 1) error("boom") else com.rokid.context.core.model.CapturePlanAck(gen = 0, caps = listOf("plan1")) }
        var opened = 0
        c.onSessionOpened = { opened++ }
        bridge.phone(Channels.STATE, state())   // the lambda throws once: the session still opens, nothing was sent
        assertTrue(c.sessionOpen.value); assertEquals(1, opened)
        assertEquals(0, bridge.sent.count { it.first == Channels.CAPTURE_PLAN_ACK })
        bridge.phone(Channels.HELLO_REQUEST, Wire.encode(HelloRequest()))
        assertEquals(1, bridge.sent.count { it.first == Channels.CAPTURE_PLAN_ACK })
        bridge.rc = 1   // a failing send is ignored
        bridge.phone(Channels.HELLO_REQUEST, Wire.encode(HelloRequest()))
        assertTrue(c.sessionOpen.value)
    }

    @Test fun btDisconnectClosesTheSessionAndTheNextMessageSaysHelloAgain() = runTest {
        val c = controller()
        bridge.phone(Channels.STATE, state())
        bridge.disconnected()
        assertFalse(c.sessionOpen.value)
        assertEquals("no frames while closed", -1, c.sendFrameChunk(FrameCodec.split("g1", ByteArray(4) { 1 }, capturedAtMs = 1).single()))
        bridge.phone(Channels.SETTINGS, Wire.encode(HudSettings()))
        assertTrue(c.sessionOpen.value)
        assertEquals(2, hellos())
    }

    @Test fun aSessionCloseTellsPhoneUseItIsUnknown() = runTest {
        val c = controller()
        val seen = ArrayList<Boolean?>()
        c.onPhoneUse = { seen += it }
        bridge.phone(Channels.STATE, state())
        c.onActivityExit()
        assertEquals(listOf<Boolean?>(null), seen)
    }

    @Test fun activityExitClosesTheSession() = runTest {
        val c = controller()
        bridge.phone(Channels.STATE, state())
        c.onActivityExit()
        assertFalse(c.sessionOpen.value)
    }

    /** Review Focus 4: another phone version → "Update", no hello, no frames. */
    @Test fun otherPhoneVersionShowsOutdatedAndNeverOpensASession() = runTest {
        val c = controller()
        bridge.phone(Channels.STATE, state().replace("\"v\":$PROTOCOL_VERSION", "\"v\":${PROTOCOL_VERSION + 1}"))
        assertEquals(HudConnection.OUTDATED, c.connection.value)
        assertFalse(c.sessionOpen.value)
        assertTrue(bridge.sent.isEmpty())
    }

    @Test fun settingsArePersistedAcrossRestarts() = runTest {
        controller(prefs = "persist")
        bridge.phone(Channels.SETTINGS, Wire.encode(HudSettings(verbosity = Verbosity.MAX, captureIntervalSec = 120)))
        val again = HudController(backgroundScope, FakeBridge(), HudSettingsCache(ctx.getSharedPreferences("persist", 0)), ::hello) { now }
        assertEquals(Verbosity.MAX, again.settings.value.verbosity)
        assertEquals(120, again.settings.value.captureIntervalSec)
    }

    @Test fun phonePausesAreForwardedFromStateAndSettings() = runTest {
        val c = controller()
        val got = mutableListOf<List<PauseRecord>>()
        val exact = mutableListOf<Boolean>()
        c.onPhoneSettings = { got += it.pauses; exact += it.pausesExact }
        val p = PauseRecord("p1", PauseOrigin.PHONE, 1, null)
        bridge.phone(Channels.STATE, state(CaptureState.Paused(listOf(p))))
        bridge.phone(Channels.SETTINGS, Wire.encode(HudSettings(pauses = listOf(p), pausesExact = true)))
        assertEquals(listOf(listOf(p), listOf(p)), got)
        assertEquals("a pc_state list is inexact; pc_settings keeps its flag", listOf(false, true), exact)
    }

    /** LGC4: only a real `pc_settings` push drives `onPhoneSettingsPush` (the persisted diagnostic request); a pc_state pause list re-applies CACHED settings and must not. */
    @Test fun onlyARealSettingsPushDrivesThePersistedDiagnosticRequest() = runTest {
        val c = controller(prefs = "lgc4push")
        val pushes = mutableListOf<Boolean>()
        c.onPhoneSettingsPush = { pushes += it.diagRecord }
        val p = PauseRecord("p1", PauseOrigin.PHONE, 1, null)
        bridge.phone(Channels.STATE, state(CaptureState.Paused(listOf(p))))
        assertEquals("a state frame is not a settings push", emptyList<Boolean>(), pushes)
        bridge.phone(Channels.SETTINGS, Wire.encode(HudSettings(diagRecord = true)))
        assertEquals(listOf(true), pushes)
    }

    /** Fix round 1 (M12): a push with out-of-range quiet minutes is kept without them (cached and forwarded). */
    @Test fun outOfRangeQuietHoursAreDroppedFromAPush() = runTest {
        val c = controller(prefs = "m12")
        val got = mutableListOf<HudSettings>()
        c.onPhoneSettings = { got += it }
        bridge.phone(Channels.SETTINGS, Wire.encode(HudSettings(quietHours = com.rokid.context.core.model.QuietHours(0, 5_000))))
        assertEquals(null, got.single().quietHours)
        assertEquals(null, c.settings.value.quietHours)
        assertEquals(null, HudSettingsCache(ctx.getSharedPreferences("m12", 0)).read().quietHours)
    }

    @Test fun frameAcksReachTheUplinkAndGarbageDoesNot() = runTest {
        val c = controller()
        val acks = mutableListOf<FrameAck>()
        c.onFrameAck = { acks += it }
        bridge.phone(Channels.FRAME_ACK, Wire.encode(FrameAck(frameId = "g1", ok = true)))
        bridge.phone(Channels.FRAME_ACK, "garbage")
        assertEquals(listOf(FrameAck(frameId = "g1", ok = true)), acks)
    }

    @Test fun aSilentPhoneGoesOfflineAndClosesTheSession() = runTest {
        val c = controller()
        bridge.phone(Channels.STATE, state())
        now += 25_000
        advanceTimeBy(1_100)
        assertEquals(HudConnection.OFFLINE, c.connection.value)
        assertFalse(c.sessionOpen.value)
    }

    @Test fun chunksGoOutAsMetaPlusBase64() = runTest {
        val c = controller()
        bridge.phone(Channels.STATE, state())
        val chunk = FrameCodec.split("g1", ByteArray(10) { 1 }, capturedAtMs = 1).single()
        assertEquals(0, c.sendFrameChunk(chunk))
        assertEquals(Channels.FRAME to listOf(Wire.encode(chunk.meta), chunk.chunk), bridge.sent.last())
    }

    /** Controller ruling: the phone holds a frame on a stale clock offset and asks for a fresh hello. */
    @Test fun aHelloRequestFromThePhoneGetsAFreshHello() = runTest {
        val c = controller()
        bridge.phone(Channels.STATE, state())
        assertEquals(1, hellos())
        now += 5_000
        bridge.phone(Channels.HELLO_REQUEST, Wire.encode(HelloRequest()))
        assertEquals(2, hellos())
        val last = bridge.sent.last()
        assertEquals(Channels.HELLO, last.first)
        assertEquals(now, (Wire.decode<Hello>(last.second.single()) as com.rokid.context.core.model.Decoded.Ok).value.glassesNowMs)
        assertTrue(c.sessionOpen.value)
    }

    @Test fun aHelloRequestOfAnotherVersionOrGarbageSendsNothing() = runTest {
        controller()
        bridge.phone(Channels.HELLO_REQUEST, Wire.encode(HelloRequest()).replace("\"v\":$PROTOCOL_VERSION", "\"v\":${PROTOCOL_VERSION + 1}"))
        bridge.phone(Channels.HELLO_REQUEST, "garbage")
        assertTrue(bridge.sent.isEmpty())
    }

    /** Spec §1.7 send gate: the session must not reopen after the activity exits, until it starts again. */
    @Test fun anExitedActivityIsNotReopenedByPhoneMessagesUntilItStartsAgain() = runTest {
        val c = controller()
        bridge.phone(Channels.STATE, state())
        assertEquals(1, hellos())
        c.onActivityExit()
        bridge.phone(Channels.STATE, state())
        bridge.phone(Channels.HELLO_REQUEST, Wire.encode(HelloRequest()))
        assertFalse(c.sessionOpen.value)
        assertEquals(1, hellos())
        assertEquals(-1, c.sendFrameChunk(FrameCodec.split("g1", ByteArray(4) { 1 }, capturedAtMs = 1).single()))
        c.onActivityStart()
        bridge.phone(Channels.STATE, state())
        assertTrue(c.sessionOpen.value)
        assertEquals(2, hellos())
    }

    /** Privacy: a malformed phone message is logged by class name only; its text (memory lines) never reaches logcat. */
    @Test fun aMalformedMessageNeverLogsItsPayload() = runTest {
        controller()
        val marker = "SECRET_MEMORY_MARKER"
        val bad = state().replace("\"today\":[]", "\"today\":[{\"id\":\"m1\",\"atMs\":\"$marker\",\"text\":\"$marker\"}]")
        check(bad.contains(marker)) { bad }
        ShadowLog.clear()
        bridge.phone(Channels.STATE, bad)
        bridge.phone(Channels.SETTINGS, Wire.encode(HudSettings()).replace("\"scale\":0.4", "\"scale\":\"$marker\""))
        bridge.phone(Channels.HELLO_REQUEST, "{\"v\":$PROTOCOL_VERSION,\"x\":[\"$marker\"")
        val logs = ShadowLog.getLogs()
        assertTrue("the malformed messages were logged", logs.count { it.msg.startsWith("bad pc_") } >= 2)
        assertTrue(logs.joinToString("\n") { it.msg }, logs.none { it.msg.contains(marker) })
    }

    @Test fun connectionStates() {
        assertEquals(HudConnection.CONNECTING, connectionFor(everReceived = false, online = false, outdated = false, sinceStartMs = 11_999))
        assertEquals(HudConnection.OFFLINE, connectionFor(everReceived = false, online = false, outdated = false, sinceStartMs = 12_000))
        assertEquals(HudConnection.OFFLINE, connectionFor(everReceived = true, online = false, outdated = false, sinceStartMs = 1))
        assertEquals(HudConnection.LIVE, connectionFor(everReceived = true, online = true, outdated = false, sinceStartMs = 1))
        assertEquals(HudConnection.OUTDATED, connectionFor(everReceived = true, online = true, outdated = true, sinceStartMs = 1))
    }

    /** Push-to-talk wire (preflight F3): audio only while the session is open; pc_listen_end too, and it carries `v` like every payload. */
    @Test fun audioNeedsAnOpenSessionAndListenEndIsVersioned() = runTest {
        val c = controller()
        assertFalse(c.sendAudio("AAAA"))
        assertFalse("a closed session takes no pc_listen_end", c.sendListenEnd())
        assertEquals(0, bridge.sent.count { it.first == Channels.LISTEN_END })
        bridge.phone(Channels.STATE, state())
        assertTrue(c.sendAudio("AAAA"))
        assertEquals(listOf("AAAA"), bridge.sent.single { it.first == Channels.AUDIO }.second)
        assertTrue(c.sendListenEnd())
        assertEquals(listOf("""{"v":$PROTOCOL_VERSION}"""), bridge.sent.single { it.first == Channels.LISTEN_END }.second)
    }

    /** Fix wave I1: a pc_wear stamped before the phone's current session began is stale and never applied. */
    @Test fun aStaleWearMessageIsIgnoredAndAFreshOneApplies() = runTest {
        val c = controller()
        val seen = ArrayList<Boolean?>()
        c.onWear = { seen += it }
        bridge.phone(Channels.WEAR, Wire.encode(com.rokid.context.core.model.WearMessage(worn = false, atMs = 1_000, sessionStartMs = 5_000)))
        assertTrue(seen.isEmpty())
        bridge.phone(Channels.WEAR, Wire.encode(com.rokid.context.core.model.WearMessage(worn = false, atMs = 6_000, sessionStartMs = 5_000)))
        bridge.phone(Channels.WEAR, Wire.encode(com.rokid.context.core.model.WearMessage(worn = true, atMs = 1, sessionStartMs = 0)))
        assertEquals("an old phone (no session start) is never ignored", listOf<Boolean?>(false, true), seen)
    }

    /** Codex wear r2-r4: a delayed pc_wear never beats a newer one, whatever other channels did in between, a retired session's message stays dropped, and a same-session recovery keeps working. */
    @Test fun wearUpdatesAreOrderedByEpochSessionAndSeq() = runTest {
        val c = controller()
        val checks = ArrayList<Boolean?>(); val worn = ArrayList<Boolean?>()
        c.onWearCheck = { checks += it }; c.onWear = { worn += it }
        fun wear(check: Boolean?, seq: Long, sessionNo: Long = 2L, epoch: Long = 77L, w: Boolean? = null, start: Long = 200L) =
            bridge.phone(Channels.WEAR, Wire.encode(com.rokid.context.core.model.WearMessage(worn = w, atMs = 500, sessionStartMs = start, wearCheck = check, seq = seq, phoneEpoch = epoch, sessionNo = sessionNo)))
        wear(true, 20, w = true)
        bridge.phone(Channels.PHONE_CONTEXT, Wire.encode(com.rokid.context.core.model.PhoneContextMessage(inUse = true, atMs = 150, sessionStartMs = 100))) // another channel of an older session
        wear(false, 10) // delayed "check off" of the same session
        assertEquals("the late check off is dropped", listOf<Boolean?>(true), checks)
        wear(false, 3, sessionNo = 1L, start = 100L, w = true) // a retired session's message (its stamp is older, its check off)
        wear(null, 4, sessionNo = 1L, start = 100L, w = false)
        assertEquals("a retired session cannot offer a check or a wear value", listOf<Boolean?>(true), checks)
        assertEquals(listOf<Boolean?>(true), worn)
        bridge.phone(Channels.STATE, state()); bridge.disconnected() // the HUD session closes: silence recovery or a blip
        wear(false, 30) // the same phone session carries on after it
        assertEquals("same-session recovery keeps working", listOf<Boolean?>(true, false), checks)
        wear(true, 25)
        assertEquals("seq 25 is older than the applied 30", listOf<Boolean?>(true, false), checks)
        wear(true, 1, sessionNo = 3L, start = 300L) // a newer session of the same epoch numbers again from the start
        assertEquals(listOf<Boolean?>(true, false, true), checks)
        wear(false, 1, sessionNo = 1L, epoch = 99L) // a new phone epoch (the hub restarted) is applied: it cannot be ordered against the old one
        assertEquals(listOf<Boolean?>(true, false, true, false), checks)
        wear(true, 1, sessionNo = 0L, epoch = 0L, start = 0L) // an old phone (no epoch) is never ignored
        assertEquals(listOf<Boolean?>(true, false, true, false, true), checks)
    }

    @Test fun aFreshPhoneContextReachesTheCallbackAndAStaleOneIsIgnored() = runTest {
        val c = controller()
        val seen = ArrayList<Boolean?>()
        c.onPhoneUse = { seen += it }
        bridge.phone(Channels.PHONE_CONTEXT, Wire.encode(com.rokid.context.core.model.PhoneContextMessage(inUse = true, atMs = 1_000, sessionStartMs = 5_000)))
        assertTrue(seen.isEmpty())
        bridge.phone(Channels.PHONE_CONTEXT, Wire.encode(com.rokid.context.core.model.PhoneContextMessage(inUse = true, atMs = 6_000, sessionStartMs = 5_000)))
        assertEquals(listOf<Boolean?>(true), seen)
    }

    @Test fun theAutoOpenMarkerReachesTheActivity() = runTest {
        val c = controller()
        var n = 0
        c.onAutoOpen = { n++ }
        bridge.phone(Channels.AUTO_OPEN, Wire.encode(com.rokid.context.core.model.AutoOpen(atMs = 5)))
        assertEquals(1, n)
    }

    private fun ctxMsg(atMs: Long, sessionStartMs: Long = 0, state: com.rokid.context.core.model.context.ContextState = com.rokid.context.core.model.context.ContextState.REST) =
        Wire.encode(com.rokid.context.core.model.context.MotionContextMessage(state = state, sinceMs = 100, atMs = atMs, sessionStartMs = sessionStartMs))

    @Test fun motionContextStaleAndOlderMessagesAreIgnoredAndFreshOnesReach() = runTest {
        val c = controller()
        val seen = ArrayList<com.rokid.context.core.model.context.MotionContextMessage?>()
        c.onMotionContext = { seen += it }
        bridge.phone(Channels.STATE, state())
        bridge.phone(Channels.MOTION_CONTEXT, ctxMsg(atMs = 1_000, sessionStartMs = 5_000))
        assertTrue("before the session start", seen.isEmpty())
        bridge.phone(Channels.MOTION_CONTEXT, ctxMsg(atMs = 6_000, sessionStartMs = 5_000))
        assertEquals(1, seen.size)
        bridge.phone(Channels.MOTION_CONTEXT, ctxMsg(atMs = 5_500, sessionStartMs = 5_000))
        assertEquals("older than the last accepted", 1, seen.size)
        bridge.phone(Channels.MOTION_CONTEXT, ctxMsg(atMs = 6_000, sessionStartMs = 5_000))
        bridge.phone(Channels.MOTION_CONTEXT, ctxMsg(atMs = 7_000, sessionStartMs = 5_000))
        assertEquals("equal and newer are accepted", 3, seen.size)
    }


    /** A REST callback stalled inside the handler while a newer UNKNOWN revoke arrives: REST must never land after it. */
    @Test fun aDelayedRestCallbackNeverOverwritesANewerRevoke() = runTest {
        val c = controller()
        bridge.phone(Channels.STATE, state())
        val seen = java.util.concurrent.CopyOnWriteArrayList<CS?>()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        c.onMotionContext = { m ->
            if (m?.state == CS.REST) { entered.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS) }
            seen += m?.state
        }
        val a = Thread { bridge.phone(Channels.MOTION_CONTEXT, ctxMsg(atMs = 1_000, state = CS.REST)) }.also { it.start() }
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val b = Thread { bridge.phone(Channels.MOTION_CONTEXT, ctxMsg(atMs = 2_000, state = CS.UNKNOWN)) }.also { it.start() }
        Thread.sleep(150)
        release.countDown(); a.join(5_000); b.join(5_000)
        assertEquals("the revoke is the last gate update", listOf<CS?>(CS.REST, CS.UNKNOWN), seen.toList())
    }

    /** A stalled callback while the session closes: the close reset is the last update, and a later message is dropped. */
    @Test fun aDelayedMessageNeverSurvivesSessionCloseAndALateOneIsDropped() = runTest {
        val c = controller()
        bridge.phone(Channels.STATE, state())
        val seen = java.util.concurrent.CopyOnWriteArrayList<CS?>()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        c.onMotionContext = { m ->
            if (m?.state == CS.REST) { entered.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS) }
            seen += m?.state
        }
        val a = Thread { bridge.phone(Channels.MOTION_CONTEXT, ctxMsg(atMs = 1_000, state = CS.REST)) }.also { it.start() }
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val b = Thread { bridge.disconnected() }.also { it.start() }
        Thread.sleep(150)
        release.countDown(); a.join(5_000); b.join(5_000)
        assertEquals(listOf<CS?>(CS.REST, null), seen.toList())
        bridge.phone(Channels.MOTION_CONTEXT, ctxMsg(atMs = 9_000, state = CS.WALKING))
        assertEquals("a message of a closed session is dropped", 2, seen.size)
    }

    @Test fun motionContextSessionCloseResetsAndAReconnectEnvelopeIsAccepted() = runTest {
        val c = controller()
        val seen = ArrayList<com.rokid.context.core.model.context.MotionContextMessage?>()
        c.onMotionContext = { seen += it }
        bridge.phone(Channels.STATE, state())
        bridge.phone(Channels.MOTION_CONTEXT, ctxMsg(atMs = 9_000, sessionStartMs = 5_000))
        bridge.disconnected()
        assertEquals(listOf(true, false), seen.map { it != null })
        // reconnect: a new session start, the phone's fresh envelope has an old sinceMs but atMs >= sessionStartMs
        bridge.phone(Channels.STATE, state())
        bridge.phone(Channels.MOTION_CONTEXT, ctxMsg(atMs = 2_000, sessionStartMs = 1_500))
        assertEquals(3, seen.size)
        assertEquals(100L, seen.last()?.sinceMs)
    }

    @Test fun sendMotionHintOnlyWhileTheSessionIsOpen() = runTest {
        val c = controller()
        val h = com.rokid.context.core.model.MotionHint(state = com.rokid.context.core.model.MotionState.WALKING, atMs = 5)
        assertFalse(c.sendMotionHint(h))
        bridge.phone(Channels.STATE, state())
        assertTrue(c.sendMotionHint(h))
        assertEquals(1, bridge.sent.count { it.first == Channels.MOTION_HINT })
    }

    // ---- slice 3a Task 11: the plan channel ----

    private fun planMsg(gen: Long = 1, atMs: Long = 5, sessionStartMs: Long = 0) =
        Wire.encode(com.rokid.context.core.model.CapturePlanMessage(gen = gen, atMs = atMs, sessionStartMs = sessionStartMs, phoneInstall = "p1"))

    @Test fun planMessageReachesTheHandlerAndAStaleCopyDoesNot() = runTest {
        val c = controller()
        val got = ArrayList<Long>()
        c.onCapturePlan = { got += it.gen }
        bridge.phone(Channels.CAPTURE_PLAN, planMsg(1))
        bridge.phone(Channels.CAPTURE_PLAN, planMsg(2, atMs = 10, sessionStartMs = 11))   // older than its session
        assertEquals(listOf(1L), got)
    }

    @Test fun planAckGoesOutOnlyWhileTheSessionIsOpen() = runTest {
        val c = controller()
        val a = com.rokid.context.core.model.CapturePlanAck(gen = 3, state = com.rokid.context.core.model.PlanAckState.EFFECTIVE)
        assertEquals(false, c.sendPlanAck(a))
        bridge.phone(Channels.STATE, state())
        assertEquals(true, c.sendPlanAck(a))
        assertEquals(2, bridge.sent.count { it.first == Channels.CAPTURE_PLAN_ACK })    // the caps ack and this one
    }

    @Test fun sessionOpenSendsCapsAckThenASeparateStatusAck() = runTest {
        val c = controller()
        c.statusAck = { com.rokid.context.core.model.CapturePlanAck(gen = 7, state = com.rokid.context.core.model.PlanAckState.REVERTED, reason = "auto:reboot", caps = listOf("plan1", "shadow", "trial"), phoneInstall = "p1", sessionStartMs = 9) }
        bridge.phone(Channels.STATE, state())
        val acks = bridge.sent.filter { it.first == Channels.CAPTURE_PLAN_ACK }.map { (Wire.decode<com.rokid.context.core.model.CapturePlanAck>(it.second.single()) as com.rokid.context.core.model.Decoded.Ok).value }
        assertEquals(2, acks.size)
        val isCaps = { x: com.rokid.context.core.model.CapturePlanAck -> x.gen == 0L && x.phoneInstall.isEmpty() && x.sessionStartMs == 0L && x.reason == null }
        kotlin.test.assertTrue(isCaps(acks[0]), "the announcement stays caps-only")
        assertEquals(com.rokid.context.capture.plan.PlanReceiver.CAPS_EXPR, acks[0].caps)
        assertEquals(7L, acks[1].gen); assertEquals("auto:reboot", acks[1].reason)
        kotlin.test.assertFalse(isCaps(acks[1]))
    }

    @Test fun noStatusAckWhenNoPlanWasEverHeld() = runTest {
        controller(); bridge.phone(Channels.STATE, state())
        assertEquals(1, bridge.sent.count { it.first == Channels.CAPTURE_PLAN_ACK })
    }
}
