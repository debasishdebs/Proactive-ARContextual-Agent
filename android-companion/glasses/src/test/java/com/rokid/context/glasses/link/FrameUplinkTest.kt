package com.rokid.context.glasses.link

import com.rokid.context.core.model.FrameAck
import com.rokid.context.core.model.FrameAckReason
import com.rokid.context.core.model.FrameChunk
import com.rokid.context.glasses.capture.OutboundBuffer
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FrameUplinkTest {
    @get:Rule val tmp = TemporaryFolder()
    private val now = 1_793_000_000_000L
    private val sent = mutableListOf<FrameChunk>()
    private val logs = mutableListOf<String>()
    private var open = true
    private var clockThrows = 0
    private var changed = 0
    private fun buffer() = OutboundBuffer(File(tmp.root, "out"), clock = {
        if (clockThrows > 0) { clockThrows--; throw IllegalStateException("clock") }
        now
    }, elapsed = { 0L }, bootId = { "" })
    private fun jpeg(n: Int) = ByteArray(n) { (it % 7).toByte() }

    /** [answer] is the phone: called for each frame's last chunk, its ack (if any) arrives before the wait starts. */
    private fun TestScope.uplink(b: OutboundBuffer, config: UplinkConfig = UplinkConfig(), rc: Int = 0, answer: (FrameChunk) -> FrameAck? = { null }): FrameUplink {
        lateinit var u: FrameUplink
        u = FrameUplink(this, b, sendChunk = { c ->
            sent += c
            if (rc == 0 && c.meta.chunkIdx == c.meta.chunkCount - 1) answer(c)?.let(u::onAck)
            rc
        }, canSend = { open }, config = config, log = { logs += it }, onBufferChanged = { changed++ })
        return u
    }
    private fun okFor(c: FrameChunk) = FrameAck(frameId = c.meta.frameId, ok = true)

    @Test fun bytesSentSumsAcceptedChunkLengthsOnly() = runTest {
        val b = buffer()
        b.add("g1", jpeg(100), now - 1, null, null, 80)
        val accepted = ArrayList<FrameChunk>()
        var n = 0
        val u = FrameUplink(this, b, sendChunk = { c -> if (n++ == 0) -1 else { accepted += c; 0 } }, canSend = { open }, config = UplinkConfig(maxAttempts = 2))
        assertEquals(0L, u.bytesSent)
        u.kick(); advanceUntilIdle()
        assertEquals(accepted.sumOf { it.chunk.length }.toLong(), u.bytesSent)
        assertTrue(u.bytesSent > 0)
    }

    @Test fun ackedFramesAreDeletedOldestFirst() = runTest {
        val b = buffer()
        b.add("g1", jpeg(100), now - 2, null, null, 80); b.add("g2", jpeg(100), now - 1, null, null, 80)
        uplink(b, answer = ::okFor).kick(); advanceUntilIdle()
        assertEquals(listOf("g1", "g2"), sent.map { it.meta.frameId })
        assertEquals(0, b.size)
    }

    @Test fun aLargeFrameSendsEveryChunkBeforeWaiting() = runTest {
        val b = buffer()
        b.add("g1", jpeg(5_000), now, null, null, null)
        uplink(b, UplinkConfig(chunkChars = 2_000), answer = ::okFor).kick(); advanceUntilIdle()
        assertEquals(listOf(0, 1, 2, 3), sent.map { it.meta.chunkIdx })
        assertEquals(0, b.size)
    }

    /** Phase 0: 5 s ack timeout + 2 s backoff, 3 attempts, then stop and keep the backlog. */
    @Test fun noAckRetriesThreeTimesThenKeepsTheBacklog() = runTest {
        val b = buffer()
        b.add("g1", jpeg(100), now - 1, null, null, null); b.add("g2", jpeg(100), now, null, null, null)
        val sentAt = mutableListOf<Long>()
        val u = FrameUplink(this, b, sendChunk = { sentAt += testScheduler.currentTime; sent += it; 0 }, canSend = { open })
        u.kick(); advanceUntilIdle()
        assertEquals(listOf(0L, 7_000L, 14_000L), sentAt)
        assertEquals("later frames wait for the next flush", setOf("g1"), sent.map { it.meta.frameId }.toSet())
        assertEquals(2, b.size)
        assertFalse(u.flushing.value)
    }

    @Test fun nackResendsOnceThenDropsAndMovesOn() = runTest {
        val b = buffer()
        b.add("bad", jpeg(100), now - 1, null, null, null); b.add("good", jpeg(100), now, null, null, null)
        uplink(b, answer = { c ->
            if (c.meta.frameId == "bad") FrameAck(frameId = "bad", ok = false, reason = FrameAckReason.CRC) else okFor(c)
        }).kick(); advanceUntilIdle()
        assertEquals(listOf("bad", "bad", "good"), sent.map { it.meta.frameId })
        assertEquals(0, b.size)
    }

    /** rc ≠ 0 (CXR refused) is a failed attempt without the 5 s wait. */
    @Test fun aRefusedSendCountsAsATimeout() = runTest {
        val b = buffer()
        b.add("g1", jpeg(100), now, null, null, null)
        uplink(b, rc = -1).kick(); advanceUntilIdle()
        assertEquals(3, sent.size)
        assertEquals(1, b.size)
        assertEquals(4_000L, testScheduler.currentTime)
    }

    /** Session closed or listening (PTT priority): nothing is sent; the next kick after the gate opens flushes. */
    @Test fun aClosedGateSendsNothing() = runTest {
        val b = buffer()
        b.add("g1", jpeg(100), now, null, null, null)
        open = false
        val u = uplink(b, answer = ::okFor)
        u.kick(); advanceUntilIdle()
        assertTrue(sent.isEmpty())
        assertEquals(1, b.size)
        open = true
        u.kick(); advanceUntilIdle()
        assertEquals(0, b.size)
    }

    /** Review Focus 3: link drop mid-frame keeps it and the rest; the next flush restarts it from chunk 0. */
    @Test fun gateClosingMidBacklogKeepsTheRestAndRestartsFromChunkZero() = runTest {
        val b = buffer()
        b.add("g1", jpeg(5_000), now - 1, null, null, null); b.add("g2", jpeg(100), now, null, null, null)
        val first = FrameUplink(this, b, sendChunk = { c ->
            sent += c
            if (c.meta.frameId == "g1" && c.meta.chunkIdx == 1) open = false
            0
        }, canSend = { open }, config = UplinkConfig(chunkChars = 2_000))
        first.kick(); advanceUntilIdle()
        assertEquals(listOf(0, 1), sent.map { it.meta.chunkIdx })
        assertEquals(2, b.size)
        sent.clear(); open = true
        uplink(b, UplinkConfig(chunkChars = 2_000), answer = ::okFor).kick(); advanceUntilIdle()
        assertEquals(listOf(0, 1, 2, 3, 0), sent.map { it.meta.chunkIdx })
        assertEquals(0, b.size)
    }

    @Test fun kickWhileFlushingDoesNotStartASecondFlush() = runTest {
        val b = buffer()
        b.add("g1", jpeg(100), now, null, null, null)
        val u = uplink(b)
        u.kick(); u.kick(); advanceTimeBy(1)
        assertEquals(1, sent.size)
        assertTrue(u.flushing.value)
    }

    /** R-65: a privacy pause clears the buffer; the frame in flight must not keep sending or retrying. */
    @Test fun clearingTheBufferMidFrameStopsSending() = runTest {
        val b = buffer()
        b.add("g1", jpeg(5_000), now, null, null, null)
        val u = FrameUplink(this, b, sendChunk = { c ->
            sent += c
            if (c.meta.chunkIdx == 1) b.clear()
            0
        }, canSend = { open }, config = UplinkConfig(chunkChars = 2_000))
        u.kick(); advanceUntilIdle()
        assertEquals(listOf(0, 1), sent.map { it.meta.chunkIdx })
        assertEquals(0, b.size)
        assertFalse(u.flushing.value)
    }

    @Test fun clearingWhileAwaitingTheAckDoesNotRetry() = runTest {
        val b = buffer()
        b.add("g1", jpeg(100), now, null, null, null)
        val u = FrameUplink(this, b, sendChunk = { sent += it; 0 }, canSend = { open })
        u.kick(); advanceTimeBy(1_000); b.clear(); advanceUntilIdle()
        assertEquals(1, sent.size)
    }

    @Test fun aThrowingSendKeepsTheFrameAndDoesNotEscape() = runTest {
        val b = buffer()
        b.add("g1", jpeg(100), now, null, null, null)
        val u = FrameUplink(this, b, sendChunk = { throw IllegalStateException("boom") }, canSend = { open })
        u.kick(); advanceUntilIdle()
        assertEquals(1, b.size)
        assertFalse(u.flushing.value)
    }

    @Test fun aThirdConsecutiveCrashOnTheSameFrameDropsIt() = runTest {
        val b = buffer()
        b.add("poison", jpeg(100), now - 1, null, null, null); b.add("g2", jpeg(100), now, null, null, null)
        val u = FrameUplink(this, b, sendChunk = { if (it.meta.frameId == "poison") throw IllegalStateException("boom"); sent += it; 0 }, canSend = { open })
        u.kick(); advanceUntilIdle(); u.kick(); advanceUntilIdle()
        assertEquals(2, b.size)
        u.kick(); advanceUntilIdle()
        assertEquals("g2", b.oldest()!!.meta.frameId)
        assertEquals(1, b.size)
    }

    private fun TestScope.crashingUplink(b: OutboundBuffer, crashFor: MutableMap<String, Int>, answer: (FrameChunk) -> FrameAck? = ::okFor): FrameUplink {
        lateinit var u: FrameUplink
        u = FrameUplink(this, b, sendChunk = { c ->
            val left = crashFor[c.meta.frameId] ?: 0
            if (left > 0) { crashFor[c.meta.frameId] = left - 1; throw IllegalStateException("boom") }
            sent += c
            if (c.meta.chunkIdx == c.meta.chunkCount - 1) answer(c)?.let(u::onAck)
            0
        }, canSend = { open }, log = { logs += it }, onBufferChanged = { changed++ })
        return u
    }

    @Test fun aCrashInOldestIsNotChargedToThePreviousFrame() = runTest {
        val b = buffer()
        b.add("x", jpeg(100), now - 1, null, null, null); b.add("y", jpeg(100), now, null, null, null)
        val u = uplink(b, answer = { c -> if (c.meta.frameId == "x") { clockThrows = 3; okFor(c) } else null })
        u.kick(); advanceUntilIdle()
        assertFalse(b.contains("x"))
        assertEquals(null, u.crashCount("x"))
        assertEquals(null, u.crashCount("y"))
        assertTrue(logs.none { it.startsWith("dropped") })
    }

    @Test fun crashCountsResetAfterAFrameIsDelivered() = runTest {
        val b = buffer()
        b.add("a", jpeg(100), now - 1, null, null, null); b.add("b", jpeg(100), now, null, null, null)
        val crash = mutableMapOf("a" to 2)
        val u = crashingUplink(b, crash, answer = { c -> if (c.meta.frameId == "a") okFor(c) else null })
        repeat(2) { u.kick(); advanceUntilIdle() }
        u.kick(); advanceUntilIdle() // a delivered; b times out and stays
        assertFalse(b.contains("a"))
        crash["b"] = 2
        repeat(2) { u.kick(); advanceUntilIdle() }
        assertTrue(b.contains("b"))
    }

    @Test fun aPoisonFrameIsDroppedAndTheFlushContinues() = runTest {
        val b = buffer()
        b.add("poison", jpeg(100), now - 1, null, null, null); b.add("g2", jpeg(100), now, null, null, null)
        val u = crashingUplink(b, mutableMapOf("poison" to 3))
        repeat(3) { u.kick(); advanceUntilIdle() }
        assertEquals(0, b.size)
        assertEquals(listOf("g2"), sent.map { it.meta.frameId })
    }

    @Test fun aClearDuringTheAckWaitEndsTheWaitWithinOnePoll() = runTest {
        val b = buffer()
        b.add("g1", jpeg(100), now, null, null, null)
        val u = uplink(b)
        u.kick(); advanceTimeBy(100); b.clear()
        advanceTimeBy(300)
        assertFalse(u.flushing.value)
        assertEquals(1, sent.size)
    }

    @Test fun aTransientReadErrorKeepsTheFrame() = runTest {
        val b = buffer()
        b.add("g1", jpeg(100), now, null, null, null)
        val f = b.oldest()!!.jpeg
        assertTrue(f.setReadable(false))
        val u = uplink(b, answer = ::okFor)
        u.kick(); advanceUntilIdle()
        f.setReadable(true)
        assertTrue(b.contains("g1"))
        u.kick(); advanceUntilIdle()
        assertEquals(0, b.size)
    }

    @Test fun aMissingJpegIsDropped() = runTest {
        val b = buffer()
        b.add("g1", jpeg(100), now, null, null, null)
        assertTrue(b.oldest()!!.jpeg.delete())
        uplink(b, answer = ::okFor).kick(); advanceUntilIdle()
        assertEquals(0, b.size)
        assertTrue(changed >= 1)
    }

    /** The race: a capture kicks after the loop found the buffer empty but before the flush job ended. */
    @Test fun aKickDuringTheFinalFrameRunsAnotherFlush() = runTest {
        val b = buffer()
        b.add("g1", jpeg(100), now - 1, null, null, null)
        lateinit var u: FrameUplink
        var raced = false
        u = FrameUplink(this, b, sendChunk = { c ->
            sent += c
            u.onAck(okFor(c))
            0
        }, canSend = { open }, log = { msg ->
            if (msg.startsWith("flush done") && !raced) { raced = true; b.add("g2", jpeg(100), now, null, null, null); u.kick() }
        })
        u.kick(); advanceUntilIdle()
        assertEquals(listOf("g1", "g2"), sent.map { it.meta.frameId })
        assertEquals(0, b.size)
        assertFalse(u.flushing.value)
    }

    @Test fun aHelloFollowsAFlushThatDroppedAFrame() = runTest {
        val b = buffer()
        b.add("bad", jpeg(100), now, null, null, null)
        uplink(b, answer = { c -> FrameAck(frameId = c.meta.frameId, ok = false, reason = FrameAckReason.CRC) }).kick(); advanceUntilIdle()
        assertEquals(0, b.size)
        assertTrue(changed >= 1)
    }

    @Test fun aHelloFollowsEveryFlushThatSentFrames() = runTest {
        val b = buffer()
        b.add("g1", jpeg(100), now - 1, null, null, null); b.add("g2", jpeg(100), now, null, null, null)
        uplink(b, answer = ::okFor).kick(); advanceUntilIdle()
        assertEquals(0, b.size)
        assertEquals(1, changed)
    }

    @Test fun chunksCarryTheRebasedCaptureTime() = runTest {
        var wall = now
        var up = 10_000L
        val b = OutboundBuffer(File(tmp.root, "rebased"), clock = { wall }, elapsed = { up }, bootId = { "boot" })
        b.add("g1", jpeg(100), now, null, null, null)
        wall += 300_000; up += 1_000
        val expected = b.sendTimeMs(b.oldest()!!)
        uplink(b, answer = ::okFor).kick(); advanceUntilIdle()
        assertEquals(now + 299_000, expected)
        assertEquals(expected, sent.single().meta.capturedAtMs)
    }
}
