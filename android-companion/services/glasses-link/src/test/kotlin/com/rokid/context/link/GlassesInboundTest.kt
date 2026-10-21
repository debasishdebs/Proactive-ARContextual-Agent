package com.rokid.context.link

import com.rokid.context.core.model.Channels
import com.rokid.context.core.model.Command
import com.rokid.context.core.model.CommandEnvelope
import com.rokid.context.core.model.FrameCodec
import com.rokid.context.core.model.Hello
import com.rokid.context.core.model.PROTOCOL_VERSION
import com.rokid.context.core.model.Wire
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlassesInboundTest {
    private val inbound = GlassesInbound()
    private fun cmd(c: Command) = Wire.encode(CommandEnvelope(command = c))
    private val hello = Hello(glassesBatteryPct = 80, bufferSize = 3, glassesNowMs = 1_000, pauses = emptyList())

    @Test fun userExitIsDecoded() =
        assertEquals(listOf<GlassesEvent>(GlassesEvent.UserExit), inbound.onMessage(Channels.USER_EXIT, listOf(Wire.encode(com.rokid.context.core.model.UserExit()))))

    @Test fun motionHintWithVersionIsDecoded() {
        val json = """{"v":$PROTOCOL_VERSION,"state":"WALKING","atMs":5,"stillFrac5m":0.25}"""
        val hint = com.rokid.context.core.model.MotionHint(com.rokid.context.core.model.MotionState.WALKING, 5, stillFrac5m = 0.25f)
        assertEquals(listOf<GlassesEvent>(GlassesEvent.MotionHintReceived(hint)), inbound.onMessage(Channels.MOTION_HINT, listOf(json)))
    }

    @Test fun motionHintWithoutVersionIsOutdated() =
        assertEquals(listOf<GlassesEvent>(GlassesEvent.Outdated(null)), inbound.onMessage(Channels.MOTION_HINT, listOf("""{"state":"WALKING","atMs":5}""")))

    @Test fun helloIsDecoded() =
        assertEquals(listOf<GlassesEvent>(GlassesEvent.HelloReceived(hello)), inbound.onMessage(Channels.HELLO, listOf(Wire.encode(hello))))

    @Test fun otherVersionIsReportedNotDecoded() {
        val text = Wire.encode(hello).replace("\"v\":$PROTOCOL_VERSION", "\"v\":${PROTOCOL_VERSION + 1}")
        assertEquals(listOf<GlassesEvent>(GlassesEvent.Outdated(PROTOCOL_VERSION + 1)), inbound.onMessage(Channels.HELLO, listOf(text)))
        val frame = FrameCodec.split("f1", ByteArray(10) { 1 }, capturedAtMs = 5).single()
        val oldMeta = Wire.encode(frame.meta).replace("\"v\":$PROTOCOL_VERSION", "\"v\":${PROTOCOL_VERSION + 1}")
        assertEquals(listOf<GlassesEvent>(GlassesEvent.Outdated(PROTOCOL_VERSION + 1)), inbound.onMessage(Channels.FRAME, listOf(oldMeta, frame.chunk)), "never ingested")
    }

    @Test fun frameNeedsMetaAndChunk() {
        val c = FrameCodec.split("f1", ByteArray(10) { 1 }, capturedAtMs = 5).single()
        assertEquals(listOf<GlassesEvent>(GlassesEvent.Frame(c)), inbound.onMessage(Channels.FRAME, listOf(Wire.encode(c.meta), c.chunk)))
        assertTrue(inbound.onMessage(Channels.FRAME, listOf(Wire.encode(c.meta))).isEmpty(), "chunk missing")
    }

    @Test fun commandsAreDecoded() = assertEquals(
        listOf<GlassesEvent>(GlassesEvent.Cmd(Command.Resume(setOf("p1")))),
        inbound.onMessage(Channels.CMD, listOf(cmd(Command.Resume(setOf("p1"))))),
    )

    @Test fun malformedMessagesAreDropped() {
        assertTrue(inbound.onMessage(Channels.CMD, listOf("not json")).isEmpty())
        assertTrue(inbound.onMessage(Channels.HELLO, emptyList()).isEmpty())
        assertTrue(inbound.onMessage("pc_unknown", listOf("{}")).isEmpty())
    }

    /** pc_audio has no `v`: it counts only after an accepted (same-version) ListenStart. */
    @Test fun audioCountsOnlyAfterAnAcceptedListenStart() {
        val pcm = Base64.getEncoder().encodeToString(ByteArray(3_200) { 2 })
        assertTrue(inbound.onMessage(Channels.AUDIO, listOf(pcm)).isEmpty())
        inbound.onMessage(Channels.CMD, listOf(cmd(Command.ListenStart)))
        assertEquals(3_200, (inbound.onMessage(Channels.AUDIO, listOf(pcm)).single() as GlassesEvent.Audio).pcm.size)
        assertEquals(listOf<GlassesEvent>(GlassesEvent.ListenEnd), inbound.onMessage(Channels.LISTEN_END, listOf("")))
        assertTrue(inbound.onMessage(Channels.AUDIO, listOf(pcm)).isEmpty(), "closed by listen end")
    }

    @Test fun resetForgetsAnOpenListen() {
        inbound.onMessage(Channels.CMD, listOf(cmd(Command.ListenStart)))
        inbound.reset()
        assertTrue(inbound.onMessage(Channels.LISTEN_END, listOf("")).isEmpty())
    }

    /** Carry (Task 9): the glasses send `pc_listen_end` as `{"v":N}` (F3); the body is not version-gated, only the open listen counts. */
    @Test fun listenEndCarriesAVersionButIsNotGatedByIt() {
        inbound.onMessage(Channels.CMD, listOf(cmd(Command.ListenStart)))
        assertEquals(listOf<GlassesEvent>(GlassesEvent.ListenEnd), inbound.onMessage(Channels.LISTEN_END, listOf("{\"v\":$PROTOCOL_VERSION}")))
        inbound.onMessage(Channels.CMD, listOf(cmd(Command.ListenStart)))
        assertEquals(listOf<GlassesEvent>(GlassesEvent.ListenEnd), inbound.onMessage(Channels.LISTEN_END, listOf("{\"v\":${PROTOCOL_VERSION + 1}}")), "no Outdated from listen_end")
    }
}
