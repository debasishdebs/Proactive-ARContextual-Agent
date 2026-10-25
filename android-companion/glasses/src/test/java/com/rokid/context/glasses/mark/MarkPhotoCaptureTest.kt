package com.rokid.context.glasses.mark

import com.rokid.context.core.model.MarkPhotoReason
import com.rokid.context.glasses.capture.KeyframeOut
import com.rokid.context.glasses.capture.session.CameraOwner
import com.rokid.context.glasses.capture.session.EncodedKeyframe
import com.rokid.context.glasses.capture.session.FrameCopy
import com.rokid.context.glasses.capture.session.FrameSource
import com.rokid.context.glasses.capture.session.KeyframeEncoder
import com.rokid.context.glasses.capture.session.SamplingCamera
import com.rokid.context.glasses.capture.keyframe.Candidate
import com.rokid.context.glasses.capture.keyframe.Nv21Frame
import com.rokid.context.glasses.capture.keyframe.Thumb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * v0.9.6 slice C: the photo of a mark. The camera is opened explicitly and held for the hold; the sharpest usable frame of the hold is the photo; every failure is a reason token and the camera is always released.
 * No pause, battery-saver, low-battery or dark rule is consulted anywhere in this class (that is the bounded exception); only heat STOP and the camera itself can refuse.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MarkPhotoCaptureTest {
    private fun thumb(sharp: Boolean): Thumb = Thumb(16, 12, ByteArray(16 * 12) { i -> if (sharp) (if ((i + i / 16) % 2 == 0) 0 else 255).toByte() else 100 })
    private fun copy(sharp: Boolean) = FrameCopy(thumb(sharp), Nv21Frame(640, 480, 0, ByteArray(4)))

    /** Frames arrive [at] ms after the camera opened; [close] times are recorded in virtual time. */
    private class FakeCamera(val scope: TestScope, val frames: List<Pair<Long, FrameCopy>> = emptyList(), var openOk: Boolean = true, override var busy: Boolean = false) : SamplingCamera {
        var opened = 0; var openedAt = -1L; val closedAt = ArrayList<Long>(); var copies = 0
        override suspend fun open(onFrame: (FrameSource) -> Unit): Boolean {
            opened++; openedAt = scope.testScheduler.currentTime
            if (!openOk) return false
            scope.backgroundScope.launch { var last = 0L; for ((at, f) in frames) { delay(at - last); last = at; onFrame(object : FrameSource { override fun copy(): FrameCopy { copies++; return f } }) } }
            return true
        }
        override fun close() { closedAt += scope.testScheduler.currentTime }
    }

    private class Stored(val frameId: String, val out: KeyframeOut, val capturedAtMs: Long, val luma: Int, val markRef: String)
    private val stored = ArrayList<Stored>()
    private var storeOk = true
    private var heat = false
    private var hold = 3_000L
    private var encoderOk = true
    private val kept = ArrayList<Candidate>()

    private fun TestScope.capture(cam: SamplingCamera, owner: CameraOwner = CameraOwner(), ownerWaitMs: Long = 5_000) = MarkPhotoCapture(
        owner, cam, KeyframeEncoder { if (encoderOk) EncodedKeyframe(byteArrayOf(9, 9), 640, 480) else null },
        store = { id, out, at, luma, ref -> if (storeOk) stored += Stored(id, out, at, luma, ref); storeOk },
        holdMs = { hold }, heatStop = { heat }, clock = { testScheduler.currentTime }, newFrameId = { "mk${it}" },
        onKeep = { c, _, _ -> kept += c }, ownerWaitMs = ownerWaitMs, frameGapMs = 250,
    )

    @Test fun theCameraStaysOpenForTheHoldAndTheSharpestFrameIsThePhoto() = runTest {
        val cam = FakeCamera(this, listOf(100L to copy(false), 400L to copy(true), 900L to copy(false), 2_500L to copy(false)))
        val r = capture(cam).take("ep:3")
        assertIs<MarkPhotoOutcome.Taken>(r)
        assertEquals(listOf(3_000L), cam.closedAt, "released exactly once, only after the hold")
        assertEquals(3_000L, testScheduler.currentTime)
        val s = stored.single()
        assertEquals("ep:3", s.markRef); assertEquals(r.frameId, s.frameId)
        assertEquals(400L, s.capturedAtMs, "the sharp frame, seen at 400 ms")
        assertTrue(s.out.blur > 100f, "blur ${s.out.blur}")
        assertEquals(1, kept.size, "the gate is told so the next ordinary capture does not duplicate it")
    }

    @Test fun framesCloserThanTheGapAreNotAllCopied() = runTest {
        val cam = FakeCamera(this, (0..30).map { (it * 10L) to copy(false) } + (500L to copy(true)))
        val cap = capture(cam)
        cap.take("ep:3")
        assertEquals(500L, stored.single().capturedAtMs)
        assertTrue(cam.copies <= 3, "31 frames in 300 ms are one look, not 31 copies: ${cam.copies}")
    }

    @Test fun theHoldIsClampedToTwoToFourSeconds() = runTest {
        hold = 100; val a = FakeCamera(this, listOf(50L to copy(true))); capture(a).take("ep:1"); assertEquals(2_000L, a.closedAt.single() - a.openedAt)
        hold = 99_000; val t0 = testScheduler.currentTime; val b = FakeCamera(this, listOf(50L to copy(true))); capture(b).take("ep:2"); assertEquals(4_000L, b.closedAt.single() - t0)
    }

    @Test fun heatStopRefusesBeforeTheCameraIsOpened() = runTest {
        heat = true; val cam = FakeCamera(this, listOf(10L to copy(true)))
        assertEquals(MarkPhotoOutcome.Failed(MarkPhotoReason.HEAT), capture(cam).take("ep:1"))
        assertEquals(0, cam.opened); assertTrue(stored.isEmpty())
    }

    @Test fun aBusyCameraOwnerWaitsAtMostFiveSecondsThenSaysBusy() = runTest {
        val owner = CameraOwner(); val cam = FakeCamera(this, listOf(10L to copy(true)))
        val holder = backgroundScope.launch { owner.acquire("session") { delay(60_000) } }
        runCurrent()
        val r = async { capture(cam, owner).take("ep:1") }
        advanceTimeBy(4_999); runCurrent(); assertFalse(r.isCompleted)
        advanceTimeBy(1_100); runCurrent()
        assertEquals(MarkPhotoOutcome.Failed(MarkPhotoReason.CAMERA_BUSY), r.await())
        assertEquals(0, cam.opened); holder.cancel()
    }

    @Test fun anOwnerThatFreesUpInTimeLetsTheMarkThrough() = runTest {
        val owner = CameraOwner(); val cam = FakeCamera(this, listOf(10L to copy(true)))
        backgroundScope.launch { owner.acquire("session") { delay(2_000) } }
        runCurrent()
        assertIs<MarkPhotoOutcome.Taken>(capture(cam, owner).take("ep:1"))
        assertEquals(1, cam.opened)
    }

    @Test fun aFailedBindIsABindFailureOrBusyWhenAnotherAppHoldsTheCamera() = runTest {
        val a = FakeCamera(this, openOk = false)
        assertEquals(MarkPhotoOutcome.Failed(MarkPhotoReason.CAMERA_BIND_FAILED), capture(a).take("ep:1"))
        val b = FakeCamera(this, openOk = false, busy = true)
        assertEquals(MarkPhotoOutcome.Failed(MarkPhotoReason.CAMERA_BUSY), capture(b).take("ep:2"))
        assertEquals(1, a.closedAt.size); assertEquals(1, b.closedAt.size)
    }

    @Test fun noUsableFrameInTheHoldIsNoFrameAndAnEncoderFailureToo() = runTest {
        val a = FakeCamera(this, emptyList())
        assertEquals(MarkPhotoOutcome.Failed(MarkPhotoReason.NO_FRAME), capture(a).take("ep:1"))
        encoderOk = false
        val b = FakeCamera(this, listOf(10L to copy(true)))
        assertEquals(MarkPhotoOutcome.Failed(MarkPhotoReason.NO_FRAME), capture(b).take("ep:2"))
        assertEquals(1, b.closedAt.size); assertTrue(stored.isEmpty())
    }

    @Test fun aBufferThatCouldNotStoreTheFrameIsNotStored() = runTest {
        storeOk = false
        assertEquals(MarkPhotoOutcome.Failed(MarkPhotoReason.NOT_STORED), capture(FakeCamera(this, listOf(10L to copy(true)))).take("ep:1"))
    }

    @Test fun aCancelledPhotoReleasesTheCameraAndStoresNothing() = runTest {
        val cam = FakeCamera(this, listOf(10L to copy(true)))
        val job = backgroundScope.launch { capture(cam).take("ep:1") }
        advanceTimeBy(1_000); runCurrent()
        job.cancel(); runCurrent()
        assertEquals(1, cam.closedAt.size); assertTrue(stored.isEmpty())
    }

    @Test fun twoPhotosShareOneCameraOneAfterTheOther() = runTest {
        val owner = CameraOwner(); val c1 = FakeCamera(this, listOf(10L to copy(true))); val c2 = FakeCamera(this, listOf(10L to copy(true)))
        val a = async { capture(c1, owner).take("ep:1") }; val b = async { capture(c2, owner, ownerWaitMs = 10_000).take("ep:2") }
        a.await(); b.await()
        assertTrue(c2.openedAt >= c1.closedAt.single(), "never two bound cameras at once")
        assertEquals(listOf("ep:1", "ep:2"), stored.map { it.markRef })
    }
}
