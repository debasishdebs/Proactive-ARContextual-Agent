package com.rokid.context.glasses.link

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.CaptureState
import com.rokid.context.core.model.Channels
import com.rokid.context.core.model.Decoded
import com.rokid.context.core.model.Health
import com.rokid.context.core.model.Hello
import com.rokid.context.core.model.HelloRequest
import com.rokid.context.core.model.MarkAck
import com.rokid.context.core.model.MarkMessage
import com.rokid.context.core.model.StateFrame
import com.rokid.context.core.model.Wire
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Slice 3a Task 30: the `pc_mark` / `pc_mark_ack` legs of the glasses link. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MarkLinkTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val bridge = FakeBridge()
    private var now = 9_000L
    private fun hello() = Hello(glassesBatteryPct = 80, bufferSize = 0, glassesNowMs = now, pauses = emptyList())
    private fun TestScope.controller() = HudController(backgroundScope, bridge, HudSettingsCache(ctx.getSharedPreferences("mark_link", 0)), ::hello) { now }.also { it.start() }
    private fun state() = Wire.encode(StateFrame(seq = 1, capture = CaptureState.Running, intervalSec = 300, phoneLinked = true, aiHealth = Health.Ok))
    private val mark = MarkMessage(epoch = "e", seq = 1, atMs = 100, atElapsedMs = 50, listen = true)
    private fun marks() = bridge.sent.filter { it.first == Channels.MARK }.map { (Wire.decode<MarkMessage>(it.second.single()) as Decoded.Ok).value }

    @Test fun markGoesOutOnlyWhileTheSessionIsOpenAndKeepsItsOccurrenceTime() = runTest {
        val c = controller()
        assertFalse(c.sendMark(mark)); assertTrue(marks().isEmpty())
        bridge.phone(Channels.STATE, state())
        now = 20_000L
        assertTrue(c.sendMark(mark))
        assertEquals(mark.copy(sessionStartMs = 9_000L), marks().single())
    }

    @Test fun aRefusedSendIsFalseSoTheMarkStaysQueued() = runTest {
        val c = controller(); bridge.phone(Channels.STATE, state())
        bridge.rc = -1
        assertFalse(c.sendMark(mark))
    }

    @Test fun aPhotoFailureGoesOutOnlyWhileTheSessionIsOpenAsAMarkPhotoMessage() = runTest {
        val c = controller()
        val r = com.rokid.context.core.model.MarkPhotoResult(epoch = "e", seq = 1, reason = "camera_busy")
        assertFalse(c.sendMarkPhoto(r)); assertTrue(bridge.sent.none { it.first == Channels.MARK_PHOTO })
        bridge.phone(Channels.STATE, state())
        assertTrue(c.sendMarkPhoto(r))
        assertEquals(r, (Wire.decode<com.rokid.context.core.model.MarkPhotoResult>(bridge.sent.single { it.first == Channels.MARK_PHOTO }.second.single()) as Decoded.Ok).value)
        bridge.rc = -1
        assertFalse(c.sendMarkPhoto(r))
    }

    @Test fun markAckReachesTheOutbox() = runTest {
        val c = controller(); val got = mutableListOf<MarkAck>()
        c.onMarkAck = { got += it }
        bridge.phone(Channels.MARK_ACK, Wire.encode(MarkAck(epoch = "e", seqs = listOf(1, 2))))
        bridge.phone(Channels.MARK_ACK, "not json")
        assertEquals(listOf(MarkAck(epoch = "e", seqs = listOf(1, 2))), got)
    }

    @Test fun queuedMarksAreDueOnSessionOpenAndOnEveryHelloRequestOfAnOpenSession() = runTest {
        val c = controller(); var due = 0
        c.onMarksDue = { due++ }
        bridge.phone(Channels.STATE, state()); assertEquals(1, due)
        bridge.phone(Channels.STATE, state()); assertEquals(1, due)
        bridge.phone(Channels.HELLO_REQUEST, Wire.encode(HelloRequest())); assertEquals(2, due)
    }

    @Test fun aThrowingMarksDueHandlerNeverBreaksTheSession() = runTest {
        val c = controller(); c.onMarksDue = { error("boom") }
        bridge.phone(Channels.STATE, state())
        assertTrue(c.sessionOpen.value)
    }
}
