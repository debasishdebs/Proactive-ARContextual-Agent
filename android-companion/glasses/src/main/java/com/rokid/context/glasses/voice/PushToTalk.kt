package com.rokid.context.glasses.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.rokid.context.core.model.audio.EnergyVad
import com.rokid.context.core.model.audio.Pcm
import com.rokid.context.core.model.audio.VadDecision
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Blocking PCM16 mono 16 kHz source; [read] returns bytes read, 0 at the end, < 0 on an error. */
interface PcmSource {
    fun start()
    fun read(buf: ByteArray): Int
    fun close()
}

class AudioRecordSource : PcmSource {
    @SuppressLint("MissingPermission") // PushToTalk checks RECORD_AUDIO before it opens a source
    private val rec: AudioRecord = run {
        val min = AudioRecord.getMinBufferSize(Pcm.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        AudioRecord(MediaRecorder.AudioSource.MIC, Pcm.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, Pcm.CHUNK_BYTES * 2))
    }.also { if (it.state != AudioRecord.STATE_INITIALIZED) { it.release(); throw IllegalStateException("AudioRecord not initialized") } }

    /** startRecording() fails silently when another client holds the mic: check that it is really recording. */
    override fun start() {
        rec.startRecording()
        check(rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "AudioRecord not recording" }
    }
    override fun read(buf: ByteArray) = rec.read(buf, 0, buf.size)
    override fun close() { runCatching { rec.stop() }; rec.release() }
}

/** How push-to-talk reaches the phone: `ListenStart` on pc_cmd, Base64 chunks on pc_audio, then pc_listen_end. */
interface PttLink {
    fun start(): Boolean
    fun audio(base64: String): Boolean
    fun end()
    /** Tell the phone to drop the ask (a local mic failure after its ListenStart). */
    fun cancel() {}
}

enum class PttProblem { NO_PERMISSION, MIC_UNAVAILABLE, LINK_DOWN }

/**
 * Tap-to-talk on the glasses (spec §5.1), adapted from Live Fit `PushToTalk`: the glasses' own mic, 100 ms chunks of
 * 3 200 bytes (phase 0), ending on a second tap ([stop], which keeps the chunk in flight), 2.2 s of silence after
 * speech, 5 s without speech or 15 s ([EnergyVad]). A tap within [lateTapGraceMs] of such an auto-end is the "send"
 * the wearer meant ([takeLateTap]), never a new ask. [cancel] (double-tap) stops without `pc_listen_end` and drops the chunk in
 * flight. A dead link or a mic error sends no `pc_listen_end`; a mic error after the ListenStart asks the phone to drop
 * the ask. A read that does not return within [readTimeoutMs] (a hung HAL) is abandoned and the mic released.
 * [recording] is the runtime's `listening` flag, so frames never flush while it is true (spec §1.8). Audio is never logged.
 */
