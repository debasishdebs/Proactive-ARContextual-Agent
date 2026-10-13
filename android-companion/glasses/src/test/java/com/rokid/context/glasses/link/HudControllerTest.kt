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
        c.onPhonePauses = { got += it }
        val p = PauseRecord("p1", PauseOrigin.PHONE, 1, null)
        bridge.phone(Channels.STATE, state(CaptureState.Paused(listOf(p))))
        bridge.phone(Channels.SETTINGS, Wire.encode(HudSettings(pauses = listOf(p))))
        assertEquals(listOf(listOf(p), listOf(p)), got)
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
}
