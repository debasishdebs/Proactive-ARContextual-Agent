package com.rokid.context.glasses.mark

import com.rokid.context.core.model.MarkMessage
import com.rokid.context.core.model.MarkPhotoReason
import com.rokid.context.core.model.MarkPhotoRequest
import com.rokid.context.core.model.MarkPhotoResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** v0.9.7 slice D: the photo of a PHONE-origin mark goes through the same job as a tap mark (same runner, same reasons), never twice, never more than three at once. */
@OptIn(ExperimentalCoroutinesApi::class)
class MarkTapPhoneRequestTest {
    private class MemStore : MarkStore { var text: String? = null; override fun read() = text; override fun write(text: String): Boolean { this.text = text; return true } }
    private class Runner(var outcome: MarkPhotoOutcome = MarkPhotoOutcome.Taken("f1"), var gate: CompletableDeferred<Unit>? = null) : MarkPhotoRunner {
        val takes = ArrayList<String>()
        override suspend fun take(markRef: String): MarkPhotoOutcome { takes += markRef; gate?.await(); return outcome }
        override fun discard(markRef: String) = Unit
    }
    private val reports = ArrayList<MarkPhotoResult>()
    private val sent = ArrayList<MarkMessage>()
    private var wall = 5_000_000L
    /** A request as the phone sends it: the ref is the phone ms of the mark, `atMs` the send time (phone clock = glasses clock here: offset 0). */
    private fun req(refMs: Long = wall, at: Long = wall, session: Long = wall - 10_000) = MarkPhotoRequest(ref = "phone:$refMs", atMs = at, sessionStartMs = session)

    private fun TestScope.rig(runner: MarkPhotoRunner?): Pair<MarkTap, MarkCue> {
        val cue = MarkCue(backgroundScope)
        val t = MarkTap(MarkOutbox(MemStore(), wallClock = { wall }, newEpoch = { "ep" }), cue, send = { sent += it; true }, enabled = { true }, wallClock = { wall }, elapsed = { 1 }, bootCount = { 1 },
            photo = runner, scope = backgroundScope, sendPhotoResult = { reports += it; true })
        return t to cue
    }

    @Test fun aRequestRunsThePhotoJobForItsReferenceAndRecordsNoMarkOnTheGlasses() = runTest {
        val r = Runner(); val (t, cue) = rig(r)
        t.onPhoneRequest(req()); runCurrent()
        assertEquals(listOf("phone:$wall"), r.takes)
        assertTrue(sent.isEmpty(), "the phone owns this mark: nothing is sent on pc_mark")
        assertEquals(MarkCue.PHOTO_OK, cue.text.value)
    }

    @Test fun aFailureIsReportedWithTheMarksEpochAndSeq() = runTest {
        val r = Runner(outcome = MarkPhotoOutcome.Failed(MarkPhotoReason.CAMERA_BUSY)); val (t, _) = rig(r)
        t.onPhoneRequest(req(refMs = wall)); runCurrent()
        assertEquals(listOf(MarkPhotoResult(epoch = "phone", seq = wall, reason = MarkPhotoReason.CAMERA_BUSY)), reports)
    }

    @Test fun aResentRequestNeverTakesASecondPhoto() = runTest {
        val r = Runner(); val (t, _) = rig(r)
        t.onPhoneRequest(req()); runCurrent(); t.onPhoneRequest(req()); runCurrent()
        assertEquals(1, r.takes.size)
    }

    @Test fun aMalformedReferenceIsIgnored() = runTest {
        val r = Runner(); val (t, _) = rig(r)
        for (bad in listOf("", "nocolon", "phone:abc", "phone:1:2", "a b:1", "phone:")) t.onPhoneRequest(MarkPhotoRequest(ref = bad, atMs = wall, sessionStartMs = 1))
        runCurrent()
        assertTrue(r.takes.isEmpty()); assertTrue(reports.isEmpty())
    }

