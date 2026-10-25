package com.rokid.context.glasses.mark

import com.rokid.context.core.model.MarkMessage
import com.rokid.context.core.model.MarkPhotoReason
import com.rokid.context.core.model.MarkPhotoResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** v0.9.6 slice C: a tap (and so every voice mark) opens the camera for ONE photo without ever waiting for it; the wearer sees one short confirm or one short error line, the phone hears about a failure. */
@OptIn(ExperimentalCoroutinesApi::class)
class MarkTapPhotoTest {
    private class MemStore : MarkStore {
        var text: String? = null
        override fun read() = text
        override fun write(text: String): Boolean { this.text = text; return true }
    }

    private class Runner(var outcome: MarkPhotoOutcome = MarkPhotoOutcome.Taken("f1"), var gate: CompletableDeferred<Unit>? = null, var boom: Boolean = false) : MarkPhotoRunner {
        val takes = ArrayList<String>(); val discards = ArrayList<String>(); var cancelled = 0
        override suspend fun take(markRef: String): MarkPhotoOutcome {
            takes += markRef
            try { gate?.await() } catch (e: kotlinx.coroutines.CancellationException) { cancelled++; throw e }
            if (boom) throw IllegalStateException("camera")
            return outcome
        }
        override fun discard(markRef: String) { discards += markRef }
    }

    private val sent = ArrayList<MarkMessage>()
    private val reports = ArrayList<MarkPhotoResult>()
    private var linkUp = true
    private var reportUp = true
    private var wall = 5_000_000L

    private fun TestScope.rig(runner: MarkPhotoRunner?, enabled: Boolean = true): Triple<MarkTap, MarkOutbox, MarkCue> {
        val o = MarkOutbox(MemStore(), wallClock = { wall }, newEpoch = { "ep" })
        val cue = MarkCue(backgroundScope)
        val t = MarkTap(o, cue, send = { m -> if (linkUp) { sent += m; true } else false }, enabled = { enabled }, wallClock = { wall }, elapsed = { 777 }, bootCount = { 9 },
            photo = runner, scope = backgroundScope, sendPhotoResult = { r -> reports += r; reportUp })
        return Triple(t, o, cue)
    }

    @Test fun aTapWithACameraPromisesAPhotoAndStartsOneForThisMark() = runTest {
        val r = Runner(); val (t, _, _) = rig(r)
        t.onTap(listen = true); runCurrent()
        assertTrue(sent.single().photo)
        assertEquals(listOf("ep:1"), r.takes)
    }

    @Test fun aTapWithNoCameraSaysNothingAboutAPhoto() = runTest {
        val (t, _, _) = rig(null)
        t.onTap(listen = true); runCurrent()
        assertFalse(sent.single().photo)
    }

    @Test fun theTapNeverWaitsForThePhotoAndTheMarkIsAlreadySentAndCued() = runTest {
        val r = Runner(gate = CompletableDeferred()); val (t, _, cue) = rig(r)
        assertEquals(1L, t.onTap(true)); runCurrent()
        assertEquals(1, sent.size); assertEquals(MarkCue.MARKED, cue.text.value)
        assertTrue(reports.isEmpty())
    }

    @Test fun aTakenPhotoShowsOneShortConfirmAndSendsNothingElse() = runTest {
        val r = Runner(); val (t, _, cue) = rig(r)
        t.onTap(true); runCurrent()
        assertEquals(MarkCue.PHOTO_OK, cue.text.value)
        advanceTimeBy(MarkCue.DURATION_MS + 1); assertNull(cue.text.value)
        assertTrue(reports.isEmpty(), "the frame itself carries the news")
    }

    @Test fun aFailedPhotoShowsOneErrorLineForLongerAndTellsThePhone() = runTest {
        val r = Runner(MarkPhotoOutcome.Failed(MarkPhotoReason.CAMERA_BUSY)); val (t, _, cue) = rig(r)
        t.onTap(true); runCurrent()
        assertEquals(MarkCue.errorText(MarkPhotoReason.CAMERA_BUSY), cue.text.value)
        assertEquals("no photo: busy", cue.text.value)
        advanceTimeBy(MarkCue.ERROR_MS - 1); assertEquals("no photo: busy", cue.text.value)
        advanceTimeBy(2); assertNull(cue.text.value)
        assertEquals(listOf(MarkPhotoResult(epoch = "ep", seq = 1, reason = MarkPhotoReason.CAMERA_BUSY)), reports)
    }

    @Test fun aFailedReportThatCannotBeSentNeverBreaksTheTap() = runTest {
        reportUp = false
        val (t, _, cue) = rig(Runner(MarkPhotoOutcome.Failed(MarkPhotoReason.HEAT)))
        t.onTap(true); runCurrent()
        assertEquals("no photo: warm", cue.text.value)
    }

    @Test fun aCameraThatThrowsIsAFailureNotACrash() = runTest {
        val (t, _, cue) = rig(Runner(boom = true))
        t.onTap(true); runCurrent()
        assertEquals(MarkCue.errorText(MarkPhotoReason.NO_FRAME), cue.text.value)
        assertEquals(MarkPhotoReason.NO_FRAME, reports.single().reason)
    }

    @Test fun undoingTheMarkCancelsAPhotoStillBeingTakenAndDiscardsItAndReportsNothing() = runTest {
        val r = Runner(gate = CompletableDeferred()); val (t, _, cue) = rig(r)
        val seq = t.onTap(true)!!; runCurrent()
        t.onUndo(); runCurrent()
        assertEquals(1, r.cancelled); assertEquals(listOf("ep:$seq"), r.discards)
        assertEquals(MarkCue.UNDONE, cue.text.value); assertTrue(reports.isEmpty())
        assertEquals(seq, sent.last().undo)
    }

    @Test fun undoingAfterThePhotoWasBufferedDiscardsItToo() = runTest {
        val r = Runner(); val (t, _, _) = rig(r)
        t.onTap(true); runCurrent()
        t.onUndo(); runCurrent()
        assertEquals(listOf("ep:1"), r.discards)
    }

    @Test fun rapidTapsAreBoundedToThreePhotosInFlight() = runTest {
        val r = Runner(gate = CompletableDeferred()); val (t, _, _) = rig(r)
        repeat(4) { t.onTap(false); runCurrent() }
        assertEquals(3, r.takes.size)
        assertEquals(listOf(MarkPhotoResult(epoch = "ep", seq = 4, reason = MarkPhotoReason.CAMERA_BUSY)), reports)
        assertEquals(4, sent.size, "all four marks were still taken")
    }

    @Test fun aMarkTurnedOffTakesNoPhotoEither() = runTest {
        val r = Runner(); val (t, _, _) = rig(r, enabled = false)
        assertNull(t.onTap(true)); runCurrent()
        assertTrue(r.takes.isEmpty() && sent.isEmpty())
    }

    @Test fun aMarkThatCouldNotBeSentIsStillQueuedAndStillGetsItsPhoto() = runTest {
        linkUp = false
        val r = Runner(); val (t, o, _) = rig(r)
        t.onTap(true); runCurrent()
        assertEquals(1, o.size); assertTrue(o.all().single().photo); assertEquals(1, r.takes.size)
    }
}
