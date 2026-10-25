package com.rokid.context.glasses.mark

import com.rokid.context.core.model.MarkMessage
import com.rokid.context.core.model.MarkPhotoReason
import com.rokid.context.core.model.MarkPhotoResult
import com.rokid.context.core.model.MarkRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** The wearer's feedback for a mark, local and never waiting for the phone: a corner "✓" for 600 ms, or "mark undone" on undo. */
class MarkCue(private val scope: CoroutineScope, private val durationMs: Long = DURATION_MS) {
    private val _text = MutableStateFlow<String?>(null)
    val text: StateFlow<String?> = _text
    private var generation = 0L

    fun show(text: String, forMs: Long = durationMs) {
        val g = synchronized(this) { _text.value = text; ++generation }
        scope.launch { delay(forMs); synchronized(this@MarkCue) { if (generation == g) _text.value = null } }
    }

    companion object {
        const val DURATION_MS = 600L; const val MARKED = "✓"; const val UNDONE = "mark undone"
        /** v0.9.6 slice C: the photo of the mark was taken (one short confirm, after the check). */
        const val PHOTO_OK = "photo ✓"
        /** The error line stays long enough to read (one line, once per mark). */
        const val ERROR_MS = 2_500L
        /** One short line per reason token (never more than 14 characters: it sits in a corner of the display). */
        fun errorText(reason: String): String = when (reason) {
            MarkPhotoReason.CAMERA_BUSY -> "no photo: busy"
            MarkPhotoReason.HEAT -> "no photo: warm"
            MarkPhotoReason.NOT_STORED -> "no photo: full"
            else -> "no photo"
        }
    }
}

/**
 * Slice 3a Task 30 (design §4.3): a D-pad tap while our app is in the foreground takes a mark, then the normal push-to-talk goes on.
 * The mark is queued (persisted) first, sent if the link is up, and the cue shows at once. A double-tap while that listen is recording
 * undoes its mark ([onUndo]); once the listen is finished or cancelled the mark is final.
 *
 * **v0.9.6 slice C: a mark touches capture** (this supersedes slice 3a's S45 "a mark never touches capture"). With a [photo] runner the mark says
 * `photo = true` and ONE photo job is started for it (a tap and a voice mark are the same tap): the camera is opened explicitly, even when capture is
 * paused or the battery policy would stop it (see [MarkPhotoCapture] for the bound). The tap never waits for it; the wearer sees one short confirm, or
 * one short error line, and a failure is reported to the phone (`pc_mark_photo`, best effort: a lost report is the phone's photo timeout). At most
 * [MAX_IN_FLIGHT] photos run at once (rapid taps cannot hold the camera). Undoing the mark cancels its photo and throws away an undelivered one.
 */
class MarkTap(
    private val outbox: MarkOutbox,
    private val cue: MarkCue,
    private val send: (MarkMessage) -> Boolean,
    private val enabled: () -> Boolean,
    private val wallClock: () -> Long,
    private val elapsed: () -> Long,
    private val bootCount: () -> Int,
    /** Takes the photo of a mark; null = this build cannot (the mark then does not promise one). */
    private val photo: MarkPhotoRunner? = null,
    /** Where the photo jobs run (never the main thread of the tap). */
    private val scope: CoroutineScope? = null,
    /** Best-effort `pc_mark_photo`: false = it could not be sent now. */
    private val sendPhotoResult: (MarkPhotoResult) -> Boolean = { false },
) {
    @Volatile private var current: Long? = null
    private val jobs = java.util.concurrent.ConcurrentHashMap<Long, Job>()
    private val photoEnabled get() = photo != null && scope != null

    /** Takes the mark now; [listen] = a push-to-talk is about to start. Returns its seq, or null (setting off). Never throws. */
    fun onTap(listen: Boolean): Long? {
        if (!enabled()) return null
        val m = try { outbox.record(wallClock(), elapsed(), bootCount(), listen, photo = photoEnabled) } catch (e: Exception) { return null }
        current = m.seq
        cue.show(MarkCue.MARKED)
        trySend(m)
        if (photoEnabled) startPhoto(m)
        return m.seq
    }

    /** Double-tap while recording: undo this listen's mark (once). */
    fun onUndo() {
        val seq = current ?: return
        current = null
        cancelPhoto(seq)
        val m = try { outbox.record(wallClock(), elapsed(), bootCount(), listen = false, undo = seq) } catch (e: Exception) { return }
        cue.show(MarkCue.UNDONE)
        trySend(m)
    }

    /** The listen ended (sent, auto-ended or cancelled): its mark is final. */
    fun onListenFinished() { current = null }

    /** Session open or hello: re-send what the phone has not acked (at most one burst), whatever its age up to 24 h. */
    fun flush() { for (m in outbox.pending()) if (!trySend(m)) break }

    private fun startPhoto(m: MarkMessage) {
        val runner = photo ?: return; val sc = scope ?: return
        val ref = MarkRef.of(m.epoch, m.seq)
        if (jobs.size >= MAX_IN_FLIGHT) { fail(m, MarkPhotoReason.CAMERA_BUSY); return }
        // registered BEFORE it starts: a photo that ends at once (on another thread) must find its entry to remove
        val job = sc.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val r = try { runner.take(ref) } catch (e: CancellationException) { throw e } catch (e: Exception) { MarkPhotoOutcome.Failed(MarkPhotoReason.NO_FRAME) }
            jobs.remove(m.seq)
            when (r) {
                is MarkPhotoOutcome.Taken -> cue.show(MarkCue.PHOTO_OK)
                is MarkPhotoOutcome.Failed -> fail(m, r.reason)
            }
        }
        jobs[m.seq] = job
        job.start()
    }

    private fun fail(m: MarkMessage, reason: String) {
        cue.show(MarkCue.errorText(reason), MarkCue.ERROR_MS)
        try { sendPhotoResult(MarkPhotoResult(epoch = m.epoch, seq = m.seq, reason = reason)) } catch (e: Exception) { /* the phone times the photo out */ }
    }

    /** The mark was undone: no photo for it, running or buffered. */
    private fun cancelPhoto(seq: Long) {
        jobs.remove(seq)?.cancel()
        val runner = photo ?: return
        val epoch = outbox.all().firstOrNull { it.seq == seq }?.epoch ?: return
        runner.discard(MarkRef.of(epoch, seq))
    }

    private fun trySend(m: MarkMessage): Boolean = try { send(m) } catch (e: Exception) { false }

    private companion object { const val MAX_IN_FLIGHT = 3 }
}
