package com.rokid.context.glasses.mark

import com.rokid.context.core.model.MARK_PHOTO_HOLD_MAX_MS
import com.rokid.context.core.model.MARK_PHOTO_HOLD_MIN_MS
import com.rokid.context.core.model.MarkPhotoReason
import com.rokid.context.core.model.MotionState
import com.rokid.context.glasses.capture.KeyframeOut
import com.rokid.context.glasses.capture.session.CameraOwner
import com.rokid.context.glasses.capture.session.FrameCopy
import com.rokid.context.glasses.capture.session.KeyframeEncoder
import com.rokid.context.glasses.capture.session.SamplingCamera
import com.rokid.context.glasses.capture.keyframe.Candidate
import com.rokid.context.glasses.capture.keyframe.ImageHashes
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** How a mark's photo ended: [Taken] (buffered, its frame names the mark) or [Failed] with a [MarkPhotoReason] token. */
sealed interface MarkPhotoOutcome {
    data class Taken(val frameId: String) : MarkPhotoOutcome
    data class Failed(val reason: String) : MarkPhotoOutcome
}

/** What a mark needs from capture: take its photo, or throw away an undelivered one (the mark was undone). */
interface MarkPhotoRunner {
    suspend fun take(markRef: String): MarkPhotoOutcome
    fun discard(markRef: String)
}

/**
 * v0.9.6 slice C, "a mark touches capture": opens the camera explicitly for ONE photo and keeps it open for the hold (the user's `markPhotoHoldMs`, clamped to 2..4 s), then keeps the sharpest
 * usable frame of the hold. Every camera use goes through [owner] (one user at a time; it waits at most [ownerWaitMs] for a running sampling episode, then says `camera_busy`).
 *
 * **The bounded exception.** Except for a pause ZONE ([zoneActive]: photographing there is not allowed, a mark included), this class deliberately does NOT consult the capture pauses, the battery saver, the low-battery floor or the dark-frame skip (a mark is the user's explicit request, even
 * while capture is paused or the battery policy would stop it). It is bounded: one photo per call, the hold (at most [MARK_PHOTO_HOLD_MAX_MS]) of camera time, one camera at a time, the camera is released
 * on every path including cancellation, and heat STOP still refuses (hardware safety). Nothing else about capture changes: no pause, battery or quiet-hours rule is touched.
 * Pure seams, no logging (a mark never reaches the diagnostic files; the caller turns the outcome into the HUD cue and, on failure, a `pc_mark_photo`).
 */
