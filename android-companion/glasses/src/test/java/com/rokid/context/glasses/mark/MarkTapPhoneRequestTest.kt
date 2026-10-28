package com.rokid.context.glasses.mark

import com.rokid.context.core.model.MarkMessage
import com.rokid.context.core.model.MarkPhotoReason
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

    private fun TestScope.rig(runner: MarkPhotoRunner?): Pair<MarkTap, MarkCue> {
        val cue = MarkCue(backgroundScope)
        val t = MarkTap(MarkOutbox(MemStore(), wallClock = { 1L }, newEpoch = { "ep" }), cue, send = { sent += it; true }, enabled = { true }, wallClock = { 1L }, elapsed = { 1 }, bootCount = { 1 },
            photo = runner, scope = backgroundScope, sendPhotoResult = { reports += it; true })
        return t to cue
    }

    @Test fun aRequestRunsThePhotoJobForItsReferenceAndRecordsNoMarkOnTheGlasses() = runTest {
        val r = Runner(); val (t, cue) = rig(r)
        t.onPhoneRequest("phone:1700000000123"); runCurrent()
        assertEquals(listOf("phone:1700000000123"), r.takes)
        assertTrue(sent.isEmpty(), "the phone owns this mark: nothing is sent on pc_mark")
        assertEquals(MarkCue.PHOTO_OK, cue.text.value)
    }

    @Test fun aFailureIsReportedWithTheMarksEpochAndSeq() = runTest {
        val r = Runner(outcome = MarkPhotoOutcome.Failed(MarkPhotoReason.CAMERA_BUSY)); val (t, _) = rig(r)
        t.onPhoneRequest("phone:42"); runCurrent()
        assertEquals(listOf(MarkPhotoResult(epoch = "phone", seq = 42, reason = MarkPhotoReason.CAMERA_BUSY)), reports)
    }

    @Test fun aResentRequestNeverTakesASecondPhoto() = runTest {
        val r = Runner(); val (t, _) = rig(r)
        t.onPhoneRequest("phone:42"); runCurrent(); t.onPhoneRequest("phone:42"); runCurrent()
        assertEquals(1, r.takes.size)
    }

    @Test fun aMalformedReferenceIsIgnored() = runTest {
        val r = Runner(); val (t, _) = rig(r)
        for (bad in listOf("", "nocolon", "phone:abc", "phone:1:2", "a b:1", "phone:")) t.onPhoneRequest(bad)
        runCurrent()
        assertTrue(r.takes.isEmpty()); assertTrue(reports.isEmpty())
    }

    @Test fun aFourthRequestInFlightFailsFastWithCameraBusy() = runTest {
        val r = Runner(gate = CompletableDeferred()); val (t, _) = rig(r)
        for (i in 1..4) t.onPhoneRequest("phone:$i")
        runCurrent()
        assertEquals(3, r.takes.size)
        assertEquals(listOf(MarkPhotoResult(epoch = "phone", seq = 4, reason = MarkPhotoReason.CAMERA_BUSY)), reports)
    }

    @Test fun aBuildWithoutACameraIgnoresTheRequest() = runTest {
        val (t, _) = rig(null)
        t.onPhoneRequest("phone:1"); runCurrent()
        assertTrue(reports.isEmpty())
    }
}
