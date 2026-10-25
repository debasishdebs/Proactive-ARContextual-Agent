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

    private class Marks(var state: MarkState = MarkState.OPEN) : MarkLookup {
        val asked = ArrayList<String>(); val durable = HashMap<String, String>()
        override suspend fun state(markRef: String): MarkState { asked += markRef; return state }
        override suspend fun claim(markRef: String, frameId: String): Boolean = durable.getOrPut(markRef) { frameId } == frameId
    }
    private fun admission(m: Marks, cap: Int = 16) = FrameAdmission(ledger(), Paused(), marks = MarkPhotoAllowance(m, cap))

    /** The bounded exception: only a frame of a REAL, still-waiting mark passes the pause history; every other frame of the same moment is still dropped. */
    @Test fun aMarkPhotoPassesAPauseOnlyForARealOpenMarkAnOrdinaryFrameDoesNot() = runTest {
        val a = admission(Marks())
        assertEquals(Admission.AckOnly(FrameAckReason.PAUSED_DROP), a.decide(meta("g1", null), ClockOffset(0), phoneNow))
        assertTrue(a.decide(meta("g2", "ep:1"), ClockOffset(0), phoneNow) is Admission.Ingest)
    }

    @Test fun aReferenceToNoRealMarkIsDroppedOrHeldNeverIngested() = runTest {
        assertEquals(Admission.AckOnly(FrameAckReason.PAUSED_DROP), admission(Marks(MarkState.CLOSED)).decide(meta("g1", "fake:1"), ClockOffset(0), phoneNow))
        assertEquals(Admission.Hold, admission(Marks(MarkState.UNKNOWN)).decide(meta("g2", "fake:1"), ClockOffset(0), phoneNow), "the mark may simply not have arrived yet: no ack, the glasses resend")
        assertEquals(Admission.AckOnly(FrameAckReason.PAUSED_DROP), FrameAdmission(ledger(), Paused()).decide(meta("g3", "ep:1"), ClockOffset(0), phoneNow), "with no lookup at all nothing passes")
    }

    @Test fun oneMarkAllowsOneFrameManyIdsWithOneReferenceAreNotUnlimitedPhotos() = runTest {
        val a = admission(Marks())
        assertTrue(a.decide(meta("p1", "ep:1"), ClockOffset(0), phoneNow) is Admission.Ingest)
        assertEquals(Admission.AckOnly(FrameAckReason.PAUSED_DROP), a.decide(meta("p2", "ep:1"), ClockOffset(0), phoneNow))
        assertEquals(Admission.AckOnly(FrameAckReason.PAUSED_DROP), a.decide(meta("p3", "ep:1"), ClockOffset(0), phoneNow))
        assertTrue(a.decide(meta("p1", "ep:1"), ClockOffset(0), phoneNow) is Admission.Ingest, "a resend of the SAME frame passes again")
    }

    @Test fun onlyAFewMarksAreClaimedAtOnce() = runTest {
        val allow = MarkPhotoAllowance(Marks(), cap = 2)
        val a = FrameAdmission(ledger(), Paused(), marks = allow)
        for (i in 1..3) assertTrue(a.decide(meta("p$i", "ep:$i"), ClockOffset(0), phoneNow) is Admission.Ingest)
        assertEquals(2, allow.claimed, "the oldest claim was evicted, so the cache stays bounded")
    }

    /** Codex recheck: cycling fresh frame ids over more pending marks than the cache holds, or a restart, must not reopen a mark's allowance. */
    @Test fun anEvictedClaimOrARestartDoesNotReopenTheAllowance() = runTest {
        val marks = Marks()
        val a = FrameAdmission(ledger(), Paused(), marks = MarkPhotoAllowance(marks, cap = 2))
        for (i in 1..5) assertTrue(a.decide(meta("p$i", "ep:$i"), ClockOffset(0), phoneNow) is Admission.Ingest)
        for (i in 1..5) assertEquals(Admission.AckOnly(FrameAckReason.PAUSED_DROP), a.decide(meta("x$i", "ep:$i"), ClockOffset(0), phoneNow), "mark $i already has its frame")
        val restarted = FrameAdmission(ledger(), Paused(), marks = MarkPhotoAllowance(marks, cap = 2))
        assertEquals(Admission.AckOnly(FrameAckReason.PAUSED_DROP), restarted.decide(meta("y1", "ep:1"), ClockOffset(0), phoneNow))
        assertTrue(restarted.decide(meta("p1", "ep:1"), ClockOffset(0), phoneNow) is Admission.Ingest, "its own frame still passes after a restart")
    }

    @Test fun aMarkPhotoIsStillDedupedAndStillNeedsTheClockOffset() = runTest {
        val l = ledger().also { it.add("g3") }
        val a = FrameAdmission(l, Paused(), marks = MarkPhotoAllowance(Marks()))
        assertEquals(Admission.AckOnly(FrameAckReason.DUPLICATE), a.decide(meta("g3", "ep:1"), ClockOffset(0), phoneNow))
        assertEquals(Admission.Hold, a.decide(meta("g4", "ep:1"), offset = null, nowMs = phoneNow))
    }

    @Test fun aMalformedMarkReferenceNeverEvenAsksTheLookup() = runTest {
        val m = Marks()
        val a = admission(m)
        for (bad in listOf("", "no-colon", ":1", "ep:", "ep:x", "ep:1:2", "e p:1", "x".repeat(200) + ":1")) {
            assertEquals(Admission.AckOnly(FrameAckReason.PAUSED_DROP), a.decide(meta("b${bad.hashCode().toUInt()}", bad), ClockOffset(0), phoneNow), "ref=$bad")
        }
        assertTrue(m.asked.isEmpty())
    }
}
