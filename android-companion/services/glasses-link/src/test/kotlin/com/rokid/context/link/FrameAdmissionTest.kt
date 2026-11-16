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
        // Uncorrected (stale offset), the same stamp lands 70 min in the future: held, never judged at "now".
        assertEquals(Admission.Hold, a.decide(meta("g1", capturedOnGlasses), ClockOffset(0), phoneNow))
    }

    @Test fun futureFrameWithinSkewToleranceIsIngestedAtNow() = runTest {
        val a = FrameAdmission(ledger(), Intervals())
        assertEquals(Admission.Ingest(phoneNow), a.decide(meta("s1", phoneNow + 2_000), ClockOffset(0), phoneNow))
        assertEquals(Admission.Hold, a.decide(meta("s2", phoneNow + FrameAdmission.SKEW_TOLERANCE_MS + 1), ClockOffset(0), phoneNow))
    }

    @Test fun extremeTimestampsDoNotOverflowIntoAdmission() = runTest {
        val a = FrameAdmission(ledger(), Intervals())
        assertEquals(Admission.Hold, a.decide(meta("m1", Long.MAX_VALUE), ClockOffset(-5), phoneNow))
        assertEquals(Admission.AckOnly(FrameAckReason.TOO_OLD), a.decide(meta("m2", Long.MIN_VALUE), ClockOffset(5), phoneNow))
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

    // ---- Pause zones: a frame captured inside a zone interval is dropped whatever it is, before any mark allowance is asked. ----

    /** Ordinary pauses and zone intervals separately; a zone interval is also a pause (like the hub's Room history). */
    private class ZoneHistory(val zone: List<Pair<Long, Long?>>, val other: List<Pair<Long, Long?>> = emptyList()) : PauseHistory {
        private fun List<Pair<Long, Long?>>.covers(t: Long) = any { (s, e) -> t >= s && (e == null || t < e) }
        override suspend fun pausedAt(phoneMs: Long) = zone.covers(phoneMs) || other.covers(phoneMs)
        override suspend fun zonePausedAt(phoneMs: Long) = zone.covers(phoneMs)
    }
    private class CountingMarks(var state: MarkState = MarkState.OPEN) : MarkLookup {
        var asked = 0; var claimed = 0
        override suspend fun state(markRef: String): MarkState { asked++; return state }
        override suspend fun claim(markRef: String, frameId: String): Boolean { claimed++; return true }
    }
    private fun markMeta(id: String, markRef: String?, at: Long) = FrameMeta(frameId = id, chunkIdx = 0, chunkCount = 1, crc32 = 1, capturedAtMs = at, markRef = markRef)

    @Test fun aFrameInsideAZoneIntervalIsDropped() = runTest {
        val a = FrameAdmission(ledger(), ZoneHistory(listOf((phoneNow - 600_000) to null)))
        assertEquals(Admission.AckOnly(FrameAckReason.PAUSED_DROP), a.decide(meta("z1", phoneNow - 1_000), ClockOffset(0), phoneNow))
    }

    @Test fun aMarkPhotoInsideAZoneIntervalIsDroppedAndTheAllowanceIsNeverAsked() = runTest {
        val marks = CountingMarks()
        val a = FrameAdmission(ledger(), ZoneHistory(listOf((phoneNow - 600_000) to null)), marks = MarkPhotoAllowance(marks))
        assertEquals(Admission.AckOnly(FrameAckReason.PAUSED_DROP), a.decide(markMeta("z2", "ep:1", phoneNow - 1_000), ClockOffset(0), phoneNow))
        assertEquals(0, marks.asked, "a zone beats the mark allowance before it is looked at"); assertEquals(0, marks.claimed, "no allowance is consumed")
    }

    @Test fun aMarkPhotoDuringAnOrdinaryPauseStillPassesButNotInAZone() = runTest {
        val a = FrameAdmission(ledger(), ZoneHistory(zone = listOf((phoneNow - 100_000) to (phoneNow - 50_000)), other = listOf((phoneNow - 600_000) to null)), marks = MarkPhotoAllowance(CountingMarks()))
        // before the zone began: an ordinary pause only, the mark allowance applies as before
        assertEquals(Admission.Ingest(phoneNow - 300_000), a.decide(markMeta("z3", "ep:1", phoneNow - 300_000), ClockOffset(0), phoneNow))
        // inside the zone interval, even though the same ordinary pause also covers it
        assertEquals(Admission.AckOnly(FrameAckReason.PAUSED_DROP), a.decide(markMeta("z4", "ep:2", phoneNow - 75_000), ClockOffset(0), phoneNow))
        // after the zone interval closed (half open): the ordinary pause and the allowance decide again
        assertEquals(Admission.Ingest(phoneNow - 50_000), a.decide(markMeta("z5", "ep:3", phoneNow - 50_000), ClockOffset(0), phoneNow))
    }

    @Test fun theZoneIntervalIsHalfOpenAndUsesTheCorrectedTime() = runTest {
        val start = phoneNow - 600_000; val end = phoneNow - 300_000
        val a = FrameAdmission(ledger(), ZoneHistory(listOf(start to end)))
        assertEquals(Admission.AckOnly(FrameAckReason.PAUSED_DROP), a.decide(meta("h1", start + twoHours), ClockOffset(twoHours), phoneNow))
        assertEquals(Admission.Ingest(end), a.decide(meta("h2", end), ClockOffset(0), phoneNow))
        assertEquals(Admission.Ingest(start - 1), a.decide(meta("h3", start - 1), ClockOffset(0), phoneNow))
    }

    @Test fun aHistoryThatKnowsNothingOfZonesChangesNothing() = runTest {
        // the default zonePausedAt is false: the old interface contract holds for any other history
        assertEquals(Admission.Ingest(phoneNow - 10), FrameAdmission(ledger(), Intervals()).decide(meta("n1", phoneNow - 10), ClockOffset(0), phoneNow))
    }
}
