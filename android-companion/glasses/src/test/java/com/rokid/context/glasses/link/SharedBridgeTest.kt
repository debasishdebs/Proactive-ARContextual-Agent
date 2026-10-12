package com.rokid.context.glasses.link

import kotlin.test.Test
import kotlin.test.assertEquals

class SharedBridgeTest {
    private val inner = FakeBridge()
    private val shared = SharedBridge(inner)

    @Test fun eachChannelIsSubscribedOnceAndTheLatestHandlerWins() {
        val got = mutableListOf<String>()
        shared.subscribe("pc_state") { got += "old:$it" }
        shared.subscribe("pc_state") { got += "new:$it" }
        inner.phone("pc_state", "x")
        assertEquals(listOf("pc_state"), inner.subscriptions)
        assertEquals(listOf("new:x"), got)
    }

    @Test fun releaseSilencesHandlersUntilSomeoneSubscribesAgain() {
        val got = mutableListOf<String>()
        var disconnects = 0
        shared.subscribe("pc_state") { got += it }
        shared.setConnectionListener(connected = {}, disconnected = { disconnects++ })
        shared.release()
        inner.phone("pc_state", "lost")
        inner.disconnected()
        shared.subscribe("pc_state") { got += it }
        inner.phone("pc_state", "kept")
        assertEquals(listOf("kept"), got)
        assertEquals(0, disconnects)
        assertEquals(1, inner.subscriptions.size)
    }

    @Test fun sendsPassThrough() {
        inner.rc = -2
        assertEquals(-2, shared.send("pc_cmd", "a", "b"))
        assertEquals("pc_cmd" to listOf("a", "b"), inner.sent.single())
    }
}