class PushToTalk(
    private val link: PttLink,
    val recording: MutableStateFlow<Boolean> = MutableStateFlow(false),
    private val hasPermission: () -> Boolean = { true },
    private val sourceFactory: () -> PcmSource = { AudioRecordSource() },
    private val vad: () -> EnergyVad = { EnergyVad() },
    private val log: (String) -> Unit = {},
    private val readTimeoutMs: Long = READ_TIMEOUT_MS,
    private val lateTapGraceMs: Long = LATE_TAP_GRACE_MS,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /** NO_PERMISSION comes on the caller's thread (from [start]); the others on the recording or watchdog thread. */
    @Volatile var onProblem: (PttProblem) -> Unit = {}
    private val lock = Any()
    /** The capture in progress; guarded by [lock]. */
    private var current: Capture? = null
    /** When the VAD last ended a capture (not a tap or a cancel); guarded by [lock]. */
    private var autoEndedAtMs: Long? = null

    /** One capture. The flags are guarded by [lock]; [readSinceMs] is 0 outside a read. */
    private class Capture {
        var stop = false
        var cancelled = false
        /** The phone has this capture's ListenStart. */
        var started = false
        @Volatile var readSinceMs = 0L
        @Volatile var source: PcmSource? = null
        val closed = AtomicBoolean(false)
        fun close() { if (closed.compareAndSet(false, true)) source?.let { runCatching { it.close() } } }
    }

    /** False when nothing started: no permission, or a capture is still running. */
    fun start(): Boolean {
        if (!hasPermission()) { onProblem(PttProblem.NO_PERMISSION); return false }
        val c = Capture()
        synchronized(lock) {
            if (current != null) return false
            current = c
            autoEndedAtMs = null
            recording.value = true
        }
        thread(name = "ptt", isDaemon = true) { try { capture(c) } finally { finish(c) } }
        thread(name = "ptt-watchdog", isDaemon = true) { watch(c) }
        return true
    }

    /**
     * A tap with no capture running: true when it lands within [lateTapGraceMs] of an auto-end, so it is taken as the
     * send (the listen already ended and sent) and must not start a new listen. One tap is absorbed at most.
     */
    fun takeLateTap(): Boolean = synchronized(lock) {
        val at = autoEndedAtMs ?: return false
        autoEndedAtMs = null
        clock() - at <= lateTapGraceMs
    }

    /** A second tap: send what was heard, then `pc_listen_end`. */
    fun stop() = synchronized(lock) { current?.stop = true }

    /**
     * Double-tap, session close or activity exit: stop without `pc_listen_end`. True when the phone already has this
     * capture's ListenStart, so the caller owes it a Cancel; a ListenStart in flight is waited for (it is sent under
     * the same lock), so the caller's Cancel always follows it, and none is sent after a cancel. Called on Main, it
     * can therefore wait for that one CXR send (PENDING-DEVICE, T16: a blocking send would stall Main).
     */
    fun cancel(): Boolean = synchronized(lock) {
        val c = current ?: return false
        if (c.cancelled) return false
        c.stop = true
        c.cancelled = true
        c.started
    }

    private fun finish(c: Capture) = synchronized(lock) {
        if (current === c) { current = null; recording.value = false }
    }

    private fun capture(c: Capture) {
        try {
            sourceFactory().also { c.source = it }.start()
        } catch (e: Exception) {
            c.close() // built but failed to start: it still holds the mic
            log("mic failed (${e.javaClass.simpleName})"); onProblem(PttProblem.MIC_UNAVAILABLE); return
        }
        val source = c.source ?: return
        var clean = false
        var autoEnded = false
        var micFailed = false
        try {
            // ListenStart goes out under the lock, so a cancel() waits for it (and Cancel always follows it). A cancel()
            // on Main therefore waits for this CXR send; PENDING-DEVICE (T16): a blocking CXR send would stall Main.
            val opened = synchronized(lock) {
                if (c.stop) return
                link.start().also { c.started = it }
            }
            if (!opened) { onProblem(PttProblem.LINK_DOWN); return }
            log("listening")
            val v = vad()
            val chunk = ByteArray(Pcm.CHUNK_BYTES)
            while (true) {
                if (synchronized(lock) { c.stop }) { clean = true; break }
                c.readSinceMs = nowMs()
                val n = source.read(chunk)
                c.readSinceMs = 0L
                if (synchronized(lock) { c.cancelled }) break
                if (n < 0) { micFailed = true; log("mic read failed ($n)"); onProblem(PttProblem.MIC_UNAVAILABLE); break }
                if (n == 0) { clean = true; break }
                val bytes = chunk.copyOf(n)
                if (!link.audio(Base64.getEncoder().encodeToString(bytes))) { onProblem(PttProblem.LINK_DOWN); break }
                val d = v.feed(bytes)
                if (d != VadDecision.CONTINUE) { clean = true; autoEnded = true; log("auto-end ${d.name}"); break }
            }
        } catch (e: Exception) {
            micFailed = true
            log("capture failed (${e.javaClass.simpleName})"); onProblem(PttProblem.MIC_UNAVAILABLE)
        } finally {
            val (sendEnd, dropAsk) = synchronized(lock) {
                if (autoEnded && !c.cancelled) autoEndedAtMs = nowMs()
                (clean && c.started && !c.cancelled) to (micFailed && c.started && !c.cancelled)
            }
            // End before the mic stops: AudioRecord.stop()/release() must not delay the phone's final.
            if (sendEnd) runCatching { link.end() }
            if (dropAsk) runCatching { link.cancel() }
            c.close()
            log("listen ended")
        }
    }

    /** Abandons a read stuck for [readTimeoutMs]: Ask, the flush and the mic must not wait on a hung HAL. */
    private fun watch(c: Capture) {
        val step = (readTimeoutMs / 4).coerceIn(10L, 250L)
        while (true) {
            try { Thread.sleep(step) } catch (e: InterruptedException) { return }
            val since = c.readSinceMs
            val fired = synchronized(lock) {
                if (current !== c) return
                if (since == 0L || nowMs() - since < readTimeoutMs || c.readSinceMs != since) false
                else {
                    // The owed Cancel goes out under the lock, before the capture is released: a new tap's ListenStart
                    // (also sent under the lock) cannot overtake it.
                    if (c.started && !c.cancelled) runCatching { link.cancel() }
                    c.stop = true; c.cancelled = true
                    current = null; recording.value = false
                    true
                }
            }
            if (!fired) continue
            log("mic stalled")
            onProblem(PttProblem.MIC_UNAVAILABLE)
            c.close() // last: on a hung HAL, stop()/release() may block too, and must not swallow the Cancel or the toast
            return
        }
    }

    private fun nowMs() = clock()

    companion object {
        const val READ_TIMEOUT_MS = 2_000L
        /** A "send" tap a beat after the VAD's auto-end (slow speech; BUG-R3-3) is still the send. */
        const val LATE_TAP_GRACE_MS = 1_500L
    }
}
