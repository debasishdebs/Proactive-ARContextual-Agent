package com.rokid.context.link

import com.rokid.context.core.model.Channels
import com.rokid.context.core.model.Hello
import com.rokid.context.core.model.Wire
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Records what the minimised data path sends; no SDK, no appStart (the transport has no way to start the HUD). */
private class FakeBackgroundTransport(var code: Int? = 0, var bindOk: Boolean = true) : BackgroundTransport {
    val sent = mutableListOf<Pair<String, String>>()
    var starts = 0
    var stops = 0
    override var active = false
        private set

    override fun start(token: String): Boolean { starts++; active = bindOk; return bindOk }
    override fun stop() { if (active) stops++; active = false }
    override fun send(channel: String, json: String): Int? { sent += channel to json; return code }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MinimisedLinkTest {
    private val scope = TestScope()
    private val transport = FakeBackgroundTransport()
    private val inbound = SessionInbound()
    private var alive = 0
    private var silent = 0
    private val link = MinimisedLink(scope, transport, inbound, onAlive = { alive++ }, onSilent = { silent++ }, log = {})
    private val hello = listOf(Wire.encode(Hello(glassesBatteryPct = 50, bufferSize = 2, glassesNowMs = 1L, pauses = emptyList())))

    @Test fun bindSendsOnePcHelloRequestSoTheGlassesFlush() {
        link.start("t")
        assertTrue(transport.sent.isEmpty(), "unbound: nothing can be sent yet")
        link.onBound(true)
        assertEquals(listOf(Channels.HELLO_REQUEST), transport.sent.map { it.first })
        link.onBound(true)
        assertEquals(1, transport.sent.size, "one hello per bind")
    }

    @Test fun sendGoesThroughTheBackgroundLinkOnlyWhileBoundAndHandedOver() {
        link.start("t")
        assertFalse(link.send(Channels.FRAME_ACK, "{}"), "not bound yet")
        link.onBound(true)
        assertTrue(link.send(Channels.FRAME_ACK, "{}"))
        transport.code = 7
        assertFalse(link.send(Channels.STATE, "{}"), "Hi Rokid refused it")
        transport.code = null
        assertFalse(link.send(Channels.STATE, "{}"), "SDK unbound")
        link.onBound(false)
        transport.code = 0
        assertFalse(link.send(Channels.STATE, "{}"), "service disconnected")
    }

    @Test fun inboundDecodesOnTheBackgroundKeyAndStopsWhenTheSessionTakesOver() {
        link.start("t"); link.onBound(true)
        assertIs<GlassesEvent.HelloReceived>(link.onMessage(Channels.HELLO, hello).single())
        val session = Any()
        link.stop(); inbound.open(session) // openSession(): background first, then the session's own key
        assertTrue(link.onMessage(Channels.HELLO, hello).isEmpty(), "a late background message is never delivered twice")
        assertEquals(1, inbound.onMessage(session, Channels.HELLO, hello).size)
    }

    @Test fun aRestartedLinkIgnoresMessagesOfTheEarlierBind() {
        link.start("t"); link.stop()
        link.start("t")
        assertEquals(1, link.onMessage(Channels.HELLO, hello).size)
    }

    @Test fun firstGlassesMessageConfirmsTheMinimisedLinkOnce() {
        link.start("t"); link.onBound(true)
        link.onMessage(Channels.HELLO, hello); link.onMessage(Channels.HELLO, hello)
        assertEquals(1, alive)
        scope.advanceTimeBy(MinimisedLink.CONFIRM_MS + 1); scope.runCurrent()
        assertEquals(0, silent)
    }

    @Test fun noGlassesMessageAfterBindMeansTheAppReallyExited() {
        link.start("t"); link.onBound(true)
        scope.advanceTimeBy(MinimisedLink.CONFIRM_MS - 1); scope.runCurrent()
        assertEquals(0, silent)
        scope.advanceTimeBy(2); scope.runCurrent()
        assertEquals(1, silent)
        link.onMessage(Channels.HELLO, hello)
        assertEquals(1, alive, "a late message minimises again")
    }

    @Test fun aFailedBindIsSilentAtOnce() {
        transport.bindOk = false
        assertFalse(link.start("t"))
        assertEquals(1, silent)
    }

    @Test fun stopCancelsTheConfirmTimerAndUnbinds() {
        link.start("t"); link.onBound(true)
        link.stop()
        scope.advanceTimeBy(MinimisedLink.CONFIRM_MS + 1); scope.runCurrent()
        assertEquals(0, silent)
        assertEquals(1, transport.stops)
        assertFalse(link.active)
        assertFalse(link.send(Channels.STATE, "{}"))
    }
}
