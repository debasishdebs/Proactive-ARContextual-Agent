package com.rokid.context.glasses.link

import com.rokid.context.core.model.FrameAck
import com.rokid.context.core.model.FrameChunk
import com.rokid.context.core.model.MotionState
import com.rokid.context.glasses.capture.KeyframeOut
import com.rokid.context.glasses.capture.OutboundBuffer
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FrameUplinkMetaTest {
    @get:Rule val tmp = TemporaryFolder()
    private val now = 1_793_000_000_000L

    @Test fun uplinkSendsAllKeyframeFields() = runTest {
        val dir = File(tmp.root, "out")
        val kf = KeyframeOut(ByteArray(300) { (it % 5).toByte() }, 640, MotionState.HEAD_MOVING, true, 33.5f, "0123456789abcdef", "fedcba9876543210", 77L, "sess-7")
        OutboundBuffer(dir, clock = { now }, elapsed = { 0L }, bootId = { "" }).add("g1", kf.jpeg, now, 9, kf.phash, 80, keyframe = kf)
        val b = OutboundBuffer(dir, clock = { now }, elapsed = { 0L }, bootId = { "" }) // restarted
        val sent = mutableListOf<FrameChunk>()
        lateinit var u: FrameUplink
        u = FrameUplink(this, b, sendChunk = { c ->
            sent += c
            if (c.meta.chunkIdx == c.meta.chunkCount - 1) u.onAck(FrameAck(frameId = c.meta.frameId, ok = true))
            0
        }, canSend = { true })
        u.kick(); advanceUntilIdle()
        assertTrue(sent.isNotEmpty())
        sent.forEach {
            val m = it.meta
            assertEquals(MotionState.HEAD_MOVING, m.motion)
            assertTrue(m.dwell)
            assertEquals(33.5f, m.blur)
            assertEquals("0123456789abcdef", m.dhash)
            assertEquals("fedcba9876543210", m.phash)
            assertEquals(640, m.width)
            assertEquals(77L, m.sampleSeq)
            assertEquals("sess-7", m.sessionId)
        }
    }
}
