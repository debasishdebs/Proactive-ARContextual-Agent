package com.rokid.context.link

import com.rokid.context.core.model.FrameAckReason
import com.rokid.context.core.model.FrameMeta
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class FrameAdmissionTest {
    @get:Rule val tmp = TemporaryFolder()
    private val twoHours = 2 * 3_600_000L
    private val phoneNow = 1_793_000_000_000L

    /** Half-open phone-clock intervals [start, end); end null = still paused. */
    private class Intervals(vararg val spans: Pair<Long, Long?>) : PauseHistory {
        override suspend fun pausedAt(phoneMs: Long) = spans.any { (s, e) -> phoneMs >= s && (e == null || phoneMs < e) }
    }
    private fun ledger() = ReceivedFrameLedger(File(tmp.root, "ledger.txt"))
    private fun meta(id: String, capturedAtGlassesMs: Long) = FrameMeta(frameId = id, chunkIdx = 0, chunkCount = 1, crc32 = 1, capturedAtMs = capturedAtGlassesMs)

    /** Spec §5.5 fail-closed: no `pc_hello` offset yet → no ack, the glasses keep the frame. */
    @Test fun withoutAHelloOffsetFramesAreHeld() = runTest {
        assertEquals(Admission.Hold, FrameAdmission(ledger(), Intervals()).decide(meta("g1", phoneNow), offset = null, nowMs = phoneNow))
    }

    /** Review Focus 1: glasses 2 h ahead; phone paused for 30 min ending 30 min ago; a frame taken 10 min into it is dropped. */
    @Test fun frameInsideAPauseIsDroppedUsingTheCorrectedTime() = runTest {
        val pauseStart = phoneNow - 60 * 60_000
        val a = FrameAdmission(ledger(), Intervals(pauseStart to pauseStart + 30 * 60_000))
        val capturedOnGlasses = pauseStart + 10 * 60_000 + twoHours
        assertEquals(Admission.AckOnly(FrameAckReason.PAUSED_DROP), a.decide(meta("g1", capturedOnGlasses), ClockOffset(twoHours), phoneNow))
        // Uncorrected, the same stamp lands 70 min in the future, is clamped to now and slips past the pause.
        assertEquals(Admission.Ingest(phoneNow), a.decide(meta("g1", capturedOnGlasses), ClockOffset(0), phoneNow))
    }

    @Test fun frameOutsidePausesIsIngestedAtPhoneTime() = runTest {
        val a = FrameAdmission(ledger(), Intervals((phoneNow - 3_600_000) to (phoneNow - 1_800_000)))
        assertEquals(Admission.Ingest(phoneNow - 60_000), a.decide(meta("g2", phoneNow - 60_000 + twoHours), ClockOffset(twoHours), phoneNow))
    }

    @Test fun closedIntervalsAreHalfOpen() = runTest {
        val start = phoneNow - 600_000
        val end = phoneNow - 300_000
        val a = FrameAdmission(ledger(), Intervals(start to end))
        assertEquals(Admission.AckOnly(FrameAckReason.PAUSED_DROP), a.decide(meta("a", start), ClockOffset(0), phoneNow))
        assertEquals(Admission.Ingest(end), a.decide(meta("b", end), ClockOffset(0), phoneNow))
    }

    @Test fun anOpenIntervalDropsEverythingSinceItsStart() = runTest {
        val a = FrameAdmission(ledger(), Intervals((phoneNow - 1_000) to null))
        assertEquals(Admission.AckOnly(FrameAckReason.PAUSED_DROP), a.decide(meta("c", phoneNow - 10), ClockOffset(0), phoneNow))
    }

    /** Ack lost after ingest → the glasses resend → acked again, never ingested twice (also before a hello). */
    @Test fun alreadyIngestedFrameIsAckedNotReingested() = runTest {
        val l = ledger()
        l.add("g9")
        assertEquals(Admission.AckOnly(FrameAckReason.DUPLICATE), FrameAdmission(l, Intervals()).decide(meta("g9", phoneNow), offset = null, nowMs = phoneNow))
    }

    @Test fun framesOlderThanThePauseHistoryAreDropped() = runTest {
        val a = FrameAdmission(ledger(), Intervals())
        assertEquals(Admission.AckOnly(FrameAckReason.TOO_OLD), a.decide(meta("old", phoneNow - FrameAdmission.HISTORY_MS - 1), ClockOffset(0), phoneNow))
        assertEquals(Admission.Ingest(phoneNow - FrameAdmission.HISTORY_MS), a.decide(meta("edge", phoneNow - FrameAdmission.HISTORY_MS), ClockOffset(0), phoneNow))
    }

    @Test fun unsafeIdIsRejected() = runTest {
        assertEquals(Admission.Reject(FrameAckReason.DECODE), FrameAdmission(ledger(), Intervals()).decide(meta("../x", phoneNow), ClockOffset(0), phoneNow))
    }
}