    @Test fun aFourthRequestInFlightFailsFastWithCameraBusy() = runTest {
        val r = Runner(gate = CompletableDeferred()); val (t, _) = rig(r)
        for (i in 1..4) t.onPhoneRequest(req(refMs = wall + i))
        runCurrent()
        assertEquals(3, r.takes.size)
        assertEquals(listOf(MarkPhotoResult(epoch = "phone", seq = wall + 4, reason = MarkPhotoReason.CAMERA_BUSY)), reports)
    }

    @Test fun aBuildWithoutACameraIgnoresTheRequest() = runTest {
        val (t, _) = rig(null)
        t.onPhoneRequest(req()); runCurrent()
        assertTrue(reports.isEmpty())
    }

    @Test fun aReferenceOlderThanAMinuteOrAheadOfThePhoneIsRefused() = runTest {
        val r = Runner(); val (t, _) = rig(r)
        t.onPhoneRequest(req(refMs = wall)); runCurrent() // teaches the offset
        t.onPhoneRequest(req(refMs = wall - 61_000)); t.onPhoneRequest(req(refMs = wall + 6_000)); runCurrent()
        assertEquals(listOf("phone:$wall"), r.takes)
        t.onPhoneRequest(req(refMs = wall - 59_000)); t.onPhoneRequest(req(refMs = wall + 4_000)); runCurrent()
        assertEquals(3, r.takes.size)
    }

    @Test fun aRequestDeliveredLongAfterItWasSentIsRefused() = runTest {
        val r = Runner(); val (t, _) = rig(r)
        t.onPhoneRequest(req(refMs = wall)); runCurrent()
        val sentAt = wall; wall += 61_000
        t.onPhoneRequest(req(refMs = wall - 1_000, at = sentAt)); runCurrent()
        assertEquals(1, r.takes.size)
    }

    @Test fun aRequestWithNoClockOrOlderThanItsSessionIsRefused() = runTest {
        val r = Runner(); val (t, _) = rig(r)
        t.onPhoneRequest(MarkPhotoRequest(ref = "phone:$wall")); t.onPhoneRequest(req(session = wall + 1)); runCurrent()
        assertTrue(r.takes.isEmpty())
    }

    @Test fun aConsumedReferenceStaysConsumedForTheWholeFreshnessWindowWhateverElseCameIn() = runTest {
        val r = Runner(); val (t, _) = rig(r)
        t.onPhoneRequest(req(refMs = wall)); runCurrent()
        val first = wall
        // 6 attempts a minute is all the glasses allow: spread more than 32 distinct refs over minutes, then replay the first inside its freshness window
        for (i in 1..40) { wall += 12_000; t.onPhoneRequest(req(refMs = wall)); runCurrent() }
        wall = first + 30_000 // back inside the window of the first ref (a replayed old message)
        val before = r.takes.size
        t.onPhoneRequest(req(refMs = first, at = wall)); runCurrent()
        assertEquals(before, r.takes.size, "not run again")
    }

    @Test fun moreThanSixRequestsInAMinuteAreRateLimitedAndRefusedOnesCountToo() = runTest {
        val r = Runner(); val (t, _) = rig(r)
        repeat(3) { t.onPhoneRequest(req(refMs = wall - 70_000 - it)) } // stale ones count against the rate
        for (i in 1..3) { t.onPhoneRequest(req(refMs = wall + i)); runCurrent() }
        assertEquals(3, r.takes.size)
        t.onPhoneRequest(req(refMs = wall + 10)); runCurrent()
        assertEquals(3, r.takes.size)
        assertEquals(listOf(MarkPhotoReason.RATE_LIMITED), reports.map { it.reason })
        wall += 61_000
        t.onPhoneRequest(req(refMs = wall)); runCurrent()
        assertEquals(4, r.takes.size, "the window rolls")
    }

    @Test fun aNewSessionLearnsTheClockAgain() = runTest {
        val r = Runner(); val (t, _) = rig(r)
        t.onPhoneRequest(req(refMs = wall)); runCurrent()
        t.onSessionClosed()
        wall += 7_200_000 // the phone clock is now 2 h away: only a re-learned offset accepts it
        t.onPhoneRequest(req(refMs = wall)); runCurrent()
        assertEquals(2, r.takes.size)
    }
}
