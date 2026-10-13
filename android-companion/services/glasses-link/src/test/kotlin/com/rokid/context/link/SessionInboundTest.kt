package com.rokid.context.link

import com.rokid.context.core.model.Channels
import com.rokid.context.core.model.Command
import com.rokid.context.core.model.CommandEnvelope
import com.rokid.context.core.model.Wire
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SessionInboundTest {
    private val pcm = listOf(Base64.getEncoder().encodeToString(ByteArray(3_200) { 1 }))
    private fun cmd(c: Command) = listOf(Wire.encode(CommandEnvelope(command = c)))
    private val a = Any()
    private val b = Any()
    private val inbound = SessionInbound()

    @Test fun aLateListenStartFromTheOldSessionOpensNothingInTheNewOne() {
        inbound.open(a); inbound.open(b)
        assertTrue(inbound.onMessage(a, Channels.CMD, cmd(Command.ListenStart)).isEmpty(), "old session's command is dropped")
        assertTrue(inbound.onMessage(b, Channels.AUDIO, pcm).isEmpty(), "no listen was opened in the new session")
        assertTrue(inbound.onMessage(a, Channels.AUDIO, pcm).isEmpty())
    }

    @Test fun aListenOpenInTheOldSessionDoesNotCarryOver() {
        inbound.open(a)
        inbound.onMessage(a, Channels.CMD, cmd(Command.ListenStart))
        assertEquals(1, inbound.onMessage(a, Channels.AUDIO, pcm).size)
        inbound.open(b)
        assertTrue(inbound.onMessage(b, Channels.AUDIO, pcm).isEmpty())
        inbound.onMessage(b, Channels.CMD, cmd(Command.ListenStart))
        assertEquals(3_200, (inbound.onMessage(b, Channels.AUDIO, pcm).single() as GlassesEvent.Audio).pcm.size)
    }

    @Test fun interruptAndCloseForgetTheListen() {
        inbound.open(a)
        inbound.onMessage(a, Channels.CMD, cmd(Command.ListenStart))
        inbound.interrupt(a) // a BT pause
        assertTrue(inbound.onMessage(a, Channels.LISTEN_END, listOf("{}")).isEmpty())
        inbound.onMessage(a, Channels.CMD, cmd(Command.ListenStart))
        inbound.close(a)
        assertTrue(inbound.onMessage(a, Channels.AUDIO, pcm).isEmpty())
        assertTrue(inbound.onMessage(a, Channels.CMD, cmd(Command.Cancel)).isEmpty(), "a closed session decodes nothing")
    }

    @Test fun closingAnOldSessionLeavesTheCurrentOneAlone() {
        inbound.open(a); inbound.open(b)
        inbound.onMessage(b, Channels.CMD, cmd(Command.ListenStart))
        inbound.close(a); inbound.interrupt(a)
        assertEquals(1, inbound.onMessage(b, Channels.AUDIO, pcm).size)
    }

    @Test fun aSessionWhoseSetupThrowsDecodesNothing() {
        assertFailsWith<IllegalStateException> {
            inbound.open(a) {
                assertEquals(1, inbound.onMessage(a, Channels.CMD, cmd(Command.ListenStart)).size, "decodes while setting up")
                throw IllegalStateException("connect failed")
            }
        }
        assertTrue(inbound.onMessage(a, Channels.AUDIO, pcm).isEmpty(), "the listen opened during setup is gone")
        assertTrue(inbound.onMessage(a, Channels.CMD, cmd(Command.ListenStart)).isEmpty(), "a late message is dropped")
        assertTrue(inbound.onMessage(a, Channels.AUDIO, pcm).isEmpty())
    }

    @Test fun aSessionWhoseSetupSucceedsStaysOpen() {
        inbound.open(a) {}
        inbound.onMessage(a, Channels.CMD, cmd(Command.ListenStart))
        assertEquals(1, inbound.onMessage(a, Channels.AUDIO, pcm).size)
    }

    @Test fun aFailedSetupOfAnOlderSessionLeavesTheCurrentOneAlone() {
        assertFailsWith<IllegalStateException> { inbound.open(a) { inbound.open(b); throw IllegalStateException() } }
        inbound.onMessage(b, Channels.CMD, cmd(Command.ListenStart))
        assertEquals(1, inbound.onMessage(b, Channels.AUDIO, pcm).size)
    }
}
