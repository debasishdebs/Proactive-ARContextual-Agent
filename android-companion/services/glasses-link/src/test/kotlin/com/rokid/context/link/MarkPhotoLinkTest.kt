package com.rokid.context.link

import com.rokid.context.core.model.Channels
import com.rokid.context.core.model.FrameAckReason
import com.rokid.context.core.model.FrameMeta
import com.rokid.context.core.model.MarkPhotoResult
import com.rokid.context.core.model.Wire
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** v0.9.6 slice C: the phone side of a mark that touches capture (the inbound event and the bounded pause exception of the frame admission). */
class MarkPhotoLinkTest {
    @get:Rule val tmp = TemporaryFolder()
    private val phoneNow = 1_793_000_000_000L
    private class Paused : PauseHistory { override suspend fun pausedAt(phoneMs: Long) = true }
    private fun ledger() = ReceivedFrameLedger(File(tmp.root, "ledger.txt"))
    private fun meta(id: String, markRef: String?) = FrameMeta(frameId = id, chunkIdx = 0, chunkCount = 1, crc32 = 1, capturedAtMs = phoneNow - 1_000, markRef = markRef)

    @Test fun aPhotoFailureArrivesAsAnEvent() {
        val r = MarkPhotoResult(epoch = "e", seq = 4, reason = "camera_busy")
        assertEquals(listOf<GlassesEvent>(GlassesEvent.MarkPhotoReceived(r)), GlassesInbound().onMessage(Channels.MARK_PHOTO, listOf(Wire.encode(r))))
        assertTrue(GlassesInbound().onMessage(Channels.MARK_PHOTO, listOf("not json")).isEmpty())
    }

    @Test fun aWrongVersionOfAPhotoFailureIsAnOutdatedEventNotARecord() {
        val ev = GlassesInbound().onMessage(Channels.MARK_PHOTO, listOf("""{"v":2,"epoch":"e","seq":1,"reason":"x"}"""))
        assertEquals(listOf<GlassesEvent>(GlassesEvent.Outdated(2)), ev)
    }

    /** The bounded exception: only a frame that names its mark passes the pause history; every other frame of the same moment is still dropped. */
    @Test fun aMarkPhotoPassesAPauseAnOrdinaryFrameDoesNot() = runTest {
        val a = FrameAdmission(ledger(), Paused())
        assertEquals(Admission.AckOnly(FrameAckReason.PAUSED_DROP), a.decide(meta("g1", null), ClockOffset(0), phoneNow))
        assertTrue(a.decide(meta("g2", "ep:1"), ClockOffset(0), phoneNow) is Admission.Ingest)
    }

    @Test fun aMarkPhotoIsStillDedupedAndStillNeedsTheClockOffset() = runTest {
        val l = ledger().also { it.add("g3") }
        val a = FrameAdmission(l, Paused())
        assertEquals(Admission.AckOnly(FrameAckReason.DUPLICATE), a.decide(meta("g3", "ep:1"), ClockOffset(0), phoneNow))
        assertEquals(Admission.Hold, a.decide(meta("g4", "ep:1"), offset = null, nowMs = phoneNow))
    }

    @Test fun aMalformedMarkReferenceDoesNotOpenThePauseException() = runTest {
        val a = FrameAdmission(ledger(), Paused())
        for (bad in listOf("", "no-colon", ":1", "ep:", "ep:x", "ep:1:2", "e p:1", "x".repeat(200) + ":1")) {
            assertEquals(Admission.AckOnly(FrameAckReason.PAUSED_DROP), a.decide(meta("b${bad.hashCode().toUInt()}", bad), ClockOffset(0), phoneNow), "ref=$bad")
        }
    }
}