class MarkPhotoCapture(
    private val owner: CameraOwner,
    private val camera: SamplingCamera,
    private val encoder: KeyframeEncoder,
    /** Buffers the photo for the phone: (frameId, keyframe, capturedAtMs, meanLuma, markRef); false = the buffer refused it. NOT the paused-checking `bufferCaptured`. */
    private val store: (String, KeyframeOut, Long, Int, String) -> Boolean,
    private val holdMs: () -> Long,
    private val heatStop: () -> Boolean,
    /** Wall clock, ms: the capture time of the photo and the spacing of the frames that are looked at. */
    private val clock: () -> Long,
    private val newFrameId: (Long) -> String,
    /** The gate is told of the kept frame (candidate, dhash, phash) so the next ordinary capture does not duplicate it. */
    private val onKeep: (Candidate, Long, Long) -> Unit = { _, _, _ -> },
    private val ownerWaitMs: Long = OWNER_WAIT_MS,
    private val frameGapMs: Long = FRAME_GAP_MS,
    /**
     * Pause zones: true = a `zone:` pause record is active (or its local approach hold): NO photo, whatever else is true. The one pause rule a mark does NOT bypass. A suspend seam so the pause store
     * is loaded first; REQUIRED (no default), so a caller cannot forget it.
     */
    private val zoneActive: suspend () -> Boolean,
    /** The same rule without suspending: asked inside the camera callback and right before the photo is stored. REQUIRED, like [zoneActive]. */
    private val zoneActiveNow: () -> Boolean,
) {
    private class Best(val copy: FrameCopy, val blur: Float, val at: Long)

    suspend fun take(markRef: String): MarkPhotoOutcome {
        if (zoneActive()) return MarkPhotoOutcome.Failed(MarkPhotoReason.PAUSE_ZONE) // before the camera is touched
        if (heatStop()) return MarkPhotoOutcome.Failed(MarkPhotoReason.HEAT)
        val hold = holdMs().coerceIn(MARK_PHOTO_HOLD_MIN_MS.toLong(), MARK_PHOTO_HOLD_MAX_MS.toLong())
        var acquired = false
        // The camera may be acquired up to ownerWaitMs later: the zone is asked again the moment the camera is ours, before it is opened.
        val r = withTimeoutOrNull(ownerWaitMs + hold + OPEN_SLACK_MS) {
            owner.acquire(OWNER) { acquired = true; if (zoneActive()) MarkPhotoOutcome.Failed(MarkPhotoReason.PAUSE_ZONE) else hold(markRef, hold) }
        }
        return r ?: MarkPhotoOutcome.Failed(if (acquired) MarkPhotoReason.NO_FRAME else MarkPhotoReason.CAMERA_BUSY)
    }

    private suspend fun hold(markRef: String, hold: Long): MarkPhotoOutcome {
        val lock = Any()
        var best: Best? = null
        var lastAt: Long? = null
        val zoneSeen = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            val opened = try { camera.open { src ->
                if (zoneActiveNow()) { zoneSeen.set(true); synchronized(lock) { best = null }; return@open } // a zone became active during the hold: nothing seen from then on is kept
                val now = clock()
                if (lastAt?.let { now - it < frameGapMs } == true) return@open
                val c = try { src.copy() } catch (e: Exception) { null } ?: return@open
                lastAt = now
                val b = ImageHashes.blur(c.thumb)
                synchronized(lock) { if (best == null || b > best!!.blur) best = Best(c, b, now) }
            } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { false }
            if (!opened) return MarkPhotoOutcome.Failed(if (camera.busy) MarkPhotoReason.CAMERA_BUSY else MarkPhotoReason.CAMERA_BIND_FAILED)
            delay(hold) // the camera stays open for the hold: the light is on, the exposure settles, the user sees it
        } finally {
            camera.close()
        }
        if (zoneSeen.get() || zoneActiveNow()) return MarkPhotoOutcome.Failed(MarkPhotoReason.PAUSE_ZONE) // after the hold: discard whatever was seen
        val b = synchronized(lock) { best } ?: return MarkPhotoOutcome.Failed(if (camera.busy) MarkPhotoReason.CAMERA_BUSY else MarkPhotoReason.NO_FRAME)
        val enc = try { encoder.encode(b.copy.pixels) } catch (e: Exception) { null } ?: return MarkPhotoOutcome.Failed(MarkPhotoReason.NO_FRAME)
        val d = ImageHashes.dHash(b.copy.thumb); val p = ImageHashes.pHash(b.copy.thumb)
        val luma = meanLuma(b.copy)
        val frameId = newFrameId(b.at)
        val out = KeyframeOut(enc.jpeg, enc.width, MotionState.UNKNOWN, dwell = false, blur = b.blur, dhash = hex(d), phash = hex(p), sampleSeq = b.at, sessionId = SESSION)
        if (!store(frameId, out, b.at, luma, markRef)) return MarkPhotoOutcome.Failed(if (zoneActiveNow()) MarkPhotoReason.PAUSE_ZONE else MarkPhotoReason.NOT_STORED)
        try { onKeep(Candidate(b.at, b.copy.thumb, b.copy.pixels), d, p) } catch (e: Exception) { /* the photo is stored; the gate's memory is a courtesy */ }
        return MarkPhotoOutcome.Taken(frameId)
    }

    private fun meanLuma(c: FrameCopy): Int {
        var sum = 0L; var n = 0
        for (i in c.thumb.luma.indices step 4) { sum += c.thumb.luma[i].toInt() and 0xFF; n++ }
        return (sum / n.coerceAtLeast(1)).toInt()
    }

    private fun hex(v: Long) = v.toULong().toString(16).padStart(16, '0')

    companion object {
        const val OWNER = "mark"
        const val SESSION = "mark"
        const val OWNER_WAIT_MS = 5_000L
        const val FRAME_GAP_MS = 250L
        /** The time to bind the camera and hand over the first frame, on top of the hold. */
        const val OPEN_SLACK_MS = 3_000L
    }
}
