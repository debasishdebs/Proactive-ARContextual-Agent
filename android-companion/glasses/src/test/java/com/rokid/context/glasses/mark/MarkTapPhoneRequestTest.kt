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
    private fun req(refMs: Long = wall, at: Long = wall, session: Long = 1) = MarkPhotoRequest(ref = "phone:$refMs", atMs = at, sessionStartMs = session)

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
        t.onPhoneRequest(req()); runCurrent()
        assertEquals(listOf(MarkPhotoResult(epoch = "phone", seq = wall, reason = MarkPhotoReason.CAMERA_BUSY)), reports)
    }

    @Test fun aResentRequestNeverTakesASecondPhoto() = runTest {
        val r = Runner(); val (t, _) = rig(r)
        t.onPhoneRequest(req()); runCurrent(); t.onPhoneRequest(req()); runCurrent()
        assertEquals(1, r.takes.size)
    }

    @Test fun aMalformedReferenceIsDroppedBeforeAnyBookkeepingAndNeverTakesARateSlot() = runTest {
        val r = Runner(); val (t, _) = rig(r)
        repeat(20) { for (bad in listOf("", "nocolon", "phone:abc", "phone:1:2", "a b:1", "phone:")) t.onPhoneRequest(MarkPhotoRequest(ref = bad)) }
        assertEquals(0, t.consumedCount)
        for (i in 1..6) { t.onPhoneRequest(req(refMs = wall + i)); runCurrent() }
        assertEquals(6, r.takes.size, "the malformed flood used none of the six slots")
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

    @Test fun atMostSixOpensPerRollingMinuteWhateverTheRefsAndTheTimestamps() = runTest {
        val r = Runner(); val (t, _) = rig(r)
        // any phone timestamps (stale, future, zero) change nothing: the glasses' own clock decides
        for (i in 1..9) { t.onPhoneRequest(req(refMs = i.toLong(), at = if (i % 2 == 0) 0 else Long.MAX_VALUE / 2)); runCurrent() }
        assertEquals(6, r.takes.size)
        assertEquals(listOf(MarkPhotoReason.RATE_LIMITED), reports.map { it.reason }.distinct())
        assertEquals(3, reports.size)
        wall += 60_000
        t.onPhoneRequest(req(refMs = 99)); runCurrent()
        assertEquals(7, r.takes.size, "the minute rolls")
    }

    @Test fun refusedAndReplayedArrivalsCountAgainstTheSix() = runTest {
        val r = Runner(); val (t, _) = rig(r)
        t.onPhoneRequest(req(refMs = 1)); runCurrent()
        repeat(5) { t.onPhoneRequest(req(refMs = 1)) } // five replays use five slots
        t.onPhoneRequest(req(refMs = 2)); runCurrent()
        assertEquals(1, r.takes.size)
        assertEquals(listOf(MarkPhotoReason.RATE_LIMITED), reports.map { it.reason })
    }

    @Test fun anAdmittedRefStaysConsumedForTenMinutesAndOnlyThen() = runTest {
        val r = Runner(); val (t, _) = rig(r)
        t.onPhoneRequest(req(refMs = 5)); runCurrent()
        wall += 540_000; t.onPhoneRequest(req(refMs = 5)); runCurrent()
        assertEquals(1, r.takes.size, "still consumed after 9 minutes")
        wall += 70_000; t.onPhoneRequest(req(refMs = 5)); runCurrent()
        assertEquals(2, r.takes.size, "forgotten after 10 minutes")
    }

    @Test fun theConsumedSetStaysSmallAndOnlyHoldsAdmittedRefs() = runTest {
        val r = Runner(); val (t, _) = rig(r)
        for (i in 1..200) { wall += 10_000; t.onPhoneRequest(req(refMs = i.toLong())); runCurrent(); assertTrue(t.consumedCount <= 64) }
        assertTrue(t.consumedCount in 1..64)
        val n = t.consumedCount
        repeat(30) { t.onPhoneRequest(req(refMs = 10_000L + it)); t.onPhoneRequest(MarkPhotoRequest(ref = "bad")) }
        assertTrue(t.consumedCount <= 64 && n <= 64)
    }
}
